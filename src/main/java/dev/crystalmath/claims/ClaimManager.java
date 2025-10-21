package dev.crystalmath.claims;

import dev.crystalmath.CrystalMathPlugin;
import dev.crystalmath.claims.model.BlockPosition;
import dev.crystalmath.claims.model.ChunkPosition;
import dev.crystalmath.claims.model.Claim;
import dev.crystalmath.claims.storage.SimpleJson;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public class ClaimManager {
    private final CrystalMathPlugin plugin;
    private final Map<String, Claim> claimsByBeacon = new ConcurrentHashMap<>();
    private final Map<ChunkPosition, Set<Claim>> claimsByChunk = new ConcurrentHashMap<>();

    public ClaimManager(CrystalMathPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        claimsByBeacon.clear();
        claimsByChunk.clear();
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File file = new File(dataFolder, "claims.json");
        if (!file.exists()) {
            return;
        }
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Object parsed = SimpleJson.parse(json);
            if (!(parsed instanceof List<?> list)) {
                return;
            }
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> map)) {
                    continue;
                }
                Claim claim = readClaim((Map<String, Object>) map);
                if (claim != null) {
                    addClaimInternal(claim, false);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to load claims: " + e.getMessage());
        }
    }

    public void save() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File file = new File(dataFolder, "claims.json");
        List<Map<String, Object>> serialized = claimsByBeacon.values().stream()
                .map(this::writeClaim)
                .collect(Collectors.toList());
        try {
            String json = SimpleJson.stringify(serialized);
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save claims: " + e.getMessage());
        }
    }

    public Optional<Claim> getClaimByBeacon(Block block) {
        if (block == null) {
            return Optional.empty();
        }
        String key = buildBeaconKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        return Optional.ofNullable(claimsByBeacon.get(key));
    }

    public Optional<Claim> getClaimAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        ChunkPosition chunk = ChunkPosition.fromLocation(location);
        Set<Claim> candidates = claimsByChunk.get(chunk);
        if (candidates != null) {
            Optional<Claim> directHit = candidates.stream()
                    .filter(claim -> claim.contains(location))
                    .findFirst();
            if (directHit.isPresent()) {
                return directHit;
            }
        }
        return claimsByBeacon.values().stream()
                .filter(claim -> claim.contains(location))
                .findFirst();
    }

    public Collection<Claim> getClaims() {
        return Collections.unmodifiableCollection(claimsByBeacon.values());
    }

    public Map<UUID, List<Claim>> getClaimsGroupedByOwner() {
        return claimsByBeacon.values().stream()
                .collect(Collectors.groupingBy(Claim::getOwner));
    }

    public boolean createClaim(Player player, Block block) {
        if (player == null || block == null || block.getType() != Material.BEACON) {
            return false;
        }
        if (!hasSkyAccess(block)) {
            player.sendMessage("§cBeacon must have an unobstructed view of the sky to create a claim.");
            return false;
        }
        if (getClaimByBeacon(block).isPresent()) {
            player.sendMessage("§cThis beacon is already part of a claim.");
            return false;
        }
        Claim claim = new Claim(player.getUniqueId(), block.getWorld().getName(), BlockPosition.fromBlock(block), computeChunks(block));
        addClaimInternal(claim, true);
        save();
        player.sendMessage("§aClaim created. Protected radius: " + (int) claim.getRadius() + " blocks.");
        return true;
    }

    public boolean removeClaim(Block block) {
        if (block == null) {
            return false;
        }
        String key = buildBeaconKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        Claim claim = claimsByBeacon.remove(key);
        if (claim == null) {
            return false;
        }
        removeFromChunkIndex(claim);
        save();
        return true;
    }

    public void trustPlayer(Claim claim, OfflinePlayer target) {
        if (claim == null || target == null) {
            return;
        }
        if (claim.getTrusted().add(target.getUniqueId())) {
            save();
        }
    }

    public void untrustPlayer(Claim claim, OfflinePlayer target) {
        if (claim == null || target == null) {
            return;
        }
        if (claim.getTrusted().remove(target.getUniqueId())) {
            save();
        }
    }

    public Optional<Claim> getClaimAtChunk(Chunk chunk) {
        if (chunk == null) {
            return Optional.empty();
        }
        Set<Claim> candidates = claimsByChunk.get(ChunkPosition.fromChunk(chunk));
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        ChunkPosition target = ChunkPosition.fromChunk(chunk);
        return candidates.stream()
                .filter(claim -> claim.getChunks().contains(target))
                .findFirst();
    }

    public Optional<Claim> getClaimByKey(String key) {
        return Optional.ofNullable(claimsByBeacon.get(key));
    }

    public BeaconTier getBeaconTier(Claim claim) {
        if (claim == null) {
            return BeaconTier.NONE;
        }
        World world = Bukkit.getWorld(claim.getWorld());
        if (world == null) {
            return BeaconTier.NONE;
        }
        Block beaconBlock = world.getBlockAt(claim.getBeacon().getX(), claim.getBeacon().getY(), claim.getBeacon().getZ());
        if (beaconBlock.getType() != Material.BEACON) {
            return BeaconTier.NONE;
        }
        int ironLayers = countIronLayers(beaconBlock);
        return BeaconTier.fromIronLayers(ironLayers);
    }

    private void addClaimInternal(Claim claim, boolean announce) {
        claimsByBeacon.put(claim.getBeaconKey(), claim);
        for (ChunkPosition chunkPosition : computeIndexedChunks(claim)) {
            claimsByChunk.computeIfAbsent(chunkPosition, ignored -> ConcurrentHashMap.newKeySet()).add(claim);
        }
        if (announce) {
            Player owner = Bukkit.getPlayer(claim.getOwner());
            if (owner != null) {
                owner.sendMessage("§aLand claim registered around the beacon.");
            }
        }
    }

    private int countIronLayers(Block beaconBlock) {
        World world = beaconBlock.getWorld();
        int layers = 0;
        for (int level = 1; level <= 4; level++) {
            int y = beaconBlock.getY() - level;
            if (y < world.getMinHeight()) {
                break;
            }
            int radius = level;
            boolean fullLayer = true;
            for (int x = beaconBlock.getX() - radius; x <= beaconBlock.getX() + radius && fullLayer; x++) {
                for (int z = beaconBlock.getZ() - radius; z <= beaconBlock.getZ() + radius; z++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type != Material.IRON_BLOCK) {
                        fullLayer = false;
                        break;
                    }
                }
            }
            if (fullLayer) {
                layers++;
            } else {
                break;
            }
        }
        return layers;
    }

    private void removeFromChunkIndex(Claim claim) {
        for (ChunkPosition chunkPosition : computeIndexedChunks(claim)) {
            Set<Claim> set = claimsByChunk.get(chunkPosition);
            if (set != null) {
                set.remove(claim);
                if (set.isEmpty()) {
                    claimsByChunk.remove(chunkPosition);
                }
            }
        }
    }

    private Set<ChunkPosition> computeIndexedChunks(Claim claim) {
        Set<ChunkPosition> positions = new HashSet<>();
        double radius = claim.getRadius();
        double centerX = claim.getBeacon().getX() + 0.5D;
        double centerZ = claim.getBeacon().getZ() + 0.5D;
        int minChunkX = (int) Math.floor((centerX - radius) / 16.0D);
        int maxChunkX = (int) Math.floor((centerX + radius) / 16.0D);
        int minChunkZ = (int) Math.floor((centerZ - radius) / 16.0D);
        int maxChunkZ = (int) Math.floor((centerZ + radius) / 16.0D);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                positions.add(new ChunkPosition(chunkX, chunkZ));
            }
        }
        return positions;
    }

    private String buildBeaconKey(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    private Set<ChunkPosition> computeChunks(Block block) {
        Set<ChunkPosition> positions = new HashSet<>();
        Chunk chunk = block.getChunk();
        int startX = chunk.getX() - 1;
        int startZ = chunk.getZ() - 1;
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                positions.add(new ChunkPosition(startX + dx, startZ + dz));
            }
        }
        return positions;
    }

    private boolean hasSkyAccess(Block block) {
        World world = block.getWorld();
        int maxHeight = world.getMaxHeight();
        for (int y = block.getY() + 1; y < maxHeight; y++) {
            Material type = world.getBlockAt(block.getX(), y, block.getZ()).getType();
            if (type != Material.AIR && type != Material.CAVE_AIR && type != Material.VOID_AIR) {
                return false;
            }
        }
        return true;
    }

    private Claim readClaim(Map<String, Object> map) {
        try {
            Object ownerObj = map.get("owner");
            Object worldObj = map.get("world");
            Object beaconObj = map.get("beacon");
            if (!(ownerObj instanceof String owner) || !(worldObj instanceof String world) || !(beaconObj instanceof Map<?, ?> beaconMap)) {
                return null;
            }
            Map<String, Object> beacon = (Map<String, Object>) beaconMap;
            int x = ((Number) beacon.getOrDefault("x", 0)).intValue();
            int y = ((Number) beacon.getOrDefault("y", 0)).intValue();
            int z = ((Number) beacon.getOrDefault("z", 0)).intValue();
            BlockPosition beaconPosition = new BlockPosition(x, y, z);
            Set<ChunkPosition> chunks = new HashSet<>();
            Object chunksObj = map.get("chunks");
            if (chunksObj instanceof List<?> chunkList) {
                for (Object chunkEntry : chunkList) {
                    if (chunkEntry instanceof Map<?, ?> chunkMap) {
                        Map<String, Object> chunkData = (Map<String, Object>) chunkMap;
                        int chunkX = ((Number) chunkData.getOrDefault("x", 0)).intValue();
                        int chunkZ = ((Number) chunkData.getOrDefault("z", 0)).intValue();
                        chunks.add(new ChunkPosition(chunkX, chunkZ));
                    }
                }
            }
            Claim claim = new Claim(UUID.fromString(owner), world, beaconPosition, chunks);
            Object trustedObj = map.get("trusted");
            if (trustedObj instanceof List<?> trustedList) {
                for (Object trustedEntry : trustedList) {
                    if (trustedEntry instanceof String trusted) {
                        claim.getTrusted().add(UUID.fromString(trusted));
                    }
                }
            }
            Object radiusObj = map.get("radius");
            if (radiusObj instanceof Number number) {
                double radius = number.doubleValue();
                if (radius > 0) {
                    claim.setRadius(radius);
                }
            }
            return claim;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse claim entry: " + e.getMessage());
            return null;
        }
    }

    private Map<String, Object> writeClaim(Claim claim) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("owner", claim.getOwner().toString());
        data.put("world", claim.getWorld());
        Map<String, Object> beacon = new LinkedHashMap<>();
        beacon.put("x", claim.getBeacon().getX());
        beacon.put("y", claim.getBeacon().getY());
        beacon.put("z", claim.getBeacon().getZ());
        data.put("beacon", beacon);
        data.put("radius", claim.getRadius());
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (ChunkPosition chunkPosition : claim.getChunks()) {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("x", chunkPosition.getX());
            chunk.put("z", chunkPosition.getZ());
            chunks.add(chunk);
        }
        data.put("chunks", chunks);
        List<String> trusted = claim.getTrusted().stream().map(UUID::toString).collect(Collectors.toList());
        data.put("trusted", trusted);
        return data;
    }
}
