package dev.crystalmath.amethyst.util;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class MintedCrystalUtil {
    private MintedCrystalUtil() {
    }

    public static void applyMetadata(ItemStack itemStack, UUID uuid, NamespacedKey key) {
        if (itemStack == null) {
            return;
        }

        itemStack.setType(Material.AMETHYST_SHARD);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Minted Amethyst Crystal");
        meta.setLore(List.of(ChatColor.GRAY + "Ledger ID: " + uuid));
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, uuid.toString());
        itemStack.setItemMeta(meta);
    }

    public static Optional<UUID> readLedgerId(ItemStack itemStack, NamespacedKey key) {
        if (itemStack == null) {
            return Optional.empty();
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (container.has(key, PersistentDataType.STRING)) {
            String raw = container.get(key, PersistentDataType.STRING);
            if (raw != null) {
                try {
                    return Optional.of(UUID.fromString(raw));
                } catch (IllegalArgumentException ignored) {
                    // fall back to lore parsing
                }
            }
        }

        List<String> lore = meta.getLore();
        if (lore == null) {
            return Optional.empty();
        }

        for (String line : lore) {
            if (line == null) {
                continue;
            }

            String stripped = ChatColor.stripColor(line);
            if (stripped == null) {
                continue;
            }

            String prefix = "Ledger ID: ";
            if (!stripped.startsWith(prefix)) {
                continue;
            }

            String candidate = stripped.substring(prefix.length()).trim();
            try {
                return Optional.of(UUID.fromString(candidate));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed lore entries and continue searching
            }
        }

        return Optional.empty();
    }
}
