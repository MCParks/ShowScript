package us.mcparks.showscript.compat;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Item helpers for Groovy shows whose API differs between 1.12.2 and 1.21: custom-texture player heads
 * ({@code customSkull}, {@code skullTexture}, {@code isPlayerHead}) and enchantments by name
 * ({@code enchantment}).
 *
 * <p>Heads use Paper's {@code PlayerProfile} API ({@code Bukkit.createProfile}, {@code SkullMeta.setPlayerProfile}),
 * which exists on Paper 1.12.2 and 1.21. It's called reflectively because ShowsRebuild compiles against the Spigot
 * 1.12 API. Without it (plain Spigot 1.12) the old authlib reflection on {@code CraftMetaSkull.profile} is used.
 * Reading a skull <em>block</em>'s profile has no API on 1.12, so that path reads {@code CraftSkull.profile}.
 */
public final class ItemCompat {

    private ItemCompat() {
    }

    // ---------------------------------------------------------------- items

    /**
     * An item stack from a 1.12-style item description, the {@code item(...)} show helper. {@code spec} is anything
     * {@link MaterialSpec#material(Object)} takes; {@code damage} is the 1.12 damage value, which meant two
     * different things in 1.12 and is translated accordingly on 1.13+:
     * <ul>
     * <li>for an item with durability (tools, armour, {@code DIAMOND_HOE:5}) it stays the damage, so damage-based
     * resource-pack models keep working;</li>
     * <li>for anything else it was the variant ({@code WOOL} 14 = red wool, {@code INK_SACK} 4 = lapis,
     * {@code SKULL_ITEM} 3 = player head), so on 1.13+ it picks the flattened material ({@code RED_WOOL}, ...).</li>
     * </ul>
     * A data value in the spec ({@code "WOOL:14"}) is used when {@code damage} is 0. Returns null if the material
     * is unknown on this server.
     */
    @SuppressWarnings("deprecation")
    public static ItemStack item(Object spec, int amount, int damage) {
        if (spec == null) {
            return null;
        }
        MaterialSpec.Parsed p = spec instanceof Material || spec instanceof Number ? null : MaterialSpec.parse(spec.toString());
        int value = damage != 0 || p == null || !p.hasData ? damage : p.data;
        if (ServerVersion.LEGACY) {
            Material m = MaterialSpec.material(spec);
            return m == null ? null : new ItemStack(m, amount, (short) value);
        }
        Material legacy;
        if (spec instanceof Material) {
            legacy = ModernApi.isLegacy((Material) spec) ? (Material) spec : null;
        } else if (spec instanceof Number) {
            legacy = ModernApi.legacyMaterialById(((Number) spec).intValue());
        } else {
            legacy = MaterialSpec.legacyFor(p);
        }
        Material modern;
        if (legacy == null) {
            modern = spec instanceof Material ? (Material) spec : p == null ? null : MaterialSpec.modernByName(p.name);
            if (modern == null) {
                return null;
            }
        } else if (legacy.getMaxDurability() > 0) {
            modern = ModernApi.materialFromLegacy(legacy, (byte) 0);
        } else {
            modern = ModernApi.materialFromLegacy(legacy, (byte) value);
            value = 0;
        }
        if (modern == null || modern == Material.AIR && (legacy == null || legacy.getId() != 0)) {
            return null;
        }
        ItemStack stack = new ItemStack(modern, amount);
        if (value != 0 && modern.getMaxDurability() > 0) {
            stack.setDurability((short) value); // Damageable damage on 1.13+
        }
        return stack;
    }

    // ---------------------------------------------------------------- heads

    /** A new player-head item stack (1.12 {@code SKULL_ITEM:3}, 1.21 {@code PLAYER_HEAD}). */
    public static ItemStack playerHead() {
        if (ServerVersion.LEGACY) {
            return new ItemStack(Material.valueOf("SKULL_ITEM"), 1, (short) 3);
        }
        return new ItemStack(Material.valueOf("PLAYER_HEAD"));
    }

    /** True for a player-head item (1.12 {@code SKULL_ITEM} with damage 3, 1.21 {@code PLAYER_HEAD}). */
    @SuppressWarnings("deprecation")
    public static boolean isPlayerHead(ItemStack item) {
        if (item == null) {
            return false;
        }
        String type = item.getType().name();
        if (ServerVersion.LEGACY) {
            return type.equals("SKULL_ITEM") && item.getDurability() == 3;
        }
        return type.equals("PLAYER_HEAD");
    }

    /**
     * Normalises a texture to the base64 {@code textures} property value. Accepts the base64 value itself (what
     * heads' NBT stores as {@code Value:"..."}), a {@code http(s)://textures.minecraft.net/texture/<hash>} URL, or
     * the bare 64-character hash.
     */
    public static String textureValue(String texture) {
        if (texture == null || texture.trim().isEmpty()) {
            throw new IllegalArgumentException("texture is empty");
        }
        String t = texture.trim();
        String url = null;
        if (t.startsWith("http://") || t.startsWith("https://")) {
            url = t;
        } else if (t.matches("[0-9a-fA-F]{50,70}")) {
            url = "http://textures.minecraft.net/texture/" + t;
        }
        if (url == null) {
            return t;
        }
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A player head with a custom skin texture. {@code uuid} may be null: a stable UUID is derived from the
     * texture, so the same texture always gives stackable, identical heads.
     */
    public static ItemStack customSkull(String uuid, String texture) {
        String value = textureValue(texture);
        UUID id = uuid != null && !uuid.trim().isEmpty() ? UUID.fromString(uuid.trim())
                : UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        ItemStack head = playerHead();
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        applyTexture(meta, id, value);
        head.setItemMeta(meta);
        return head;
    }

    private static void applyTexture(SkullMeta meta, UUID id, String value) {
        Reflect r = Reflect.get();
        if (r.paperProfiles) {
            try {
                Object profile = r.createProfile.invoke(null, id, null);
                Object property = r.profilePropertyCtor.newInstance("textures", value);
                r.setProperty.invoke(profile, property);
                r.setPlayerProfile.invoke(meta, profile);
                return;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("could not set a head texture through Paper's PlayerProfile API", e);
            }
        }
        // Plain Spigot 1.12: authlib GameProfile in CraftMetaSkull.profile (what the shows used to do themselves)
        try {
            Class<?> gp = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> prop = Class.forName("com.mojang.authlib.properties.Property");
            Object profile = gp.getConstructor(UUID.class, String.class).newInstance(id, null);
            Object properties = gp.getMethod("getProperties").invoke(profile);
            Method put = properties.getClass().getMethod("put", Object.class, Object.class);
            put.invoke(properties, "textures", prop.getConstructor(String.class, String.class).newInstance("textures", value));
            Field f = findField(meta.getClass(), "profile");
            f.setAccessible(true);
            f.set(meta, profile);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not set a head texture (no Paper profile API, authlib fallback failed)", e);
        }
    }

    /**
     * The skin of a player head: an item stack, a placed head block (or its state), or null. Returns a map with
     * {@code uuid}, {@code name} (may be null) and {@code texture} (the base64 value), or null if it isn't a player
     * head or has no texture.
     */
    public static Map<String, String> skullTexture(Object o) {
        if (o instanceof ItemStack) {
            ItemStack item = (ItemStack) o;
            if (!isPlayerHead(item) || !item.hasItemMeta()) {
                return null;
            }
            ItemMeta meta = item.getItemMeta();
            Reflect r = Reflect.get();
            if (r.paperProfiles) {
                try {
                    return fromPaperProfile(r.getPlayerProfile.invoke(meta));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("could not read a head's PlayerProfile", e);
                }
            }
            return fromGameProfile(readField(meta, "profile"));
        }
        BlockState state = o instanceof Block ? ((Block) o).getState() : o instanceof BlockState ? (BlockState) o : null;
        if (state == null || !(state instanceof org.bukkit.block.Skull)) {
            return null;
        }
        if (!ServerVersion.LEGACY) {
            try {
                Method m = state.getClass().getMethod("getPlayerProfile");
                return fromPaperProfile(m.invoke(state));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("could not read a head block's PlayerProfile", e);
            }
        }
        return fromGameProfile(readField(state, "profile")); // CraftSkull.profile (no API on 1.12)
    }

    private static Map<String, String> fromPaperProfile(Object profile) throws ReflectiveOperationException {
        if (profile == null) {
            return null;
        }
        Class<?> c = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
        Object id = c.getMethod("getId").invoke(profile);
        Object name = c.getMethod("getName").invoke(profile);
        Collection<?> props = (Collection<?>) c.getMethod("getProperties").invoke(profile);
        Class<?> pp = Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
        for (Object p : props) {
            if ("textures".equals(pp.getMethod("getName").invoke(p))) {
                return result(id, name, (String) pp.getMethod("getValue").invoke(p));
            }
        }
        return null;
    }

    private static Map<String, String> fromGameProfile(Object profile) {
        if (profile == null) {
            return null;
        }
        try {
            Object id = profile.getClass().getMethod("getId").invoke(profile);
            Object name = profile.getClass().getMethod("getName").invoke(profile);
            Object properties = profile.getClass().getMethod("getProperties").invoke(profile);
            Collection<?> textures = (Collection<?>) properties.getClass().getMethod("get", Object.class).invoke(properties, "textures");
            for (Object p : textures) {
                Method value;
                try {
                    value = p.getClass().getMethod("getValue"); // authlib 1.x
                } catch (NoSuchMethodException e) {
                    value = p.getClass().getMethod("value");    // authlib 6 (record)
                }
                return result(id, name, (String) value.invoke(p));
            }
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read a GameProfile", e);
        }
    }

    private static Map<String, String> result(Object id, Object name, String texture) {
        if (texture == null) {
            return null;
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("uuid", id == null ? null : id.toString());
        m.put("name", name == null ? null : name.toString());
        m.put("texture", texture);
        return m;
    }

    private static Object readField(Object o, String name) {
        try {
            Field f = findField(o.getClass(), name);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no field " + name + " on " + o.getClass().getName(), e);
        }
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Reflective handles for Paper's profile API, looked up once. */
    private static final class Reflect {
        private static volatile Reflect instance;

        final boolean paperProfiles;
        final Method createProfile;          // Bukkit.createProfile(UUID, String)
        final java.lang.reflect.Constructor<?> profilePropertyCtor;
        final Method setProperty;            // PlayerProfile.setProperty(ProfileProperty)
        final Method setPlayerProfile;       // SkullMeta.setPlayerProfile(PlayerProfile)
        final Method getPlayerProfile;       // SkullMeta.getPlayerProfile()

        private Reflect() {
            Method cp = null, sp = null, spp = null, gpp = null;
            java.lang.reflect.Constructor<?> ctor = null;
            boolean ok;
            try {
                Class<?> profile = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
                Class<?> property = Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
                cp = Bukkit.class.getMethod("createProfile", UUID.class, String.class);
                ctor = property.getConstructor(String.class, String.class);
                sp = profile.getMethod("setProperty", property);
                spp = SkullMeta.class.getMethod("setPlayerProfile", profile);
                gpp = SkullMeta.class.getMethod("getPlayerProfile");
                ok = true;
            } catch (ReflectiveOperationException e) {
                ok = false;
            }
            paperProfiles = ok;
            createProfile = cp;
            profilePropertyCtor = ctor;
            setProperty = sp;
            setPlayerProfile = spp;
            getPlayerProfile = gpp;
        }

        static Reflect get() {
            Reflect r = instance;
            if (r == null) {
                r = new Reflect();
                instance = r;
            }
            return r;
        }
    }

    // ---------------------------------------------------------------- enchantments

    /** {1.12 Bukkit name, Minecraft key}. The 1.21 Bukkit names equal the keys (upper case). */
    private static final String[][] ENCHANTMENTS = {
            {"PROTECTION_ENVIRONMENTAL", "protection"},
            {"PROTECTION_FIRE", "fire_protection"},
            {"PROTECTION_FALL", "feather_falling"},
            {"PROTECTION_EXPLOSIONS", "blast_protection"},
            {"PROTECTION_PROJECTILE", "projectile_protection"},
            {"OXYGEN", "respiration"},
            {"WATER_WORKER", "aqua_affinity"},
            {"THORNS", "thorns"},
            {"DEPTH_STRIDER", "depth_strider"},
            {"FROST_WALKER", "frost_walker"},
            {"BINDING_CURSE", "binding_curse"},
            {"DAMAGE_ALL", "sharpness"},
            {"DAMAGE_UNDEAD", "smite"},
            {"DAMAGE_ARTHROPODS", "bane_of_arthropods"},
            {"KNOCKBACK", "knockback"},
            {"FIRE_ASPECT", "fire_aspect"},
            {"LOOT_BONUS_MOBS", "looting"},
            {"SWEEPING_EDGE", "sweeping_edge"},
            {"DIG_SPEED", "efficiency"},
            {"SILK_TOUCH", "silk_touch"},
            {"DURABILITY", "unbreaking"},
            {"LOOT_BONUS_BLOCKS", "fortune"},
            {"ARROW_DAMAGE", "power"},
            {"ARROW_KNOCKBACK", "punch"},
            {"ARROW_FIRE", "flame"},
            {"ARROW_INFINITE", "infinity"},
            {"LUCK", "luck_of_the_sea"},
            {"LURE", "lure"},
            {"MENDING", "mending"},
            {"VANISHING_CURSE", "vanishing_curse"},
    };

    private static final Map<String, String[]> ENCHANTMENT_ROWS = new HashMap<>();

    static {
        for (String[] row : ENCHANTMENTS) {
            ENCHANTMENT_ROWS.put(normalize(row[0]), row);
            ENCHANTMENT_ROWS.putIfAbsent(normalize(row[1]), row);
        }
    }

    private static String normalize(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("minecraft:")) {
            n = n.substring("minecraft:".length());
        }
        return n.replace("_", "").replace(" ", "");
    }

    /** The 1.12 Bukkit name for an enchantment name in either form (for tests). */
    static String legacyEnchantmentName(String name) {
        String[] row = ENCHANTMENT_ROWS.get(normalize(name));
        return row == null ? name.trim().toUpperCase(Locale.ROOT) : row[0];
    }

    /** The Minecraft key for an enchantment name in either form (for tests). */
    static String enchantmentKey(String name) {
        String[] row = ENCHANTMENT_ROWS.get(normalize(name));
        return row == null ? name.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "") : row[1];
    }

    /**
     * The running server's enchantment for a 1.12 Bukkit name ({@code DURABILITY}), a 1.21 name / Minecraft key
     * ({@code unbreaking}), or an {@link Enchantment}.
     *
     * @throws IllegalArgumentException if there's no such enchantment
     */
    @SuppressWarnings("deprecation")
    public static Enchantment enchantment(Object spec) {
        if (spec instanceof Enchantment) {
            return (Enchantment) spec;
        }
        String name = String.valueOf(spec);
        Enchantment e;
        if (ServerVersion.LEGACY) {
            e = Enchantment.getByName(legacyEnchantmentName(name));
        } else {
            try {
                Method byKey = Enchantment.class.getMethod("getByKey", NamespacedKey.class);
                e = (Enchantment) byKey.invoke(null, NamespacedKey.minecraft(enchantmentKey(name)));
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Enchantment.getByKey is not available", ex);
            }
        }
        if (e == null) {
            throw new IllegalArgumentException("Unknown enchantment '" + name + "' on this server (" + ServerVersion.describe() + ")");
        }
        return e;
    }
}
