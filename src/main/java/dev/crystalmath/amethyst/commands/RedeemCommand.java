package dev.crystalmath.amethyst.commands;

import dev.crystalmath.amethyst.MintLedger;
import dev.crystalmath.amethyst.util.MintedCrystalUtil;
import dev.crystalmath.claims.BeaconTier;
import dev.crystalmath.claims.ClaimManager;
import dev.crystalmath.claims.model.Claim;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.Optional;
import java.util.UUID;

public class RedeemCommand implements CommandExecutor {
    private static final double MAX_BEACON_DISTANCE_SQUARED = 25.0D;

    private final JavaPlugin plugin;
    private final MintLedger ledger;
    private final NamespacedKey crystalKey;
    private final ClaimManager claimManager;

    public RedeemCommand(JavaPlugin plugin, MintLedger ledger, NamespacedKey crystalKey, ClaimManager claimManager) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.crystalKey = crystalKey;
        this.claimManager = claimManager;
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

        Optional<Claim> claimOptional = claimManager.getClaimAt(player.getLocation());
        if (claimOptional.isEmpty()) {
            player.sendMessage(ChatColor.RED + "You must stand within your beacon claim to redeem a crystal.");
            return true;
        }

        Claim claim = claimOptional.get();
        if (!claim.isTrusted(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You are not trusted to use this beacon's redemption.");
            return true;
        }

        World claimWorld = Bukkit.getWorld(claim.getWorld());
        if (claimWorld == null) {
            player.sendMessage(ChatColor.RED + "The beacon's world is currently unavailable.");
            return true;
        }

        Block beaconBlock = claimWorld.getBlockAt(claim.getBeacon().getX(), claim.getBeacon().getY(), claim.getBeacon().getZ());
        if (beaconBlock.getType() != Material.BEACON) {
            player.sendMessage(ChatColor.RED + "The beacon for this claim is missing or inactive.");
            return true;
        }

        if (!beaconBlock.getWorld().equals(player.getWorld())) {
            player.sendMessage(ChatColor.RED + "You must redeem crystals in the same world as the beacon.");
            return true;
        }

        double distanceSquared = beaconBlock.getLocation().add(0.5, 0.5, 0.5).distanceSquared(player.getLocation());
        if (distanceSquared > MAX_BEACON_DISTANCE_SQUARED) {
            player.sendMessage(ChatColor.RED + "Stand closer to your beacon to redeem this crystal.");
            return true;
        }

        BeaconTier tier = claimManager.getBeaconTier(claim);
        if (!tier.protectsBlocks()) {
            player.sendMessage(ChatColor.RED + "This beacon's protections are inactive.");
            return true;
        }

        UUID uuid = uuidOptional.get();
        String beaconOwnerName = resolveOwnerName(claim.getOwner());
        String detailString = formatDetails(player.getName(), beaconOwnerName, claim, tier);

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
                redeemed = ledger.markRedeemed(uuid, MintLedger.EVENT_BEACON_REDEEM, detailString);
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

                player.sendMessage(ChatColor.GREEN + "Crystal " + ChatColor.LIGHT_PURPLE + uuid + ChatColor.GREEN + " has been redeemed at "
                        + ChatColor.AQUA + beaconOwnerName + ChatColor.GREEN + "'s beacon (" + tier.getDisplayName() + ").");
                plugin.getLogger().info("Player " + player.getName() + " redeemed crystal " + uuid + " at beacon owned by "
                        + beaconOwnerName + " [" + claim.getWorld() + " " + claim.getBeacon().getX() + ","
                        + claim.getBeacon().getY() + "," + claim.getBeacon().getZ() + ", tier=" + tier.name() + "]");
            });
        });
        return true;
    }

    private String resolveOwnerName(UUID owner) {
        if (owner == null) {
            return "Unknown";
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(owner);
        if (offlinePlayer != null && offlinePlayer.getName() != null) {
            return offlinePlayer.getName();
        }
        return owner.toString();
    }

    private String formatDetails(String redeemer, String beaconOwner, Claim claim, BeaconTier tier) {
        return "Redeemer=" + redeemer
                + ", BeaconOwner=" + beaconOwner
                + ", World=" + claim.getWorld()
                + ", X=" + claim.getBeacon().getX()
                + ", Y=" + claim.getBeacon().getY()
                + ", Z=" + claim.getBeacon().getZ()
                + ", Tier=" + tier.name();
    }
}
