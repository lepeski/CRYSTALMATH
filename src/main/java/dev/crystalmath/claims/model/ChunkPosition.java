package dev.crystalmath.claims.model;

import org.bukkit.Chunk;
import org.bukkit.Location;

import java.util.Objects;

public class ChunkPosition {
    private int x;
    private int z;

    public ChunkPosition() {
    }

    public ChunkPosition(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public static ChunkPosition fromChunk(Chunk chunk) {
        return new ChunkPosition(chunk.getX(), chunk.getZ());
    }

    public static ChunkPosition fromLocation(Location location) {
        return new ChunkPosition(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChunkPosition that)) return false;
        return x == that.x && z == that.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, z);
    }

    @Override
    public String toString() {
        return x + ":" + z;
    }
}
