package dev.crystalmath.claims.gui;

import dev.crystalmath.CrystalMathPlugin;
import dev.crystalmath.claims.ClaimManager;
import dev.crystalmath.claims.model.Claim;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AdminGui implements Listener {
    private static final int GUI_SIZE = 54;

    private final CrystalMathPlugin plugin;
    private final ClaimManager claimManager;
    private final NamespacedKey ownerKey;
    private final NamespacedKey claimKey;

    public AdminGui(CrystalMathPlugin plugin, ClaimManager claimManager) {
        this.plugin = plugin;
        this.claimManager = claimManager;
        this.ownerKey = new NamespacedKey(plugin, "owner");
        this.claimKey = new NamespacedKey(plugin, "claim");
    }

    public void open(Player player) {
        if (!player.hasPermission("claimer.admin")) {
            player.sendMessage("§cYou do not have permission to open this panel.");
            return;
        }
        OwnerListHolder holder = new OwnerListHolder();
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, Component.text("Claim Admin", NamedTextColor.GOLD));
        holder.setInventory(inventory);

        Map<UUID, List<Claim>> grouped = claimManager.getClaimsGroupedByOwner();
        grouped.entrySet().stream()
                .sorted(Comparator.comparing(entry -> getOfflineName(entry.getKey()), String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> inventory.addItem(createOwnerItem(entry.getKey(), entry.getValue())));

        player.openInventory(inventory);
    }

    private ItemStack createOwnerItem(UUID ownerUuid, List<Claim> claims) {
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUuid);
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        meta.displayName(Component.text(owner.getName() != null ? owner.getName() : ownerUuid.toString(), NamedTextColor.AQUA));
        meta.setOwningPlayer(owner);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Claims: " + claims.size(), NamedTextColor.GRAY));
        meta.lore(lore);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(ownerKey, PersistentDataType.STRING, ownerUuid.toString());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createClaimItem(Claim claim) {
        ItemStack stack = new ItemStack(Material.BEACON);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Beacon Claim", NamedTextColor.GREEN));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("World: " + claim.getWorld(), NamedTextColor.GRAY));
        lore.add(Component.text("Location: " + claim.getBeacon().getX() + ", " + claim.getBeacon().getY() + ", " + claim.getBeacon().getZ(), NamedTextColor.GRAY));
        lore.add(Component.text("Trusted: " + claim.getTrusted().size(), NamedTextColor.GRAY));
        lore.add(Component.text("Click to teleport", NamedTextColor.YELLOW));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(claimKey, PersistentDataType.STRING, claim.getBeaconKey());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createBackItem() {
        ItemStack stack = new ItemStack(Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Back", NamedTextColor.RED));
        stack.setItemMeta(meta);
        return stack;
    }

    private String getOfflineName(UUID uuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return player.getName() != null ? player.getName() : uuid.toString();
    }

    private void openOwnerClaims(Player player, UUID ownerUuid) {
        List<Claim> claims = claimManager.getClaimsGroupedByOwner().get(ownerUuid);
        if (claims == null || claims.isEmpty()) {
            player.sendMessage("§cNo claims found for that owner.");
            return;
        }
        OwnerClaimsHolder holder = new OwnerClaimsHolder(ownerUuid);
        String title = "Claims - " + getOfflineName(ownerUuid);
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, Component.text(title, NamedTextColor.GOLD));
        holder.setInventory(inventory);
        for (Claim claim : claims) {
            inventory.addItem(createClaimItem(claim));
        }
        inventory.setItem(GUI_SIZE - 1, createBackItem());
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof AdminHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }

        if (holder instanceof OwnerListHolder) {
            ItemMeta meta = clicked.getItemMeta();
            if (meta == null) {
                return;
            }
            String ownerId = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerId == null) {
                return;
            }
            openOwnerClaims(player, UUID.fromString(ownerId));
            return;
        }

        if (holder instanceof OwnerClaimsHolder) {
            ItemMeta meta = clicked.getItemMeta();
            if (meta == null) {
                return;
            }
            if (clicked.getType() == Material.BARRIER) {
                open(player);
                return;
            }
            String claimId = meta.getPersistentDataContainer().get(claimKey, PersistentDataType.STRING);
            if (claimId == null) {
                return;
            }
            claimManager.getClaimByKey(claimId).ifPresentOrElse(claim -> {
                World world = Bukkit.getWorld(claim.getWorld());
                if (world == null) {
                    player.sendMessage("§cWorld " + claim.getWorld() + " is not available.");
                    return;
                }
                world.getChunkAt(claim.getBeacon().getX() >> 4, claim.getBeacon().getZ() >> 4);
                player.teleport(claim.getBeacon().toLocation(world).add(0, 1, 0));
                player.sendMessage("§aTeleported to claim beacon.");
            }, () -> player.sendMessage("§cClaim data missing."));
        }
    }

    private interface AdminHolder extends InventoryHolder {
        void setInventory(Inventory inventory);
    }

    private static class OwnerListHolder implements AdminHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        @Override
        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private static class OwnerClaimsHolder implements AdminHolder {
        private final UUID ownerUuid;
        private Inventory inventory;

        private OwnerClaimsHolder(UUID ownerUuid) {
            this.ownerUuid = ownerUuid;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        @Override
        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }
}
