package me.BaddCamden.SBPCLifesteal.combat;


import me.BaddCamden.SBPCLifesteal.SBPCLifestealPlugin;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Handles combat-tagging and combat-logging zombies for SBPCLifesteal.
 *
 * Rules implemented:
 * - If a player logs out within 5 minutes of being damaged by another player, a no-AI zombie
 *   with their gear, health, and name is spawned in their place.
 * - The zombie is persistent, glows (nametag through walls) and can only be damaged by players.
 * - If the zombie is killed by a player within 2 minutes, the offline player is treated as if
 *   they died in PvP: items + XP drop and lifesteal logic should be applied.
 * - If the player rejoins first or 2 minutes pass, the zombie despawns and the player gets their
 *   items/XP back safely.
 * - Combat-log state is persisted per-player in plugins/SBPCLifesteal/Players/<uuid>.yml.
 */
public class CombatLogManager implements Listener {

    // How long a player remains combat-tagged after being hit (ms)
    private static final long COMBAT_TAG_DURATION_MS = 5L * 60L * 1000L;
    // How long a combat-log zombie stays in the world before despawning (ms)
    private static final long ZOMBIE_TTL_MS = 2L * 60L * 1000L;

    private final SBPCLifestealPlugin plugin;

    // last time a player was damaged by another player (ms since epoch)
    private final Map<UUID, Long> combatTagUntil = new HashMap<>();

    // per-player combat log entries
    private final Map<UUID, CombatLogEntry> entries = new HashMap<>();

    // mapping from zombie UUID -> player UUID
    private final Map<UUID, UUID> zombieToPlayer = new HashMap<>();

    private File playersFolder;

    public CombatLogManager(SBPCLifestealPlugin plugin) {
        this.plugin = plugin;
        this.playersFolder = new File(plugin.getDataFolder(), "Players");
        if (!playersFolder.exists()) {
            playersFolder.mkdirs();
        }

        // Schedule TTL task
        new BukkitRunnable() {
            @Override
            public void run() {
                tickZombieTTL();
            }
        }.runTaskTimer(plugin, 20L * 10L, 20L * 10L); // every 10 seconds
    }

    // ------------------------------------------------------------------------
    // Combat tagging
    // ------------------------------------------------------------------------

    public void tagCombat(UUID victim, long nowMillis) {
        combatTagUntil.put(victim, nowMillis + COMBAT_TAG_DURATION_MS);
    }

    public void clearTag(UUID uuid) {
        combatTagUntil.remove(uuid);
    }

    public boolean isCombatTagged(UUID uuid, long nowMillis) {
        Long until = combatTagUntil.get(uuid);
        if (until == null) return false;
        if (until < nowMillis) {
            combatTagUntil.remove(uuid);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------
    // Public lifecycle hooks
    // ------------------------------------------------------------------------

    /**
     * Called from plugin.onEnable() after Players folder and configs exist.
     */
    public void loadAllEntries() {
        if (!playersFolder.exists() || !playersFolder.isDirectory()) {
            return;
        }

        File[] files = playersFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            String uuidStr = (dot == -1) ? name : name.substring(0, dot);
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException ex) {
                continue;
            }

            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            if (!cfg.getBoolean("combat-log.active", false)) {
                continue;
            }

            CombatLogEntry entry = loadEntryFromConfig(uuid, cfg.getConfigurationSection("combat-log"));
            if (entry == null) {
                continue;
            }

            entries.put(uuid, entry);
            if (entry.isZombieAlive()) {
                // Re-spawn zombie if TTL not exceeded
                long now = System.currentTimeMillis();
                if (now - entry.getSpawnTimeMillis() <= ZOMBIE_TTL_MS) {
                    spawnZombieForEntry(entry);
                } else {
                    // TTL expired while server was offline, treat as safe return case
                    entry.setZombieAlive(false);
                    entry.setZombieId(null);
                }
            }
        }
    }

    /**
     * Called from plugin.onDisable() to persist any active combat-log entries.
     */
    public void saveAllEntries() {
        if (!playersFolder.exists() && !playersFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create Players folder for combat log persistence.");
            return;
        }

        for (Map.Entry<UUID, CombatLogEntry> mapEntry : entries.entrySet()) {
            UUID uuid = mapEntry.getKey();
            CombatLogEntry entry = mapEntry.getValue();
            File file = new File(playersFolder, uuid.toString() + ".yml");
            YamlConfiguration cfg = file.exists()
                    ? YamlConfiguration.loadConfiguration(file)
                    : new YamlConfiguration();

            saveEntryToConfig(entry, cfg.createSection("combat-log"));
            cfg.set("combat-log.active", true);

            try {
                cfg.save(file);
            } catch (IOException ex) {
                plugin.getLogger().warning("Failed to save combat-log data for " + uuid + ": " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------
    // Player lifecycle events
    // ------------------------------------------------------------------------

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (player.isBanned()) {
            return;
        }

        if (!isCombatTagged(uuid, now)) {
            return;
        }

        // Create entry and spawn zombie
        CombatLogEntry entry = new CombatLogEntry(uuid);

        Location loc = player.getLocation();
        entry.setWorldName(loc.getWorld().getName());
        entry.setX(loc.getX());
        entry.setY(loc.getY());
        entry.setZ(loc.getZ());
        entry.setYaw(loc.getYaw());
        entry.setPitch(loc.getPitch());

        double health = player.getHealth();
        double maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue();
        entry.setHealth(health);
        entry.setMaxHealth(maxHealth);

        entry.setSpawnTimeMillis(now);
        entry.setZombieAlive(true);

        entry.setContents(player.getInventory().getContents());
        entry.setArmor(player.getInventory().getArmorContents());

        entry.setXpLevel(player.getLevel());
        entry.setXpProgress(player.getExp());
        entry.setXpTotal(player.getTotalExperience());

        // Clear player inventory/xp so we don't duplicate anything if they die elsewhere
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[0]);
        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);

        // Store entry and spawn the zombie
        entries.put(uuid, entry);
        spawnZombieForEntry(entry);

        // Persist immediately so it's safe on crash
        saveSingleEntry(entry);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        CombatLogEntry entry = entries.remove(uuid);
        if (entry == null) {
            // No active combat-log state, but might have stale data in file; clear it
            clearCombatLogSection(uuid);
            clearTag(uuid);
            return;
        }

        // Safe-return case: zombie still alive or TTL expired without kill
        // Despawn zombie if still around
        if (entry.isZombieAlive()) {
            despawnZombie(entry.getZombieId());
        }

        // Restore items / armor / xp
        player.getInventory().setContents(entry.getContents());
        player.getInventory().setArmorContents(entry.getArmor());
        player.setLevel(entry.getXpLevel());
        player.setExp(entry.getXpProgress());
        player.setTotalExperience(entry.getXpTotal());

        // Health: keep current max health in case lifesteal changed while offline,
        // but clamp to stored health if higher than max.
        double maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue();
        double targetHealth = Math.min(entry.getHealth(), maxHealth);
        if (targetHealth <= 0.0) {
            targetHealth = maxHealth;
        }
        player.setHealth(targetHealth);

        // Clear state from disk
        clearCombatLogSection(uuid);
        clearTag(uuid);
    }

    // ------------------------------------------------------------------------
    // Damage events & kills
    // ------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();

        // Combat tag for players
        if (victim instanceof Player victimPlayer) {
            Player damagerPlayer = null;

            if (event.getDamager() instanceof Player p) {
                damagerPlayer = p;
            } else if (event.getDamager() instanceof Projectile proj &&
                    proj.getShooter() instanceof Player shooter) {
                damagerPlayer = shooter;
            }

            if (damagerPlayer != null) {
                tagCombat(victimPlayer.getUniqueId(), System.currentTimeMillis());
            }
        }

        // Only players are allowed to damage combat-log zombies
        if (isCombatLogZombie(victim)) {
            Player damagerPlayer = null;

            if (event.getDamager() instanceof Player p) {
                damagerPlayer = p;
            } else if (event.getDamager() instanceof Projectile proj &&
                    proj.getShooter() instanceof Player shooter) {
                damagerPlayer = shooter;
            }

            if (damagerPlayer == null) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!isCombatLogZombie(entity)) {
            return;
        }

        // Only allow damage from players (handled in EntityDamageByEntityEvent)
        switch (event.getCause()) {
            case ENTITY_ATTACK:
            case PROJECTILE:
                // These are handled above; allow through here
                break;
            default:
                // Cancel all environmental / mob damage
                event.setCancelled(true);
                break;
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (!isCombatLogZombie(entity)) {
            return;
        }

        UUID zombieId = entity.getUniqueId();
        UUID playerId = zombieToPlayer.remove(zombieId);
        if (playerId == null) {
            return;
        }

        CombatLogEntry entry = entries.remove(playerId);
        if (entry == null) {
            return;
        }

        entry.setZombieAlive(false);
        entry.setZombieId(null);

        // Determine killer
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            // No player killer -> treat as safe despawn (but this should be rare due to damage filtering)
            // Restore items to offline player file? For now, drop anyway since it's a "death".
            killer = null;
        }

        // Prevent vanilla equipment drops from the zombie itself
        event.getDrops().clear();
        event.setDroppedExp(0);

        // Drop stored items/xp at zombie location
        Location loc = entity.getLocation();
        World world = loc.getWorld();
        if (world != null) {
            if (entry.getContents() != null) {
                for (ItemStack stack : entry.getContents()) {
                    if (stack != null && stack.getType() != Material.AIR) {
                        world.dropItemNaturally(loc, stack.clone());
                    }
                }
            }
            if (entry.getArmor() != null) {
                for (ItemStack stack : entry.getArmor()) {
                    if (stack != null && stack.getType() != Material.AIR) {
                        world.dropItemNaturally(loc, stack.clone());
                    }
                }
            }
            if (entry.getXpTotal() > 0) {
                world.spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(entry.getXpTotal()));
            }
        }

        // Clear stored items/xp to avoid any later restoration
        entry.setContents(new ItemStack[0]);
        entry.setArmor(new ItemStack[0]);
        entry.setXpLevel(0);
        entry.setXpProgress(0f);
        entry.setXpTotal(0);

        // Persist a cleared state (or remove the combat-log section)
        clearCombatLogSection(playerId);

        // Apply lifesteal effects for offline kill
        if (killer != null) {
            plugin.handleOfflineCombatLogKill(playerId, killer);
        }
    }


    // ------------------------------------------------------------------------
    // TTL check task
    // ------------------------------------------------------------------------

    private void tickZombieTTL() {
        long now = System.currentTimeMillis();

        for (Iterator<Map.Entry<UUID, CombatLogEntry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, CombatLogEntry> mapEntry = it.next();
            CombatLogEntry entry = mapEntry.getValue();

            if (!entry.isZombieAlive()) {
                // nothing to do; player will get items back on join and we clear on join
                continue;
            }

            if (now - entry.getSpawnTimeMillis() > ZOMBIE_TTL_MS) {
                // TTL expired: despawn zombie, but keep stored items/xp for safe return
                despawnZombie(entry.getZombieId());
                entry.setZombieAlive(false);
                entry.setZombieId(null);

                // Persist updated state so it's safe across restarts
                saveSingleEntry(entry);
            }
        }
    }

    // ------------------------------------------------------------------------
    // Spawn / despawn helpers
    // ------------------------------------------------------------------------

    private void spawnZombieForEntry(CombatLogEntry entry) {
        Location loc = entry.toLocation(plugin.getServer());
        if (loc == null) {
            plugin.getLogger().warning("Could not spawn combat-log zombie for " + entry.getPlayerId()
                    + ": invalid world.");
            return;
        }

        World world = loc.getWorld();
        if (world == null) {
            return;
        }

        Zombie zombie = world.spawn(loc, Zombie.class, z -> {
            z.setAdult();
            z.setAI(false);
            z.setCanPickupItems(false);
            z.setRemoveWhenFarAway(false);
            z.setPersistent(true);

            // Health & max health
            var attr = z.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (attr != null) {
                attr.setBaseValue(entry.getMaxHealth());
            }
            z.setHealth(Math.max(1.0, Math.min(entry.getHealth(), entry.getMaxHealth())));

            // Name & glowing: show through walls
            OfflinePlayer offline = Bukkit.getOfflinePlayer(entry.getPlayerId());
            z.setCustomName(offline.getName() != null ? offline.getName() : entry.getPlayerId().toString());
            z.setCustomNameVisible(true);
            z.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0, false, false, false));

            // Visual equipment (copies)
            EntityEquipment equip = z.getEquipment();
            if (equip != null) {
                ItemStack[] armor = entry.getArmor();
                if (armor != null && armor.length >= 4) {
                    equip.setBoots(cloneOrNull(armor[0]));
                    equip.setLeggings(cloneOrNull(armor[1]));
                    equip.setChestplate(cloneOrNull(armor[2]));
                    equip.setHelmet(cloneOrNull(armor[3]));
                }
                ItemStack[] contents = entry.getContents();
                if (contents != null && contents.length > 0) {
                    // Use first hotbar slot as main-hand visual
                    ItemStack first = null;
                    for (ItemStack stack : contents) {
                        if (stack != null && stack.getType() != Material.AIR) {
                            first = stack;
                            break;
                        }
                    }
                    equip.setItemInMainHand(cloneOrNull(first));
                }
            }
        });

        entry.setZombieId(zombie.getUniqueId());
        entry.setZombieAlive(true);
        zombieToPlayer.put(zombie.getUniqueId(), entry.getPlayerId());
    }

    private void despawnZombie(UUID zombieId) {
        if (zombieId == null) return;
        Entity entity = null;
        entity = Bukkit.getEntity(zombieId);
        
        if (entity != null && entity instanceof Zombie) {
            entity.remove();
        }
        zombieToPlayer.remove(zombieId);
    }

    private boolean isCombatLogZombie(Entity entity) {
        if (!(entity instanceof Zombie)) return false;
        return zombieToPlayer.containsKey(entity.getUniqueId());
    }

    private ItemStack cloneOrNull(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return null;
        return stack.clone();
    }

    // ------------------------------------------------------------------------
    // Per-player file persistence helpers
    // ------------------------------------------------------------------------

    private CombatLogEntry loadEntryFromConfig(UUID uuid, ConfigurationSection sec) {
        if (sec == null) return null;

        CombatLogEntry entry = new CombatLogEntry(uuid);

        entry.setWorldName(sec.getString("world", null));
        entry.setX(sec.getDouble("x", 0.0));
        entry.setY(sec.getDouble("y", 0.0));
        entry.setZ(sec.getDouble("z", 0.0));
        entry.setYaw((float) sec.getDouble("yaw", 0.0));
        entry.setPitch((float) sec.getDouble("pitch", 0.0));

        entry.setHealth(sec.getDouble("health", 20.0));
        entry.setMaxHealth(sec.getDouble("max-health", 20.0));
        entry.setSpawnTimeMillis(sec.getLong("spawn-time", System.currentTimeMillis()));
        entry.setZombieAlive(sec.getBoolean("zombie-alive", false));

        String zombieIdStr = sec.getString("zombie-uuid", null);
        if (zombieIdStr != null && !zombieIdStr.isEmpty()) {
            try {
                entry.setZombieId(UUID.fromString(zombieIdStr));
            } catch (IllegalArgumentException ignored) {
            }
        }

        entry.setXpLevel(sec.getInt("xp.level", 0));
        entry.setXpProgress((float) sec.getDouble("xp.progress", 0.0));
        entry.setXpTotal(sec.getInt("xp.total", 0));

        // Items are stored using Bukkit's default config serialization
        Object contentsObj = sec.get("contents");
        if (contentsObj instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<ItemStack> list = (List<ItemStack>) contentsObj;
            entry.setContents(list.toArray(new ItemStack[0]));
        }

        Object armorObj = sec.get("armor");
        if (armorObj instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<ItemStack> list = (List<ItemStack>) armorObj;
            entry.setArmor(list.toArray(new ItemStack[0]));
        }

        return entry;
    }

    private void saveEntryToConfig(CombatLogEntry entry, ConfigurationSection sec) {
        sec.set("world", entry.getWorldName());
        sec.set("x", entry.getX());
        sec.set("y", entry.getY());
        sec.set("z", entry.getZ());
        sec.set("yaw", entry.getYaw());
        sec.set("pitch", entry.getPitch());

        sec.set("health", entry.getHealth());
        sec.set("max-health", entry.getMaxHealth());
        sec.set("spawn-time", entry.getSpawnTimeMillis());
        sec.set("zombie-alive", entry.isZombieAlive());
        sec.set("zombie-uuid", entry.getZombieId() != null ? entry.getZombieId().toString() : null);

        sec.set("xp.level", entry.getXpLevel());
        sec.set("xp.progress", entry.getXpProgress());
        sec.set("xp.total", entry.getXpTotal());

        // Bukkit will serialize ItemStacks automatically
        sec.set("contents", entry.getContents() != null ? Arrays.asList(entry.getContents()) : null);
        sec.set("armor", entry.getArmor() != null ? Arrays.asList(entry.getArmor()) : null);
    }

    private void saveSingleEntry(CombatLogEntry entry) {
        UUID uuid = entry.getPlayerId();
        File file = new File(playersFolder, uuid.toString() + ".yml");
        YamlConfiguration cfg = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();

        ConfigurationSection sec = cfg.getConfigurationSection("combat-log");
        if (sec == null) {
            sec = cfg.createSection("combat-log");
        }
        saveEntryToConfig(entry, sec);
        cfg.set("combat-log.active", true);

        try {
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save combat-log data for " + uuid + ": " + ex.getMessage());
        }
    }

    private void clearCombatLogSection(UUID uuid) {
        File file = new File(playersFolder, uuid.toString() + ".yml");
        if (!file.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        cfg.set("combat-log", null);
        cfg.set("combat-log.active", null);

        try {
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to clear combat-log data for " + uuid + ": " + ex.getMessage());
        }
    }
}
