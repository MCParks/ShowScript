package us.mcparks.showscript.showscript.groovy.dsl;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import groovy.lang.Closure;
import groovy.lang.Script;
import groovy.transform.BaseScript;
import us.mcparks.showscript.Main;
import us.mcparks.showscript.event.show.SetShowScriptVariableEvent;
import us.mcparks.showscript.showscript.framework.ShowArgs;
import us.mcparks.showscript.showscript.framework.schedulers.ShowScheduler;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.groovy.GroovyShowAction;
import us.mcparks.showscript.showscript.groovy.GroovyShowConfig;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import us.mcparks.showscript.compat.BlockSpec;
import us.mcparks.showscript.compat.ItemCompat;
import us.mcparks.showscript.compat.MaterialSpec;
import us.mcparks.showscript.compat.ParticleCompat;
import us.mcparks.showscript.compat.ServerVersion;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@BaseScript
public abstract class TimecodeDsl extends Script {
    protected Multimap<Integer, ShowAction> actions = ArrayListMultimap.create();
    protected Closure<?> showClosure = null;

    private static final Map<String, Object> globalVariables = new ConcurrentHashMap<>();

    private final Map<String, Object> exportedVariables = new HashMap<>();

    public TimecodeDsl() {

    }

    // the entire show may be wrapped in a show { } block -- this means that it takes arguments like show { arg, arg -> ... }
    public void show(Closure<?> closure) {
        closure.setDelegate(this);
        closure.setResolveStrategy(Closure.DELEGATE_FIRST);
        showClosure = closure;
    }

    // handle tick(<int>} { ... }, a closure whose body will be parsed as ShowActionDsl. The resulting show actions will be scheduled to run at the specified tick.
    public void ticks(int tick, Closure<?> closure) {
        ticks(new HashMap<>(), tick, closure);
    }

    // handle seconds(<int>} { ... }, a closure whose body will be parsed as ShowActionDsl. The resulting show actions will be scheduled to run at seconds*20
    public void seconds(double seconds, Closure<?> closure) {
        seconds(new HashMap<>(), seconds, closure);
    }


    // handle tick([parseNow: <boolean>], <int>} { ... }, a closure whose body will be parsed as ShowActionDsl. The resulting show actions will be scheduled to run at the specified tick.
    // if parseNow is true, the show action blocks will be executed immediately and their resulting actions will be scheduled. otherwise, the code that will resolve to the show actions will be scheduled to run at the specified tick.
    public void ticks(Map<String,?> optionalParams, int tick, Closure<?> closure) {
        //System.out.println("ticks(" + tick + ")");
        // closure will be several ShowActions
        processTimecode(tick, optionalParams, closure);
    }

    // delegate to ticks with seconds * 20
    public void seconds(Map<String,?> optionalParams, double seconds, Closure<?> closure) {
        ticks(optionalParams, (int) (seconds*20), closure);
    }

    private void processTimecode(int tick, Map<String,?> optionalParams, Closure<?> closure) {
        boolean parseNow = false;
        if (optionalParams.containsKey("parseNow")) {
            parseNow = (Boolean) optionalParams.get("parseNow");
        }


        if (parseNow) {
            //System.out.println("parsing now");
            ShowActionDsl showActionDsl = new ShowActionDsl();
            closure.setDelegate(showActionDsl);
            closure.setResolveStrategy(Closure.DELEGATE_FIRST);
            closure.run();
            actions.putAll(tick, showActionDsl.getActions());
        } else {
            //System.out.println("waiting to parse later");
            actions.put(tick, new GroovyShowAction(closure));
        }

    }

    // this is used internally to wrap around the text of the script, end-users shouldn't be using it
    public Multimap<Integer, ShowAction> evaluate(Object[] args) {
        actions = ArrayListMultimap.create();
        run();

        if (showClosure != null) {
            // no arguments given (show start without --args, show startasync) = zero arguments
            Object[] showArgs = args == null ? new Object[0] : args;
            if (showArgs.length != showClosure.getMaximumNumberOfParameters()) {
                throw new IllegalArgumentException("Expected " + showClosure.getMaximumNumberOfParameters() + " arguments, but got " + showArgs.length);
            }
            showClosure.call(showArgs);
        }
        return actions;
    }

    public int getMaximumNumberOfParameters() {
        return showClosure == null ? 0 : showClosure.getMaximumNumberOfParameters();
    }


    /*
    Global Variables -- these are variables that are shared across all shows and can be accessed and modified by any show
     */

    public Object getGlobalVariable(String name) {
        return globalVariables.get(name);
    }

    public Object getGlobalVariable(String name, Object defaultValue) {
        return globalVariables.getOrDefault(name, defaultValue);
    }

    public void setGlobalVariable(String name, Object value) {
        globalVariables.put(name, value);
        Bukkit.getServer().getPluginManager().callEvent(new SetShowScriptVariableEvent(name, value));
    }

    /*
        `export` and `load` allow a show to export variables to be used by other shows, and to load exported variables
        from other shows into a Map<String, Object> that can be used by the calling show
     */

    public Map<String, Object> load(String showPath, Object... args) throws Exception {
        GroovyShowConfig showConfig = new GroovyShowConfig(showPath, new ShowArgs(args));
        return showConfig.getExportedVariables();
    }

    public void export(String name, Object value) {
        exportedVariables.put(name, value);
    }

    public Map<String, Object> getExports() {
        return exportedVariables;
    }

    /*
    * Convenience methods for end-users to create Bukkit objects and perform common operations
    * */

    public World world(String worldName) {
        return Bukkit.getWorld(worldName);
    }

    public Player player(String playerName) {
        return Bukkit.getPlayerExact(playerName);
    }

    public Location location(String worldName, double x, double y, double z) {
        return new Location(world(worldName), x, y, z);
    }

    public Location location(World world, double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    public Location location(double x, double y, double z) {
        return new Location(world("world"), x, y, z);
    }

    public Collection<? extends Player> onlinePlayers() {
        return Bukkit.getOnlinePlayers();
    }

    public double sin(double angle) {
        return Math.sin(Math.toRadians(angle));
    }

    public double cos(double angle) {
        return Math.cos(Math.toRadians(angle));
    }

    public double tan(double angle) {
        return Math.tan(Math.toRadians(angle));
    }

    public Collection<Player> playersInRegion(String regionName) {
        return Main.getPlugin(Main.class).getRegionListener().getPlayersInRegion(regionName);
    }

    public Collection<String> playerRegions(Player player) {
        return Main.getPlugin(Main.class).getRegionListener().getRegionsForPlayer(player);
    }

    public Collection<String> playerRegions(String playerName) {
        return Main.getPlugin(Main.class).getRegionListener().getRegionsForPlayer(player(playerName));
    }

    public Collection<ShowScheduler> runningShows() {
        return Main.getPlugin(Main.class).getActiveShows();
    }
    public boolean isShowRunning(String showName) {
        return runningShows().stream().anyMatch(show -> show.getName().equals(showName));
    }


    /*
     * Version-agnostic helpers (tracker #31). The same show file runs on Paper 1.12.2 and 1.21.x: names and data
     * types that changed between versions are translated for the running server. Guide: docs/version-agnostic-helpers.md
     * in the technicians' shows repo.
     */

    /** The running server's particle for a 1.12 name (REDSTONE), a 1.20.5+ name / id (dust), or a Particle. */
    public Particle particleType(Object name) {
        return ParticleCompat.resolve(name);
    }

    public void particle(Object target, Object type, Location loc) {
        particle(target, type, loc.getX(), loc.getY(), loc.getZ(), 1, 0, 0, 0, 0, null);
    }

    public void particle(Object target, Object type, Location loc, int count, double dx, double dy, double dz, double extra) {
        particle(target, type, loc.getX(), loc.getY(), loc.getZ(), count, dx, dy, dz, extra, null);
    }

    public void particle(Object target, Object type, Location loc, int count, double dx, double dy, double dz, double extra, Object data) {
        particle(target, type, loc.getX(), loc.getY(), loc.getZ(), count, dx, dy, dz, extra, data);
    }

    public void particle(Object target, Object type, double x, double y, double z) {
        particle(target, type, x, y, z, 1, 0, 0, 0, 0, null);
    }

    public void particle(Object target, Object type, double x, double y, double z, int count) {
        particle(target, type, x, y, z, count, 0, 0, 0, 0, null);
    }

    public void particle(Object target, Object type, double x, double y, double z, int count, double dx, double dy, double dz) {
        particle(target, type, x, y, z, count, dx, dy, dz, 0, null);
    }

    public void particle(Object target, Object type, double x, double y, double z, int count, double dx, double dy, double dz, double extra) {
        particle(target, type, x, y, z, count, dx, dy, dz, extra, null);
    }

    /**
     * Like {@code world.spawnParticle(type, x, y, z, count, dx, dy, dz, extra, data)} on any server version.
     * {@code target} is a World (everyone nearby sees it) or a Player (only they do). Block particles take a block
     * spec ("95:1", "water"), dust takes a colour (0xRRGGBB, "#RRGGBB", or [r, g, b] / [r:, g:, b:] of 0-1 fractions).
     */
    public void particle(Object target, Object type, double x, double y, double z, int count, double dx, double dy, double dz, double extra, Object data) {
        ParticleCompat.spawn(target, type, x, y, z, count, dx, dy, dz, extra, data);
    }

    /** One coloured dust particle (1.12 REDSTONE / 1.21 DUST) at x, y, z. */
    public void dust(Object target, double x, double y, double z, Object color) {
        ParticleCompat.dust(target, x, y, z, 1, 0, 0, 0, ParticleCompat.color(color), 1.0f);
    }

    /** One coloured dust particle; {@code size} only has an effect on 1.13+ (1.12 dust has a fixed size). */
    public void dust(Object target, double x, double y, double z, Object color, double size) {
        ParticleCompat.dust(target, x, y, z, 1, 0, 0, 0, ParticleCompat.color(color), (float) size);
    }

    public void dust(Object target, Location loc, Object color) {
        dust(target, loc.getX(), loc.getY(), loc.getZ(), color);
    }

    public void dust(Object target, Location loc, Object color, double size) {
        dust(target, loc.getX(), loc.getY(), loc.getZ(), color, size);
    }

    /** {@code count} dust particles spread around x, y, z by the offsets (Gaussian, like spawnParticle). */
    public void dust(Object target, double x, double y, double z, int count, double dx, double dy, double dz, Object color) {
        ParticleCompat.dust(target, x, y, z, count, dx, dy, dz, ParticleCompat.color(color), 1.0f);
    }

    public void dust(Object target, double x, double y, double z, int count, double dx, double dy, double dz, Object color, double size) {
        ParticleCompat.dust(target, x, y, z, count, dx, dy, dz, ParticleCompat.color(color), (float) size);
    }

    /** A Bukkit Color from 0xRRGGBB, "#RRGGBB", a colour name, or [r, g, b] / [r:, g:, b:] of 0-1 fractions. */
    public Color color(Object spec) {
        return ParticleCompat.color(spec);
    }

    /** Block particle data for this server (MaterialData on 1.12, BlockData on 1.13+) from "95:1", "water", ... */
    public Object blockData(Object spec) {
        return BlockSpec.particleData(spec);
    }

    /** The running server's Material for a name, "name:data", a 1.12 id or "id:data"; null if unknown. */
    public Material material(Object spec) {
        return MaterialSpec.material(spec);
    }

    /**
     * An item stack from a 1.12-style description: {@code item("DIAMOND_HOE", 1, 5)} keeps damage 5 (tools and
     * armour), {@code item("WOOL", 1, 14)} / {@code item("35:14")} is red wool on both versions (the damage value
     * picks the variant). Null if the material is unknown. Use this instead of
     * {@code new ItemStack(material(name), 1, (short) damage)}, which loses the variant on 1.13+.
     */
    public ItemStack item(Object spec, int amount, int damage) {
        return ItemCompat.item(spec, amount, damage);
    }

    public ItemStack item(Object spec, int amount) {
        return ItemCompat.item(spec, amount, 0);
    }

    public ItemStack item(Object spec) {
        return ItemCompat.item(spec, 1, 0);
    }

    /** The running server's enchantment for a 1.12 name (DURABILITY) or a 1.21 name / key (unbreaking). */
    public Enchantment enchantment(Object name) {
        return ItemCompat.enchantment(name);
    }

    /** A player head with a custom texture (base64 value, textures.minecraft.net URL, or hash). */
    public ItemStack customSkull(String texture) {
        return ItemCompat.customSkull(null, texture);
    }

    /** A player head with a custom texture and an explicit profile UUID. */
    public ItemStack customSkull(String uuid, String texture) {
        return ItemCompat.customSkull(uuid, texture);
    }

    /** [uuid:, name:, texture:] of a player-head item or placed head block, or null. */
    public Map<String, String> skullTexture(Object itemOrBlock) {
        return ItemCompat.skullTexture(itemOrBlock);
    }

    public boolean isPlayerHead(ItemStack item) {
        return ItemCompat.isPlayerHead(item);
    }

    /** True on a pre-1.13 server (1.12.2). Prefer the helpers above; branch on this only as a last resort. */
    public boolean isLegacyServer() {
        return ServerVersion.LEGACY;
    }

    /** The running Minecraft version, e.g. "1.12.2" or "1.21.4". */
    public String minecraftVersion() {
        return ServerVersion.CURRENT.toString();
    }

    // this is used internally to wrap around the text of the script, end-users shouldn't be told about it
    public Multimap<Integer, ShowAction> getActions() {
        return actions;
    }

}
