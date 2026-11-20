package us.mcparks.showscript.compat;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;

/**
 * Block operations whose API changed in the 1.13 flattening. Show content (rebuild files, YAML
 * {@code fountain} actions, {@code .toaster} {@code setblock}) stores blocks as 1.12 numeric id + data value,
 * so these methods take and return that legacy form on every server version.
 */
public interface BlockCompat {

    /** Sets a block from a 1.12 numeric id and data value, applying physics (like 1.12's setTypeId + setData). */
    void setLegacyBlock(Block block, int legacyId, byte data);

    /**
     * The block's 1.12 numeric id and data value (for writing rebuild files). Both come from one mapping of the
     * block's full state, so state-dependent ids (lit/unlit lamp, furnace, double/single slab, powered repeater,
     * ...) stay consistent with their data value.
     */
    LegacyBlock getLegacy(Block block);

    /** Spawns a falling block from a 1.12 numeric id and data value. Returns null if the id is unknown. */
    FallingBlock spawnFallingBlock(Location location, int legacyId, byte data);
}
