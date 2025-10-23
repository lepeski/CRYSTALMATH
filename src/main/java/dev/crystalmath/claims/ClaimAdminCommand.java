package dev.crystalmath.claims;

import dev.crystalmath.CrystalMathPlugin;
import dev.crystalmath.claims.gui.AdminGui;
import dev.crystalmath.claims.model.Claim;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ClaimAdminCommand implements CommandExecutor, TabCompleter {
    private final CrystalMathPlugin plugin;
    private final ClaimManager claimManager;
    private final AdminGui adminGui;

    public ClaimAdminCommand(CrystalMathPlugin plugin, ClaimManager claimManager, AdminGui adminGui) {
        this.plugin = plugin;
        this.claimManager = claimManager;
        this.adminGui = adminGui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("claimer.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (sender instanceof Player player) {
                adminGui.open(player);
            } else {
                sender.sendMessage("§cOnly players can open the admin panel.");
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "trust":
            case "untrust":
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /" + label + " " + args[0] + " <owner> <player>");
                    return true;
                }
                OfflinePlayer owner = Bukkit.getOfflinePlayer(args[1]);
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                if (owner.getUniqueId() == null || target.getUniqueId() == null) {
                    sender.sendMessage("§cUnknown player specified.");
                    return true;
                }
                Map<UUID, List<Claim>> grouped = claimManager.getClaimsGroupedByOwner();
                List<Claim> ownerClaims = grouped.get(owner.getUniqueId());
                if (ownerClaims == null || ownerClaims.isEmpty()) {
                    sender.sendMessage("§cNo claims found for that owner.");
                    return true;
                }
                if (args[0].equalsIgnoreCase("trust")) {
                    for (Claim claim : ownerClaims) {
                        claimManager.trustPlayer(claim, target);
                    }
                    sender.sendMessage("§aAdded " + target.getName() + " as trusted for all claims owned by " + owner.getName() + ".");
                } else {
                    for (Claim claim : ownerClaims) {
                        claimManager.untrustPlayer(claim, target);
                    }
                    sender.sendMessage("§aRemoved " + target.getName() + " from trusted list for all claims owned by " + owner.getName() + ".");
                }
                return true;
            case "reload":
                claimManager.load();
                sender.sendMessage("§aClaims reloaded. Current claims: " + claimManager.getClaims().size());
                return true;
            default:
                sender.sendMessage("§cUnknown sub-command. Available: open, trust, untrust, reload");
                return true;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("claimer.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Arrays.asList("open", "trust", "untrust", "reload").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            Map<UUID, List<Claim>> grouped = claimManager.getClaimsGroupedByOwner();
            return grouped.keySet().stream()
                    .map(uuid -> {
                        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                        return offline.getName() != null ? offline.getName() : uuid.toString();
                    })
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            List<String> names = new ArrayList<>();
            for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                if (offlinePlayer.getName() != null && offlinePlayer.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    names.add(offlinePlayer.getName());
                }
            }
            return names;
        }
        return Collections.emptyList();
    }
}
