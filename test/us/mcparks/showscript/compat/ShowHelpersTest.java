package us.mcparks.showscript.compat;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Server-free parts of the #31 show helpers: name tables and colour/spec parsing. */
public class ShowHelpersTest {

    /** org.bukkit.Particle constants of paper-api 1.21.4-R0.1-SNAPSHOT (javap). */
    private static final Set<String> PARTICLES_1_21_4 = new HashSet<>(Arrays.asList((
            "POOF EXPLOSION EXPLOSION_EMITTER FIREWORK BUBBLE SPLASH FISHING UNDERWATER CRIT ENCHANTED_HIT SMOKE "
            + "LARGE_SMOKE EFFECT INSTANT_EFFECT ENTITY_EFFECT WITCH DRIPPING_WATER DRIPPING_LAVA ANGRY_VILLAGER "
            + "HAPPY_VILLAGER MYCELIUM NOTE PORTAL ENCHANT FLAME LAVA CLOUD DUST ITEM_SNOWBALL ITEM_SLIME HEART ITEM "
            + "BLOCK RAIN ELDER_GUARDIAN DRAGON_BREATH END_ROD DAMAGE_INDICATOR SWEEP_ATTACK FALLING_DUST "
            + "TOTEM_OF_UNDYING SPIT SQUID_INK BUBBLE_POP CURRENT_DOWN BUBBLE_COLUMN_UP NAUTILUS DOLPHIN SNEEZE "
            + "CAMPFIRE_COSY_SMOKE CAMPFIRE_SIGNAL_SMOKE COMPOSTER FLASH FALLING_LAVA LANDING_LAVA FALLING_WATER "
            + "BLOCK_MARKER").split(" ")));

    @Test
    public void everyTableEntryExistsOnItsVersion() {
        for (String[] row : ParticleCompat.table()) {
            Particle.valueOf(row[0]); // 1.12 API on the test classpath; throws if the legacy name is wrong
            if (row[1] != null) {
                assertTrue("not a 1.21.4 particle: " + row[1], PARTICLES_1_21_4.contains(row[1]));
            }
        }
        // every 1.12 particle has a row
        for (Particle p : Particle.values()) {
            assertEquals(p.name(), ParticleCompat.enumNameFor(p.name(), true));
        }
    }

    @Test
    public void namesResolveInEveryForm() {
        String[][] cases = {
                // input, legacy, modern
                {"REDSTONE", "REDSTONE", "DUST"},
                {"dust", "REDSTONE", "DUST"},
                {"minecraft:dust", "REDSTONE", "DUST"},
                {"reddust", "REDSTONE", "DUST"},
                {"BLOCK_DUST", "BLOCK_DUST", "BLOCK"},
                {"blockcrack", "BLOCK_CRACK", "BLOCK"},
                {"block", "BLOCK_DUST", "BLOCK"},
                {"FIREWORKS_SPARK", "FIREWORKS_SPARK", "FIREWORK"},
                {"firework", "FIREWORKS_SPARK", "FIREWORK"},
                {"fireworksSpark", "FIREWORKS_SPARK", "FIREWORK"},
                {"WATER_SPLASH", "WATER_SPLASH", "SPLASH"},
                {"splash", "WATER_SPLASH", "SPLASH"},
                {"end_rod", "END_ROD", "END_ROD"},
                {"note", "NOTE", "NOTE"},
                {"Flame", "FLAME", "FLAME"},
                {"TOTEM", "TOTEM", "TOTEM_OF_UNDYING"},
                {"explode", "EXPLOSION_NORMAL", "POOF"},
                {"SQUID_INK", "SQUID_INK", "SQUID_INK"}, // unknown to the table: passed through
        };
        for (String[] c : cases) {
            assertEquals(c[0] + " on 1.12", c[1], ParticleCompat.enumNameFor(c[0], true));
            assertEquals(c[0] + " on 1.21", c[2], ParticleCompat.enumNameFor(c[0], false));
        }
        assertNull(ParticleCompat.enumNameFor("footstep", false));
    }

    @Test
    public void colours() {
        assertEquals(Color.fromRGB(0xFF0000), ParticleCompat.color(0xFF0000));
        assertEquals(Color.fromRGB(0x00FF7F), ParticleCompat.color("#00FF7F"));
        assertEquals(Color.fromRGB(0x00FF7F), ParticleCompat.color("00ff7f"));
        assertEquals(Color.AQUA, ParticleCompat.color("aqua"));
        // fractions (Groovy decimal literals are BigDecimal)
        Color c = ParticleCompat.color(Arrays.asList(new BigDecimal("0.001"), new BigDecimal("0.443"), new BigDecimal("0.808")));
        assertEquals(0, c.getRed());
        assertEquals(113, c.getGreen());
        assertEquals(206, c.getBlue());
        // one scale only (#221 A33): whole numbers in a list are fractions too, so [1, 0, 0] and [1.0, 0, 0] are red
        assertEquals(Color.fromRGB(255, 0, 0), ParticleCompat.color(Arrays.asList(1, 0, 0)));
        assertEquals(Color.fromRGB(255, 0, 0), ParticleCompat.color(Arrays.asList(1.0, 0, 0)));
        for (Object outOfRange : Arrays.<Object>asList(Arrays.asList(10, 20, 30), Arrays.asList(255, 0, 0),
                Arrays.asList(1.5, 0, 0), Arrays.asList(-1.0, -1.0, -1.0))) {
            try {
                ParticleCompat.color(outOfRange);
                fail("expected an error for " + outOfRange);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("0-1 fractions"));
            }
        }
        Map<String, Object> m = new HashMap<>();
        m.put("r", 0.92);
        m.put("g", 0.26);
        m.put("b", 0.26);
        assertEquals(Color.fromRGB(235, 66, 66), ParticleCompat.color(m));
        try {
            ParticleCompat.color("not a colour");
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void materialSpecs() {
        MaterialSpec.Parsed p = MaterialSpec.parse("95:1");
        assertNull(p.name);
        assertEquals(95, p.id);
        assertEquals(1, p.data);
        assertTrue(p.hasData);
        p = MaterialSpec.parse("minecraft:stained_glass:14");
        assertEquals("STAINED_GLASS", p.name);
        assertEquals(14, p.data);
        p = MaterialSpec.parse("orange_stained_glass");
        assertEquals("ORANGE_STAINED_GLASS", p.name);
        assertFalse(p.hasData);
        assertEquals(-1, p.id);
        assertFalse(p.namespaced);
        // a minecraft: prefix marks a modern id (BRICK the item on 1.21, not the 1.12 bricks block)
        p = MaterialSpec.parse("minecraft:brick");
        assertEquals("BRICK", p.name);
        assertTrue(p.namespaced);
        assertFalse(MaterialSpec.parse("BRICK").namespaced);
    }

    @Test
    public void mobSpellIsColourable() {
        assertTrue(ParticleCompat.isMobSpell(Particle.SPELL_MOB));
        assertTrue(ParticleCompat.isMobSpell(Particle.SPELL_MOB_AMBIENT));
        assertFalse(ParticleCompat.isMobSpell(Particle.SPELL));
        assertEquals("ENTITY_EFFECT", ParticleCompat.enumNameFor("mobspell", false));
    }

    @Test
    public void enchantmentNames() {
        assertEquals("DURABILITY", ItemCompat.legacyEnchantmentName("unbreaking"));
        assertEquals("DURABILITY", ItemCompat.legacyEnchantmentName("DURABILITY"));
        assertEquals("unbreaking", ItemCompat.enchantmentKey("DURABILITY"));
        assertEquals("unbreaking", ItemCompat.enchantmentKey("minecraft:unbreaking"));
        assertEquals("sharpness", ItemCompat.enchantmentKey("DAMAGE_ALL"));
    }

    @Test
    public void textureValues() {
        String v = ItemCompat.textureValue("http://textures.minecraft.net/texture/abc");
        assertEquals("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc\"}}}",
                new String(java.util.Base64.getDecoder().decode(v), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("eyJ0ZXh0dXJlcyI6e30=", ItemCompat.textureValue("eyJ0ZXh0dXJlcyI6e30="));
    }
}
