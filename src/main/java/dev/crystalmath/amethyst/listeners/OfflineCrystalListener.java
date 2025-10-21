package dev.crystalmath.amethyst.listeners;

import dev.crystalmath.amethyst.MintLedger;
import dev.crystalmath.amethyst.util.MintedCrystalUtil;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public class OfflineCrystalListener implements Listener {
    private final JavaPlugin plugin;
    private final MintLedger ledger;
    private final NamespacedKey crystalKey;

    public OfflineCrystalListener(JavaPlugin plugin, MintLedger ledger, NamespacedKey crystalKey) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.crystalKey = crystalKey;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Map<UUID, List<String>> contexts = new HashMap<>();

        capture(contexts, player.getInventory().getContents(), slot -> "Inventory slot " + slot);
        capture(contexts, player.getEnderChest().getContents(), slot -> "Ender chest slot " + slot);

        Map<UUID, List<String>> payload = contexts.isEmpty() ? Map.of() : contexts;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ledger.replaceOfflineHoldings(player.getUniqueId(), player.getName(), payload);
            } catch (MintLedger.LedgerException exception) {
                plugin.getLogger().warning("Failed to record offline holdings for " + player.getName() + ": " + exception.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ledger.clearOfflineHoldings(player.getUniqueId());
            } catch (MintLedger.LedgerException exception) {
                plugin.getLogger().warning("Failed to clear offline holdings for " + player.getName() + ": " + exception.getMessage());
            }
        });
    }

    private void capture(Map<UUID, List<String>> contexts,
                         ItemStack[] contents,
                         Function<Integer, String> contextFactory) {
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

            UUID uuid = uuidOptional.get();
            contexts.computeIfAbsent(uuid, key -> new ArrayList<>()).add(contextFactory.apply(slot));
        }
    }
}
