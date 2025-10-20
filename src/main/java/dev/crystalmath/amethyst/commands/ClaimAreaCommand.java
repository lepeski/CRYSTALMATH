package dev.crystalmath.amethyst.commands;

import dev.crystalmath.CrystalMathPlugin;
import dev.crystalmath.amethyst.AreaManager;
import dev.crystalmath.amethyst.MintLedger;
import dev.crystalmath.amethyst.MintLedger.ChunkCoordinate;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class ClaimAreaCommand implements CommandExecutor {
    private static final int GEODE_RADIUS = 4;
    private static final double MAX_AIR_RATIO = 0.35D;
    private static final double MIN_GEODE_DISTANCE_SQUARED = 144.0D; // 12 blocks squared

    private final CrystalMathPlugin plugin;
    private final MintLedger ledger;
    private final AreaManager areaManager;

    public ClaimAreaCommand(CrystalMathPlugin plugin, MintLedger ledger, AreaManager areaManager) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.areaManager = areaManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can claim areas.");
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <areaId> <targetCrystals> <radiusInChunks> <geodeCount>");
            return true;
        }

        String areaId = args[0];
        if (areaId.isBlank()) {
            sender.sendMessage(ChatColor.RED + "Area ID cannot be blank.");
            return true;
        }

        int targetCrystals;
        int radius;
        int geodeCount;
        try {
            targetCrystals = Integer.parseInt(args[1]);
            radius = Integer.parseInt(args[2]);
            geodeCount = Integer.parseInt(args[3]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Target crystals, radius, and geode count must be numbers.");
            return true;
        }

        if (targetCrystals < 0) {
            sender.sendMessage(ChatColor.RED + "Target crystals cannot be negative.");
            return true;
        }
        if (radius <= 0) {
            sender.sendMessage(ChatColor.RED + "Radius must be at least 1 chunk.");
            return true;
        }
        if (geodeCount <= 0) {
            sender.sendMessage(ChatColor.RED + "Geode count must be at least 1.");
            return true;
        }

        if (ledger.areaExists(areaId)) {
            sender.sendMessage(ChatColor.RED + "An area with ID '" + areaId + "' already exists in the ledger.");
            return true;
        }

        World world = player.getWorld();
        Chunk centerChunk = player.getLocation().getChunk();
        Set<Chunk> chunkSet = collectChunks(world, centerChunk.getX(), centerChunk.getZ(), radius);
        if (chunkSet.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Unable to determine chunks for the requested radius.");
            return true;
        }

        List<Location> geodeCenters = planGeodeCenters(world, chunkSet, geodeCount);
        if (geodeCenters.size() != geodeCount) {
            sender.sendMessage(ChatColor.RED + "Unable to locate suitable underground space for all geodes. More solid ground is required.");
            return true;
        }

        Set<ChunkCoordinate> chunkCoordinates = new HashSet<>();
        for (Chunk chunk : chunkSet) {
            chunkCoordinates.add(new ChunkCoordinate(chunk.getX(), chunk.getZ()));
        }

        try {
            ledger.createArea(areaId, world.getName(), targetCrystals, chunkCoordinates);
        } catch (MintLedger.LedgerException exception) {
            sender.sendMessage(ChatColor.RED + "Failed to record the claimed area in the ledger: " + exception.getMessage());
            plugin.getLogger().severe("Failed to create area '" + areaId + "': " + exception.getMessage());
            return true;
        }

        for (Location center : geodeCenters) {
            generateGeode(center);
        }

        persistAreaInConfig(areaId, world.getName(), targetCrystals, chunkSet);

        sender.sendMessage(ChatColor.GREEN + "Claimed area '" + areaId + "' across " + chunkSet.size() + " chunks and generated " + geodeCount + " geodes.");
        sender.sendMessage(ChatColor.GRAY + "Chunks: " + String.join(", ", AreaManager.toChunkKeys(chunkSet)));
        return true;
    }

    private Set<Chunk> collectChunks(World world, int centerX, int centerZ, int radius) {
        Set<Chunk> chunks = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Chunk chunk = world.getChunkAt(centerX + dx, centerZ + dz);
                chunk.load();
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    private List<Location> planGeodeCenters(World world, Set<Chunk> chunks, int geodeCount) {
        List<Location> centers = new ArrayList<>();
        if (chunks.isEmpty()) {
            return centers;
        }

        List<Chunk> chunkList = new ArrayList<>(chunks);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int attemptsPerGeode = Math.max(geodeCount * 20, 60);

        for (int i = 0; i < geodeCount; i++) {
            boolean found = false;
            for (int attempt = 0; attempt < attemptsPerGeode; attempt++) {
                Chunk chunk = chunkList.get(random.nextInt(chunkList.size()));
                Location candidate = findGeodeLocation(world, chunk, random);
                if (candidate == null) {
                    continue;
                }

                if (!isFarFromExisting(candidate, centers)) {
                    continue;
                }

                centers.add(candidate);
                found = true;
                break;
            }

            if (!found) {
                break;
            }
        }

        return centers;
    }

    private Location findGeodeLocation(World world, Chunk chunk, ThreadLocalRandom random) {
        int minY = Math.max(world.getMinHeight() + GEODE_RADIUS + 2, -48);
        int maxY = Math.min(world.getMaxHeight() - GEODE_RADIUS - 2, 40);
        if (minY >= maxY) {
            return null;
        }

        int attempts = 40;
        for (int i = 0; i < attempts; i++) {
            int blockX = (chunk.getX() << 4) + random.nextInt(16);
            int blockZ = (chunk.getZ() << 4) + random.nextInt(16);
            int blockY = random.nextInt(maxY - minY) + minY;

            Location location = new Location(world, blockX, blockY, blockZ);
            if (isSuitableLocation(location)) {
                return location;
            }
        }
        return null;
    }

    private boolean isSuitableLocation(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }

        int centerX = location.getBlockX();
        int centerY = location.getBlockY();
        int centerZ = location.getBlockZ();

        if (centerY - GEODE_RADIUS <= world.getMinHeight() || centerY + GEODE_RADIUS >= world.getMaxHeight()) {
            return false;
        }

        int surface = world.getHighestBlockYAt(centerX, centerZ);
        if (centerY >= surface - 4) {
            return false;
        }

        Block centerBlock = world.getBlockAt(centerX, centerY, centerZ);
        if (!centerBlock.getType().isSolid() || centerBlock.isLiquid()) {
            return false;
        }

        int total = 0;
        int airBlocks = 0;
        for (int dx = -GEODE_RADIUS; dx <= GEODE_RADIUS; dx++) {
            for (int dy = -GEODE_RADIUS; dy <= GEODE_RADIUS; dy++) {
                for (int dz = -GEODE_RADIUS; dz <= GEODE_RADIUS; dz++) {
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (distance > GEODE_RADIUS + 0.5D) {
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

        for (int offset = 1; offset <= 3; offset++) {
            Block support = world.getBlockAt(centerX, centerY - offset, centerZ);
            if (!support.getType().isSolid() || support.isLiquid()) {
                return false;
            }
        }

        return true;
    }

    private boolean isFarFromExisting(Location candidate, List<Location> existing) {
        for (Location other : existing) {
            if (!other.getWorld().equals(candidate.getWorld())) {
                continue;
            }
            if (other.distanceSquared(candidate) < MIN_GEODE_DISTANCE_SQUARED) {
                return false;
            }
        }
        return true;
    }

    private void generateGeode(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();

        for (int dx = -GEODE_RADIUS; dx <= GEODE_RADIUS; dx++) {
            for (int dy = -GEODE_RADIUS; dy <= GEODE_RADIUS; dy++) {
                for (int dz = -GEODE_RADIUS; dz <= GEODE_RADIUS; dz++) {
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (distance > GEODE_RADIUS + 0.5D) {
                        continue;
                    }

                    Block block = world.getBlockAt(centerX + dx, centerY + dy, centerZ + dz);
                    if (block.getType() == Material.BEDROCK) {
                        continue;
                    }

                    Material target = Material.SMOOTH_BASALT;
                    if (distance <= 3.5D) {
                        target = Material.CALCITE;
                    }
                    if (distance <= 2.5D) {
                        target = Material.BUDDING_AMETHYST;
                    }
                    if (distance <= 1.5D) {
                        target = Material.AIR;
                    }

                    block.setType(target, false);
                }
            }
        }
    }

    private void persistAreaInConfig(String areaId, String world, int targetCrystals, Set<Chunk> chunkSet) {
        List<Map<?, ?>> existing = plugin.getConfig().getMapList("areas");
        List<Map<String, Object>> updated = new ArrayList<>();

        for (Map<?, ?> entry : existing) {
            if (entry == null) {
                continue;
            }

            Object idObject = entry.get("id");
            if (idObject instanceof String id && id.equalsIgnoreCase(areaId)) {
                continue;
            }

            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> rawEntry : entry.entrySet()) {
                if (rawEntry.getKey() instanceof String key) {
                    copy.put(key, rawEntry.getValue());
                }
            }
            updated.add(copy);
        }

        Map<String, Object> newEntry = new java.util.LinkedHashMap<>();
        newEntry.put("id", areaId);
        newEntry.put("world", world);
        newEntry.put("crystals", targetCrystals);
        newEntry.put("chunks", new ArrayList<>(AreaManager.toChunkKeys(chunkSet)));
        updated.add(newEntry);

        plugin.getConfig().set("areas", updated);
        plugin.saveConfig();
    }
}
