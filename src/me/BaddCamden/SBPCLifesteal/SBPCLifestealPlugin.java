package me.BaddCamden.SBPCLifesteal;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import me.BaddCamden.SBPC.api.SbpcAPI;
import me.BaddCamden.SBPCLifesteal.combat.CombatLogManager;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ALL Lifesteal logic lives here:
 *
 * - PVP kill:
 *   victim loses 1 heart (2 half-hearts) max health,
 *   killer gains exactly what was taken (capped if victim was low).
 *
 * - Environmental death:
 *   victim loses 0.5 heart max health and drops a "Broken Heart" (BEETROOT).
 *
 * - If max health after death <= 0:
 *   * If there are destroyed Broken Hearts "in stock", they save the player:
 *       - consume one stock, revive player with 0.5 heart max.
 *   * Otherwise, lifesteal-ban the player (tracked & persisted).
 *
 * - Destroyed Broken Hearts (lava, fire, void, despawn, etc.) are counted
 *   as "destroyedHalfHeartsStock".
 *   When a player is banned, any stored destroyed hearts will auto-revive
 *   banned players in chronological order, giving them pending half-hearts
 *   when they next join.
 *
 * - Section trade:
 *   If killer is in a LOWER SBPC section than victim, and this victim
 *   has not already been demoted from that section, then:
 *     victim is dropped one full section,
 *     killer is bumped up one full section.
 *   This only happens ONCE per section per victim.
 *
 * - First-time PVP damage between two players:
 *     both get warnings about this section trade mechanic.
 *
 * - Environmental death resets the player's current SBPC entry progress
 *   (NOT the section), and progress speed stays unchanged in SBPC.
 *
 * - Health-based tick multiplier:
 *   This plugin runs its own 1-second scheduler. For each player, it computes
 *   a health-based factor f and then calls:
 *       SbpcAPI.applyExternalTimeSkip(playerUUID, extraSeconds, 0, "...")
 *   where extraSeconds approximates (f - 1) seconds each second.
 *   That means SBPC stays untouched; all extra speed comes from external skips.
 */
public class SBPCLifestealPlugin extends JavaPlugin implements Listener {

    private NamespacedKey brokenHeartKey;

    private static final String PVP_UNLOCK_ENTRY_ID = "pvp_unlock";

    private final Map<String, String> messages = new HashMap<>();
    private String brokenHeartName;
    private List<String> brokenHeartLore = new ArrayList<>();

    // players that have taken/inflicted PVP damage (for one-time warnings)
    private final Set<UUID> pvpWarned = new HashSet<>();
  
    // per-victim section demotion flags: UUID -> set of sectionIds already demoted from
    private final Map<UUID, Set<String>> victimSectionDemoted = new HashMap<>();
    // Marks zombies that represent offline players
    private NamespacedKey combatLoggerOwnerKey;

    // Runtime record: players whose combat-log zombie died while they were offline
    private final Map<UUID, PendingCombatLogDeath> pendingCombatLogDeaths = new HashMap<>();

    private static final class PendingCombatLogDeath {
        final boolean pvp;

        /**
         * Record whether the offline kill was caused by a player (PVP) or the environment.
         */
        PendingCombatLogDeath(boolean pvp) {
            this.pvp = pvp;
        }
    }

    // lifesteal "bans"
    private final List<UUID> banQueue = new ArrayList<>(); // chronological
    private final Set<UUID> bannedPlayers = new HashSet<>();

    // destroyed Broken Hearts that have not yet been used to revive
    private int destroyedHalfHeartsStock = 0;
    // Minimum allowed max health (in health points, not hearts)
    // e.g. config "min-max-health: 2.0" means 1 heart.
    private double minMaxHealth;
    //Hearts lost on PVP / environmental death (in hearts, not half-hearts)
    private double pvpLossHearts;
    private double envLossHearts;
    private final long hundredYearsMs = 100L * 365L * 24L * 60L * 60L * 1000L;
    // pending revive hearts for offline players (UUID -> half-hearts)
    private final Map<UUID, Integer> pendingReviveHearts = new HashMap<>();

    private File playersFolder;

    // destroyed Broken Heart item entities we've already counted, by entity UUID
    private final Set<UUID> countedBrokenHeartItems = new HashSet<>();

    // health-based external skip accumulator per player
    private final Map<UUID, Double> healthSkipAccumulator = new HashMap<>();

    private CombatLogManager combatLogManager;

    private long combatTagDurationMs;
    private long combatLogZombieTtlMs;

    private int healthTaskId = -1;
    private static class LastHitInfo {
        final UUID attackerId;
        final long timestampMillis;

        /**
         * Snapshot of who last damaged a victim and when.
         */
        private LastHitInfo(UUID attackerId, long timestampMillis) {
            this.attackerId = attackerId;
            this.timestampMillis = timestampMillis;
        }
    }

    // victim UUID -> last relevant attacker + timestamp
    private final Map<UUID, LastHitInfo> lastHitMap = new HashMap<>();

    // 30 seconds window for last-hit credit
    private static final long LAST_HIT_WINDOW_MS = 30_000L;


    /**
     * Bootstrap the plugin: verify dependencies, load configuration and data, then register listeners/tasks.
     */
    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("SBPC") == null) {
            getLogger().severe("SBPC not found; disabling SBPCLifesteal.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        reloadConfig();
        combatLoggerOwnerKey = new NamespacedKey(this, "combat_logger_owner");
        this.brokenHeartKey = new NamespacedKey(this, "broken_heart");
        this.playersFolder = resolvePlayersFolder();
        loadConfiguredValues();

        loadData();
        combatLogManager = new CombatLogManager(this, combatTagDurationMs, combatLogZombieTtlMs, playersFolder);
        // Register it as an event listener
        getServer().getPluginManager().registerEvents(combatLogManager, this);

        // Load persisted combat-log states and respawn zombies if needed
        combatLogManager.loadAllEntries();
        Bukkit.getPluginManager().registerEvents(this, this);

        // Periodic health-based tick multiplier -> external time skips
        this.healthTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                this,
                this::tickHealthBasedSkips,
                20L,
                20L
        );

        getLogger().info("SBCPLifesteal enabled.");
    }

    /**
     * Shutdown hook that cancels tasks, flushes combat-log data, and persists lifesteal state.
     */
    @Override
    public void onDisable() {
        if (healthTaskId != -1) {
            Bukkit.getScheduler().cancelTask(healthTaskId);
            healthTaskId = -1;
        }
        if (combatLogManager != null) {
            combatLogManager.saveAllEntries();
        }
        saveData();
    }

    /**
     * Ensure the players data folder exists, migrating legacy "Players" capitalization if needed.
     */
    private File resolvePlayersFolder() {
        File lower = new File(getDataFolder(), "players");
        File upper = new File(getDataFolder(), "Players");

        if (upper.exists() && upper.isDirectory()) {
            if (!lower.exists() && upper.renameTo(lower)) {
                return lower;
            }

            if (!lower.exists() && !lower.mkdirs()) {
                getLogger().warning("Could not create players data folder at " + lower.getPath());
                return lower;
            }

            File[] legacyFiles = upper.listFiles();
            if (legacyFiles != null) {
                for (File legacy : legacyFiles) {
                    File dest = new File(lower, legacy.getName());
                    if (dest.exists()) {
                        continue;
                    }
                    if (!legacy.renameTo(dest)) {
                        getLogger().warning("Could not move legacy player data file " + legacy.getName()
                                + " from Players to players folder.");
                    }
                }
            }

            File[] remaining = upper.listFiles();
            if (remaining == null || remaining.length == 0) {
                upper.delete();
            }
        }

        if (!lower.exists() && !lower.mkdirs()) {
            getLogger().warning("Could not create players data folder at " + lower.getPath());
        }

        return lower;
    }

    /**
     * Read configuration values for lifesteal, combat logging, messaging, and Broken Heart items.
     */
    private void loadConfiguredValues() {
        FileConfiguration cfg = getConfig();

        // Hearts lost (in hearts)
        pvpLossHearts = cfg.getDouble("lifesteal.pvp-loss-hearts", 1.0);
        envLossHearts = cfg.getDouble("lifesteal.env-loss-hearts", 0.5);

        // Minimum max health (in health points). 2.0 = 1 heart.
        minMaxHealth = cfg.getDouble("lifesteal.min-max-health", 2.0);

        ConfigurationSection combatLogConfig = cfg.getConfigurationSection("combat-log");
        combatTagDurationMs = 1000L * (combatLogConfig != null
                ? combatLogConfig.getLong("tag-duration-seconds", 5 * 60)
                : 5 * 60L);
        combatLogZombieTtlMs = 1000L * (combatLogConfig != null
                ? combatLogConfig.getLong("zombie-ttl-seconds", 2 * 60)
                : 2 * 60L);

        brokenHeartName = colorize(cfg.getString("broken-heart-item.name", "&cBroken Heart"));
        brokenHeartLore = colorizeList(cfg.getStringList("broken-heart-item.lore"));

        loadMessages(cfg);
    }

    /**
     * Populate the internal message map with colorized strings from config defaults.
     */
    private void loadMessages(FileConfiguration cfg) {
        messages.clear();
        putMessage("pvp-first-hit-victim", cfg.getString("messages.pvp-first-hit-victim",
                "&c&lWatch out! &fDying to another player who is in a &ehigher section&f than you will cost you a section!"));
        putMessage("pvp-first-hit-attacker", cfg.getString("messages.pvp-first-hit-attacker",
                "&a&lHe he he! &fKilling another player who is in a &ehigher section&f than you will gain you a section!"));
        putMessage("pvp-death-victim", cfg.getString("messages.pvp-death-victim",
                "&cYou were killed by &e{killer}&c and lost &4{hearts}&c heart(s)!"));
        putMessage("pvp-death-killer", cfg.getString("messages.pvp-death-killer",
                "&aYou killed &e{victim}&a and gained &2{hearts}&a heart(s)!"));
        putMessage("env-death-loss", cfg.getString("messages.env-death-loss",
                "&cYou died to the environment and lost &4{hearts}&c heart(s)!"));
        putMessage("saved-by-destroyed-heart", cfg.getString("messages.saved-by-destroyed-heart",
                "&dA destroyed Broken Heart has saved you from banishment!"));
        putMessage("banned-message", cfg.getString("messages.banned-message",
                "&4You have run out of hearts and have been banned from the world."));
        putMessage("section-bump-up", cfg.getString("messages.section-bump-up",
                "&aYour kill has bumped you up to section &e{section}&a!"));
        putMessage("section-bump-down", cfg.getString("messages.section-bump-down",
                "&cDying to a lower-section player has set you back to section &e{section}&c!"));
        putMessage("pvp-locked-self", cfg.getString("messages.pvp-locked-self",
                "&cYou must unlock PVP before fighting other players."));
        putMessage("pvp-locked-target", cfg.getString("messages.pvp-locked-target",
                "&cThat player hasn't unlocked PVP yet."));
        putMessage("pvp-locked-victim", cfg.getString("messages.pvp-locked-victim",
                "&cYou haven't unlocked PVP yet. Progress the PVP Unlock entry to enable PVP."));
        putMessage("pending-revive", cfg.getString("messages.pending-revive",
                "&dA Broken Heart that was destroyed brought you back with {hearts} half-heart(s)."));
        putMessage("broken-heart-use", cfg.getString("messages.broken-heart-use",
                "&dYou feel a tiny spark of life return. (&c+½&d heart)"));
        putMessage("ban-login", cfg.getString("messages.ban-login",
                "&cYou are banned by the Lifesteal system (no hearts remaining)."));
        putMessage("combat-log-ban", cfg.getString("messages.combat-log-ban",
                "You lost all your hearts while logged out in combat."));
    }

    /**
     * Helper to cache a message after applying color codes.
     */
    private void putMessage(String key, String raw) {
        messages.put(key, colorize(raw));
    }

    /**
     * Fetch a colorized message string by key, defaulting to empty if missing.
     */
    private String msg(String key) {
        return messages.getOrDefault(key, "");
    }

    // ------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------

    /**
     * Load persisted lifesteal state for bans, destroyed hearts, pending revives, and warnings.
     */
    private void loadData() {
        this.destroyedHalfHeartsStock = getConfig().getInt("destroyedHalfHeartsStock", 0);

        this.banQueue.clear();
        this.bannedPlayers.clear();
        this.pendingReviveHearts.clear();
        this.victimSectionDemoted.clear();
        this.pvpWarned.clear();

        if (playersFolder == null || !playersFolder.exists() || !playersFolder.isDirectory()) {
            return;
        }

        File[] files = playersFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            String uuidString = (dot == -1) ? name : name.substring(0, dot);

            try {
                UUID uuid = UUID.fromString(uuidString);
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

                // banned state + queue reconstruction
                if (cfg.getBoolean("banned", false)) {
                    bannedPlayers.add(uuid);
                    banQueue.add(uuid);
                }

                // pending revive half-hearts
                int pending = cfg.getInt("pendingReviveHalfHearts", 0);
                if (pending > 0) {
                    pendingReviveHearts.put(uuid, pending);
                }

                // victim section demotion flags
                List<String> secs = cfg.getStringList("victimSectionDemoted");
                if (!secs.isEmpty()) {
                    victimSectionDemoted.put(uuid, new HashSet<>(secs));
                }

                // PVP warning flag
                if (cfg.getBoolean("pvpWarned", false)) {
                    pvpWarned.add(uuid);
                }

            } catch (IllegalArgumentException ex) {
                getLogger().warning("Invalid player data file name in players folder: " + name);
            }
        }
    }

    /**
     * Persist all lifesteal bookkeeping (ban queue, pending hearts, warnings) to disk.
     */
    private void saveData() {
        if (playersFolder == null) {
            // plugin never fully enabled (e.g. SBPC missing)
            return;
        }

        // Always capture current online heart totals before writing files
        for (Player online : Bukkit.getOnlinePlayers()) {
            persistPlayerHearts(online, getBaseMaxHealth(online));
        }

        // global (non-player) data still goes in config.yml
        getConfig().set("destroyedHalfHeartsStock", destroyedHalfHeartsStock);
        saveConfig();

        if (!playersFolder.exists() && !playersFolder.mkdirs()) {
            getLogger().warning("Could not create players data folder at " + playersFolder.getPath());
            return;
        }

        // collect all players we have any state for
        Set<UUID> allIds = new HashSet<>();
        allIds.addAll(bannedPlayers);
        allIds.addAll(pendingReviveHearts.keySet());
        allIds.addAll(victimSectionDemoted.keySet());
        allIds.addAll(pvpWarned);

        for (UUID id : allIds) {
            File file = new File(playersFolder, id.toString() + ".yml");
            YamlConfiguration cfg = new YamlConfiguration();

            // banned state
            cfg.set("banned", bannedPlayers.contains(id));

            // pending revive half-hearts (if any)
            int pending = pendingReviveHearts.getOrDefault(id, 0);
            if (pending > 0) {
                cfg.set("pendingReviveHalfHearts", pending);
            }

            // section demotion flags for this victim
            Set<String> secs = victimSectionDemoted.get(id);
            if (secs != null && !secs.isEmpty()) {
                cfg.set("victimSectionDemoted", new ArrayList<>(secs));
            }

            // PVP warning flag
            cfg.set("pvpWarned", pvpWarned.contains(id));

            try {
                cfg.save(file);
            } catch (IOException ex) {
                getLogger().warning("Could not save lifesteal data for " + id + ": " + ex.getMessage());
            }
        }
    }


    // ------------------------------------------------------------------------
    // Broken Heart item
    // ------------------------------------------------------------------------

    /**
     * Create a Broken Heart item stack with the configured name/lore and data marker.
     */
    private ItemStack createBrokenHeart(int amount) {
        ItemStack stack = new ItemStack(Material.BEETROOT, amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(brokenHeartName);
            meta.setLore(brokenHeartLore);
            meta.getPersistentDataContainer().set(brokenHeartKey, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * Check whether an item stack is a Broken Heart (by material and PDC marker).
     */
    private boolean isBrokenHeart(ItemStack stack) {
        if (stack == null || stack.getType() != Material.BEETROOT) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(brokenHeartKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------------
    // Lifesteal max health helpers
    // ------------------------------------------------------------------------
    /**
     * Resolve which PLAYER is responsible for the damage:
     * - Direct melee from a player
     * - Projectiles shot by a player
     * - TNT ignited by a player (TNTPrimed.getSource())
     * - Wolves owned by a player
     * - TNT minecarts / end crystals whose PDC stores the owner's UUID
     */
    private Player resolveDamagerPlayer(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }

        // Projectile (arrow, trident, etc.) shot by a player
        if (damager instanceof org.bukkit.entity.Projectile proj) {
            org.bukkit.projectiles.ProjectileSource shooter = proj.getShooter();
            if (shooter instanceof Player pShooter) {
                return pShooter;
            }
        }

        // TNT that was ignited by someone (flint & steel, etc.)
        if (damager instanceof org.bukkit.entity.TNTPrimed tnt) {
            Entity source = tnt.getSource();
            if (source instanceof Player pSource) {
                return pSource;
            }
        }

        // Wolves owned by a player
        if (damager instanceof org.bukkit.entity.Wolf wolf) {
            org.bukkit.entity.AnimalTamer owner = wolf.getOwner();
            if (owner instanceof Player pOwner) {
                return pOwner;
            }
        }

        // TNT minecart / end crystal: use PDC to read last known owner UUID
        if (damager instanceof ExplosiveMinecart ||
            damager instanceof org.bukkit.entity.EnderCrystal) {

            PersistentDataContainer pdc = damager.getPersistentDataContainer();
            String raw = pdc.get(brokenHeartKey, PersistentDataType.STRING);
            if (raw != null) {
                try {
                    UUID id = UUID.fromString(raw);
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        return p;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Bad UUID in PDC, just ignore
                }
            }
        }

        return null;
    }

    /**
     * Store the last hit info for a victim, for 30 seconds.
     */
    private void recordLastHit(Player victim, Player attacker) {
        if (attacker == null || attacker == victim) return;
        lastHitMap.put(
                victim.getUniqueId(),
                new LastHitInfo(attacker.getUniqueId(), System.currentTimeMillis())
        );
    }

    /**
     * Return the player who last damaged this victim within LAST_HIT_WINDOW_MS,
     * or null if there is no recent attacker.
     */
    private Player getLastHitKiller(Player victim) {
        LastHitInfo info = lastHitMap.get(victim.getUniqueId());
        if (info == null) return null;

        long now = System.currentTimeMillis();
        if (now - info.timestampMillis > LAST_HIT_WINDOW_MS) {
            lastHitMap.remove(victim.getUniqueId());
            return null;
        }

        Player p = Bukkit.getPlayer(info.attackerId);
        if (p == null || !p.isOnline()) {
            return null;
        }
        return p;
    }

    /**
     * Resolve the effective killer for lifesteal:
     *  - Prefer last-hit attacker within 30s (covers indirect / environmental deaths),
     *  - Fallback to Bukkit's built-in victim.getKiller().
     */
    private Player resolveKillerForDeath(Player victim) {
        Player fromLastHit = getLastHitKiller(victim);
        if (fromLastHit != null) {
            return fromLastHit;
        }
        return victim.getKiller();
    }

    /**
     * Read the player's base max health attribute, defaulting to 20.0 when missing.
     */
    private double getBaseMaxHealth(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return 20.0;
        return attr.getBaseValue();
    }

    /**
     * Determine the default heart count configured for new players.
     */
    private double getDefaultConfiguredHearts() {
        double configuredBase = getConfig().getDouble("lifesteal.base-max-health", 20.0);
        double fromConfigured = configuredBase / 2.0;
        return Math.max(1.0, fromConfigured);
    }

    /**
     * Apply a new base max health to the player while clamping and syncing current health.
     */
    private void setBaseMaxHealth(Player p, double value) {
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;
        double newVal = Math.max(0.0, value);
        attr.setBaseValue(newVal);
        if (p.getHealth() > newVal) {
            p.setHealth(newVal);
        }
    }

    /**
     * Translate alternate color codes (& and §) to Bukkit formatting codes.
     */
    private String colorize(String raw) {
        if (raw == null) {
            return "";
        }
        // Accept both '&' and '§' as alternate color prefixes so admin-edited configs
        // or previously colorized strings still render correctly.
        String normalized = raw.replace("§", "&");
        return ChatColor.translateAlternateColorCodes('&', normalized);
    }

    /**
     * Apply colorization to each string in a list, returning an empty list for null inputs.
     */
    private List<String> colorizeList(List<String> rawList) {
        if (rawList == null) {
            return Collections.emptyList();
        }
        return rawList.stream().map(this::colorize).collect(Collectors.toList());
    }

    /**
     * Format a half-heart count into a human-friendly string (whole or one decimal place).
     */
    private String formatHearts(int halfHearts) {
        double hearts = halfHearts / 2.0;
        if (Math.abs(hearts - Math.round(hearts)) < 0.0001) {
            return String.format(Locale.US, "%.0f", hearts);
        }
        return String.format(Locale.US, "%.1f", hearts);
    }

    /**
     * Apply a change in max health, measured in half-hearts (1.0 = half-heart).
     * Returns the actual delta applied (in half-hearts, signed).
     */
    private int applyMaxHealthChange(Player p, int deltaHalfHearts) {
        if (deltaHalfHearts == 0) return 0;
        double base = getBaseMaxHealth(p);
        double newVal = base + deltaHalfHearts;
        if (newVal < 0) {
            // clamp at 0 and adjust delta accordingly
            deltaHalfHearts -= (int) Math.ceil(0 - newVal);
            newVal = 0;
        }
        setBaseMaxHealth(p, newVal);
        persistPlayerHearts(p, newVal);
        return deltaHalfHearts;
    }

    /**
     * Save the player's heart total (in hearts) to their data file using the provided base max health.
     */
    private void persistPlayerHearts(Player p, double maxHealthValue) {
        double hearts = maxHealthValue / 2.0;
        saveHeartsToFile(p.getUniqueId(), hearts);
    }

    // ------------------------------------------------------------------------
    // Health-based tick multiplier (handled HERE)
    // ------------------------------------------------------------------------

    /**
     * Scheduler task that computes health multipliers and feeds extra SBPC time skips per player.
     */
    private void tickHealthBasedSkips() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            double factor = computeHealthFactor(p);
            if (factor <= 1.0) {
                // nothing extra this second
                continue;
            }
            double extraPerSecond = factor - 1.0; // base SBPC tick already gives 1x

            UUID id = p.getUniqueId();
            double acc = healthSkipAccumulator.getOrDefault(id, 0.0);
            acc += extraPerSecond;

            int whole = (int) Math.floor(acc);
            if (whole > 0) {
                acc -= whole;
                // Push additional skip seconds into SBPC
                // percentSpeedIncrease = 0, just raw seconds
                SbpcAPI.applyExternalTimeSkip(
                        id,
                        whole,
                        0.0,
                        "" // no spammy message; SBPC can ignore empty description
                );
            }

            healthSkipAccumulator.put(id, acc);
        }
    }

    /**
     * Compute the health-based multiplier factor:
     *
     * current hearts = min(currentHealth / 2, 10)
     * max hearts     = min(maxHealth    / 2, 10)
     *
     * f_current = 1 + (1 - currentHearts / 10)
     * f_max     = 1 + (2 - maxHearts / 5)
     *
     * total = f_current * f_max
     *
     * Health above 10 hearts is ignored (clamped).
     */
    private double computeHealthFactor(Player p) {
        double current = p.getHealth();
        double max = getBaseMaxHealth(p);

        double currentHearts = Math.min(current / 2.0, 10.0);
        double maxHearts = Math.min(max / 2.0, 10.0);

        double factorCurrent = 1.0 + (1.0 - (currentHearts / 10.0));
        double factorMax = 1.0 + (2.0 - (maxHearts / 5.0));

        return factorCurrent * factorMax;
    }

    // ------------------------------------------------------------------------
    // Ban & destroyed heart helpers
    // ------------------------------------------------------------------------

    /**
     * Check if a player is marked as lifesteal-banned in memory.
     */
    private boolean isBanned(UUID uuid) {
        return bannedPlayers.contains(uuid);
    }

    /**
     * Add a player to the lifesteal ban list and immediately kick them with a message.
     */
    private void banPlayer(Player p) {
        UUID id = p.getUniqueId();
        if (bannedPlayers.add(id)) {
            banQueue.add(id);
        }

        Bukkit.getScheduler().runTask(this, () -> {
            p.kickPlayer(msg("banned-message"));
        });

        getLogger().info("Lifesteal banned player " + p.getName());
        saveData();
    }

    /**
     * Consume a destroyed Broken Heart stockpile to revive a player at 0.5 heart max health.
     */
    private void spendDestroyedHeartOnNewDeath(Player p) {
        destroyedHalfHeartsStock--;
        if (destroyedHalfHeartsStock < 0) destroyedHalfHeartsStock = 0;
        setBaseMaxHealth(p, 1.0); // ½ heart max
        p.sendMessage(msg("saved-by-destroyed-heart"));
        getLogger().info("Destroyed Broken Heart prevented ban for " + p.getName());
        saveData();
    }

    /**
     * Track Broken Hearts lost to the void/fire/etc. and use them to revive banned players.
     */
    private void onBrokenHeartDestroyed(int amount) {
        if (amount <= 0) return;
        destroyedHalfHeartsStock += amount;

        // Use destroyed hearts to revive banned players in order
        while (destroyedHalfHeartsStock > 0 && !banQueue.isEmpty()) {
            UUID revived = banQueue.remove(0);
            bannedPlayers.remove(revived);
            destroyedHalfHeartsStock--;

            if (destroyedHalfHeartsStock < 0) destroyedHalfHeartsStock = 0;

            int pending = pendingReviveHearts.getOrDefault(revived, 0) + 1;
            pendingReviveHearts.put(revived, pending);

            getLogger().info("Destroyed Broken Heart revived banned player " + revived
                    + " with pending ½-heart.");
        }

        saveData();
    }

    /**
     * Delay-count a Broken Heart item that might be destroyed, ensuring we only count once.
     */
    private void scheduleBrokenHeartDestructionCount(Item item, int amount) {
        UUID id = item.getUniqueId();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!item.isValid() || item.isDead()) {
                if (countedBrokenHeartItems.add(id)) {
                    onBrokenHeartDestroyed(amount);
                }
            } else {
                countedBrokenHeartItems.remove(id);
            }
        });
    }

    // ------------------------------------------------------------------------
    // Section trade via SBPC
    // ------------------------------------------------------------------------

    /**
     * Swap SBPC sections between a lower-section killer and higher-section victim when eligible.
     */
    private void handleSectionTradeOnKill(Player killer, Player victim) {
        UUID kId = killer.getUniqueId();
        UUID vId = victim.getUniqueId();

        String killerSec = SbpcAPI.getCurrentSectionId(kId);
        String victimSec = SbpcAPI.getCurrentSectionId(vId);
        if (killerSec == null || victimSec == null) return;
        if (killerSec.equals(victimSec)) return;

        int killerIdx = SbpcAPI.getSectionIndex(killerSec);
        int victimIdx = SbpcAPI.getSectionIndex(victimSec);
        if (killerIdx < 0 || victimIdx < 0) return;

        // Only if killer is strictly below victim
        if (killerIdx >= victimIdx) {
            return;
        }

        // Has this victim already been demoted from this section?
        Set<String> used = victimSectionDemoted.computeIfAbsent(vId, x -> new HashSet<>());
        if (used.contains(victimSec)) {
            return;
        }

        used.add(victimSec);

        // One section down for victim, one up for killer
        SbpcAPI.bumpPlayerDownOneSection(vId);
        SbpcAPI.bumpPlayerUpOneSection(kId);

        // If keepInventory is enabled, drop newly disallowed equipped items
        World world = victim.getWorld();
        Boolean keepInv = world.getGameRuleValue(GameRule.KEEP_INVENTORY);
        if (keepInv != null && keepInv) {
            SbpcAPI.dropNowDisallowedEquippedItems(vId, victim.getLocation());
        }

        String newKillerSec = SbpcAPI.getCurrentSectionId(kId);
        String newVictimSec = SbpcAPI.getCurrentSectionId(vId);

        String killerMsg = msg("section-bump-up").replace("{section}",
                newKillerSec != null ? newKillerSec : "?");
        String victimMsg = msg("section-bump-down").replace("{section}",
                newVictimSec != null ? newVictimSec : "?");

        killer.sendMessage(killerMsg);
        victim.sendMessage(victimMsg);
        saveData();
    }

    /**
     * Send the one-time PVP warning messages to both participants.
     */
    private void markPvpWarned(Player a, Player b) {
        boolean changedA = pvpWarned.add(a.getUniqueId());
        boolean changedB = pvpWarned.add(b.getUniqueId());

        if (changedA) {
            a.sendMessage(msg("pvp-first-hit-victim"));
        }
        if (changedB) {
            b.sendMessage(msg("pvp-first-hit-attacker"));
        }
    }
    /**
     * Handles a lifesteal kill where the victim is offline and represented by a
     * combat-log zombie.
     *
     * Contract:
     * - The offline victim loses one heart.
     * - The killer gains one heart.
     * - If the victim reaches 0 hearts (or whatever threshold you use),
     *   they should be banned or otherwise marked as dead, just like a normal kill.
     *
     * This implementation assumes per-player data in players/<uuid>.yml and
     * stores hearts under "lifesteal.hearts". Adjust the paths to match your
     * existing data model if needed.
     */
    public void handleOfflineCombatLogKill(UUID victimId, Player killer) {
        if (killer == null) {
            return;
        }

        // 1) Adjust offline victim hearts
        double victimHearts = loadHeartsFromFile(victimId);
        double newVictimHearts = Math.max(0.0, victimHearts - 1.0);
        saveHeartsToFile(victimId, newVictimHearts);

        // 2) Ban if hearts reached zero
        if (newVictimHearts <= 0) {
            OfflinePlayer offlineVictim = Bukkit.getOfflinePlayer(victimId);
            if (offlineVictim != null && offlineVictim.getName() != null) {

                String name = offlineVictim.getName();

                // "Permanent" ban: set expiry to something insanely far in the future
               
                Date expires = new Date(System.currentTimeMillis() + hundredYearsMs);

                // Use OfflinePlayer#ban to avoid BanList ambiguity
                offlineVictim.ban(
                        msg("combat-log-ban"),
                        expires,
                        "SBPCLifesteal"
                );

                // Chronological record
                File bannedFile = new File(getDataFolder(), "banned-players.yml");
                YamlConfiguration bannedCfg = bannedFile.exists()
                        ? YamlConfiguration.loadConfiguration(bannedFile)
                        : new YamlConfiguration();

                List<String> order = bannedCfg.getStringList("order");
                order.add(System.currentTimeMillis() + ":" + name + ":" + victimId);
                bannedCfg.set("order", order);

                try {
                    bannedCfg.save(bannedFile);
                } catch (IOException ex) {
                    getLogger().warning("Failed to update banned-players.yml for " + name + ": " + ex.getMessage());
                }

                getLogger().info("Offline player banned via combat log death: " + name);
            }
        }

        // 3) Adjust killer hearts
        double killerHearts = loadHeartsFromFile(killer.getUniqueId());
        double newKillerHearts = killerHearts + 1.0;
        saveHeartsToFile(killer.getUniqueId(), newKillerHearts);
        applyHeartsToOnlinePlayer(killer, newKillerHearts);

        // 4) Log result
        getLogger().info("Combat-log kill: " + killer.getName()
                + " gained heart (" + killerHearts + " -> " + newKillerHearts
                + "), victim now at " + newVictimHearts + " hearts.");
    }

    /**
     * Loads a player's heart count from players/<uuid>.yml under "lifesteal.hearts".
     * Defaults to the configured base max health if missing.
     */
    private double loadHeartsFromFile(UUID uuid) {
        if (playersFolder == null) {
            playersFolder = resolvePlayersFolder();
        }

        File file = new File(playersFolder, uuid.toString() + ".yml");
        if (!file.exists()) {
            // Default hearts if no file yet
            return getDefaultConfiguredHearts();
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        return cfg.getDouble("lifesteal.hearts", getDefaultConfiguredHearts());
    }

    /**
     * Saves a player's heart count to players/<uuid>.yml under "lifesteal.hearts".
     */
    private void saveHeartsToFile(UUID uuid, double hearts) {
        if (playersFolder == null) {
            playersFolder = resolvePlayersFolder();
        }

        File file = new File(playersFolder, uuid.toString() + ".yml");
        YamlConfiguration cfg = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();

        cfg.set("lifesteal.hearts", hearts);

        try {
            cfg.save(file);
        } catch (IOException ex) {
            getLogger().warning("Failed to save hearts for " + uuid + ": " + ex.getMessage());
        }
    }

    /**
     * Applies a heart count to an online player's max health attribute.
     * Assumes 1 heart = 2 health points.
     */
    private void applyHeartsToOnlinePlayer(Player player, double hearts) {
        double newMaxHealth = Math.max(minMaxHealth, hearts * 2.0);
        var attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(newMaxHealth);
            // Clamp current health to new max
            if (player.getHealth() > newMaxHealth) {
                player.setHealth(newMaxHealth);
            }
        }
    }
    // ------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------

    /**
     * Bridge hook so the main plugin can react to combat-log zombie kills created by CombatLogManager.
     */
    @EventHandler
    public void onCombatLoggerZombieDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Zombie zombie)) {
            return;
        }

        PersistentDataContainer pdc = zombie.getPersistentDataContainer();
        if (!pdc.has(combatLoggerOwnerKey, PersistentDataType.STRING)) {
            return; // not one of our combat loggers
        }

        String ownerIdStr = pdc.get(combatLoggerOwnerKey, PersistentDataType.STRING);
        if (ownerIdStr == null) {
            return;
        }

        UUID ownerId;
        try {
            ownerId = UUID.fromString(ownerIdStr);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("[Lifesteal] Invalid UUID on combat logger zombie: " + ownerIdStr);
            return;
        }

        // At this point, the zombie has died and its drops/xp are already in the world.
        // Record a pending combat-log death so we can apply the heart loss when the player logs back in.
        boolean isPvpKill = event.getEntity().getKiller() != null;
        pendingCombatLogDeaths.put(ownerId, new PendingCombatLogDeath(isPvpKill));

        // Optionally: remove any extra bookkeeping you keep for the combat logger here
        // (e.g., remove from some map, clear persisted state for this zombie, etc.)
    }
    /**
     * When a player rejoins and their proxy zombie died offline, apply the stored heart loss.
     */
    @EventHandler
    public void onPlayerJoinAfterCombatLog(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PendingCombatLogDeath pending = pendingCombatLogDeaths.remove(uuid);
        if (pending == null) {
            return; // no offline combat-log death to handle
        }

        // 1) Teleport to spawnpoint / world spawn
        Location spawn = player.getBedSpawnLocation();
        if (spawn == null) {
            spawn = player.getWorld().getSpawnLocation();
        }
        if (spawn != null) {
            player.teleport(spawn);
        }

        // 2) Apply the correct heart/half-heart loss, using PVP or ENV loss as appropriate
        applyCombatLogHeartLoss(player, pending.pvp);
    }
    /**
     * Applies lifesteal heart loss for a player whose combat-log zombie died
     * while they were offline. Treats it as PVP or ENV based on the flag.
     */
    private void applyCombatLogHeartLoss(Player player, boolean pvp) {
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr == null) {
            getLogger().warning("[Lifesteal] Could not apply combat-log heart loss: MAX_HEALTH attribute missing for "
                    + player.getName());
            return;
        }

        // Hearts lost from config (in hearts, not half-hearts)
        double heartsLost = pvp ? pvpLossHearts : envLossHearts;
        double healthLoss = heartsLost * 2.0; // convert hearts -> health points

        double currentMax = maxHealthAttr.getBaseValue();
        double newMax = currentMax - healthLoss;

        // If the new max is still above the configured minimum, just reduce it
        if (newMax >= minMaxHealth) {
            maxHealthAttr.setBaseValue(newMax);

            // Clamp current health so they don't log in "over max"
            if (player.getHealth() > newMax) {
                player.setHealth(newMax);
            }
            // You can send a message here if you want to explicitly tell them:
            // player.sendMessage(prefix + "Your combat logger died while you were offline. You lost " + heartsLost + " heart(s).");
            return;
        }

        // If we reach here, this offline death would push them below minimum max health.
        // Mirror your normal "final death" behavior (lifeline stock, then ban).

        // --- OPTIONAL LIFELINE: destroyed broken hearts stock ---
        if (destroyedHalfHeartsStock > 0) {
            // Consume ONE half-heart lifeline (adjust exactly how you do it elsewhere)
            destroyedHalfHeartsStock -= 1;
            if (destroyedHalfHeartsStock < 0) {
                destroyedHalfHeartsStock = 0;
            }
            saveData();

            // Keep them at minimum max health and clamp current health
            maxHealthAttr.setBaseValue(minMaxHealth);
            if (player.getHealth() > minMaxHealth) {
                player.setHealth(minMaxHealth);
            }

            player.sendMessage(msg("saved-by-destroyed-heart"));
            return;
        }

        // No lifelines left -> ban as in a normal "out of hearts" case.
        String banReason = msg("banned-message");

        // Use Player#ban with a far-future date to effectively permanent-ban.
        // (You already switched to using .ban earlier.)
        Date farFuture = new Date(System.currentTimeMillis() + 1000L * 60L * 60L * 24L * 365L * 100L);
        player.ban(banReason, farFuture, null);

        // Kick them immediately so the ban takes effect.
        player.kickPlayer(banReason);
    }



    /**
     * Track last-hits for kill attribution, enforce PVP unlocks, and warn first-time combatants.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        Entity victimEntity = event.getEntity();
        Entity damager = event.getDamager();

        // Resolve which PLAYER is really responsible for this damage (projectiles, TNT, wolves, etc.)
        Player attacker = resolveDamagerPlayer(damager);

        //
        // If TNT minecart or end crystal is being damaged by a player (or their projectile),
        // remember who last "armed" it for later explosion damage.
        //
        if (attacker != null &&
                (victimEntity instanceof ExplosiveMinecart ||
                 victimEntity instanceof org.bukkit.entity.EnderCrystal)) {

            // Use entity PDC so we don't need a separate map
            PersistentDataContainer pdc = victimEntity.getPersistentDataContainer();
            pdc.set(brokenHeartKey, PersistentDataType.STRING, attacker.getUniqueId().toString());
            // NOTE: we reuse brokenHeartKey here just as a namespaced key storage point
            // to avoid introducing another field; only this code reads it via resolveDamagerPlayer.
        }

        // If the victim is not a player, nothing else to do (we only care about
        // last-hit tracking when PLAYERS are being damaged).
        if (!(victimEntity instanceof Player victim)) {
            return;
        }

        if (attacker == null || attacker == victim) {
            return;
        }

        if (!hasUnlockedPvp(attacker)) {
            event.setCancelled(true);
            attacker.sendMessage(msg("pvp-locked-self"));
            return;
        }

        if (!hasUnlockedPvp(victim)) {
            event.setCancelled(true);
            attacker.sendMessage(msg("pvp-locked-target"));
            victim.sendMessage(msg("pvp-locked-victim"));
            return;
        }

        // Track last hit for kill attribution
        recordLastHit(victim, attacker);

        // First-time warning (existing behavior)
        markPvpWarned(attacker, victim);
    }

    /**
     * Check SBPC for the custom PVP unlock gate.
     */
    private boolean hasUnlockedPvp(Player player) {
        return SbpcAPI.isCustomUnlocked(player.getUniqueId(), PVP_UNLOCK_ENTRY_ID);
    }


    /**
     * Core lifesteal handler for player deaths, covering both PVP and environmental cases.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = resolveKillerForDeath(victim); // <-- changed

        double vMax = getBaseMaxHealth(victim);
        int vMaxHalf = (int) Math.round(vMax); // base in half-hearts

        boolean environmentalDeath = !(victim.getLastDamageCause() instanceof EntityDamageByEntityEvent);
        boolean pvpDeath = !environmentalDeath && killer != null && killer != victim;

        if (pvpDeath) {
            if (!hasUnlockedPvp(killer) || !hasUnlockedPvp(victim)) {
                lastHitMap.remove(victim.getUniqueId());
                return;
            }

            int toSteal = Math.min((int) Math.round(pvpLossHearts * 2.0), vMaxHalf);
            int actualStolen = 0;
            if (toSteal > 0) {
                int actualDeltaVictim = applyMaxHealthChange(victim, -toSteal);
                actualStolen = Math.abs(actualDeltaVictim);
                if (actualStolen > 0) {
                    applyMaxHealthChange(killer, actualStolen);
                }
            }

            if (actualStolen > 0) {
                String victimMsg = msg("pvp-death-victim")
                        .replace("{killer}", killer.getName())
                        .replace("{hearts}", formatHearts(actualStolen));
                victim.sendMessage(victimMsg);

                String killerMsg = msg("pvp-death-killer")
                        .replace("{victim}", victim.getName())
                        .replace("{hearts}", formatHearts(actualStolen));
                killer.sendMessage(killerMsg);
            }

            handleBanOrRevive(victim);
            handleSectionTradeOnKill(killer, victim);

        } else {
            // Environmental / non-PVP death: victim loses configured amount and drops Broken Heart(s)
            int toLose = Math.min((int) Math.round(envLossHearts * 2.0), vMaxHalf);
            int actualLost = 0;
            if (toLose > 0) {
                int actualDelta = applyMaxHealthChange(victim, -toLose);
                actualLost = Math.abs(actualDelta);
                if (actualLost > 0) {
                    ItemStack heart = createBrokenHeart(actualLost);
                    victim.getWorld().dropItemNaturally(victim.getLocation(), heart);
                }
            }

            if (actualLost > 0) {
                String envMsg = msg("env-death-loss")
                        .replace("{hearts}", formatHearts(actualLost));
                victim.sendMessage(envMsg);
            }

            handleBanOrRevive(victim);

            // Reset progress ONLY for current entry (not section)
            SbpcAPI.resetCurrentEntryProgress(victim.getUniqueId());
        }

        // Clear last-hit info on death so it doesn't leak into future lives
        lastHitMap.remove(victim.getUniqueId());
    }

    /**
     * Decide whether a player with zero hearts is revived by destroyed hearts or banned.
     */
    private void handleBanOrRevive(Player victim) {
        double newMax = getBaseMaxHealth(victim);
        if (newMax > 0.0) {
            return;
        }

        if (destroyedHalfHeartsStock > 0) {
            spendDestroyedHeartOnNewDeath(victim);
        } else {
            banPlayer(victim);
        }
    }

    /**
     * Deny login to lifesteal-banned players before they fully join.
     */
    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (isBanned(id)) {
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                    msg("ban-login"));
        }
    }

    /**
     * Restore pending revive hearts and synchronized max health when a player joins.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();

        if (pendingReviveHearts.containsKey(id)) {
            int amt = pendingReviveHearts.remove(id);
            if (amt > 0) {
                setBaseMaxHealth(p, Math.max(0.0, getBaseMaxHealth(p)));
                applyMaxHealthChange(p, amt);
                p.sendMessage(msg("pending-revive").replace("{hearts}", String.valueOf(amt)));
            }
            saveData();
        }

        double configuredHearts = loadHeartsFromFile(id);
        applyHeartsToOnlinePlayer(p, configuredHearts);
    }

    /**
     * Placeholder hook for symmetry; state is persisted elsewhere on disable/save.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // no special logic; state is saved via saveData/onDisable
    }

    /**
     * Reapply configured max health after respawn to avoid default values.
     */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        double configuredHearts = loadHeartsFromFile(player.getUniqueId());
        applyHeartsToOnlinePlayer(player, configuredHearts);
    }

    // ------------------------------------------------------------------------
    // Broken Heart usage & destruction
    // ------------------------------------------------------------------------

    /**
     * Prevent Broken Hearts from being eaten directly.
     */
    @EventHandler(ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (isBrokenHeart(event.getItem())) {
            // cannot eat Broken Hearts
            event.setCancelled(true);
        }
    }

    /**
     * Allow right-clicking a Broken Heart to consume it for half a heart of max health.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (!isBrokenHeart(item)) return;

        Player p = event.getPlayer();
        applyMaxHealthChange(p, 1); // +½ heart max

        // consume one
        item.setAmount(item.getAmount() - 1);
        p.sendMessage(msg("broken-heart-use"));

        event.setCancelled(true);
    }

    /**
     * Prevent crafting recipes from using Broken Hearts as ingredients.
     */
    @EventHandler(ignoreCancelled = true)
    public void onCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe == null) return;
        boolean hasBroken = false;
        for (ItemStack is : event.getInventory().getMatrix()) {
            if (isBrokenHeart(is)) {
                hasBroken = true;
                break;
            }
        }
        if (hasBroken) {
            event.getInventory().setResult(null);
        }
    }

    /**
     * Count Broken Hearts that despawn so they can be used as lifelines.
     */
    @EventHandler(ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        Item item = event.getEntity();
        ItemStack stack = item.getItemStack();
        if (!isBrokenHeart(stack)) return;

        int amt = stack.getAmount();
        scheduleBrokenHeartDestructionCount(item, amt);
    }


    /**
     * Track Broken Hearts destroyed by damage sources like lava or explosions.
     */
    @EventHandler(ignoreCancelled = true)
    public void onItemDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) return;
        ItemStack stack = item.getItemStack();
        if (!isBrokenHeart(stack)) return;

        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.LAVA ||
            cause == EntityDamageEvent.DamageCause.FIRE ||
            cause == EntityDamageEvent.DamageCause.FIRE_TICK ||
            cause == EntityDamageEvent.DamageCause.VOID ||
            cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION ||
            cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION ||
            cause == EntityDamageEvent.DamageCause.CONTACT) {

            int amt = stack.getAmount();
            scheduleBrokenHeartDestructionCount(item, amt);
        }
    }


}
