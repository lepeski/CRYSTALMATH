package dev.crystalmath.amethyst.commands;

import dev.crystalmath.amethyst.MintLedger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

public class SupplyCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final MintLedger ledger;

    public SupplyCommand(JavaPlugin plugin, MintLedger ledger) {
        this.plugin = plugin;
        this.ledger = ledger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        BukkitScheduler scheduler = Bukkit.getScheduler();

        scheduler.runTaskAsynchronously(plugin, () -> {
            MintLedger.SupplySnapshot snapshot;
            try {
                snapshot = ledger.countByStatus();
            } catch (MintLedger.LedgerException exception) {
                scheduler.runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "Unable to access the crystal ledger. Check the server logs for details."));
                return;
            }

            scheduler.runTask(plugin, () -> {
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "Minted crystal supply:");
                sender.sendMessage(ChatColor.GRAY + "  ACTIVE: " + ChatColor.WHITE + snapshot.active());
                sender.sendMessage(ChatColor.GRAY + "  HELD: " + ChatColor.WHITE + snapshot.held());
                sender.sendMessage(ChatColor.GRAY + "  LOST: " + ChatColor.WHITE + snapshot.lost());
                sender.sendMessage(ChatColor.GRAY + "  REDEEMED: " + ChatColor.WHITE + snapshot.redeemed());
            });
        });
        return true;
    }
}
