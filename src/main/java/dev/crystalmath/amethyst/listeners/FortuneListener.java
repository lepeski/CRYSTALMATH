/*
 * Plugin made by Lixqa Development.
 * Do not share the source code
 * Website: https://lix.qa/
 * Discord: https://discord.gg/ldev
 * */

package dev.crystalmath.amethyst.listeners;

import dev.crystalmath.amethyst.MintLedger;
import dev.crystalmath.amethyst.util.MintedCrystalUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.UUID;

public class FortuneListener implements Listener {
    private final JavaPlugin plugin;
    private final MintLedger ledger;
    private final NamespacedKey crystalKey;

    public FortuneListener(JavaPlugin plugin, MintLedger ledger, NamespacedKey crystalKey) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.crystalKey = crystalKey;
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.AMETHYST_CLUSTER) {
            return;
        }

        var block = event.getBlock();
        Location location = block.getLocation();
        Player player = event.getPlayer();
        Optional<MintLedger.LedgerEntry> entryOptional;
        try {
            entryOptional = ledger.findActiveByLocation(location);
        } catch (MintLedger.LedgerException exception) {
            player.sendMessage(ChatColor.RED + "Unable to verify this crystal in the ledger. Contact an administrator.");
            return;
        }

        if (entryOptional.isPresent()) {
            MintLedger.LedgerEntry entry = entryOptional.get();
            UUID uuid = entry.uuid();

            event.setDropItems(false);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean markedHeld;
                try {
                    markedHeld = ledger.markHeld(uuid);
                } catch (MintLedger.LedgerException exception) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.RED + "Warning: Failed to update the ledger for this crystal. Contact an administrator."));
                    return;
                }

                if (!markedHeld) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.RED + "This crystal could not be moved to HELD status. Check the ledger."));
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    ItemStack drop = new ItemStack(Material.AMETHYST_SHARD, 1);
                    MintedCrystalUtil.applyMetadata(drop, uuid, crystalKey);
                    block.getWorld().dropItemNaturally(location, drop);
                });
            });
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.containsEnchantment(Enchantment.FORTUNE)) {
            event.setDropItems(false);
            block.getWorld().dropItemNaturally(location, new ItemStack(Material.AMETHYST_SHARD, 1));
        }
    }
}
