package us.mcparks.showscript.compat;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * Without a running server, the block compat layer can't initialise. That must be logged once and cached, not
 * thrown out of Compat.init/blocks() or retried on every call (the fountain action calls it every tick).
 */
public class CompatFailureTest {

    @Test
    public void failedInitIsCachedAndCallsThrowIllegalState() {
        Compat.init(java.util.logging.Logger.getLogger("test")); // must not throw
        BlockCompat first = Compat.blocks();
        assertSame(first, Compat.blocks());
        assertFalse(Compat.blocksAvailable());
        try {
            first.spawnFallingBlock(null, 1, (byte) 0);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
        try {
            first.getLegacy(null);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
    }
}
