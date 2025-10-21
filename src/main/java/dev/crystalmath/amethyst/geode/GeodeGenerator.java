package dev.crystalmath.amethyst.geode;

import dev.crystalmath.CrystalMathPlugin;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class GeodeGenerator {
    private static final int MIN_RADIUS = 3;
    private static final int MAX_RADIUS = 5;
    private static final double MAX_AIR_RATIO = 0.35D;
    private static final double MIN_SURFACE_CLEARANCE = 4.0D;
    private static final double BASE_SEPARATION_BUFFER = 3.0D;
    private static final double BUDDING_CHANCE = 0.35D;

    private final CrystalMathPlugin plugin;

    public GeodeGenerator(CrystalMathPlugin plugin) {
        this.plugin = plugin;
    }

    public record PlannedGeode(Location center, int radius) {}

    public List<PlannedGeode> planGeodes(World world, Set<Chunk> chunks, int geodeCount) {
        List<PlannedGeode> planned = new ArrayList<>();
        if (world == null || chunks.isEmpty() || geodeCount <= 0) {
            return planned;
        }

        List<Chunk> chunkList = new ArrayList<>(chunks);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int attemptsPerGeode = Math.max(geodeCount * 30, 90);

        for (int i = 0; i < geodeCount; i++) {
            boolean found = false;
            for (int attempt = 0; attempt < attemptsPerGeode; attempt++) {
                Chunk chunk = chunkList.get(random.nextInt(chunkList.size()));
                int radius = random.nextInt(MIN_RADIUS, MAX_RADIUS + 1);
                Location candidate = findGeodeLocation(world, chunk, radius, random);
                if (candidate == null) {
                    continue;
                }

                if (!isFarFromExisting(candidate, radius, planned)) {
                    continue;
                }

                planned.add(new PlannedGeode(candidate, radius));
                found = true;
                break;
            }

            if (!found) {
                break;
            }
        }

        return planned;
    }

    public void generateGeodes(List<PlannedGeode> geodes) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (PlannedGeode geode : geodes) {
            carveGeode(geode, random);
        }
    }

    private Location findGeodeLocation(World world, Chunk chunk, int radius, ThreadLocalRandom random) {
        int minY = Math.max(world.getMinHeight() + radius + 2, -48);
        int maxY = Math.min(world.getMaxHeight() - radius - 2, 40);
        if (minY >= maxY) {
            return null;
        }

        for (int attempt = 0; attempt < 60; attempt++) {
            int blockX = (chunk.getX() << 4) + random.nextInt(16);
            int blockZ = (chunk.getZ() << 4) + random.nextInt(16);
            int blockY = random.nextInt(maxY - minY) + minY;

            Location candidate = new Location(world, blockX, blockY, blockZ);
            if (isSuitableLocation(candidate, radius)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isSuitableLocation(Location location, int radius) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }

        int centerX = location.getBlockX();
        int centerY = location.getBlockY();
        int centerZ = location.getBlockZ();

        if (centerY - radius <= world.getMinHeight() || centerY + radius >= world.getMaxHeight()) {
            return false;
        }

        int surface = world.getHighestBlockYAt(centerX, centerZ);
        if (centerY >= surface - MIN_SURFACE_CLEARANCE) {
            return false;
        }

        int total = 0;
        int airBlocks = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (distance > radius + 0.5D) {
                        continue;
                    }

                    Block block = world.getBlockAt(centerX + dx, centerY + dy, centerZ + dz);
                    if (block.isLiquid()) {
                        return false;
                    }

                    total++;
                    if (!block.getType().isSolid()) {
                        airBlocks++;
                    }
                }
            }
        }

        if (total == 0) {
            return false;
        }

        double airRatio = (double) airBlocks / (double) total;
        if (airRatio > MAX_AIR_RATIO) {
            return false;
        }

        for (int offset = 1; offset <= Math.min(3, radius); offset++) {
            Block support = world.getBlockAt(centerX, centerY - offset, centerZ);
            if (!support.getType().isSolid() || support.isLiquid()) {
                return false;
            }
        }

        return true;
    }

    private boolean isFarFromExisting(Location candidate, int radius, List<PlannedGeode> existing) {
        for (PlannedGeode other : existing) {
            if (!other.center().getWorld().equals(candidate.getWorld())) {
                continue;
            }

            double required = radius + other.radius() + BASE_SEPARATION_BUFFER;
            if (candidate.distanceSquared(other.center()) < required * required) {
                return false;
            }
        }
        return true;
    }

    private void carveGeode(PlannedGeode geode, ThreadLocalRandom random) {
        Location center = geode.center();
        World world = center.getWorld();
        if (world == null) {
            plugin.getLogger().warning("Skipping geode generation because the world was not loaded for center " + center);
            return;
        }

        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        int radius = geode.radius();

        double calciteThreshold = radius - 0.5D;
        double crystalThreshold = Math.max(1.5D, radius - 1.5D);
        double cavityThreshold = Math.max(0.5D, radius - 2.5D);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (distance > radius + 0.5D) {
                        continue;
                    }

                    Block block = world.getBlockAt(centerX + dx, centerY + dy, centerZ + dz);
                    if (block.getType() == Material.BEDROCK) {
                        continue;
                    }

                    Material replacement = Material.SMOOTH_BASALT;
                    if (distance <= calciteThreshold) {
                        replacement = Material.CALCITE;
                    }
                    if (distance <= crystalThreshold) {
                        replacement = random.nextDouble() < BUDDING_CHANCE ? Material.BUDDING_AMETHYST : Material.AMETHYST_BLOCK;
                    }
                    if (distance <= cavityThreshold) {
                        replacement = Material.AIR;
                    }

                    block.setType(replacement, false);
                }
            }
        }
    }
}
