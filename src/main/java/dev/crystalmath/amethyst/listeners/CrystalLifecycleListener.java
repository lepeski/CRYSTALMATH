package dev.crystalmath.amethyst.listeners;

import dev.crystalmath.amethyst.MintLedger;
import dev.crystalmath.amethyst.util.MintedCrystalUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

public class CrystalLifecycleListener implements Listener {
    private static final EnumSet<EntityDamageEvent.DamageCause> FIRE_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.LAVA,
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.HOT_FLOOR,
            EntityDamageEvent.DamageCause.VOID
    );

    private final JavaPlugin plugin;
    private final MintLedger ledger;
    private final NamespacedKey crystalKey;

    public CrystalLifecycleListener(JavaPlugin plugin, MintLedger ledger, NamespacedKey crystalKey) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.crystalKey = crystalKey;
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
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        for (ItemStack stack : event.getDrops()) {
            refreshMintedMetadata(stack);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDespawn(ItemDespawnEvent event) {
        Item item = event.getEntity();
        Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(item.getItemStack(), crystalKey);
        uuidOptional.ifPresent(this::markLost);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }

        if (!FIRE_CAUSES.contains(event.getCause())) {
            return;
        }

        Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(item.getItemStack(), crystalKey);
        uuidOptional.ifPresent(this::markLost);
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
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ledger.markLost(uuid);
            } catch (MintLedger.LedgerException exception) {
                plugin.getLogger().warning("Failed to update crystal " + uuid + " to LOST: " + exception.getMessage());
            }
        });
    }

    private void refreshMintedMetadata(ItemStack stack) {
        Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(stack, crystalKey);
        uuidOptional.ifPresent(uuid -> MintedCrystalUtil.applyMetadata(stack, uuid, crystalKey));
    }
}
