package dev.crystalmath.amethyst.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Recipe choice that matches any minted crystal tracked by the ledger.
 */
public class MintedCrystalRecipeChoice implements RecipeChoice {
    private final NamespacedKey crystalKey;
    private final ItemStack displayItem;

    public MintedCrystalRecipeChoice(NamespacedKey crystalKey) {
        this.crystalKey = crystalKey;
        ItemStack preview = new ItemStack(Material.AMETHYST_SHARD);
        MintedCrystalUtil.applyMetadata(preview, new UUID(0L, 0L), crystalKey);
        this.displayItem = preview;
    }

    @Override
    public boolean test(@NotNull ItemStack itemStack) {
        return MintedCrystalUtil.readLedgerId(itemStack, crystalKey).isPresent();
    }

    @Override
    public @NotNull ItemStack getItemStack() {
        return displayItem.clone();
    }

    @Override
    public @NotNull RecipeChoice clone() {
        return new MintedCrystalRecipeChoice(crystalKey);
    }
}
