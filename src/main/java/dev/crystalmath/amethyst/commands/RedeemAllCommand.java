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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RedeemAllCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final MintLedger ledger;
    private final NamespacedKey crystalKey;

    public RedeemAllCommand(JavaPlugin plugin, MintLedger ledger, NamespacedKey crystalKey) {
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

        if (!player.hasPermission("amethystcontrol.redeemall")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to redeem all minted crystals.");
            return true;
        }

        ItemStack[] contents = player.getInventory().getContents();
        List<RedeemRequest> requests = new ArrayList<>();

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            int slotIndex = slot;
            Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(stack, crystalKey);
            uuidOptional.ifPresent(uuid -> requests.add(new RedeemRequest(slotIndex, uuid, false)));
        }

        ItemStack offHand = player.getInventory().getItemInOffHand();
        Optional<UUID> offhandUuid = MintedCrystalUtil.readLedgerId(offHand, crystalKey);
        offhandUuid.ifPresent(uuid -> requests.add(new RedeemRequest(-1, uuid, true)));

        if (requests.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No minted crystals were found in your inventory.");
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> processBatch(player, requests));

        return true;
    }

    private void processBatch(Player player, List<RedeemRequest> requests) {
        int redeemedCount = 0;
        List<String> failureMessages = new ArrayList<>();

        for (RedeemRequest request : requests) {
            Optional<MintLedger.LedgerEntry> entryOptional;
            try {
                entryOptional = ledger.findByUuid(request.uuid());
            } catch (MintLedger.LedgerException exception) {
                failureMessages.add(ChatColor.LIGHT_PURPLE + request.uuid().toString() + ChatColor.GRAY + " - ledger access error");
                continue;
            }

            if (entryOptional.isEmpty()) {
                failureMessages.add(ChatColor.LIGHT_PURPLE + request.uuid().toString() + ChatColor.GRAY + " - entry not found");
                continue;
            }

            MintLedger.LedgerEntry entry = entryOptional.get();
            if (!entry.isHeld()) {
                failureMessages.add(ChatColor.LIGHT_PURPLE + request.uuid().toString() + ChatColor.GRAY + " - status " + entry.status());
                continue;
            }

            boolean redeemed;
            try {
                redeemed = ledger.markRedeemed(request.uuid());
            } catch (MintLedger.LedgerException exception) {
                failureMessages.add(ChatColor.LIGHT_PURPLE + request.uuid().toString() + ChatColor.GRAY + " - ledger update failed");
                continue;
            }

            if (!redeemed) {
                failureMessages.add(ChatColor.LIGHT_PURPLE + request.uuid().toString() + ChatColor.GRAY + " - already processed");
                continue;
            }

            request.markRedeemed();
            redeemedCount++;
        }

        int finalRedeemedCount = redeemedCount;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (finalRedeemedCount > 0) {
                for (RedeemRequest request : requests) {
                    if (request.redeemed()) {
                        if (request.offHand()) {
                            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                        } else if (request.slot() >= 0) {
                            player.getInventory().setItem(request.slot(), null);
                        }
                    }
                }
                player.updateInventory();
                player.sendMessage(ChatColor.GREEN + "Redeemed " + ChatColor.LIGHT_PURPLE + finalRedeemedCount + ChatColor.GREEN + " minted crystals.");
            }

            if (!failureMessages.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Failed to redeem " + failureMessages.size() + " crystals:");
                for (String message : failureMessages) {
                    player.sendMessage(ChatColor.RED + " - " + message);
                }
            }

            if (finalRedeemedCount == 0 && failureMessages.isEmpty()) {
                player.sendMessage(ChatColor.YELLOW + "No minted crystals were found in your inventory.");
            }
        });
    }

    private static class RedeemRequest {
        private final int slot;
        private final UUID uuid;
        private final boolean offHand;
        private boolean redeemed;

        RedeemRequest(int slot, UUID uuid, boolean offHand) {
            this.slot = slot;
            this.uuid = uuid;
            this.offHand = offHand;
        }

        int slot() {
            return slot;
        }

        UUID uuid() {
            return uuid;
        }

        boolean offHand() {
            return offHand;
        }

        boolean redeemed() {
            return redeemed;
        }

        void markRedeemed() {
            this.redeemed = true;
        }
    }
}
