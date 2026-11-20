package us.mcparks.showscript.compat;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;

/** 1.12.2: the numeric-id API exists, so call it directly (exactly what ShowsRebuild did before). */
@SuppressWarnings("deprecation")
final class LegacyBlockCompat implements BlockCompat {

    @Override
    public void setLegacyBlock(Block block, int legacyId, byte data) {
        block.setTypeId(legacyId);
        block.setData(data);
    }

    @Override
    public LegacyBlock getLegacy(Block block) {
        return new LegacyBlock(block.getTypeId(), block.getData(), false);
    }

    @Override
    public FallingBlock spawnFallingBlock(Location location, int legacyId, byte data) {
        return location.getWorld().spawnFallingBlock(location, legacyId, data);
    }
}
