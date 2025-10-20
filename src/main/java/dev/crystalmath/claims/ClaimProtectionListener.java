package dev.crystalmath.claims;

import dev.crystalmath.claims.model.Claim;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public class ClaimProtectionListener implements Listener {
    private final ClaimManager claimManager;
    private final Set<Material> interactiveBlocks = Set.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.DISPENSER, Material.DROPPER, Material.HOPPER,
            Material.ENDER_CHEST, Material.SHULKER_BOX, Material.WHITE_SHULKER_BOX,
            Material.ORANGE_SHULKER_BOX, Material.MAGENTA_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX,
            Material.YELLOW_SHULKER_BOX, Material.LIME_SHULKER_BOX, Material.PINK_SHULKER_BOX,
            Material.GRAY_SHULKER_BOX, Material.LIGHT_GRAY_SHULKER_BOX, Material.CYAN_SHULKER_BOX,
            Material.PURPLE_SHULKER_BOX, Material.BLUE_SHULKER_BOX, Material.BROWN_SHULKER_BOX,
            Material.GREEN_SHULKER_BOX, Material.RED_SHULKER_BOX, Material.BLACK_SHULKER_BOX,
            Material.BREWING_STAND, Material.CAULDRON, Material.LECTERN,
            Material.JUKEBOX, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.CRAFTING_TABLE, Material.SMITHING_TABLE, Material.CARTOGRAPHY_TABLE,
            Material.GRINDSTONE, Material.STONECUTTER, Material.ENCHANTING_TABLE,
            Material.LOOM, Material.BELL, Material.BEACON,
            Material.LEVER, Material.REDSTONE_WIRE, Material.REPEATER, Material.COMPARATOR,
            Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH,
            Material.DAYLIGHT_DETECTOR, Material.TARGET, Material.NOTE_BLOCK,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.SCULK_SENSOR, Material.CALIBRATED_SCULK_SENSOR,
            Material.RESPAWN_ANCHOR, Material.CHISELED_BOOKSHELF,
            Material.TRIPWIRE_HOOK, Material.TRIPWIRE,
            Material.COMPOSTER, Material.BEEHIVE, Material.BEE_NEST,
            Material.COPPER_BULB, Material.EXPOSED_COPPER_BULB,
            Material.WEATHERED_COPPER_BULB, Material.OXIDIZED_COPPER_BULB,
            Material.WAXED_COPPER_BULB, Material.WAXED_EXPOSED_COPPER_BULB,
            Material.WAXED_WEATHERED_COPPER_BULB, Material.WAXED_OXIDIZED_COPPER_BULB,
            Material.CAKE
    );

    public ClaimProtectionListener(ClaimManager claimManager) {
        this.claimManager = claimManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        if (!canModify(player, block.getLocation())) {
            event.setCancelled(true);
            notifyBlocked(player);
            return;
        }
        if (block.getType() == Material.BEACON) {
            if (!claimManager.createClaim(player, block)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Optional<Claim> claim = claimManager.getClaimAt(block.getLocation());
        if (claim.isEmpty()) {
            return;
        }
        Claim targetClaim = claim.get();
        if (block.getType() == Material.BEACON && targetClaim.getBeacon().equals(BlockPosition.fromBlock(block))) {
            if (!canModify(player, block.getLocation())) {
                event.setCancelled(true);
                notifyBlocked(player);
                return;
            }
            claimManager.removeClaim(block);
            player.sendMessage("§cClaim removed.");
            return;
        }
        if (!isAuthorized(player, targetClaim)) {
            event.setCancelled(true);
            notifyBlocked(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (event.getAction() == Action.PHYSICAL || isInteractive(block.getType())) {
            if (!canModify(player, block.getLocation())) {
                event.setCancelled(true);
                notifyBlocked(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!canModify(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            notifyBlocked(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerBucketFill(PlayerBucketFillEvent event) {
        if (!canModify(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            notifyBlocked(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        handleEntityInteraction(event.getPlayer(), event.getRightClicked().getLocation(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        handleEntityInteraction(event.getPlayer(), event.getRightClicked().getLocation(), event);
    }

    private void handleEntityInteraction(Player player, Location location, org.bukkit.event.Cancellable event) {
        if (!canModify(player, location)) {
            event.setCancelled(true);
            notifyBlocked(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            event.setCancelled(true);
            return;
        }
        if (!canModify(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
            notifyBlocked(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Entity remover = event.getRemover();
        if (remover instanceof Player player) {
            if (!canModify(player, event.getEntity().getLocation())) {
                event.setCancelled(true);
                notifyBlocked(player);
            }
        } else if (isProtected(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (isMovementBlocked(event.getBlock(), event.getBlocks(), event.getDirection().getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (isMovementBlocked(event.getBlock(), event.getBlocks(), event.getDirection().getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> isProtected(block.getLocation()));
        if (isProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> isProtected(block.getLocation()));
        if (isProtected(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Optional<Claim> claim = claimManager.getClaimAt(victim.getLocation());
        if (claim.isEmpty()) {
            return;
        }
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) {
            event.setCancelled(true);
            return;
        }
        if (hasBypass(attacker) || claim.get().isTrusted(attacker.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        notifyBlocked(attacker);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (isProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (isProtected(event.getBlock().getLocation()) || isProtected(event.getSource().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Location source = getHolderLocation(event.getSource().getHolder());
        Location destination = getHolderLocation(event.getDestination().getHolder());
        if ((source != null && isProtected(source)) || (destination != null && isProtected(destination))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (isProtected(event.getBlock().getLocation()) || isProtected(event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpongeAbsorb(org.bukkit.event.block.SpongeAbsorbEvent event) {
        if (isProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (isProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    private Location getHolderLocation(InventoryHolder holder) {
        if (holder == null) {
            return null;
        }
        if (holder instanceof BlockState state) {
            return state.getLocation();
        }
        if (holder instanceof org.bukkit.block.DoubleChest doubleChest) {
            return doubleChest.getLocation();
        }
        if (holder instanceof Entity entity) {
            return entity.getLocation();
        }
        return null;
    }

    private boolean isMovementBlocked(Block piston, List<Block> blocks, Vector direction) {
        if (isProtected(piston.getLocation())) {
            return true;
        }
        for (Block block : blocks) {
            Location current = block.getLocation();
            Location future = current.clone().add(direction);
            if (isProtected(current) || isProtected(future)) {
                return true;
            }
        }
        Block head = piston.getRelative(direction.getBlockX(), direction.getBlockY(), direction.getBlockZ());
        return isProtected(head.getLocation());
    }

    private boolean canModify(Player player, Location location) {
        if (player == null) {
            return false;
        }
        if (hasBypass(player)) {
            return true;
        }
        Optional<Claim> claim = claimManager.getClaimAt(location);
        return claim.map(value -> value.isTrusted(player.getUniqueId())).orElse(true);
    }

    private boolean isAuthorized(Player player, Claim claim) {
        return hasBypass(player) || claim.isTrusted(player.getUniqueId());
    }

    private boolean hasBypass(Player player) {
        return player.hasPermission("claimer.bypass");
    }

    private boolean isProtected(Location location) {
        return claimManager.getClaimAt(location).isPresent();
    }

    private void notifyBlocked(Player player) {
        player.sendActionBar(net.kyori.adventure.text.Component.text(
                "Claim protected",
                net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    private boolean isInteractive(Material material) {
        if (interactiveBlocks.contains(material)) {
            return true;
        }
        return Tag.BUTTONS.isTagged(material)
                || Tag.PRESSURE_PLATES.isTagged(material)
                || Tag.TRAPDOORS.isTagged(material)
                || Tag.FENCE_GATES.isTagged(material)
                || Tag.DOORS.isTagged(material)
                || Tag.CANDLES.isTagged(material)
                || Tag.SIGNS.isTagged(material)
                || isHangingSign(material);
    }

    private boolean isHangingSign(Material material) {
        return material != null && material.name().endsWith("_HANGING_SIGN");
    }

    private Player resolveAttacker(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player shooter) {
                return shooter;
            }
        }
        return null;
    }
}
