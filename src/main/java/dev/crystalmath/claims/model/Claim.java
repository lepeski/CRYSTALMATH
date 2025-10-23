package dev.crystalmath.claims.model;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class Claim {
    public static final double DEFAULT_RADIUS = 33.0D;

    private UUID owner;
    private String world;
    private BlockPosition beacon;
    private Set<ChunkPosition> chunks;
    private Set<UUID> trusted;
    double radius;

    public Claim() {
    }

    public Claim(UUID owner, String world, BlockPosition beacon, Set<ChunkPosition> chunks) {
        this.owner = owner;
        this.world = world;
        this.beacon = beacon;
        this.chunks = chunks;
        this.trusted = new HashSet<>();
        this.radius = DEFAULT_RADIUS;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getWorld() {
        return world;
    }

    public BlockPosition getBeacon() {
        return beacon;
    }

    public Set<ChunkPosition> getChunks() {
        if (chunks == null) {
            chunks = new HashSet<>();
        }
        return chunks;
    }

    public Set<UUID> getTrusted() {
        if (trusted == null) {
            trusted = new HashSet<>();
        }
        return trusted;
    }

    public double getRadius() {
        return radius <= 0 ? DEFAULT_RADIUS : radius;
    }

    public void setRadius(double radius) {
        this.radius = radius;
    }

    public boolean isTrusted(UUID uuid) {
        return uuid.equals(owner) || getTrusted().contains(uuid);
    }

    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!Objects.equals(location.getWorld().getName(), world)) {
            return false;
        }
        double radiusSquared = getRadius() * getRadius();
        double dx = location.getX() - (beacon.getX() + 0.5);
        double dy = location.getY() - (beacon.getY() + 0.5);
        double dz = location.getZ() - (beacon.getZ() + 0.5);
        return (dx * dx + dy * dy + dz * dz) <= radiusSquared;
    }

    public Location getBeaconLocation(World world) {
        return beacon.toLocation(world);
    }

    public String getBeaconKey() {
        return world + ":" + beacon;
    }
}
