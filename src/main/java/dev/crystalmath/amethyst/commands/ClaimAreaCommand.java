package dev.crystalmath.amethyst.commands;

import dev.crystalmath.CrystalMathPlugin;
import dev.crystalmath.amethyst.AreaManager;
import dev.crystalmath.amethyst.MintLedger;
import dev.crystalmath.amethyst.MintLedger.ChunkCoordinate;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.World;
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

public class ClaimAreaCommand implements CommandExecutor {
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

        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <areaId> <targetCrystals> <radiusInChunks>");
            return true;
        }

        String areaId = args[0];
        if (areaId.isBlank()) {
            sender.sendMessage(ChatColor.RED + "Area ID cannot be blank.");
            return true;
        }

        int targetCrystals;
        int radius;
        try {
            targetCrystals = Integer.parseInt(args[1]);
            radius = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Target crystals and radius must be numbers.");
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

        persistAreaInConfig(areaId, world.getName(), targetCrystals, chunkSet);

        sender.sendMessage(ChatColor.GREEN + "Claimed area '" + areaId + "' across " + chunkSet.size() + " chunks.");
        sender.sendMessage(ChatColor.GRAY + "Use /spawngeodes " + areaId + " <geodeCount> to generate supporting geodes when ready.");
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
