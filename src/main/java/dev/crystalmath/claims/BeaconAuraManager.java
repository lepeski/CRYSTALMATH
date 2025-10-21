package dev.crystalmath.claims;

import dev.crystalmath.claims.model.Claim;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Optional;

public class BeaconAuraManager implements Runnable {
    private static final int TICK_PERIOD = 100; // 5 seconds
    private static final int EFFECT_DURATION = 160; // 8 seconds to provide overlap

    private final JavaPlugin plugin;
    private final ClaimManager claimManager;
    private int taskId = -1;

    public BeaconAuraManager(JavaPlugin plugin, ClaimManager claimManager) {
        this.plugin = plugin;
        this.claimManager = claimManager;
    }

    public void start() {
        if (taskId != -1) {
            return;
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this, 0L, TICK_PERIOD);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<Claim> claimOptional = claimManager.getClaimAt(player.getLocation());
            if (claimOptional.isEmpty()) {
                continue;
            }

            Claim claim = claimOptional.get();
            if (!claim.isTrusted(player.getUniqueId())) {
                continue;
            }

            BeaconTier tier = claimManager.getBeaconTier(claim);
            if (!tier.hasAura()) {
                continue;
            }

            int amplifier = tier.getAuraAmplifier();
            PotionEffect effect = new PotionEffect(PotionEffectType.RESISTANCE, EFFECT_DURATION, amplifier, true, false, true);
            player.addPotionEffect(effect);
        }
    }
}
