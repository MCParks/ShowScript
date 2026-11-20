package us.mcparks.showscript.compat;

import org.bukkit.entity.Player;

/**
 * Reads a player's WorldEdit selection. WorldEdit 6 (1.12.2) and WorldEdit 7 (1.13+) have unrelated APIs,
 * so each lives in its own class and only the one matching the server is ever loaded.
 */
public interface WorldEditCompat {

    /**
     * The player's cuboid selection as {minX, minY, minZ, maxX, maxY, maxZ}, or null when there is no complete
     * cuboid selection (non-cuboid selections are ignored, as before).
     */
    int[] getCuboidSelection(Player player);
}
