package us.mcparks.showscript.compat;

import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.bukkit.selections.CuboidSelection;
import com.sk89q.worldedit.bukkit.selections.Selection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** WorldEdit 6 (1.12.2): the Bukkit selection API (moved here unchanged from Rebuild). */
final class LegacyWorldEditCompat implements WorldEditCompat {

    @Override
    public int[] getCuboidSelection(Player player) {
        WorldEditPlugin worldEdit = (WorldEditPlugin) Bukkit.getServer().getPluginManager().getPlugin("WorldEdit");
        Selection selection = worldEdit.getSelection(player);
        if (!(selection instanceof CuboidSelection)) {
            return null;
        }
        Vector min = selection.getNativeMinimumPoint();
        Vector max = selection.getNativeMaximumPoint();
        return new int[]{min.getBlockX(), min.getBlockY(), min.getBlockZ(), max.getBlockX(), max.getBlockY(), max.getBlockZ()};
    }
}
