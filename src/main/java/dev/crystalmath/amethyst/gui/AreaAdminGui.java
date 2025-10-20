package dev.crystalmath.amethyst.gui;

import dev.crystalmath.CrystalMathPlugin;
import dev.crystalmath.amethyst.MintLedger;
import dev.crystalmath.amethyst.MintLedger.AreaRecord;
import dev.crystalmath.amethyst.MintLedger.ChunkCoordinate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AreaAdminGui implements Listener {
    private static final int MAX_GUI_SIZE = 54;

    private final MintLedger ledger;
    private final NamespacedKey areaKey;
    private final NamespacedKey chunkKey;

    public AreaAdminGui(CrystalMathPlugin plugin, MintLedger ledger) {
        this.ledger = ledger;
        this.areaKey = new NamespacedKey(plugin, "ledger-area");
        this.chunkKey = new NamespacedKey(plugin, "ledger-chunk");
    }

    public void open(Player player) {
        if (!player.hasPermission("amethystcontrol.areaadmin")) {
            player.sendMessage("§cYou do not have permission to open this panel.");
            return;
        }

        List<AreaRecord> areas = new ArrayList<>(ledger.listAreas());
        areas.sort((left, right) -> left.id().compareToIgnoreCase(right.id()));

        int size = determineSize(areas.size());
        AreaListHolder holder = new AreaListHolder(areas);
        Inventory inventory = Bukkit.createInventory(holder, size, Component.text("Crystal Areas", NamedTextColor.LIGHT_PURPLE));
        holder.setInventory(inventory);

        if (areas.isEmpty()) {
            inventory.setItem(0, createEmptyItem());
        } else {
            for (AreaRecord area : areas) {
                inventory.addItem(createAreaItem(area));
            }
        }

        player.openInventory(inventory);
    }

    private ItemStack createAreaItem(AreaRecord area) {
        ItemStack stack = new ItemStack(Material.AMETHYST_CLUSTER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(area.id(), NamedTextColor.AQUA));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("World: " + (area.world() == null ? "Unknown" : area.world()), NamedTextColor.GRAY));
        lore.add(Component.text("Target crystals: " + area.targetCrystals(), NamedTextColor.GRAY));
        lore.add(Component.text("Chunks: " + area.chunkCoordinates().size(), NamedTextColor.GRAY));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(areaKey, PersistentDataType.STRING, area.id());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createChunkItem(ChunkCoordinate coordinate) {
        ItemStack stack = new ItemStack(Material.MAP);
        ItemMeta meta = stack.getItemMeta();
        String name = "Chunk " + coordinate.x() + ", " + coordinate.z();
        meta.displayName(Component.text(name, NamedTextColor.GREEN));
        List<Component> lore = Collections.singletonList(Component.text("Click to teleport", NamedTextColor.YELLOW));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(chunkKey, PersistentDataType.STRING, coordinate.x() + "," + coordinate.z());
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

    private ItemStack createEmptyItem() {
        ItemStack stack = new ItemStack(Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("No areas registered", NamedTextColor.RED));
        stack.setItemMeta(meta);
        return stack;
    }

    private int determineSize(int itemCount) {
        if (itemCount <= 0) {
            return 9;
        }
        int rows = (itemCount + 8) / 9;
        rows = Math.min(rows, MAX_GUI_SIZE / 9);
        return rows * 9;
    }

    private void openAreaChunks(Player player, AreaRecord area) {
        List<ChunkCoordinate> chunks = new ArrayList<>(area.chunkCoordinates());
        chunks.sort((left, right) -> {
            int result = Integer.compare(left.x(), right.x());
            if (result != 0) {
                return result;
            }
            return Integer.compare(left.z(), right.z());
        });

        int size = determineSize(chunks.size() + 1);
        AreaChunksHolder holder = new AreaChunksHolder(area);
        Inventory inventory = Bukkit.createInventory(holder, size, Component.text("Area: " + area.id(), NamedTextColor.LIGHT_PURPLE));
        holder.setInventory(inventory);

        if (chunks.isEmpty()) {
            inventory.setItem(0, createEmptyItem());
        } else {
            for (ChunkCoordinate chunk : chunks) {
                inventory.addItem(createChunkItem(chunk));
            }
        }

        inventory.setItem(size - 1, createBackItem());
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof AreaHolder)) {
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

        if (holder instanceof AreaListHolder listHolder) {
            handleAreaClick(player, clicked, listHolder);
            return;
        }

        if (holder instanceof AreaChunksHolder chunksHolder) {
            handleChunkClick(player, clicked, chunksHolder);
        }
    }

    private void handleAreaClick(Player player, ItemStack clicked, AreaListHolder holder) {
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) {
            return;
        }
        String areaId = meta.getPersistentDataContainer().get(areaKey, PersistentDataType.STRING);
        if (areaId == null) {
            return;
        }

        AreaRecord area = holder.getArea(areaId);
        if (area == null) {
            player.sendMessage("§cThat area no longer exists.");
            open(player);
            return;
        }

        openAreaChunks(player, area);
    }

    private void handleChunkClick(Player player, ItemStack clicked, AreaChunksHolder holder) {
        if (clicked.getType() == Material.BARRIER) {
            open(player);
            return;
        }

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) {
            return;
        }

        String chunkData = meta.getPersistentDataContainer().get(chunkKey, PersistentDataType.STRING);
        if (chunkData == null) {
            return;
        }

        String[] parts = chunkData.split(",");
        if (parts.length != 2) {
            return;
        }

        int chunkX;
        int chunkZ;
        try {
            chunkX = Integer.parseInt(parts[0]);
            chunkZ = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            player.sendMessage("§cUnable to parse chunk coordinates.");
            return;
        }

        String worldName = holder.getArea().world();
        if (worldName == null || worldName.isBlank()) {
            player.sendMessage("§cThe area does not have an associated world.");
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage("§cWorld '" + worldName + "' is not currently loaded.");
            return;
        }

        world.getChunkAt(chunkX, chunkZ);
        int blockX = (chunkX << 4) + 8;
        int blockZ = (chunkZ << 4) + 8;
        int blockY = world.getHighestBlockYAt(blockX, blockZ);
        Location location = new Location(world, blockX + 0.5, blockY + 1, blockZ + 0.5);
        player.teleport(location);
        player.sendMessage("§aTeleported to area chunk " + chunkX + ", " + chunkZ + ".");
    }

    private interface AreaHolder extends InventoryHolder {
        void setInventory(Inventory inventory);
    }

    private static final class AreaListHolder implements AreaHolder {
        private final Map<String, AreaRecord> areasById;
        private Inventory inventory;

        private AreaListHolder(List<AreaRecord> areas) {
            this.areasById = areas.stream().collect(Collectors.toMap(AreaRecord::id, area -> area, (a, b) -> b));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        @Override
        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private AreaRecord getArea(String id) {
            return areasById.get(id);
        }
    }

    private static final class AreaChunksHolder implements AreaHolder {
        private final AreaRecord area;
        private Inventory inventory;

        private AreaChunksHolder(AreaRecord area) {
            this.area = area;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        @Override
        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private AreaRecord getArea() {
            return area;
        }
    }
}
