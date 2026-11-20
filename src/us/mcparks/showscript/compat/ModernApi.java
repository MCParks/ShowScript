package us.mcparks.showscript.compat;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.UnsafeValues;
import org.bukkit.material.MaterialData;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * 1.13+ Bukkit methods that the show helpers need. ShowsRebuild compiles against the 1.12 API, so they're looked
 * up reflectively, once, on first use. Only touched when {@link ServerVersion#LEGACY} is false.
 */
final class ModernApi {
    static final Class<?> BLOCK_DATA;
    private static final MethodHandle IS_LEGACY;              // Material.isLegacy()
    private static final MethodHandle IS_BLOCK;               // Material.isBlock()
    private static final MethodHandle CREATE_BLOCK_DATA;      // Material.createBlockData()
    private static final MethodHandle FROM_LEGACY_BLOCK;      // UnsafeValues.fromLegacy(Material, byte) -> BlockData
    private static final MethodHandle FROM_LEGACY_DATA;       // UnsafeValues.fromLegacy(MaterialData) -> Material
    private static final MethodHandle FROM_LEGACY_MATERIAL;   // UnsafeValues.fromLegacy(Material) -> Material

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            BLOCK_DATA = Class.forName("org.bukkit.block.data.BlockData");
            IS_LEGACY = l.findVirtual(Material.class, "isLegacy", MethodType.methodType(boolean.class));
            IS_BLOCK = l.findVirtual(Material.class, "isBlock", MethodType.methodType(boolean.class));
            CREATE_BLOCK_DATA = l.findVirtual(Material.class, "createBlockData", MethodType.methodType(BLOCK_DATA));
            FROM_LEGACY_BLOCK = l.findVirtual(UnsafeValues.class, "fromLegacy",
                    MethodType.methodType(BLOCK_DATA, Material.class, byte.class));
            FROM_LEGACY_DATA = l.findVirtual(UnsafeValues.class, "fromLegacy",
                    MethodType.methodType(Material.class, MaterialData.class));
            FROM_LEGACY_MATERIAL = l.findVirtual(UnsafeValues.class, "fromLegacy",
                    MethodType.methodType(Material.class, Material.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ModernApi() {
    }

    static boolean isLegacy(Material m) {
        try {
            return (boolean) IS_LEGACY.invoke(m);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean isBlock(Material m) {
        try {
            return (boolean) IS_BLOCK.invoke(m);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The LEGACY_* material for a 1.12 name ({@code "STAINED_GLASS"}), or null. Not
     * {@code Material.getMaterial(name, true)}: that already converts to the modern material and drops the data value.
     */
    static Material legacyMaterial(String name) {
        try {
            return Material.valueOf(name.startsWith("LEGACY_") ? name : "LEGACY_" + name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Object createBlockData(Material m) {
        try {
            return CREATE_BLOCK_DATA.invoke(m);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static Object blockDataFromLegacy(Material legacy, byte data) {
        try {
            return FROM_LEGACY_BLOCK.invoke(Bukkit.getUnsafe(), legacy, data);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static Material materialFromLegacy(Material legacy, byte data) {
        try {
            return (Material) FROM_LEGACY_DATA.invoke(Bukkit.getUnsafe(), new MaterialData(legacy, data));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static Material materialFromLegacy(Material legacy) {
        try {
            return (Material) FROM_LEGACY_MATERIAL.invoke(Bukkit.getUnsafe(), legacy);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static volatile java.util.Map<Integer, Material> legacyById;

    /** The LEGACY_* material with this 1.12 numeric id, or null. */
    @SuppressWarnings("deprecation")
    static Material legacyMaterialById(int id) {
        java.util.Map<Integer, Material> map = legacyById;
        if (map == null) {
            map = new java.util.HashMap<>();
            for (Material m : Material.class.getEnumConstants()) { // not values(): see ModernBlockCompat
                if (isLegacy(m)) {
                    map.putIfAbsent(m.getId(), m);
                }
            }
            legacyById = map;
        }
        return map.get(id);
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException) {
            return (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        return new IllegalStateException(t);
    }
}
