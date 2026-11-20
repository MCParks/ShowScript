package us.mcparks.showscript.compat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.UnsafeValues;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 1.13+ (tested on Paper 1.21.4): numeric ids are gone, so translate the 1.12 id + data value through
 * Bukkit's own legacy tables ({@code UnsafeValues.fromLegacy(Material, byte)}, the same tables the server uses
 * to upgrade 1.12 worlds) and use the BlockData API. ShowsRebuild compiles against the 1.12 API, so the
 * 1.13+ methods are looked up reflectively once and called through method handles.
 */
final class ModernBlockCompat implements BlockCompat {
    private static final Logger LOG = Logger.getLogger("ShowsRebuild");

    private final Class<?> blockDataClass;
    private final MethodHandle fromLegacy;       // UnsafeValues.fromLegacy(Material, byte) -> BlockData
    private final MethodHandle setBlockData;     // Block.setBlockData(BlockData, boolean)
    private final MethodHandle spawnFalling;     // World.spawnFallingBlock(Location, BlockData)
    private final MethodHandle getBlockData;     // Block.getBlockData() -> BlockData
    private final MethodHandle getAsString;      // BlockData.getAsString() -> "minecraft:x[a=b,...]"
    private final MethodHandle getMaterial;      // BlockData.getMaterial() -> Material
    /** Reverse of fromLegacy: exact modern state string -> 1.12 id/data. Built on first use (only /rb create needs it). */
    private volatile Map<String, LegacyBlock> legacyByState;
    /** Same entries grouped by modern material, for states fromLegacy never produces (stairs shape, waterlogged, ...). */
    private volatile Map<Material, List<StateEntry>> legacyByMaterial;
    private final UnsafeValues unsafe;
    private final Map<Integer, Material> legacyById = new HashMap<>();
    private final Map<Integer, Object> blockDataCache = new ConcurrentHashMap<>();
    private final Set<Integer> warnedIds = ConcurrentHashMap.newKeySet();
    private final Set<Material> warnedUnmapped = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedApproximate = ConcurrentHashMap.newKeySet();

    ModernBlockCompat() throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        blockDataClass = Class.forName("org.bukkit.block.data.BlockData");
        unsafe = Bukkit.getUnsafe();
        fromLegacy = lookup.findVirtual(UnsafeValues.class, "fromLegacy",
                MethodType.methodType(blockDataClass, Material.class, byte.class));
        setBlockData = lookup.findVirtual(Block.class, "setBlockData",
                MethodType.methodType(void.class, blockDataClass, boolean.class));
        spawnFalling = lookup.findVirtual(World.class, "spawnFallingBlock",
                MethodType.methodType(FallingBlock.class, Location.class, blockDataClass));
        getBlockData = lookup.findVirtual(Block.class, "getBlockData", MethodType.methodType(blockDataClass));
        getAsString = lookup.findVirtual(blockDataClass, "getAsString", MethodType.methodType(String.class));
        getMaterial = lookup.findVirtual(blockDataClass, "getMaterial", MethodType.methodType(Material.class));

        MethodHandle isLegacy = lookup.findVirtual(Material.class, "isLegacy", MethodType.methodType(boolean.class));
        // Not Material.values(): for api-version >= 1.13 plugins the server rewrites that call to return only
        // modern materials. getEnumConstants() is reflection, so it isn't rewritten and includes LEGACY_*.
        for (Material material : Material.class.getEnumConstants()) {
            try {
                if ((boolean) isLegacy.invokeExact(material)) {
                    legacyById.putIfAbsent(legacyId(material), material);
                }
            } catch (Throwable t) {
                throw new IllegalStateException("could not index legacy material " + material, t);
            }
        }
        if (legacyById.isEmpty()) {
            throw new IllegalStateException("this server exposes no legacy materials; numeric block ids can't be translated");
        }
    }

    @SuppressWarnings("deprecation")
    private static int legacyId(Material legacyMaterial) {
        return legacyMaterial.getId();
    }

    /** BlockData for a 1.12 id + data, or null if the id doesn't exist. Cached: rebuilds repeat the same blocks. */
    Object blockData(int legacyId, byte rawData) {
        // 1.12 data values are 4 bits; mask once so the cache key and the lookup use the same value.
        byte data = (byte) (rawData & 0xF);
        int key = legacyId * 16 + data;
        Object cached = blockDataCache.get(key);
        if (cached != null) {
            return cached;
        }
        Material legacy = legacyById.get(legacyId);
        if (legacy == null) {
            if (warnedIds.add(legacyId)) {
                LOG.warning("[compat] unknown 1.12 block id " + legacyId + " (data " + data + "); skipping it");
            }
            return null;
        }
        try {
            Object blockData = fromLegacy.invoke(unsafe, legacy, data);
            blockDataCache.put(key, blockData);
            return blockData;
        } catch (Throwable t) {
            throw new IllegalStateException("fromLegacy(" + legacy + ", " + data + ") failed", t);
        }
    }

    @Override
    public void setLegacyBlock(Block block, int legacyId, byte data) {
        Object blockData = blockData(legacyId, data);
        if (blockData == null) {
            return;
        }
        try {
            setBlockData.invoke(block, blockData, true);
        } catch (Throwable t) {
            throw new IllegalStateException("setBlockData failed at " + block.getLocation(), t);
        }
    }

    /**
     * The 1.12 id/data whose translation ({@link #blockData}) gives this block's state, so /rb create followed by
     * /rb build restores the same block.
     *
     * <p>Neither Bukkit shortcut is right on its own: {@code UnsafeValues.toLegacy(Material)} maps only the
     * default state, and {@code BlockState.getData()} (CraftLegacy) picks the id from the block type too, so both
     * give e.g. 123 (unlit lamp) for a lit lamp, 126 (single slab) for a double slab, 61 for a lit furnace, 93 for a
     * powered repeater (measured on Paper 1.21.4). Instead this inverts the forward table the build path uses:
     * every legacy id x 16 data values through {@code fromLegacy}, indexed by resulting state.
     *
     * <p>Not used by {@code /rb create} on 1.13+ any more (it refuses there, tracker #56 decision A); kept for
     * diagnostics and tooling. Results say whether they are exact: {@link LegacyBlock#unmapped} for 1.13+-only
     * blocks, {@link LegacyBlock#approximate} for states 1.12 can't represent.
     */
    @Override
    public LegacyBlock getLegacy(Block block) {
        Object data;
        String state;
        Material modern;
        try {
            data = getBlockData.invoke(block);
            state = (String) getAsString.invoke(data);
            modern = (Material) getMaterial.invoke(data);
        } catch (Throwable t) {
            throw new IllegalStateException("could not read block state at " + block.getLocation(), t);
        }
        ensureReverseIndex();
        LegacyBlock exact = legacyByState.get(state);
        if (exact != null) {
            return exact;
        }
        // Not a state any 1.12 id/data produces (e.g. stairs with shape=outer_left, waterlogged=true, fence
        // connections): take the legacy entry of the same block type sharing the most properties, and mark the
        // result approximate. This is lossy: physics may recompute shape/connections on build, but it doesn't
        // restore e.g. waterlogged=true, so callers must report it.
        List<StateEntry> candidates = legacyByMaterial.get(modern);
        if (candidates != null && !candidates.isEmpty()) {
            Map<String, String> props = properties(state);
            StateEntry best = null;
            int bestScore = -1;
            for (StateEntry e : candidates) {
                int score = 0;
                for (Map.Entry<String, String> p : e.properties.entrySet()) {
                    if (p.getValue().equals(props.get(p.getKey()))) {
                        score++;
                    }
                }
                if (score > bestScore) {
                    best = e;
                    bestScore = score;
                }
            }
            String closest = best.state;
            if (warnedApproximate.add(state)) {
                LOG.warning("[compat] " + state + " has no exact 1.12 id/data; closest is " + best.legacy.id + ":"
                        + best.legacy.data + " (" + closest + "). First seen at " + block.getX() + "," + block.getY()
                        + "," + block.getZ());
            }
            return new LegacyBlock(best.legacy.id, best.legacy.data, false, true, state + " -> " + closest);
        }
        String name = modern.name();
        if (!name.equals("AIR") && !name.equals("CAVE_AIR") && !name.equals("VOID_AIR")) {
            if (warnedUnmapped.add(modern)) {
                LOG.warning("[compat] " + name + " has no 1.12 block id; rebuild files store it as air (0:0)."
                        + " First seen at " + block.getX() + "," + block.getY() + "," + block.getZ());
            }
            return new LegacyBlock(0, (byte) 0, true);
        }
        return AIR;
    }

    private static final LegacyBlock AIR = new LegacyBlock(0, (byte) 0, false);

    private void ensureReverseIndex() {
        if (legacyByState != null) {
            return;
        }
        synchronized (this) {
            if (legacyByState != null) {
                return;
            }
            long start = System.nanoTime();
            Map<String, LegacyBlock> byState = new HashMap<>();
            Map<Material, List<StateEntry>> byMaterial = new HashMap<>();
            List<Integer> ids = new ArrayList<>(legacyById.keySet());
            Collections.sort(ids); // lowest id/data wins when several map to one state, deterministically
            for (int id : ids) {
                if (id == 0) {
                    continue; // air
                }
                for (int d = 0; d < 16; d++) {
                    Object bd;
                    String str;
                    Material mat;
                    try {
                        bd = fromLegacy.invoke(unsafe, legacyById.get(id), (byte) d);
                        if (bd == null) {
                            continue;
                        }
                        str = (String) getAsString.invoke(bd);
                        mat = (Material) getMaterial.invoke(bd);
                    } catch (Throwable t) {
                        continue; // an id/data the server can't translate; not reachable from rebuild files either
                    }
                    if (mat.name().equals("AIR")) {
                        continue;
                    }
                    LegacyBlock lb = new LegacyBlock(id, (byte) d, false);
                    if (byState.putIfAbsent(str, lb) == null) {
                        byMaterial.computeIfAbsent(mat, k -> new ArrayList<>()).add(new StateEntry(str, lb));
                    }
                }
            }
            legacyByMaterial = byMaterial;
            legacyByState = byState;
            LOG.info("[compat] indexed " + byState.size() + " modern block states for writing 1.12 rebuild files in "
                    + (System.nanoTime() - start) / 1_000_000 + " ms");
        }
    }

    /** "minecraft:oak_stairs[facing=east,half=top]" -> {facing=east, half=top}. */
    static Map<String, String> properties(String state) {
        Map<String, String> props = new HashMap<>();
        int open = state.indexOf('[');
        if (open < 0 || !state.endsWith("]")) {
            return props;
        }
        for (String kv : state.substring(open + 1, state.length() - 1).split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                props.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return props;
    }

    private static final class StateEntry {
        final String state;
        final Map<String, String> properties;
        final LegacyBlock legacy;

        StateEntry(String state, LegacyBlock legacy) {
            this.state = state;
            this.properties = properties(state);
            this.legacy = legacy;
        }
    }

    @Override
    public FallingBlock spawnFallingBlock(Location location, int legacyId, byte data) {
        Object blockData = blockData(legacyId, data);
        if (blockData == null) {
            return null;
        }
        try {
            return (FallingBlock) spawnFalling.invoke(location.getWorld(), location, blockData);
        } catch (Throwable t) {
            throw new IllegalStateException("spawnFallingBlock failed at " + location, t);
        }
    }

    int legacyMaterialCount() {
        return legacyById.size();
    }
}
