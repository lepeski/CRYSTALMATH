package dev.crystalmath.amethyst.listeners;

import dev.crystalmath.amethyst.MintLedger;
import dev.crystalmath.amethyst.util.MintedCrystalUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class CrystalLifecycleListener implements Listener {
    private static final EnumSet<EntityDamageEvent.DamageCause> FIRE_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.LAVA,
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.HOT_FLOOR
    );

    private static final double VOID_THRESHOLD_Y = -60.0;

    private final JavaPlugin plugin;
    private final MintLedger ledger;
    private final NamespacedKey crystalKey;
    private final Map<Integer, TrackedItem> trackedItems = new HashMap<>();
    private final BukkitTask voidWatchTask;
    private final BukkitTask voidSweepTask;

    public CrystalLifecycleListener(JavaPlugin plugin, MintLedger ledger, NamespacedKey crystalKey) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.crystalKey = crystalKey;
        this.voidWatchTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pollTrackedItems, 20L, 20L);
        this.voidSweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scanForVoidLoss, 10L, 10L);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockFade(BlockFadeEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.AMETHYST_CLUSTER) {
            return;
        }

        markLostAtLocation(block.getLocation());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            if (block.getType() == Material.AMETHYST_CLUSTER) {
                markLostAtLocation(block.getLocation());
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            if (block.getType() == Material.AMETHYST_CLUSTER) {
                markLostAtLocation(block.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        refreshMintedMetadata(item.getItemStack());
        trackMintedItem(item);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        for (ItemStack stack : event.getDrops()) {
            refreshMintedMetadata(stack);
        }

        if (event.getKeepInventory()) {
            return;
        }

        EntityDamageEvent lastDamage = event.getEntity().getLastDamageCause();
        if (lastDamage != null && lastDamage.getCause() == EntityDamageEvent.DamageCause.VOID) {
            Location location = event.getEntity().getLocation().clone();
            markLostStacks(event.getDrops(), location);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDespawn(ItemDespawnEvent event) {
        Item item = event.getEntity();
        Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(item.getItemStack(), crystalKey);
        if (uuidOptional.isPresent()) {
            UUID uuid = uuidOptional.get();
            Location snapshot = item.getLocation().clone();
            if (isVoid(snapshot)) {
                markVoidLoss(uuid, snapshot);
            } else {
                markLost(uuid, snapshot);
            }
        }
        stopTracking(item);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }

        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean voidDamage = cause == EntityDamageEvent.DamageCause.VOID;
        if (!FIRE_CAUSES.contains(cause) && !voidDamage) {
            return;
        }

        Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(item.getItemStack(), crystalKey);
        if (uuidOptional.isPresent()) {
            UUID uuid = uuidOptional.get();
            Location snapshot = item.getLocation().clone();
            if (cause == EntityDamageEvent.DamageCause.VOID) {
                markVoidLoss(uuid, snapshot);
            } else {
                markLost(uuid, snapshot);
            }
        }
        stopTracking(item);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemSpawn(ItemSpawnEvent event) {
        trackMintedItem(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        stopTracking(event.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        stopTracking(event.getItem());
    }

    public void shutdown() {
        if (voidWatchTask != null) {
            voidWatchTask.cancel();
        }
        if (voidSweepTask != null) {
            voidSweepTask.cancel();
        }
        trackedItems.clear();
    }

    private void markLostAtLocation(Location location) {
        Location snapshot = location.clone();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<MintLedger.LedgerEntry> entryOptional;
            try {
                entryOptional = ledger.findActiveByLocation(snapshot);
            } catch (MintLedger.LedgerException exception) {
                plugin.getLogger().warning("Failed to lookup crystal at " + snapshot + ": " + exception.getMessage());
                return;
            }

            if (entryOptional.isEmpty()) {
                return;
            }

            UUID uuid = entryOptional.get().uuid();
            try {
                ledger.markLost(uuid, snapshot);
            } catch (MintLedger.LedgerException exception) {
                plugin.getLogger().warning("Failed to update crystal " + uuid + " to LOST: " + exception.getMessage());
            }
        });
    }

    private void markLost(UUID uuid) {
        markLost(uuid, null);
    }

    private void markLost(UUID uuid, Location location) {
        Location snapshot = location == null ? null : location.clone();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ledger.markLost(uuid, snapshot);
            } catch (MintLedger.LedgerException exception) {
                plugin.getLogger().warning("Failed to update crystal " + uuid + " to LOST: " + exception.getMessage());
            }
        });
    }

    private void refreshMintedMetadata(ItemStack stack) {
        Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(stack, crystalKey);
        uuidOptional.ifPresent(uuid -> MintedCrystalUtil.applyMetadata(stack, uuid, crystalKey));
    }

    private void trackMintedItem(Item item) {
        Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(item.getItemStack(), crystalKey);
        uuidOptional.ifPresent(uuid -> trackedItems.put(item.getEntityId(), new TrackedItem(item, uuid)));
    }

    private void stopTracking(Item item) {
        if (item != null) {
            trackedItems.remove(item.getEntityId());
        }
    }

    private void pollTrackedItems() {
        Iterator<Map.Entry<Integer, TrackedItem>> iterator = trackedItems.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, TrackedItem> entry = iterator.next();
            TrackedItem tracked = entry.getValue();
            Item item = tracked.item;
            if (item == null) {
                iterator.remove();
                continue;
            }

            if (!item.isValid() || item.isDead()) {
                if (tracked.lastKnownLocation != null && isVoid(tracked.lastKnownLocation)) {
                    markVoidLoss(tracked.uuid, tracked.lastKnownLocation);
                }
                iterator.remove();
                continue;
            }

            Location current = item.getLocation();
            tracked.lastKnownLocation = current.clone();
            if (isVoid(current)) {
                markVoidLoss(tracked.uuid, current);
                iterator.remove();
            }
        }
    }

    private boolean isVoid(Location location) {
        return location.getWorld() != null && location.getY() < VOID_THRESHOLD_Y;
    }

    private void markLostStacks(Collection<ItemStack> stacks, Location location) {
        if (stacks == null || stacks.isEmpty()) {
            return;
        }

        Set<UUID> lost = new HashSet<>();
        for (ItemStack stack : stacks) {
            MintedCrystalUtil.readLedgerId(stack, crystalKey).ifPresent(lost::add);
        }

        if (lost.isEmpty()) {
            return;
        }

        Location snapshot = location == null ? null : location.clone();
        boolean voidLoss = snapshot != null && isVoid(snapshot);
        for (UUID uuid : lost) {
            if (voidLoss) {
                markVoidLoss(uuid, snapshot);
            } else {
                markLost(uuid, snapshot);
            }
        }
    }

    private void scanForVoidLoss() {
        Map<UUID, Location> voidLosses = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                Location location = item.getLocation();
                if (location.getY() >= VOID_THRESHOLD_Y) {
                    continue;
                }

                Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(item.getItemStack(), crystalKey);
                if (uuidOptional.isEmpty()) {
                    continue;
                }

                UUID uuid = uuidOptional.get();
                Location snapshot = location.clone();
                voidLosses.put(uuid, snapshot);
                item.remove();
                stopTracking(item);
            }
        }

        if (voidLosses.isEmpty()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (Map.Entry<UUID, Location> entry : voidLosses.entrySet()) {
                try {
                    ledger.markLostWithEvent(entry.getKey(), entry.getValue(), MintLedger.EVENT_VOID_LOSS,
                            describeLocation(entry.getValue()));
                } catch (MintLedger.LedgerException exception) {
                    plugin.getLogger().warning("Failed to update crystal " + entry.getKey()
                            + " after void sweep: " + exception.getMessage());
                }
            }
        });
    }

    private void markVoidLoss(UUID uuid, Location location) {
        Location snapshot = location == null ? null : location.clone();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ledger.markLostWithEvent(uuid, snapshot, MintLedger.EVENT_VOID_LOSS, describeLocation(snapshot));
            } catch (MintLedger.LedgerException exception) {
                plugin.getLogger().warning("Failed to update crystal " + uuid + " for void loss: " + exception.getMessage());
            }
        });
    }

    private String describeLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return "Void loss";
        }

        return "World=" + location.getWorld().getName()
                + ", X=" + location.getBlockX()
                + ", Y=" + location.getBlockY()
                + ", Z=" + location.getBlockZ();
    }

    private static final class TrackedItem {
        private final Item item;
        private final UUID uuid;
        private Location lastKnownLocation;

        private TrackedItem(Item item, UUID uuid) {
            this.item = item;
            this.uuid = uuid;
        }
    }
}
