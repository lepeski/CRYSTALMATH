/*
 * Plugin made by Lixqa Development.
 * Do not share the source code
 * Website: https://lix.qa/
 * Discord: https://discord.gg/ldev
 * */

package dev.crystalmath.amethyst.commands;

import dev.crystalmath.CrystalMathPlugin;
import dev.crystalmath.amethyst.AreaManager;
import dev.crystalmath.amethyst.MintLedger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class SpawnCrystalsCommand implements CommandExecutor {

    private final CrystalMathPlugin plugin;
    private final MintLedger ledger;
    private final AreaManager areaManager;
    private final Logger logger;

    public SpawnCrystalsCommand(CrystalMathPlugin plugin, MintLedger ledger, AreaManager areaManager) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.areaManager = areaManager;
        this.logger = Logger.getLogger("CrystalMathSpawnLogger");
        configureLogger();
    }

    private void configureLogger() {
        logger.setUseParentHandlers(false);
        if (logger.getHandlers().length > 0) {
            return;
        }

        try {
            File logFile = new File(plugin.getDataFolder(), "amethyst_spawns.log");
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileHandler handler = new FileHandler(logFile.getAbsolutePath(), true);
            handler.setFormatter(new SimpleFormatter());
            logger.addHandler(handler);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to configure spawn log", exception);
        }
    }

    private static final class PlacementTarget {
        private final Block block;
        private final BlockFace direction;

        private PlacementTarget(Block block, BlockFace direction) {
            this.block = block;
            this.direction = direction;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        List<AreaManager.Area> areas = areaManager.loadAreas();
        List<String> failures = new ArrayList<>();

        for (AreaManager.Area area : areas) {
            Optional<World> worldOptional = area.resolveWorld();
            if (worldOptional.isEmpty()) {
                String message = ChatColor.RED + "Area [" + area.id() + "] references unknown world '" + area.world() + "'.";
                sender.sendMessage(message);
                failures.add(message);
                continue;
            }

            World world = worldOptional.get();

            Set<Chunk> chunks = areaManager.getChunksFromKeys(world, area.chunkKeys());
            int amethysts = 0;
            List<PlacementTarget> potential = new ArrayList<>();

            if (chunks.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "Area [" + area.id() + "] has no loaded chunks.");
                continue;
            }

            // Count existing amethysts
            for (Chunk chunk : chunks) {
                chunk.load();
                int minY = world.getMinHeight();
                int maxY = world.getMaxHeight();

                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = minY; y < maxY; y++) {
                            Block block = chunk.getBlock(x, y, z);
                            if (block.getType() == Material.AMETHYST_CLUSTER) {
                                amethysts++;
                            }
                        }
                    }
                }
            }

            sender.sendMessage(ChatColor.YELLOW + "Area [" + area.id() + "] currently has " + amethysts + " amethyst crystals (max " + area.crystals() + ").");

            /*if (amethysts >= area.crystals()) {
                sender.sendMessage(ChatColor.GRAY + "No new crystals spawned because max limit reached.");
                continue;
            }*/

            for (Chunk chunk : chunks) {
                int minY = world.getMinHeight();
                int maxY = world.getMaxHeight();

                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = minY; y < maxY; y++) {
                            Block block = chunk.getBlock(x, y, z);
                            if (block.getType() == Material.BUDDING_AMETHYST) {
                                for (BlockFace face : Arrays.asList(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN)) {
                                    Block relative = block.getRelative(face);
                                    if (relative.getType() == Material.AIR) {
                                        potential.add(new PlacementTarget(relative, face));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            sender.sendMessage(ChatColor.YELLOW + "Found " + potential.size() + " potential crystal placement spots.");

            if (potential.isEmpty()) {
                failures.add(ChatColor.RED + "Area [" + area.id() + "] has no available spots to spawn crystals.");
                continue;
            }

            Collections.shuffle(potential);
            int crystalsToSpawn = area.crystals() - amethysts;

            if (crystalsToSpawn <= 0) {
                sender.sendMessage(ChatColor.GRAY + "No new crystals spawned because max limit reached.");
                continue;
            }

            if (potential.size() > crystalsToSpawn) {
                potential = new ArrayList<>(potential.subList(0, crystalsToSpawn));
            }

            int spawnedCount = 0;
            for (PlacementTarget target : potential) {
                Block block = target.block;
                BlockFace direction = target.direction;

                block.setType(Material.AMETHYST_CLUSTER);
                BlockData data = Bukkit.createBlockData(Material.AMETHYST_CLUSTER);

                if (data instanceof Directional directional) {
                    directional.setFacing(direction);
                    block.setBlockData(directional);
                }

                Location location = block.getLocation();
                if (block.getType() != Material.AMETHYST_CLUSTER) {
                    failures.add(ChatColor.RED + "Unable to place a crystal in area [" + area.id() + "] at " + location.toVector());
                    continue;
                }

                try {
                    UUID ledgerId = ledger.recordMint(area.id(), location);
                    block.getWorld().playSound(location, Sound.BLOCK_AMETHYST_BLOCK_PLACE, 1, 1);

                    logger.info(String.format("Spawned amethyst crystal at %s in world '%s', facing %s, area ID: %s, ledger UUID: %s",
                            location.toVector(),
                            location.getWorld().getName(),
                            direction.name(),
                            area.id(),
                            ledgerId));
                    spawnedCount++;
                } catch (MintLedger.LedgerException exception) {
                    block.setType(Material.AIR);
                    plugin.getLogger().log(Level.SEVERE, "Failed to record minted crystal", exception);
                    failures.add(ChatColor.RED + "Failed to record a crystal in area [" + area.id() + "] at " + location.toVector());
                }
            }

            sender.sendMessage(ChatColor.GREEN + "Spawned " + spawnedCount + " amethyst crystals in area [" + area.id() + "].");
        }

        if (!failures.isEmpty()) {
            failures.forEach(sender::sendMessage);
        } else {
            sender.sendMessage(ChatColor.GREEN + "All areas processed successfully.");
        }

        return true;
    }
}
