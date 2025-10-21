package dev.crystalmath.amethyst.commands;

import dev.crystalmath.amethyst.gui.AreaAdminGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AreaAdminCommand implements CommandExecutor {
    private final AreaAdminGui areaAdminGui;

    public AreaAdminCommand(AreaAdminGui areaAdminGui) {
        this.areaAdminGui = areaAdminGui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can open the crystal area admin panel.");
            return true;
        }

        areaAdminGui.open(player);
        return true;
    }
}
