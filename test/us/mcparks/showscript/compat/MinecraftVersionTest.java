package us.mcparks.showscript.compat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftVersionTest {

    @Test
    public void parses1122AsLegacy() {
        MinecraftVersion v = MinecraftVersion.parse("1.12.2-R0.1-SNAPSHOT");
        assertEquals(1, v.major);
        assertEquals(12, v.minor);
        assertEquals(2, v.patch);
        assertTrue(v.isLegacy());
        assertFalse(v.isAtLeast(1, 13, 0));
    }

    @Test
    public void parses1214AsModern() {
        MinecraftVersion v = MinecraftVersion.parse("1.21.4-R0.1-SNAPSHOT");
        assertEquals(new MinecraftVersion(1, 21, 4), v);
        assertFalse(v.isLegacy());
        assertTrue(v.isAtLeast(1, 21, 4));
        assertFalse(v.isAtLeast(1, 21, 5));
    }

    @Test
    public void parses113WithoutPatchAsModern() {
        MinecraftVersion v = MinecraftVersion.parse("1.13-R0.1-SNAPSHOT");
        assertEquals(new MinecraftVersion(1, 13, 0), v);
        assertFalse(v.isLegacy());
    }

    @Test
    public void yearVersionedReleaseIsModernAndNewest() {
        MinecraftVersion v = MinecraftVersion.parse("26.1-R0.1-SNAPSHOT");
        assertEquals(26, v.major);
        assertEquals(1, v.minor);
        assertEquals(0, v.patch);
        assertFalse(v.isLegacy());
        assertTrue(v.isAtLeast(1, 21, 4));
        assertTrue(v.isAtLeast(1, 99, 99));
        assertTrue(MinecraftVersion.parse("26.1.2-R0.1-SNAPSHOT").isAtLeast(26, 1, 1));
    }

    @Test
    public void unknownFormatIsTreatedAsNewest() {
        for (String s : new String[]{null, "", "garbage", "R0.1"}) {
            MinecraftVersion v = MinecraftVersion.parse(s);
            assertFalse(s, v.isLegacy());
            assertTrue(s, v.isAtLeast(26, 0, 0));
        }
    }
}
