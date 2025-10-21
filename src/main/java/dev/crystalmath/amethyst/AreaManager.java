/*
 * Plugin made by Lixqa Development.
 * Do not share the source code
 * Website: https://lix.qa/
 * Discord: https://discord.gg/ldev
 * */

package dev.crystalmath.amethyst;

import dev.crystalmath.CrystalMathPlugin;
import dev.crystalmath.amethyst.MintLedger.AreaRecord;
import dev.crystalmath.amethyst.MintLedger.ChunkCoordinate;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AreaManager {
    private final CrystalMathPlugin plugin;
    private final MintLedger ledger;

    public AreaManager(CrystalMathPlugin plugin, MintLedger ledger) {
        this.plugin = plugin;
        this.ledger = ledger;
    }

    public record Area(String id, String world, int crystals, List<String> chunkKeys) {
        public Optional<World> resolveWorld() {
            if (world == null || world.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(Bukkit.getWorld(world));
        }
    }

    public List<Area> loadAreas() {
        Map<String, Area> areas = new LinkedHashMap<>();

        List<Map<?, ?>> rawAreas = plugin.getConfig().getMapList("areas");

        for (Map<?, ?> rawArea : rawAreas) {
            if (rawArea == null) {
                continue;
            }

            Object idObject = rawArea.get("id");
            Object worldObject = rawArea.get("world");
            Object crystalsObject = rawArea.get("crystals");
            Object chunksObject = rawArea.get("chunks");

            if (!(idObject instanceof String id) || id.isBlank()) {
                continue;
            }

            String world = worldObject instanceof String worldName ? worldName : null;
            int crystals = crystalsObject instanceof Number number ? number.intValue() : 0;
            List<String> chunks = extractChunkKeys(chunksObject);

            areas.put(id, new Area(id, world, crystals, chunks));
        }

        if (ledger != null) {
            for (AreaRecord record : ledger.listAreas()) {
                List<String> chunkKeys = new ArrayList<>();
                for (ChunkCoordinate coordinate : record.chunkCoordinates()) {
                    chunkKeys.add(coordinate.x() + "," + coordinate.z());
                }
                areas.put(record.id(), new Area(record.id(), record.world(), record.targetCrystals(), chunkKeys));
            }
        }

        return new ArrayList<>(areas.values());
    }

    public Optional<Area> findArea(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        List<Area> areas = loadAreas();
        for (Area area : areas) {
            if (area.id().equalsIgnoreCase(id)) {
                return Optional.of(area);
            }
        }
        return Optional.empty();
    }

    public Set<Chunk> getChunksFromKeys(World world, List<String> keys) {
        Set<Chunk> chunks = new HashSet<>();
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }

            String[] parts = key.split(",");
            if (parts.length != 2) {
                continue;
            }

            try {
                int x = Integer.parseInt(parts[0].trim());
                int z = Integer.parseInt(parts[1].trim());
                chunks.add(world.getChunkAt(x, z));
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning("Invalid chunk key '" + key + "' for world " + world.getName());
            }
        }
        return chunks;
    }

    public static List<String> toChunkKeys(Set<Chunk> chunks) {
        List<String> keys = new ArrayList<>();
        for (Chunk chunk : chunks) {
            keys.add(chunk.getX() + "," + chunk.getZ());
        }
        return keys;
    }

    private static List<String> extractChunkKeys(Object chunksObject) {
        if (chunksObject instanceof List<?> list) {
            List<String> keys = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof String string) {
                    keys.add(string);
                }
            }
            return keys;
        }
        return List.of();
    }
}
