package us.mcparks.showscript.compat;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.inventory.ItemStack;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * The show helpers against the Paper 26.3 API (ryan/1-12-2-to-1-21-4#191). Compiled against the 1.12 API like the
 * plugin, run on Java 25 with only paper-api 26.3 on the class path (task {@code test263}).
 */
public class Paper263ApiTest {

    /** Data types {@link ParticleCompat#convertData} fills in when a show passes none. */
    private static final Set<String> DEFAULTED = new HashSet<>(Arrays.asList(
            "java.lang.Void", "org.bukkit.Color", "java.lang.Float", "org.bukkit.Particle$Spell",
            "org.bukkit.Particle$DustOptions"));

    @Test
    public void runsOnPaper263() {
        assertTrue("expected Java 25+, got " + System.getProperty("java.version"),
                Integer.parseInt(System.getProperty("java.specification.version")) >= 25);
        assertNotNull("paper-api 26.x has Particle.Spell", type("org.bukkit.Particle$Spell"));
    }

    /** 1.21.9+ gave FLASH, EFFECT, INSTANT_EFFECT and DRAGON_BREATH required data; a show passing none must still work. */
    @Test
    public void particlesThatGainedDataGetDefaults() {
        assertEquals(Color.WHITE, ParticleCompat.convertData(Particle.valueOf("FLASH"), null));
        assertEquals(1.0f, ParticleCompat.convertData(Particle.valueOf("DRAGON_BREATH"), null));
        for (String name : new String[]{"EFFECT", "INSTANT_EFFECT"}) {
            Object spell = ParticleCompat.convertData(Particle.valueOf(name), null);
            assertTrue(name + " -> " + spell, type("org.bukkit.Particle$Spell").isInstance(spell));
            assertEquals(Color.WHITE, call(spell, "getColor"));
            assertEquals(1.0f, call(spell, "getPower"));
        }
    }

    @Test
    public void showDataIsConvertedForNewDataTypes() {
        Object spell = ParticleCompat.convertData(Particle.valueOf("EFFECT"), "#00FF00");
        assertEquals(Color.fromRGB(0x00FF00), call(spell, "getColor"));
        assertEquals(2.5f, ParticleCompat.convertData(Particle.valueOf("DRAGON_BREATH"), 2.5));
        assertEquals(Color.AQUA, ParticleCompat.convertData(Particle.valueOf("FLASH"), "aqua"));
    }

    /** EFFECT's data is a colour: an int is 0xRRGGBB (not a power), and the power stays vanilla's 1.0. */
    @Test
    public void spellNumberIsAColour() {
        Object spell = ParticleCompat.convertData(Particle.valueOf("INSTANT_EFFECT"), 0xFF0000);
        assertEquals(Color.RED, call(spell, "getColor"));
        assertEquals(1.0f, call(spell, "getPower"));
        try {
            ParticleCompat.convertData(Particle.valueOf("EFFECT"), 2.5);
            fail("a fractional number isn't a colour");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("EFFECT"));
        }
    }

    /** DRAGON_BREATH's power accepts a numeric string; anything else fails with a message naming the particle. */
    @Test
    public void floatDataIsCheckedHere() {
        assertEquals(0.5f, ParticleCompat.convertData(Particle.valueOf("DRAGON_BREATH"), " 0.5 "));
        try {
            ParticleCompat.convertData(Particle.valueOf("DRAGON_BREATH"), "big");
            fail("a non-numeric power should be refused before Paper sees it");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("DRAGON_BREATH"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("big"));
        }
    }

    /** 1.12 names shows use resolve to particles whose data the helper can supply (or that take a block/item). */
    @Test
    public void every112NameResolvesOn263() {
        for (String[] row : ParticleCompat.table()) {
            if (row[1] == null) {
                continue; // removed after 1.12 (FOOTSTEP)
            }
            Particle p = Particle.valueOf(row[1]);
            Class<?> t = p.getDataType();
            if (t == ItemStack.class || t.getName().equals("org.bukkit.block.data.BlockData")) {
                continue; // needs a running server (BlockSpec/ItemCompat); covered on a real 26.3 server
            }
            assertTrue(row[0] + " -> " + p + " has data type " + t.getName(), DEFAULTED.contains(t.getName()));
            Object data = ParticleCompat.convertData(p, null);
            if (t == Void.class) {
                assertNull(data);
            } else {
                assertTrue(row[0] + " -> " + p + ": " + data, t.isInstance(data));
            }
        }
    }

    /** Every particle with a data type the helper can default works without data; the rest fail with a clear message. */
    @Test
    public void everyParticleWithoutDataEitherWorksOrSaysWhy() {
        List<Particle> all = Arrays.asList(Particle.values());
        assertTrue(all.size() > 100);
        for (Particle p : all) {
            Class<?> t = p.getDataType();
            if (t == ItemStack.class || t.getName().equals("org.bukkit.block.data.BlockData")
                    || t.getName().equals("org.bukkit.material.MaterialData")) {
                continue;
            }
            if (DEFAULTED.contains(t.getName())) {
                Object data = ParticleCompat.convertData(p, null);
                assertTrue(p + ": " + data, t == Void.class ? data == null : t.isInstance(data));
            } else {
                try {
                    ParticleCompat.convertData(p, null);
                    fail(p + " (" + t.getName() + ") without data should be refused");
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains(p.name()));
                }
            }
        }
    }

    /** The 1.13+ methods ModernApi binds at class init (legacy materials, block data, fromLegacy) still exist on 26.3. */
    @Test
    public void modernApiBindsOn263() {
        assertFalse(ModernApi.isLegacy(Material.STONE));
        Material legacy = ModernApi.legacyMaterial("STAINED_GLASS");
        assertNotNull("26.3 still has LEGACY_* materials", legacy);
        assertTrue(ModernApi.isLegacy(legacy));
        assertEquals("1.12 numeric ids still map to LEGACY_*", legacy, ModernApi.legacyMaterialById(95));
        // isBlock/createBlockData/fromLegacy need a running server's registries; the phase1-shows harness covers them
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Object call(Object o, String method) {
        try {
            return o.getClass().getMethod(method).invoke(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
