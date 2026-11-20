package us.mcparks.showscript.compat;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Particle helpers on a (mocked) Paper 1.12.2 server: coloured mob spell keeps count and spread, item particles get
 * the same item as the item(...) helper (tracker ryan/1-12-2-to-1-21-4#31, review of !27). Runs in its own JVM
 * (forkEvery = 1) because ServerVersion is fixed at class load.
 */
public class LegacyParticleTest {

    @BeforeClass
    public static void legacyServer() {
        Server server = mock(Server.class);
        when(server.getLogger()).thenReturn(Logger.getLogger("test"));
        when(server.getName()).thenReturn("test");
        when(server.getVersion()).thenReturn("test");
        when(server.getBukkitVersion()).thenReturn("1.12.2-R0.1-SNAPSHOT");
        Bukkit.setServer(server);
        assertTrue("ServerVersion must see 1.12.2", ServerVersion.LEGACY);
    }

    @Test
    public void colouredMobSpellKeepsCountAndSpreadOnWorld() {
        World world = mock(World.class);
        ParticleCompat.spawn(world, "spell_mob", 10, 64, 10, 50, .3, .3, .3, 0, 0x00FF00);
        assertSpread(world, null, 50);
    }

    @Test
    public void colouredMobSpellKeepsCountAndSpreadOnPlayer() {
        Player player = mock(Player.class);
        ParticleCompat.spawn(player, "ENTITY_EFFECT", 10, 64, 10, 20, .5, 0, .5, 0, "#00FF00");
        assertSpread(null, player, 20);
    }

    @Test
    public void mobSpellCountZeroIdiomIsOneParticleAtOrigin() {
        World world = mock(World.class);
        ParticleCompat.spawn(world, "SPELL_MOB", 10, 64, 10, 0, 0.0, 1.0, 0.0, 1, null);
        verify(world, times(1)).spawnParticle(eq(Particle.SPELL_MOB), eq(10.0), eq(64.0), eq(10.0), eq(0),
                eq(0.0), eq(1.0), eq(0.0), eq(1.0), isNull());
    }

    @Test
    public void itemParticleSpecsKeepTheVariant() {
        for (String spec : new String[] {"WOOL:14", "35:14"}) {
            Object data = ParticleCompat.convertData(Particle.ITEM_CRACK, spec);
            assertTrue(spec, data instanceof ItemStack);
            ItemStack stack = (ItemStack) data;
            assertEquals(spec, Material.WOOL, stack.getType());
            assertEquals(spec, 14, stack.getDurability());
        }
        ItemStack plain = (ItemStack) ParticleCompat.convertData(Particle.ITEM_CRACK, "diamond");
        assertEquals(Material.DIAMOND, plain.getType());
    }

    @Test(expected = IllegalArgumentException.class)
    public void itemParticleRejectsUnknownItems() {
        ParticleCompat.convertData(Particle.ITEM_CRACK, "NOT_A_REAL_ITEM");
    }

    @Test
    public void itemParticleSpawnsTheVariantItem() {
        World world = mock(World.class);
        ParticleCompat.spawn(world, "item_crack", 1, 2, 3, 5, .1, .1, .1, .05, "35:14");
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(world).spawnParticle(eq(Particle.ITEM_CRACK), eq(1.0), eq(2.0), eq(3.0), eq(5), eq(.1), eq(.1),
                eq(.1), eq(.05), data.capture());
        ItemStack stack = (ItemStack) data.getValue();
        assertEquals(Material.WOOL, stack.getType());
        assertEquals(14, stack.getDurability());
    }

    /** n packets, each count 0 with green (0, 1, 0) in the offsets, at varied positions around (10, 64, 10). */
    private static void assertSpread(World world, Player player, int n) {
        ArgumentCaptor<Double> x = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> z = ArgumentCaptor.forClass(Double.class);
        if (world != null) {
            verify(world, times(n)).spawnParticle(eq(Particle.SPELL_MOB), x.capture(), anyDouble(), z.capture(),
                    eq(0), eq(0.0), eq(1.0), eq(0.0), eq(1.0), isNull());
        } else {
            verify(player, times(n)).spawnParticle(eq(Particle.SPELL_MOB), x.capture(), anyDouble(), z.capture(),
                    eq(0), eq(0.0), eq(1.0), eq(0.0), eq(1.0), isNull());
        }
        List<Double> xs = x.getAllValues();
        Set<Double> distinct = new HashSet<>(xs);
        assertTrue("particles are spread out, not all at the origin: " + distinct.size(), distinct.size() > n / 2);
        for (int i = 0; i < n; i++) {
            assertTrue("within the spread", Math.abs(xs.get(i) - 10) < 5 && Math.abs(z.getAllValues().get(i) - 10) < 5);
        }
    }
}
