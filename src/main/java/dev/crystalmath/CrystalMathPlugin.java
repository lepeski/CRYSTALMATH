package dev.crystalmath;

import dev.crystalmath.amethyst.AreaManager;
import dev.crystalmath.amethyst.MintLedger;
import dev.crystalmath.amethyst.commands.AreaAdminCommand;
import dev.crystalmath.amethyst.commands.ClaimAreaCommand;
import dev.crystalmath.amethyst.commands.CrystalAuditCommand;
import dev.crystalmath.amethyst.commands.GenerateGeodesCommand;
import dev.crystalmath.amethyst.commands.RedeemAllCommand;
import dev.crystalmath.amethyst.commands.RedeemCommand;
import dev.crystalmath.amethyst.commands.SpawnCrystalsCommand;
import dev.crystalmath.amethyst.commands.SupplyCommand;
import dev.crystalmath.amethyst.listeners.BeaconCraftListener;
import dev.crystalmath.amethyst.listeners.CrystalLifecycleListener;
import dev.crystalmath.amethyst.listeners.FortuneListener;
import dev.crystalmath.amethyst.listeners.GrowthListener;
import dev.crystalmath.amethyst.listeners.OfflineCrystalListener;
import dev.crystalmath.amethyst.gui.AreaAdminGui;
import dev.crystalmath.amethyst.geode.GeodeGenerator;
import dev.crystalmath.claims.BeaconAuraManager;
import dev.crystalmath.claims.ClaimAdminCommand;
import dev.crystalmath.claims.ClaimManager;
import dev.crystalmath.claims.ClaimProtectionListener;
import dev.crystalmath.claims.gui.AdminGui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Iterator;
import java.util.Objects;
import java.util.logging.Level;

public class CrystalMathPlugin extends JavaPlugin {
    private MintLedger ledger;
    private AreaManager areaManager;
    private NamespacedKey mintedCrystalKey;
    private ClaimManager claimManager;
    private AdminGui adminGui;
    private AreaAdminGui areaAdminGui;
    private NamespacedKey beaconRecipeKey;
    private CrystalLifecycleListener lifecycleListener;
    private GeodeGenerator geodeGenerator;
    private BeaconAuraManager beaconAuraManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        mintedCrystalKey = new NamespacedKey(this, "minted-crystal");
        beaconRecipeKey = new NamespacedKey(this, "beacon");
        ledger = new MintLedger(this);
        try {
            ledger.initialize();
        } catch (MintLedger.LedgerException exception) {
            getLogger().log(Level.SEVERE, "Failed to initialise the crystal ledger", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        areaManager = new AreaManager(this, ledger);
        geodeGenerator = new GeodeGenerator(this);

        claimManager = new ClaimManager(this);
        claimManager.load();
        adminGui = new AdminGui(this, claimManager);
        areaAdminGui = new AreaAdminGui(this, ledger);
        beaconAuraManager = new BeaconAuraManager(this, claimManager);

        Bukkit.getPluginManager().registerEvents(new FortuneListener(this, ledger, mintedCrystalKey), this);
        lifecycleListener = new CrystalLifecycleListener(this, ledger, mintedCrystalKey);
        Bukkit.getPluginManager().registerEvents(lifecycleListener, this);
        Bukkit.getPluginManager().registerEvents(new OfflineCrystalListener(this, ledger, mintedCrystalKey), this);
        Bukkit.getPluginManager().registerEvents(new GrowthListener(), this);
        Bukkit.getPluginManager().registerEvents(new BeaconCraftListener(this, ledger, mintedCrystalKey, beaconRecipeKey), this);
        Bukkit.getPluginManager().registerEvents(new ClaimProtectionListener(claimManager), this);
        Bukkit.getPluginManager().registerEvents(adminGui, this);
        Bukkit.getPluginManager().registerEvents(areaAdminGui, this);

        registerExecutor("claimarea", new ClaimAreaCommand(this, ledger, areaManager));
        registerExecutor("spawncrystals", new SpawnCrystalsCommand(this, ledger, areaManager));
        registerExecutor("spawngeodes", new GenerateGeodesCommand(areaManager, geodeGenerator));
        registerExecutor("supply", new SupplyCommand(this, ledger));
        registerExecutor("redeem", new RedeemCommand(this, ledger, mintedCrystalKey, claimManager));
        registerExecutor("redeemall", new RedeemAllCommand(this, ledger, mintedCrystalKey));
        registerExecutor("crystalaudit", new CrystalAuditCommand(this, ledger, mintedCrystalKey));
        registerExecutor("areaadmin", new AreaAdminCommand(areaAdminGui));

        ClaimAdminCommand adminCommand = new ClaimAdminCommand(this, claimManager, adminGui);
        PluginCommand claimAdmin = getCommand("claimadmin");
        if (claimAdmin != null) {
            claimAdmin.setExecutor(adminCommand);
            claimAdmin.setTabCompleter(adminCommand);
        }

        beaconAuraManager.start();
        registerBeaconRecipe();
    }

    @Override
    public void onDisable() {
        if (beaconAuraManager != null) {
            beaconAuraManager.stop();
        }
        if (claimManager != null) {
            claimManager.save();
        }
        if (ledger != null) {
            ledger.close();
        }
        if (lifecycleListener != null) {
            lifecycleListener.shutdown();
        }
    }

    public MintLedger getLedger() {
        return ledger;
    }

    public NamespacedKey getMintedCrystalKey() {
        return mintedCrystalKey;
    }

    public AreaManager getAreaManager() {
        return areaManager;
    }

    public ClaimManager getClaimManager() {
        return claimManager;
    }

    private void registerExecutor(String command, CommandExecutor executor) {
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand(command), command + " command not registered");
        pluginCommand.setExecutor(executor);
    }

    private void registerBeaconRecipe() {
        removeVanillaBeacon();
        Bukkit.removeRecipe(beaconRecipeKey);

        ItemStack result = new ItemStack(Material.BEACON);
        ShapedRecipe recipe = new ShapedRecipe(beaconRecipeKey, result);
        // Layout:
        // G M G
        // G N G
        // O O O
        // (M and N represent minted crystals validated during crafting.)
        recipe.shape("GMG", "GNG", "OOO");
        recipe.setIngredient('G', Material.GLASS);
        recipe.setIngredient('O', Material.OBSIDIAN);

        recipe.setIngredient('M', Material.AMETHYST_SHARD);
        recipe.setIngredient('N', Material.AMETHYST_SHARD);

        boolean registered = Bukkit.addRecipe(recipe);
        if (!registered) {
            getLogger().warning("Failed to register custom beacon recipe; a conflicting recipe may already exist.");
        }
    }

    private void removeVanillaBeacon() {
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        NamespacedKey vanillaKey = NamespacedKey.minecraft("beacon");
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            if (recipe instanceof org.bukkit.Keyed keyed && vanillaKey.equals(keyed.getKey())) {
                iterator.remove();
                break;
            }
        }
    }
}
