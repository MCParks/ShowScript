package us.mcparks.showscript.compat;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * WorldEdit 7 (1.13+), called reflectively because ShowsRebuild compiles against WorldEdit 6:
 * {@code WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player)).getSelection(world)}.
 */
final class ModernWorldEditCompat implements WorldEditCompat {

    @Override
    public int[] getCuboidSelection(Player player) {
        try {
            Class<?> worldEditClass = Class.forName("com.sk89q.worldedit.WorldEdit");
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Class<?> actorClass = Class.forName("com.sk89q.worldedit.extension.platform.Actor");
            Class<?> sessionOwner = Class.forName("com.sk89q.worldedit.session.SessionOwner");
            Class<?> weWorldClass = Class.forName("com.sk89q.worldedit.world.World");

            Object worldEdit = worldEditClass.getMethod("getInstance").invoke(null);
            Object actor = adapter.getMethod("adapt", Player.class).invoke(null, player);
            Object sessionManager = worldEditClass.getMethod("getSessionManager").invoke(worldEdit);
            Object session = sessionManager.getClass().getMethod("get", sessionOwner).invoke(sessionManager, actor);
            Object weWorld = adapter.getMethod("adapt", org.bukkit.World.class).invoke(null, player.getWorld());

            Object region;
            try {
                region = session.getClass().getMethod("getSelection", weWorldClass).invoke(session, weWorld);
            } catch (java.lang.reflect.InvocationTargetException e) {
                // IncompleteRegionException: nothing (or only one point) selected
                return null;
            }
            if (region == null || !region.getClass().getName().equals("com.sk89q.worldedit.regions.CuboidRegion")) {
                return null;
            }
            Object min = region.getClass().getMethod("getMinimumPoint").invoke(region);
            Object max = region.getClass().getMethod("getMaximumPoint").invoke(region);
            return new int[]{coord(min, "X"), coord(min, "Y"), coord(min, "Z"), coord(max, "X"), coord(max, "Y"), coord(max, "Z")};
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read the WorldEdit 7 selection of " + player.getName(), e);
        }
    }

    /** BlockVector3 coordinate: {@code getBlockX()} exists on every WorldEdit 7 release (x() only on 7.3+). */
    private static int coord(Object blockVector3, String axis) throws ReflectiveOperationException {
        Method m = blockVector3.getClass().getMethod("getBlock" + axis);
        return ((Number) m.invoke(blockVector3)).intValue();
    }
}
