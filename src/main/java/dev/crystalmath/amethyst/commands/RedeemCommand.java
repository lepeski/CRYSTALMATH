package dev.crystalmath.amethyst.commands;

import dev.crystalmath.amethyst.MintLedger;
import dev.crystalmath.amethyst.util.MintedCrystalUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.UUID;

public class RedeemCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final MintLedger ledger;
    private final NamespacedKey crystalKey;

    public RedeemCommand(JavaPlugin plugin, MintLedger ledger, NamespacedKey crystalKey) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.crystalKey = crystalKey;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can redeem minted crystals.");
            return true;
        }

        if (!player.hasPermission("amethystcontrol.redeem")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to redeem minted crystals.");
            return true;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand.getType() != Material.AMETHYST_SHARD) {
            player.sendMessage(ChatColor.RED + "Hold a minted amethyst crystal to redeem it.");
            return true;
        }

        Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(itemInHand, crystalKey);
        if (uuidOptional.isEmpty()) {
            player.sendMessage(ChatColor.RED + "This amethyst shard is not a minted crystal.");
            return true;
        }

        UUID uuid = uuidOptional.get();

        BukkitScheduler scheduler = Bukkit.getScheduler();

        scheduler.runTaskAsynchronously(plugin, () -> {
            Optional<MintLedger.LedgerEntry> entryOptional;
            try {
                entryOptional = ledger.findByUuid(uuid);
            } catch (MintLedger.LedgerException exception) {
                scheduler.runTask(plugin, () -> player.sendMessage(ChatColor.RED + "Unable to access the ledger. Check the server logs for details."));
                return;
            }

            if (entryOptional.isEmpty()) {
                scheduler.runTask(plugin, () -> player.sendMessage(ChatColor.RED + "No ledger entry was found for this crystal."));
                return;
            }

            MintLedger.LedgerEntry entry = entryOptional.get();
            if (!entry.isHeld()) {
                scheduler.runTask(plugin, () -> player.sendMessage(ChatColor.RED + "This crystal is not in a redeemable state (current status: " + ChatColor.LIGHT_PURPLE + entry.status() + ChatColor.RED + ")."));
                return;
            }

            boolean redeemed;
            try {
                redeemed = ledger.markRedeemed(uuid);
            } catch (MintLedger.LedgerException exception) {
                scheduler.runTask(plugin, () -> player.sendMessage(ChatColor.RED + "Failed to update the ledger. Check the server logs for details."));
                return;
            }

            if (!redeemed) {
                scheduler.runTask(plugin, () -> player.sendMessage(ChatColor.RED + "This crystal could not be redeemed. It may have already been processed."));
                return;
            }

            scheduler.runTask(plugin, () -> {
                ItemStack current = player.getInventory().getItemInMainHand();
                if (current != null && MintedCrystalUtil.readLedgerId(current, crystalKey).map(uuid::equals).orElse(false)) {
                    int amount = current.getAmount();
                    if (amount <= 1) {
                        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                    } else {
                        current.setAmount(amount - 1);
                        player.getInventory().setItemInMainHand(current);
                    }
                    player.updateInventory();
                }
                player.sendMessage(ChatColor.GREEN + "Crystal " + ChatColor.LIGHT_PURPLE + uuid + ChatColor.GREEN + " has been marked as redeemed.");
            });
        });
        return true;
    }
}
