package us.mcparks.showscript.compat;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Entry point of the version-compatibility layer: picks the implementation for the running server once. */
public final class Compat {
    private static final Logger LOG = Logger.getLogger("ShowsRebuild");

    private static volatile BlockCompat blocks;
    private static volatile WorldEditCompat worldEdit;

    private Compat() {
    }

    /**
     * The block compat implementation. Never null: if it couldn't be created, the failure is logged once and
     * cached, and every call on the returned object throws {@link IllegalStateException} straight away instead of
     * retrying the reflective setup.
     */
    public static BlockCompat blocks() {
        BlockCompat b = blocks;
        if (b == null) {
            synchronized (Compat.class) {
                if (blocks == null) {
                    blocks = createBlocks();
                }
                b = blocks;
            }
        }
        return b;
    }

    public static WorldEditCompat worldEdit() {
        WorldEditCompat w = worldEdit;
        if (w == null) {
            synchronized (Compat.class) {
                if (worldEdit == null) {
                    worldEdit = ServerVersion.LEGACY ? new LegacyWorldEditCompat() : new ModernWorldEditCompat();
                }
                w = worldEdit;
            }
        }
        return w;
    }

    /** True if the block compat layer initialised; false if it failed (and calls on it will throw). */
    public static boolean blocksAvailable() {
        return !(blocks() instanceof Failed);
    }

    private static BlockCompat createBlocks() {
        String version;
        try {
            version = ServerVersion.describe();
        } catch (Throwable t) { // ExceptionInInitializerError / NoClassDefFoundError from ServerVersion
            return failed("unknown server version", t);
        }
        try {
            return ServerVersion.LEGACY ? new LegacyBlockCompat() : new ModernBlockCompat();
        } catch (Throwable t) { // ReflectiveOperationException, LinkageError, IllegalStateException, ...
            return failed(version, t);
        }
    }

    private static BlockCompat failed(String version, Throwable cause) {
        String message = "Server version " + version
                + ": block compat layer failed; rebuild, fountain and .toaster setblock actions are disabled";
        LOG.log(Level.SEVERE, message, cause);
        return new Failed(message + " (" + cause + ")");
    }

    /** Called from onEnable: picks the implementations and logs them, so a broken server fails loudly at startup. */
    public static void init(Logger logger) {
        BlockCompat b = blocks(); // never throws; a failure is logged by failed()
        if (b instanceof Failed) {
            return; // don't take the whole plugin down: only rebuilds, fountains and .toaster setblocks need this
        }
        String detail = b instanceof ModernBlockCompat
                ? " (" + ((ModernBlockCompat) b).legacyMaterialCount() + " legacy block ids indexed)"
                : "";
        logger.info("Server version " + ServerVersion.describe() + ": block compat " + b.getClass().getSimpleName() + detail);
    }

    /** Cached failure: throws immediately on every call, without stack-trace cost of a fresh reflective attempt. */
    private static final class Failed implements BlockCompat {
        private final String message;

        Failed(String message) {
            this.message = message;
        }

        private IllegalStateException fail() {
            return new IllegalStateException(message);
        }

        @Override
        public void setLegacyBlock(Block block, int legacyId, byte data) {
            throw fail();
        }

        @Override
        public LegacyBlock getLegacy(Block block) {
            throw fail();
        }

        @Override
        public FallingBlock spawnFallingBlock(Location location, int legacyId, byte data) {
            throw fail();
        }
    }
}
