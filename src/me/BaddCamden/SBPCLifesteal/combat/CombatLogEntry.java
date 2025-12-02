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

    public CombatLogEntry(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public UUID getZombieId() {
        return zombieId;
    }

    public void setZombieId(UUID zombieId) {
        this.zombieId = zombieId;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public Location toLocation(org.bukkit.Server server) {
        if (worldName == null) return null;
        var world = server.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, x, y, z, yaw, pitch);
    }

    public double getHealth() {
        return health;
    }

    public void setHealth(double health) {
        this.health = health;
    }

    public double getMaxHealth() {
        return maxHealth;
    }

    public void setMaxHealth(double maxHealth) {
        this.maxHealth = maxHealth;
    }

    public long getSpawnTimeMillis() {
        return spawnTimeMillis;
    }

    public void setSpawnTimeMillis(long spawnTimeMillis) {
        this.spawnTimeMillis = spawnTimeMillis;
    }

    public boolean isZombieAlive() {
        return zombieAlive;
    }

    public void setZombieAlive(boolean zombieAlive) {
        this.zombieAlive = zombieAlive;
    }

    public ItemStack[] getContents() {
        return contents;
    }

    public void setContents(ItemStack[] contents) {
        this.contents = contents;
    }

    public ItemStack[] getArmor() {
        return armor;
    }

    public void setArmor(ItemStack[] armor) {
        this.armor = armor;
    }

    public int getXpLevel() {
        return xpLevel;
    }

    public void setXpLevel(int xpLevel) {
        this.xpLevel = xpLevel;
    }

    public float getXpProgress() {
        return xpProgress;
    }

    public void setXpProgress(float xpProgress) {
        this.xpProgress = xpProgress;
    }

    public int getXpTotal() {
        return xpTotal;
    }

    public void setXpTotal(int xpTotal) {
        this.xpTotal = xpTotal;
    }
}

