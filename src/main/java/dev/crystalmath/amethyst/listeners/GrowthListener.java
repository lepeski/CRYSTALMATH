/*
 * Plugin made by Lixqa Development.
 * Do not share the source code
 * Website: https://lix.qa/
 * Discord: https://discord.gg/ldev
 * */

package dev.crystalmath.amethyst.listeners;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;

public class GrowthListener implements Listener {
    @EventHandler
    public void onBlockSpread(BlockSpreadEvent event) {
        Material newType = event.getNewState().getType();

        if (newType == Material.SMALL_AMETHYST_BUD) {
            event.setCancelled(true);
        }
    }
}
