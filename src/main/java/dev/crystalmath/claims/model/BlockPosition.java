package dev.crystalmath.claims.model;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.Objects;

public class BlockPosition {
    private int x;
    private int y;
    private int z;

    public BlockPosition() {
    }

    public BlockPosition(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static BlockPosition fromBlock(Block block) {
        return new BlockPosition(block.getX(), block.getY(), block.getZ());
    }

    public Location toLocation(org.bukkit.World world) {
        return new Location(world, x + 0.5, y + 0.5, z + 0.5);
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockPosition that)) return false;
        return x == that.x && y == that.y && z == that.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }

    @Override
    public String toString() {
        return x + ":" + y + ":" + z;
    }
}
