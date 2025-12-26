package me.BaddCamden.SBPCLifesteal.combat;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Stored state when a player combat logs and is represented by a zombie.
 */
public class CombatLogEntry {

    private final UUID playerId;

    private UUID zombieId; // UUID of the spawned zombie, if alive

    private String worldName;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    private double health;
    private double maxHealth;

    private long spawnTimeMillis;
    private boolean zombieAlive;

    private ItemStack[] contents;
    private ItemStack[] armor;

    private int xpLevel;
    private float xpProgress;
    private int xpTotal;

    /**
     * Create an empty combat log record for the specified player.
     * All other fields are expected to be populated by the manager before persistence or use.
     */
    public CombatLogEntry(UUID playerId) {
        this.playerId = playerId;
    }

    /**
     * Unique identifier of the player who logged out in combat.
     */
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * UUID of the spawned zombie that represents the player, if present.
     */
    public UUID getZombieId() {
        return zombieId;
    }

    /**
     * Record the unique id of the zombie tied to this entry.
     */
    public void setZombieId(UUID zombieId) {
        this.zombieId = zombieId;
    }

    /**
     * Name of the world where the player logged out.
     */
    public String getWorldName() {
        return worldName;
    }

    /**
     * Store the world name so the zombie can be respawned later.
     */
    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    /**
     * X coordinate of the logout location.
     */
    public double getX() {
        return x;
    }

    /**
     * Set the X coordinate for the zombie spawn point.
     */
    public void setX(double x) {
        this.x = x;
    }

    /**
     * Y coordinate of the logout location.
     */
    public double getY() {
        return y;
    }

    /**
     * Set the Y coordinate for the zombie spawn point.
     */
    public void setY(double y) {
        this.y = y;
    }

    /**
     * Z coordinate of the logout location.
     */
    public double getZ() {
        return z;
    }

    /**
     * Set the Z coordinate for the zombie spawn point.
     */
    public void setZ(double z) {
        this.z = z;
    }

    /**
     * Yaw of the logout location.
     */
    public float getYaw() {
        return yaw;
    }

    /**
     * Set the yaw so the zombie faces the same direction as the player.
     */
    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    /**
     * Pitch of the logout location.
     */
    public float getPitch() {
        return pitch;
    }

    /**
     * Set the pitch so the zombie matches the player's look direction.
     */
    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    /**
     * Convert the stored coordinates into a Bukkit Location for spawning.
     */
    public Location toLocation(org.bukkit.Server server) {
        if (worldName == null) return null;
        var world = server.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * Stored health value at logout (current health of the player).
     */
    public double getHealth() {
        return health;
    }

    /**
     * Capture the health the player had when logging out.
     */
    public void setHealth(double health) {
        this.health = health;
    }

    /**
     * Stored maximum health for the player at logout time.
     */
    public double getMaxHealth() {
        return maxHealth;
    }

    /**
     * Record the maximum health in case the zombie needs to mirror it.
     */
    public void setMaxHealth(double maxHealth) {
        this.maxHealth = maxHealth;
    }

    /**
     * Millisecond timestamp when the zombie was spawned.
     */
    public long getSpawnTimeMillis() {
        return spawnTimeMillis;
    }

    /**
     * Persist the spawn time so TTL logic can fire across restarts.
     */
    public void setSpawnTimeMillis(long spawnTimeMillis) {
        this.spawnTimeMillis = spawnTimeMillis;
    }

    /**
     * Whether the zombie is currently considered alive in the world.
     */
    public boolean isZombieAlive() {
        return zombieAlive;
    }

    /**
     * Mark the zombie as alive or dead for restoration and TTL handling.
     */
    public void setZombieAlive(boolean zombieAlive) {
        this.zombieAlive = zombieAlive;
    }

    /**
     * Inventory contents captured from the player at logout.
     */
    public ItemStack[] getContents() {
        return contents;
    }

    /**
     * Save the player's inventory contents for restoration or dropping.
     */
    public void setContents(ItemStack[] contents) {
        this.contents = contents;
    }

    /**
     * Armor contents captured from the player at logout.
     */
    public ItemStack[] getArmor() {
        return armor;
    }

    /**
     * Save the player's armor contents for restoration or dropping.
     */
    public void setArmor(ItemStack[] armor) {
        this.armor = armor;
    }

    /**
     * Experience level value saved when the player disconnected.
     */
    public int getXpLevel() {
        return xpLevel;
    }

    /**
     * Record the player's experience level for later restoration.
     */
    public void setXpLevel(int xpLevel) {
        this.xpLevel = xpLevel;
    }

    /**
     * Fractional progress toward the next level at logout time.
     */
    public float getXpProgress() {
        return xpProgress;
    }

    /**
     * Store the fractional XP bar progress.
     */
    public void setXpProgress(float xpProgress) {
        this.xpProgress = xpProgress;
    }

    /**
     * Total experience points saved at logout.
     */
    public int getXpTotal() {
        return xpTotal;
    }

    /**
     * Record the raw XP total so restoration can be precise.
     */
    public void setXpTotal(int xpTotal) {
        this.xpTotal = xpTotal;
    }
}

