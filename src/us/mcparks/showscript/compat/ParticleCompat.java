package us.mcparks.showscript.compat;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version-agnostic particles for Groovy shows (ShowScript 3 helpers {@code particle}, {@code dust},
 * {@code particleType}). Particle names and data types changed in 1.13 and again in 1.20.5
 * ({@code REDSTONE} -> {@code DUST} + {@code DustOptions}, {@code BLOCK_DUST}/{@code BLOCK_CRACK} -> {@code BLOCK} +
 * {@code BlockData}, {@code FIREWORKS_SPARK} -> {@code FIREWORK}, ...). Shows name a particle in any of the forms
 * below and get the running server's constant, with its data converted to the type that server expects.
 *
 * <p>Accepted names (case, underscores and a {@code minecraft:} prefix are ignored): the 1.12 Bukkit enum name
 * ({@code REDSTONE}, {@code BLOCK_DUST}), the 1.21 Bukkit enum name / Minecraft id ({@code dust}, {@code block}),
 * the 1.12 Minecraft id used by {@code /particle} ({@code reddust}, {@code blockdust}), or a {@link Particle}
 * constant.
 *
 * <p>The modern column uses the <b>1.20.5+</b> enum names ({@code DUST}, {@code BLOCK}, {@code FIREWORK}, ...).
 * 1.13-1.20.4 servers (no MCParks target) named several of these differently and aren't supported by the table.
 *
 * <p>Delete the legacy branches when 1.12.2 support ends.
 */
public final class ParticleCompat {

    /** {legacy (1.12) enum name, modern (1.21) enum name or null if removed, extra aliases...}. First match wins. */
    private static final String[][] TABLE = {
            {"REDSTONE", "DUST", "reddust"},
            {"BLOCK_DUST", "BLOCK", "blockdust"},
            {"BLOCK_CRACK", "BLOCK", "blockcrack"},
            {"FALLING_DUST", "FALLING_DUST", "fallingdust"},
            {"ITEM_CRACK", "ITEM", "iconcrack"},
            {"FIREWORKS_SPARK", "FIREWORK", "fireworksspark"},
            {"EXPLOSION_NORMAL", "POOF", "explode"},
            {"EXPLOSION_LARGE", "EXPLOSION", "largeexplode"},
            {"EXPLOSION_HUGE", "EXPLOSION_EMITTER", "hugeexplosion"},
            {"WATER_BUBBLE", "BUBBLE", "bubble"},
            {"WATER_SPLASH", "SPLASH", "splash"},
            {"WATER_WAKE", "FISHING", "wake"},
            {"WATER_DROP", "RAIN", "droplet"},
            {"SUSPENDED", "UNDERWATER", "suspended"},
            {"SUSPENDED_DEPTH", "UNDERWATER", "depthsuspend"},
            {"CRIT", "CRIT"},
            {"CRIT_MAGIC", "ENCHANTED_HIT", "magiccrit"},
            {"SMOKE_NORMAL", "SMOKE", "smoke"},
            {"SMOKE_LARGE", "LARGE_SMOKE", "largesmoke"},
            {"SPELL", "EFFECT", "spell"},
            {"SPELL_INSTANT", "INSTANT_EFFECT", "instantspell"},
            {"SPELL_MOB", "ENTITY_EFFECT", "mobspell"},
            {"SPELL_MOB_AMBIENT", "ENTITY_EFFECT", "mobspellambient"},
            {"SPELL_WITCH", "WITCH", "witchmagic"},
            {"DRIP_WATER", "DRIPPING_WATER", "dripwater"},
            {"DRIP_LAVA", "DRIPPING_LAVA", "driplava"},
            {"VILLAGER_ANGRY", "ANGRY_VILLAGER", "angryvillager"},
            {"VILLAGER_HAPPY", "HAPPY_VILLAGER", "happyvillager"},
            {"TOWN_AURA", "MYCELIUM", "townaura"},
            {"NOTE", "NOTE"},
            {"PORTAL", "PORTAL"},
            {"ENCHANTMENT_TABLE", "ENCHANT", "enchantmenttable"},
            {"FLAME", "FLAME"},
            {"LAVA", "LAVA"},
            {"CLOUD", "CLOUD"},
            {"SNOWBALL", "ITEM_SNOWBALL", "snowballpoof"},
            {"SNOW_SHOVEL", "ITEM_SNOWBALL", "snowshovel"},
            {"SLIME", "ITEM_SLIME", "slime"},
            {"HEART", "HEART"},
            {"BARRIER", "BLOCK_MARKER", "barrier"},
            {"MOB_APPEARANCE", "ELDER_GUARDIAN", "mobappearance"},
            {"DRAGON_BREATH", "DRAGON_BREATH", "dragonbreath"},
            {"END_ROD", "END_ROD", "endrod"},
            {"DAMAGE_INDICATOR", "DAMAGE_INDICATOR", "damageindicator"},
            {"SWEEP_ATTACK", "SWEEP_ATTACK", "sweepattack"},
            {"TOTEM", "TOTEM_OF_UNDYING", "totem"},
            {"SPIT", "SPIT"},
            {"FOOTSTEP", null, "footstep"},
    };

    private static final Map<String, String[]> ROWS = new HashMap<>();

    static {
        for (String[] row : TABLE) {
            for (int i = 0; i < row.length; i++) {
                if (row[i] != null) {
                    ROWS.putIfAbsent(normalize(row[i]), row);
                }
            }
        }
    }

    private static final Map<String, Particle> CACHE = new ConcurrentHashMap<>();
    private static final Random RANDOM = new Random();

    private ParticleCompat() {
    }

    /** The alias table (for tests). */
    static String[][] table() {
        return TABLE;
    }

    static String normalize(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("minecraft:")) {
            n = n.substring("minecraft:".length());
        }
        return n.replace("_", "").replace(" ", "");
    }

    /** The enum name to look up on a server of the given kind, or null if the particle doesn't exist there. */
    static String enumNameFor(String name, boolean legacy) {
        String[] row = ROWS.get(normalize(name));
        if (row == null) {
            return name.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
        }
        return legacy ? row[0] : row[1];
    }

    /**
     * The running server's {@link Particle} for a name in any supported form, or the constant itself.
     *
     * @throws IllegalArgumentException if this server has no such particle
     */
    public static Particle resolve(Object type) {
        if (type instanceof Particle) {
            return (Particle) type; // a constant of this server's enum is already right
        }
        if (type == null) {
            throw new IllegalArgumentException("particle type is null");
        }
        String name = type.toString();
        Particle cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        String enumName = enumNameFor(name, ServerVersion.LEGACY);
        Particle particle = null;
        if (enumName != null) {
            try {
                particle = Particle.valueOf(enumName);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        if (particle == null) {
            throw new IllegalArgumentException("Unknown particle '" + name + "' on this server ("
                    + ServerVersion.describe() + ")");
        }
        CACHE.put(name, particle);
        return particle;
    }

    /** True if this particle is coloured redstone dust (1.12 REDSTONE / 1.21 DUST). */
    static boolean isDust(Particle particle) {
        String n = particle.name();
        return n.equals("REDSTONE") || n.equals("DUST");
    }

    /**
     * Spawns a particle like {@code World/Player.spawnParticle(type, x, y, z, count, dx, dy, dz, extra, data)},
     * resolving {@code type} and converting {@code data} for the running server:
     * <ul>
     * <li>block particles take a block spec: {@code "95:1"}, {@code "stained_glass:1"}, {@code "water"}, a
     * {@code Material}, {@code MaterialData} or {@code BlockData};</li>
     * <li>item particles take an {@code ItemStack} or anything {@code item(...)} takes ({@code "diamond"},
     * {@code "WOOL:14"}, {@code "35:14"}), giving the same item on both versions; unknown items throw;</li>
     * <li>dust takes a colour (see {@link #color(Object)}). Without one, the 1.12 convention applies: with
     * {@code count == 0} the offsets are the colour (0-1 per channel, times {@code extra}), as on 1.12; otherwise
     * the dust is red.</li>
     * <li>mob spell ({@code SPELL_MOB}/{@code SPELL_MOB_AMBIENT}, 1.21 {@code ENTITY_EFFECT}) takes an optional
     * colour the same way: with data, or with the 1.12 {@code count == 0} offsets-as-colour idiom, both versions
     * draw that colour (on 1.12 it's sent as offsets, on 1.21 as {@code Color} data); otherwise 1.21 draws white.
     * With a colour, {@code count} and the spread work on both versions (1.12 sends one packet per particle).</li>
     * </ul>
     *
     * @param target a {@link World} (everyone nearby sees it) or a {@link Player} (only they see it)
     */
    public static void spawn(Object target, Object type, double x, double y, double z, int count,
                             double dx, double dy, double dz, double extra, Object data) {
        Particle particle = resolve(type);
        if (isDust(particle)) {
            if (data == null && count == 0) {
                // 1.12 idiom: count 0 + offsets as RGB fractions + extra 1
                dust(target, x, y, z, 1, 0, 0, 0, fractionsToColor(dx * extra, dy * extra, dz * extra), 1.0f);
                return;
            }
            dust(target, x, y, z, Math.max(count, 1), dx, dy, dz, data == null ? Color.RED : color(data), 1.0f);
            return;
        }
        if (isMobSpell(particle)) {
            Color c = data != null ? color(data)
                    : count == 0 ? fractionsToColor(dx * extra, dy * extra, dz * extra) : null;
            if (c != null) {
                if (ServerVersion.LEGACY) {
                    // 1.12: count 0 makes the client read the offsets (times extra) as RGB fractions, so each
                    // coloured particle is its own packet. A count/spread is emulated like dust(): count packets at
                    // Gaussian offsets. With the count-0 idiom the offsets were the colour: one particle, no spread.
                    if (count == 0) {
                        spawnLegacyColoured(target, particle, x, y, z, 1, 0, 0, 0, c, false);
                    } else {
                        spawnLegacyColoured(target, particle, x, y, z, count, dx, dy, dz, c, false);
                    }
                } else {
                    spawnRaw(target, particle, x, y, z, Math.max(count, 1), count == 0 ? 0 : dx,
                            count == 0 ? 0 : dy, count == 0 ? 0 : dz, count == 0 ? 0 : extra, c);
                }
                return;
            }
        }
        Object converted = convertData(particle, data);
        spawnRaw(target, particle, x, y, z, count, dx, dy, dz, extra, converted);
    }

    /** True for the coloured mob-effect swirl (1.12 SPELL_MOB / SPELL_MOB_AMBIENT, 1.21 ENTITY_EFFECT). */
    static boolean isMobSpell(Particle particle) {
        String n = particle.name();
        return n.equals("SPELL_MOB") || n.equals("SPELL_MOB_AMBIENT") || n.equals("ENTITY_EFFECT");
    }

    /**
     * Coloured dust. On 1.21 this is {@code DUST} with {@code DustOptions(color, size)}. On 1.12 it's
     * {@code REDSTONE} with the colour in the offsets (count 0), and {@code size} is ignored; a spread
     * ({@code count > 1} with offsets) is emulated by spawning {@code count} particles at Gaussian offsets.
     */
    public static void dust(Object target, double x, double y, double z, int count,
                            double dx, double dy, double dz, Color color, float size) {
        if (ServerVersion.LEGACY) {
            // 1.12 client quirk (redstone only): a red value of exactly 0 is drawn as 1 (full red).
            spawnLegacyColoured(target, Particle.valueOf("REDSTONE"), x, y, z, count, dx, dy, dz, color, true);
            return;
        }
        Particle dust = Particle.valueOf("DUST");
        spawnRaw(target, dust, x, y, z, Math.max(count, 1), dx, dy, dz, 0, dustOptions(color, size));
    }

    /**
     * 1.12 coloured particles (REDSTONE, SPELL_MOB, SPELL_MOB_AMBIENT): the colour travels in the offsets of a
     * count-0 packet, so the packet itself can't carry a count or spread. Sends {@code max(count, 1)} packets, each at
     * a Gaussian offset scaled by {@code dx/dy/dz} (like the server's own spread) unless there is a single particle
     * with no spread. {@code redZeroQuirk}: the 1.12 redstone particle draws red 0 as full red, so send a tiny value.
     */
    static void spawnLegacyColoured(Object target, Particle particle, double x, double y, double z, int count,
                                    double dx, double dy, double dz, Color color, boolean redZeroQuirk) {
        double r = color.getRed() / 255.0;
        if (redZeroQuirk) {
            r = Math.max(r, 0.001);
        }
        double g = color.getGreen() / 255.0;
        double b = color.getBlue() / 255.0;
        int n = Math.max(count, 1);
        boolean spread = n > 1 || dx != 0 || dy != 0 || dz != 0;
        for (int i = 0; i < n; i++) {
            double px = x, py = y, pz = z;
            if (spread) {
                px += RANDOM.nextGaussian() * dx;
                py += RANDOM.nextGaussian() * dy;
                pz += RANDOM.nextGaussian() * dz;
            }
            spawnRaw(target, particle, px, py, pz, 0, r, g, b, 1, null);
        }
    }

    private static volatile Constructor<?> dustOptionsCtor;

    private static Object dustOptions(Color color, float size) {
        try {
            Constructor<?> c = dustOptionsCtor;
            if (c == null) {
                c = Class.forName("org.bukkit.Particle$DustOptions").getConstructor(Color.class, float.class);
                dustOptionsCtor = c;
            }
            return c.newInstance(color, size);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Particle.DustOptions is not available on this server", e);
        }
    }

    private static volatile Constructor<?> spellCtor;

    /** {@code new Particle.Spell(color, power)} (Paper 1.21.9+; looked up reflectively, ShowsRebuild compiles against 1.12). */
    private static Object spell(Class<?> spellClass, Color color, float power) {
        try {
            Constructor<?> c = spellCtor;
            if (c == null) {
                c = spellClass.getConstructor(Color.class, float.class);
                spellCtor = c;
            }
            return c.newInstance(color, power);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Particle.Spell(Color, float) is not available on this server", e);
        }
    }

    /**
     * Converts show-level data to what this server's particle expects; returns null for particles without data.
     * Particles that gained required data in a later version (1.21.9+: {@code FLASH} a colour, {@code EFFECT} and
     * {@code INSTANT_EFFECT} a {@code Spell}, {@code DRAGON_BREATH} a power) get vanilla's defaults when the show
     * passes none, so a show written for 1.12.2/1.21.4 keeps working on 26.x.
     */
    static Object convertData(Particle particle, Object data) {
        Class<?> type = particle.getDataType();
        if (type == null || type == Void.class) {
            return null;
        }
        if (data != null && type.isInstance(data)) {
            return data;
        }
        String typeName = type.getName();
        if (typeName.equals("org.bukkit.material.MaterialData") || typeName.equals("org.bukkit.block.data.BlockData")) {
            if (data == null && particle.name().equals("BLOCK_MARKER")) {
                return BlockSpec.particleData("barrier");
            }
            if (data == null) {
                throw new IllegalArgumentException("particle " + particle + " needs a block, e.g. \"95:1\" or \"water\"");
            }
            return BlockSpec.particleData(data);
        }
        if (type == ItemStack.class) {
            if (data == null) {
                throw new IllegalArgumentException("particle " + particle + " needs an item, e.g. \"diamond\"");
            }
            // Same item as the item(...) helper on both versions: "WOOL:14" / "35:14" is red wool on 1.12 (damage 14,
            // which the 1.12 item particle sends) and RED_WOOL on 1.21.
            ItemStack stack = data instanceof ItemStack ? (ItemStack) data : ItemCompat.item(data, 1, 0);
            if (stack == null || stack.getType() == Material.AIR) {
                throw new IllegalArgumentException("particle " + particle + ": unknown item '" + data
                        + "' on this server (" + ServerVersion.describe() + ")");
            }
            return stack;
        }
        if (type == Color.class) {
            return data == null ? Color.WHITE : color(data);
        }
        if (typeName.equals("org.bukkit.Particle$DustOptions")) {
            return dustOptions(data == null ? Color.RED : color(data), 1.0f);
        }
        if (type == Float.class) {
            // 1.21.9+: DRAGON_BREATH takes a power (vanilla default 1.0); it had no data on 1.12.2-1.21.4
            return data == null ? 1.0f : power(particle, data);
        }
        if (typeName.equals("org.bukkit.Particle$Spell")) {
            // 1.21.9+: EFFECT / INSTANT_EFFECT (1.12 SPELL / SPELL_INSTANT) take a colour and power; they had no data
            // on 1.12.2-1.21.4. Vanilla's defaults are white and 1.0, which is what they drew before. Show data is the
            // colour, read like every other colour (so an int is 0xRRGGBB, not a power); the power stays 1.0.
            return spell(type, data == null ? Color.WHITE : particleColor(particle, data), 1.0f);
        }
        if (data == null) {
            throw new IllegalArgumentException("particle " + particle + " needs data of type " + type.getSimpleName());
        }
        return data; // let Bukkit report the mismatch
    }

    /** A float power for a particle such as 26.x {@code DRAGON_BREATH}: a number or a numeric string. */
    private static float power(Particle particle, Object data) {
        if (data instanceof Number) {
            return ((Number) data).floatValue();
        }
        if (data instanceof CharSequence) {
            try {
                return Float.parseFloat(data.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through to the error below
            }
        }
        throw new IllegalArgumentException("particle " + particle + " takes a number (its power, e.g. 1.0), got '"
                + data + "'");
    }

    /** {@link #color(Object)}, with the particle named in the error. */
    private static Color particleColor(Particle particle, Object data) {
        try {
            return color(data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("particle " + particle + ": " + e.getMessage(), e);
        }
    }

    private static void spawnRaw(Object target, Particle particle, double x, double y, double z, int count,
                                 double dx, double dy, double dz, double extra, Object data) {
        if (target instanceof Location) {
            Location l = (Location) target;
            target = l.getWorld();
        }
        if (target instanceof Player) {
            ((Player) target).spawnParticle(particle, x, y, z, count, dx, dy, dz, extra, data);
        } else if (target instanceof World) {
            ((World) target).spawnParticle(particle, x, y, z, count, dx, dy, dz, extra, data);
        } else {
            throw new IllegalArgumentException("particle target must be a World or a Player, got "
                    + (target == null ? "null" : target.getClass().getName()));
        }
    }

    /**
     * Parses a show colour: an {@code int} {@code 0xRRGGBB}; a string {@code "#RRGGBB"}, {@code "RRGGBB"} or a
     * Bukkit colour name ({@code "aqua"}); a list {@code [r, g, b]} or map {@code [r: , g: , b: ]} of 0-1
     * fractions, e.g. {@code [1.0, 0.5, 0]} (the scale 1.12's coloured particle offsets use); or an
     * {@link org.bukkit.Color}. Lists and maps take one scale only: a channel outside 0-1 (e.g. {@code [255, 0, 0]})
     * throws instead of being guessed at, so {@code [1, 0, 0]} and {@code [1.0, 0, 0]} are both red. For 0-255
     * values use hex: {@code 0xFF0000} or {@code "#FF0000"}.
     */
    public static Color color(Object spec) {
        if (spec instanceof Color) {
            return (Color) spec;
        }
        if (spec instanceof Number && !isFractional((Number) spec)) {
            return Color.fromRGB(((Number) spec).intValue() & 0xFFFFFF);
        }
        if (spec instanceof CharSequence) {
            String s = spec.toString().trim();
            String hex = s.startsWith("#") ? s.substring(1) : s.toLowerCase(Locale.ROOT).startsWith("0x") ? s.substring(2) : s;
            if (hex.matches("[0-9a-fA-F]{6}")) {
                return Color.fromRGB(Integer.parseInt(hex, 16));
            }
            try {
                Object c = Color.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
                if (c instanceof Color) {
                    return (Color) c;
                }
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
            throw new IllegalArgumentException("Unknown colour '" + s + "' (use 0xRRGGBB, \"#RRGGBB\" or a name like \"aqua\")");
        }
        Object r = null, g = null, b = null;
        if (spec instanceof java.util.List && ((java.util.List<?>) spec).size() >= 3) {
            java.util.List<?> l = (java.util.List<?>) spec;
            r = l.get(0);
            g = l.get(1);
            b = l.get(2);
        } else if (spec instanceof Object[] && ((Object[]) spec).length >= 3) {
            Object[] a = (Object[]) spec;
            r = a[0];
            g = a[1];
            b = a[2];
        } else if (spec instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) spec;
            r = m.containsKey("r") ? m.get("r") : m.get("red");
            g = m.containsKey("g") ? m.get("g") : m.get("green");
            b = m.containsKey("b") ? m.get("b") : m.get("blue");
        }
        if (r instanceof Number && g instanceof Number && b instanceof Number) {
            double rd = ((Number) r).doubleValue(), gd = ((Number) g).doubleValue(), bd = ((Number) b).doubleValue();
            if (!isFraction(rd) || !isFraction(gd) || !isFraction(bd)) {
                throw new IllegalArgumentException("Colour " + spec + " is out of range: [r, g, b] and [r: , g: , b: ]"
                        + " take 0-1 fractions, e.g. [1.0, 0.5, 0]; for 0-255 values use 0xRRGGBB or \"#RRGGBB\"");
            }
            return fractionsToColor(rd, gd, bd);
        }
        throw new IllegalArgumentException("Can't read a colour from " + spec
                + " (use 0xRRGGBB, \"#RRGGBB\", [r, g, b] or [r: , g: , b: ])");
    }

    private static boolean isFraction(double v) {
        return v >= 0 && v <= 1;
    }

    static boolean isFractional(Number n) {
        return n instanceof Double || n instanceof Float || n instanceof java.math.BigDecimal;
    }

    static Color fractionsToColor(double r, double g, double b) {
        return Color.fromRGB(clamp255((int) Math.round(r * 255)), clamp255((int) Math.round(g * 255)),
                clamp255((int) Math.round(b * 255)));
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
