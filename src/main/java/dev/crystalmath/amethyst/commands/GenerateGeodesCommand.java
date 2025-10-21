package dev.crystalmath.amethyst.commands;

import dev.crystalmath.amethyst.AreaManager;
import dev.crystalmath.amethyst.geode.GeodeGenerator;
import dev.crystalmath.amethyst.geode.GeodeGenerator.PlannedGeode;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class GenerateGeodesCommand implements CommandExecutor {
    private final AreaManager areaManager;
    private final GeodeGenerator geodeGenerator;

    public GenerateGeodesCommand(AreaManager areaManager, GeodeGenerator geodeGenerator) {
        this.areaManager = areaManager;
        this.geodeGenerator = geodeGenerator;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <areaId> <geodeCount>");
            return true;
        }

        String areaId = args[0];
        int geodeCount;
        try {
            geodeCount = Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Geode count must be a number.");
            return true;
        }

        if (geodeCount <= 0) {
            sender.sendMessage(ChatColor.RED + "Geode count must be at least 1.");
            return true;
        }

        Optional<AreaManager.Area> areaOptional = areaManager.findArea(areaId);
        if (areaOptional.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Unknown area '" + areaId + "'.");
            return true;
        }

        AreaManager.Area area = areaOptional.get();
        Optional<World> worldOptional = area.resolveWorld();
        if (worldOptional.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Area '" + areaId + "' references an unloaded or missing world.");
            return true;
        }

        World world = worldOptional.get();
        Set<Chunk> chunks = areaManager.getChunksFromKeys(world, area.chunkKeys());
        if (chunks.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Area '" + areaId + "' has no loaded chunks to generate geodes in.");
            return true;
        }

        for (Chunk chunk : chunks) {
            chunk.load();
        }

        List<PlannedGeode> planned = geodeGenerator.planGeodes(world, chunks, geodeCount);
        if (planned.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Unable to locate valid underground space for the requested geodes. Try loading more chunks or reducing the amount.");
            return true;
        }

        geodeGenerator.generateGeodes(planned);

        List<String> radii = planned.stream()
                .map(PlannedGeode::radius)
                .map(String::valueOf)
                .collect(Collectors.toCollection(ArrayList::new));

        if (planned.size() < geodeCount) {
            sender.sendMessage(ChatColor.YELLOW + "Generated " + planned.size() + " geodes for area '" + areaId + "' (requested " + geodeCount + ").");
        } else {
            sender.sendMessage(ChatColor.GREEN + "Generated " + planned.size() + " geodes for area '" + areaId + "'.");
        }

        sender.sendMessage(ChatColor.GRAY + "Radii: " + String.join(", ", radii));
        return true;
    }
}
