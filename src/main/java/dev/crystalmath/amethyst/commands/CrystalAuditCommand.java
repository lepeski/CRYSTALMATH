package dev.crystalmath.amethyst.commands;

import dev.crystalmath.amethyst.MintLedger;
import dev.crystalmath.amethyst.util.MintedCrystalUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public class CrystalAuditCommand implements CommandExecutor {
    private static final int MAX_DETAILS = 5;

    private final JavaPlugin plugin;
    private final MintLedger ledger;
    private final NamespacedKey crystalKey;

    public CrystalAuditCommand(JavaPlugin plugin, MintLedger ledger, NamespacedKey crystalKey) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.crystalKey = crystalKey;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("fix")) {
            return handleFix(sender, label, args);
        }

        if (!sender.hasPermission("amethystcontrol.audit")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to run this audit.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Starting minted crystal audit. This may take a moment...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            AuditContext context;
            try {
                context = computeAudit();
            } catch (MintLedger.LedgerException exception) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(ChatColor.RED + "Ledger query failed: " + exception.getMessage()));
                return;
            }

            if (!context.report().success()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(ChatColor.RED + "Audit failed: " + context.report().errorMessage()));
                return;
            }

            AuditContext finalContext = context;
            Bukkit.getScheduler().runTask(plugin, () -> sendReport(sender, finalContext));
        });

        return true;
    }

    private boolean handleFix(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("amethystcontrol.audit")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to run this audit.");
            return true;
        }

        String baseCommand = (label == null || label.isEmpty()) ? "crystalaudit" : label;

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + baseCommand + " fix <missing|unexpected>");
            return true;
        }

        String scope = args[1].toLowerCase(Locale.ROOT);
        if (!scope.equals("missing") && !scope.equals("unexpected")) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + baseCommand + " fix <missing|unexpected>");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Running ledger fix for " + scope + " entries...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            AuditContext context;
            try {
                context = computeAudit();
            } catch (MintLedger.LedgerException exception) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(ChatColor.RED + "Ledger query failed: " + exception.getMessage()));
                return;
            }

            if (!context.report().success()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(ChatColor.RED + "Audit failed: " + context.report().errorMessage()));
                return;
            }

            if (scope.equals("missing")) {
                fixMissingEntries(sender, context);
            } else {
                fixUnexpectedHeld(sender, context);
            }
        });

        return true;
    }

    private AuditReport runWorldAudit(List<MintLedger.LedgerEntry> activeEntries) {
        CompletableFuture<AuditReport> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> future.complete(auditWorld(activeEntries)));
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return AuditReport.failure("Interrupted while waiting for world scan");
        } catch (ExecutionException exception) {
            String message = exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage();
            return AuditReport.failure(message == null ? "Unknown failure during world scan" : message);
        }
    }

    private AuditReport auditWorld(List<MintLedger.LedgerEntry> activeEntries) {
        int confirmedActive = 0;
        List<MintLedger.LedgerEntry> missingActive = new ArrayList<>();
        List<MintLedger.LedgerEntry> unloadedActive = new ArrayList<>();

        for (MintLedger.LedgerEntry entry : activeEntries) {
            if (entry.world() == null || entry.x() == null || entry.y() == null || entry.z() == null) {
                missingActive.add(entry);
                continue;
            }

            World world = Bukkit.getWorld(entry.world());
            if (world == null) {
                missingActive.add(entry);
                continue;
            }

            Location location = new Location(world, entry.x(), entry.y(), entry.z());
            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                unloadedActive.add(entry);
                continue;
            }

            if (world.getBlockAt(location).getType() == org.bukkit.Material.AMETHYST_CLUSTER) {
                confirmedActive++;
            } else {
                missingActive.add(entry);
            }
        }

        Map<UUID, List<String>> contexts = new HashMap<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            inspectContents(contexts, player.getInventory().getContents(),
                    slot -> "Player " + player.getName() + " inventory slot " + slot);
            inspectContents(contexts, player.getEnderChest().getContents(),
                    slot -> "Player " + player.getName() + " ender chest slot " + slot);
        }

        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(item.getItemStack(), crystalKey);
                if (uuidOptional.isPresent()) {
                    contexts.computeIfAbsent(uuidOptional.get(), key -> new ArrayList<>())
                            .add("Dropped at " + formatLocation(item.getLocation()));
                }
            }
        }

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof InventoryHolder holder) {
                        Inventory inventory = holder.getInventory();
                        Location location = state.getLocation();
                        inspectContents(contexts, inventory.getContents(),
                                slot -> holderContext(state, location, slot));
                    }
                }
            }
        }

        try {
            for (MintLedger.OfflineHolding holding : ledger.listOfflineHoldings()) {
                String playerName = holding.playerName();
                UUID playerUuid = holding.playerUuid();
                String identifier = playerName != null ? playerName : (playerUuid == null ? "Unknown player" : playerUuid.toString());
                String details = holding.details();
                String context = "Offline player " + identifier + (details == null || details.isEmpty() ? "" : " - " + details);
                contexts.computeIfAbsent(holding.crystalUuid(), key -> new ArrayList<>()).add(context);
            }
        } catch (MintLedger.LedgerException exception) {
            return AuditReport.failure("Unable to read offline crystal holdings: " + exception.getMessage());
        }

        return AuditReport.success(confirmedActive, missingActive, unloadedActive, contexts);
    }

    private void inspectContents(Map<UUID, List<String>> contexts, ItemStack[] contents, Function<Integer, String> contextFactory) {
        if (contents == null) {
            return;
        }

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }

            Optional<UUID> uuidOptional = MintedCrystalUtil.readLedgerId(stack, crystalKey);
            if (uuidOptional.isEmpty()) {
                continue;
            }

            contexts.computeIfAbsent(uuidOptional.get(), key -> new ArrayList<>())
                    .add(contextFactory.apply(slot));
        }
    }

    private String holderContext(BlockState state, Location location, int slot) {
        return state.getType() + " at " + formatLocation(location) + " slot " + slot;
    }

    private void sendReport(CommandSender sender, AuditContext context) {
        MintLedger.SupplySnapshot snapshot = context.snapshot();
        AuditReport report = context.report();
        Map<UUID, MintLedger.LedgerEntry> heldMap = context.heldMap();
        Set<UUID> missingHeld = context.missingHeld();
        Map<UUID, String> unexpectedStatuses = context.unexpectedStatuses();
        sender.sendMessage(ChatColor.GOLD + "Ledger totals: "
                + ChatColor.WHITE + "active=" + snapshot.active()
                + ChatColor.GRAY + ", held=" + snapshot.held()
                + ChatColor.GRAY + ", lost=" + snapshot.lost()
                + ChatColor.GRAY + ", redeemed=" + snapshot.redeemed()
                + ChatColor.GRAY + " (total=" + snapshot.total() + ")");

        sender.sendMessage(ChatColor.GOLD + "Active crystals: "
                + ChatColor.WHITE + report.confirmedActive() + " confirmed"
                + ChatColor.GRAY + ", " + report.missingActive().size() + " missing"
                + ChatColor.GRAY + ", " + report.unloadedActive().size() + " unloaded");

        if (!report.missingActive().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Missing active entries:");
            report.missingActive().stream().limit(MAX_DETAILS).forEach(entry ->
                    sender.sendMessage(ChatColor.RED + " - " + entry.uuid() + " at " + formatLedgerLocation(entry)));
            if (report.missingActive().size() > MAX_DETAILS) {
                sender.sendMessage(ChatColor.RED + " - ... " + (report.missingActive().size() - MAX_DETAILS) + " more");
            }
            sender.sendMessage(ChatColor.YELLOW + "Run /crystalaudit fix missing to mark absent crystals as lost in the ledger.");
        }

        if (!report.unloadedActive().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Active crystals in unloaded chunks:");
            report.unloadedActive().stream().limit(MAX_DETAILS).forEach(entry ->
                    sender.sendMessage(ChatColor.YELLOW + " - " + entry.uuid() + " at " + formatLedgerLocation(entry)));
            if (report.unloadedActive().size() > MAX_DETAILS) {
                sender.sendMessage(ChatColor.YELLOW + " - ... " + (report.unloadedActive().size() - MAX_DETAILS) + " more");
            }
        }

        int heldLocated = report.mintedContexts().size();
        sender.sendMessage(ChatColor.GOLD + "Held crystals: "
                + ChatColor.WHITE + heldMap.size() + " in ledger"
                + ChatColor.GRAY + ", " + heldLocated + " located in loaded inventories/drops"
                + ChatColor.GRAY + ", " + missingHeld.size() + " unaccounted");

        if (!missingHeld.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Unaccounted held entries (may be offline or in unloaded chunks):");
            missingHeld.stream().limit(MAX_DETAILS).forEach(uuid -> {
                MintLedger.LedgerEntry entry = heldMap.get(uuid);
                sender.sendMessage(ChatColor.RED + " - " + uuid + formatHeldDetails(entry));
            });
            if (missingHeld.size() > MAX_DETAILS) {
                sender.sendMessage(ChatColor.RED + " - ... " + (missingHeld.size() - MAX_DETAILS) + " more");
            }
        }

        if (!unexpectedStatuses.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Unexpected minted crystals found:");
            unexpectedStatuses.entrySet().stream().limit(MAX_DETAILS).forEach(entry -> {
                UUID uuid = entry.getKey();
                String status = entry.getValue();
                List<String> contexts = report.mintedContexts().getOrDefault(uuid, List.of("Unknown location"));
                sender.sendMessage(ChatColor.RED + " - " + uuid + " status=" + status);
                contexts.stream().limit(MAX_DETAILS).forEach(detail ->
                        sender.sendMessage(ChatColor.DARK_RED + "    * " + detail));
                if (contexts.size() > MAX_DETAILS) {
                    sender.sendMessage(ChatColor.DARK_RED + "    * ... " + (contexts.size() - MAX_DETAILS) + " more contexts");
                }
            });
            if (unexpectedStatuses.size() > MAX_DETAILS) {
                sender.sendMessage(ChatColor.RED + " - ... " + (unexpectedStatuses.size() - MAX_DETAILS) + " more");
            }
            sender.sendMessage(ChatColor.YELLOW + "Run /crystalaudit fix unexpected to move these crystals back to HELD status.");
        }

        sender.sendMessage(ChatColor.GRAY + "Audit complete. Online players, offline inventories, dropped items, and loaded containers were inspected.");
    }

    private String formatLedgerLocation(MintLedger.LedgerEntry entry) {
        if (entry.world() == null || entry.x() == null || entry.y() == null || entry.z() == null) {
            return "(no recorded location)";
        }
        return String.format("%s (%d, %d, %d)", entry.world(), entry.x(), entry.y(), entry.z());
    }

    private void fixMissingEntries(CommandSender sender, AuditContext context) {
        List<MintLedger.LedgerEntry> missing = context.report().missingActive();
        if (missing.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    sender.sendMessage(ChatColor.GREEN + "No missing active entries found during audit."));
            return;
        }

        int fixed = 0;
        List<String> failures = new ArrayList<>();
        for (MintLedger.LedgerEntry entry : missing) {
            String details = "Audit fix missing active - last known " + formatLedgerLocation(entry);
            try {
                boolean updated = ledger.markLostWithEvent(entry.uuid(), null, MintLedger.EVENT_AUDIT_FIX, details);
                if (updated) {
                    fixed++;
                } else {
                    failures.add(entry.uuid() + " (no status change)");
                }
            } catch (MintLedger.LedgerException exception) {
                failures.add(entry.uuid() + " (" + exception.getMessage() + ")");
            }
        }

        int fixedCount = fixed;
        List<String> failedEntries = List.copyOf(failures);
        Bukkit.getScheduler().runTask(plugin, () -> {
            sender.sendMessage(ChatColor.GREEN + "Marked " + fixedCount + " missing crystals as lost.");
            if (!failedEntries.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "Failed to update " + failedEntries.size() + " entries:");
                ListIterator<String> iterator = failedEntries.listIterator();
                for (int shown = 0; shown < MAX_DETAILS && iterator.hasNext(); shown++) {
                    sender.sendMessage(ChatColor.RED + " - " + iterator.next());
                }
                if (failedEntries.size() > MAX_DETAILS) {
                    sender.sendMessage(ChatColor.RED + " - ... " + (failedEntries.size() - MAX_DETAILS) + " more");
                }
            }
        });
    }

    private void fixUnexpectedHeld(CommandSender sender, AuditContext context) {
        Map<UUID, String> unexpectedStatuses = context.unexpectedStatuses();
        if (unexpectedStatuses.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    sender.sendMessage(ChatColor.GREEN + "No unexpected held crystals detected."));
            return;
        }

        int fixed = 0;
        List<String> failures = new ArrayList<>();
        for (UUID uuid : unexpectedStatuses.keySet()) {
            try {
                boolean updated = ledger.markHeld(uuid);
                if (updated) {
                    fixed++;
                } else {
                    failures.add(uuid + " (no status change)");
                }
            } catch (MintLedger.LedgerException exception) {
                failures.add(uuid + " (" + exception.getMessage() + ")");
            }
        }

        int fixedCount = fixed;
        List<String> failedEntries = List.copyOf(failures);
        Bukkit.getScheduler().runTask(plugin, () -> {
            sender.sendMessage(ChatColor.GREEN + "Updated " + fixedCount + " crystals to HELD status.");
            if (!failedEntries.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "Failed to update " + failedEntries.size() + " entries:");
                ListIterator<String> iterator = failedEntries.listIterator();
                for (int shown = 0; shown < MAX_DETAILS && iterator.hasNext(); shown++) {
                    sender.sendMessage(ChatColor.RED + " - " + iterator.next());
                }
                if (failedEntries.size() > MAX_DETAILS) {
                    sender.sendMessage(ChatColor.RED + " - ... " + (failedEntries.size() - MAX_DETAILS) + " more");
                }
            }
        });
    }

    private AuditContext computeAudit() {
        MintLedger.SupplySnapshot snapshot = ledger.countByStatus();
        List<MintLedger.LedgerEntry> activeEntries = ledger.listEntriesByStatus(MintLedger.STATUS_ACTIVE);
        List<MintLedger.LedgerEntry> heldEntries = ledger.listEntriesByStatus(MintLedger.STATUS_HELD);

        AuditReport report = runWorldAudit(activeEntries);
        if (!report.success()) {
            return new AuditContext(snapshot, report, Map.of(), Set.of(), Map.of());
        }

        Map<UUID, MintLedger.LedgerEntry> heldMap = new HashMap<>();
        for (MintLedger.LedgerEntry entry : heldEntries) {
            heldMap.put(entry.uuid(), entry);
        }

        Set<UUID> expectedHeld = new HashSet<>(heldMap.keySet());
        Set<UUID> locatedHeld = new HashSet<>(report.mintedContexts().keySet());

        Set<UUID> missingHeld = new HashSet<>(expectedHeld);
        missingHeld.removeAll(locatedHeld);

        Set<UUID> unexpectedHeld = new HashSet<>(locatedHeld);
        unexpectedHeld.removeAll(expectedHeld);

        Map<UUID, String> unexpectedStatuses = new HashMap<>();
        for (UUID uuid : unexpectedHeld) {
            try {
                Optional<MintLedger.LedgerEntry> entryOptional = ledger.findByUuid(uuid);
                unexpectedStatuses.put(uuid, entryOptional.map(MintLedger.LedgerEntry::status).orElse("UNKNOWN"));
            } catch (MintLedger.LedgerException exception) {
                unexpectedStatuses.put(uuid, "ERROR: " + exception.getMessage());
            }
        }

        return new AuditContext(snapshot, report, heldMap, missingHeld, unexpectedStatuses);
    }

    private String formatHeldDetails(MintLedger.LedgerEntry entry) {
        if (entry == null) {
            return " (not present in ledger cache)";
        }
        if (entry.world() != null && entry.x() != null && entry.y() != null && entry.z() != null) {
            return String.format(" (last seen at %s %d,%d,%d)", entry.world(), entry.x(), entry.y(), entry.z());
        }
        if (entry.areaId() != null) {
            return " (area " + entry.areaId() + ")";
        }
        return "";
    }

    private String formatLocation(Location location) {
        return location.getWorld().getName() + " (" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ")";
    }

    private record AuditReport(boolean success,
                               String errorMessage,
                               int confirmedActive,
                               List<MintLedger.LedgerEntry> missingActive,
                               List<MintLedger.LedgerEntry> unloadedActive,
                               Map<UUID, List<String>> mintedContexts) {

        static AuditReport success(int confirmed,
                                   List<MintLedger.LedgerEntry> missing,
                                   List<MintLedger.LedgerEntry> unloaded,
                                   Map<UUID, List<String>> contexts) {
            return new AuditReport(true, null, confirmed, missing, unloaded, contexts);
        }

        static AuditReport failure(String message) {
            return new AuditReport(false, message, 0, List.of(), List.of(), Map.of());
        }
    }

    private record AuditContext(MintLedger.SupplySnapshot snapshot,
                                AuditReport report,
                                Map<UUID, MintLedger.LedgerEntry> heldMap,
                                Set<UUID> missingHeld,
                                Map<UUID, String> unexpectedStatuses) {
    }
}

