package us.mcparks.showscript.compat;

import org.bukkit.Material;
import org.bukkit.material.MaterialData;

/**
 * Block data for block particles ({@code BLOCK_DUST}/{@code BLOCK_CRACK}/{@code FALLING_DUST} on 1.12,
 * {@code BLOCK}/{@code FALLING_DUST}/{@code BLOCK_MARKER} on 1.21) from a version-agnostic spec: the
 * {@code blockData(...)} show helper. 1.12 wants a {@link MaterialData}; 1.13+ wants a {@code BlockData}.
 */
public final class BlockSpec {

    private BlockSpec() {
    }

    /**
     * The running server's particle data for a block spec: {@code "95:1"} (1.12 id:data), {@code "STAINED_GLASS:1"}
     * (1.12 name:data), {@code "water"} / {@code "orange_stained_glass"} (a name; modern-only names work on 1.21
     * only), a numeric id, a {@link Material}, a {@link MaterialData} or a {@code BlockData}.
     *
     * @throws IllegalArgumentException if the spec names no block on this server
     */
    @SuppressWarnings("deprecation")
    public static Object particleData(Object spec) {
        if (spec == null) {
            throw new IllegalArgumentException("block spec is null");
        }
        if (ServerVersion.LEGACY) {
            if (spec instanceof MaterialData) {
                return spec;
            }
            if (spec instanceof Material) {
                return new MaterialData((Material) spec);
            }
            MaterialSpec.Parsed p = spec instanceof Number
                    ? new MaterialSpec.Parsed(null, ((Number) spec).intValue(), (byte) 0, false)
                    : MaterialSpec.parse(spec.toString());
            Material m = p.name == null ? Material.getMaterial(p.id) : Material.matchMaterial(p.name);
            if (m == null) {
                throw new IllegalArgumentException("Unknown block '" + spec + "' on this server (" + ServerVersion.describe() + ")");
            }
            return new MaterialData(m, p.data);
        }

        if (ModernApi.BLOCK_DATA.isInstance(spec)) {
            return spec;
        }
        if (spec instanceof MaterialData) {
            MaterialData md = (MaterialData) spec;
            Material m = md.getItemType();
            return ModernApi.isLegacy(m) ? ModernApi.blockDataFromLegacy(m, md.getData()) : ModernApi.createBlockData(m);
        }
        if (spec instanceof Material) {
            Material m = (Material) spec;
            return ModernApi.isLegacy(m) ? ModernApi.blockDataFromLegacy(m, (byte) 0) : ModernApi.createBlockData(m);
        }
        MaterialSpec.Parsed p = spec instanceof Number
                ? new MaterialSpec.Parsed(null, ((Number) spec).intValue(), (byte) 0, false)
                : MaterialSpec.parse(spec.toString());
        Material legacy = MaterialSpec.legacyFor(p); // 1.12 names keep their 1.12 meaning (see MaterialSpec)
        if (legacy == null && p.name != null) {
            Material modern = MaterialSpec.modernByName(p.name);
            if (modern != null) {
                if (!ModernApi.isBlock(modern)) {
                    throw new IllegalArgumentException("'" + spec + "' is an item, not a block");
                }
                return ModernApi.createBlockData(modern);
            }
        }
        if (legacy == null) {
            throw new IllegalArgumentException("Unknown block '" + spec + "' on this server (" + ServerVersion.describe() + ")");
        }
        return ModernApi.blockDataFromLegacy(legacy, p.data);
    }
}
