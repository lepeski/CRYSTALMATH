package dev.crystalmath.amethyst.listeners;

import dev.crystalmath.amethyst.MintLedger;
import dev.crystalmath.amethyst.util.MintedCrystalUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/**
 * Tracks crafted beacons so the ledger can redeem consumed crystals.
 */
public class BeaconCraftListener implements Listener {
    private final JavaPlugin plugin;
    private final MintLedger ledger;
    private final NamespacedKey crystalKey;
    public BeaconCraftListener(JavaPlugin plugin, MintLedger ledger, NamespacedKey crystalKey) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.crystalKey = crystalKey;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPrepareBeacon(PrepareItemCraftEvent event) {
        CraftingInventory inventory = event.getInventory();
        ItemStack[] matrix = inventory.getMatrix();
        if (!isBeaconAttempt(matrix)) {
            return;
        }

        if (hasMintedIngredients(matrix)) {
            inventory.setResult(new ItemStack(Material.BEACON));
        } else {
            inventory.setResult(null);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBeaconCraft(CraftItemEvent event) {
        CraftingInventory inventory = event.getInventory();
        ItemStack[] matrix = inventory.getMatrix();
        if (!isBeaconAttempt(matrix)) {
            return;
        }

        if (!hasMintedIngredients(matrix)) {
            event.setCancelled(true);
            HumanEntity who = event.getWhoClicked();
            if (who instanceof Player player) {
                player.sendMessage(ChatColor.RED + "Two minted crystals are required to craft a beacon.");
            }
            inventory.setResult(null);
            return;
        }

        List<UUID> consumed = collectMintedIds(matrix);
        if (consumed.size() < 2) {
            event.setCancelled(true);
            inventory.setResult(null);
            HumanEntity who = event.getWhoClicked();
            if (who instanceof Player player) {
                player.sendMessage(ChatColor.RED + "Unable to confirm the ledger IDs for the provided crystals.");
            }
            return;
        }

        HumanEntity human = event.getWhoClicked();
        String crafter = human.getName();
        Location location = human.getLocation().clone();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (UUID uuid : consumed) {
                try {
                    boolean updated = ledger.markRedeemed(uuid, MintLedger.EVENT_CRAFT_BEACON,
                            formatDetails(crafter, location));
                    if (updated) {
                        plugin.getLogger().info("Ledger crystal " + uuid + " redeemed via beacon craft by " + crafter + ".");
                    }
                } catch (MintLedger.LedgerException exception) {
                    plugin.getLogger().warning("Failed to mark crystal " + uuid + " redeemed after beacon craft: " + exception.getMessage());
                }
            }
        });
    }

    private String formatDetails(String crafter, Location location) {
        return "Crafter=" + crafter + ", World=" + location.getWorld().getName()
                + ", X=" + location.getBlockX()
                + ", Y=" + location.getBlockY()
                + ", Z=" + location.getBlockZ();
    }

    private boolean hasMintedIngredients(ItemStack[] matrix) {
        if (!hasRequiredSlots(matrix)) {
            return false;
        }

        return isMintedCrystal(matrix[1]) && isMintedCrystal(matrix[4]);
    }

    private boolean hasRequiredSlots(ItemStack[] matrix) {
        return matrix != null && matrix.length >= 9;
    }

    private boolean isMintedCrystal(ItemStack stack) {
        return MintedCrystalUtil.readLedgerId(stack, crystalKey).isPresent();
    }

    private boolean isBeaconAttempt(ItemStack[] matrix) {
        if (!hasRequiredSlots(matrix)) {
            return false;
        }

        return isGlass(matrix[0])
                && isAmethystShard(matrix[1])
                && isGlass(matrix[2])
                && isGlass(matrix[3])
                && isAmethystShard(matrix[4])
                && isGlass(matrix[5])
                && isObsidian(matrix[6])
                && isObsidian(matrix[7])
                && isObsidian(matrix[8]);
    }

    private boolean isGlass(ItemStack stack) {
        return stack != null && stack.getType() == Material.GLASS;
    }

    private boolean isObsidian(ItemStack stack) {
        return stack != null && stack.getType() == Material.OBSIDIAN;
    }

    private boolean isAmethystShard(ItemStack stack) {
        return stack != null && stack.getType() == Material.AMETHYST_SHARD;
    }

    private List<UUID> collectMintedIds(ItemStack[] matrix) {
        List<UUID> consumed = new ArrayList<>(2);
        addMintedId(consumed, matrix, 1);
        addMintedId(consumed, matrix, 4);
        return consumed;
    }

    private void addMintedId(List<UUID> target, ItemStack[] matrix, int index) {
        if (index < matrix.length) {
            Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(matrix[index], crystalKey);
            uuidOptional.ifPresent(target::add);
        }
    }
}
