package us.mcparks.showscript.compat;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Version-agnostic material lookup for Groovy shows (the {@code material(...)} helper). Show content written for
 * 1.12 names materials by 1.12 name, numeric id or {@code id:data}; on 1.21 those resolve through Bukkit's own
 * legacy tables (the ones that upgrade 1.12 worlds), so {@code "35:14"} and {@code "WOOL:14"} give
 * {@code RED_WOOL} and {@code "SKULL_ITEM:3"} gives {@code PLAYER_HEAD}. Modern names work on 1.21 and, where the
 * name already existed, on 1.12 too.
 *
 * <p><b>A name that existed in 1.12 always means the 1.12 thing, on both versions.</b> Some 1.12 names were reused for
 * something else in 1.13 ({@code BRICK} and {@code NETHER_BRICK} were the blocks and are now the items,
 * {@code MELON} was the slice and is now the block, {@code CARROT}/{@code POTATO} were the crop blocks and are now
 * the items, {@code GRASS} was the grass block). Without a prefix these resolve to their 1.12 meaning on 1.21 as
 * well, so a show behaves the same everywhere. To mean the modern thing on 1.21, write the namespaced id
 * ({@code "minecraft:brick"}); on 1.12 the prefix is ignored. show-check warns about these names
 * ({@code ambiguous-material}); the plan to read names the modern way once the 1.12.2 servers are gone is in
 * technicians/shows {@code docs/version-agnostic-helpers.md}.
 */
public final class MaterialSpec {

    private MaterialSpec() {
    }

    /** A parsed {@code name[:data]} / {@code id[:data]} spec. */
    static final class Parsed {
        final String name;   // upper-case name, or null for a numeric id
        final int id;        // -1 unless numeric
        final byte data;
        final boolean hasData;
        final boolean namespaced; // written as minecraft:<name>: the modern id is meant

        Parsed(String name, int id, byte data, boolean hasData) {
            this(name, id, data, hasData, false);
        }

        Parsed(String name, int id, byte data, boolean hasData, boolean namespaced) {
            this.name = name;
            this.id = id;
            this.data = data;
            this.hasData = hasData;
            this.namespaced = namespaced;
        }
    }

    static Parsed parse(String spec) {
        String s = spec.trim();
        boolean namespaced = s.toLowerCase(Locale.ROOT).startsWith("minecraft:");
        if (namespaced) {
            s = s.substring("minecraft:".length());
        }
        byte data = 0;
        boolean hasData = false;
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.substring(colon + 1).matches("\\d{1,2}")) {
            data = (byte) Integer.parseInt(s.substring(colon + 1));
            hasData = true;
            s = s.substring(0, colon);
        }
        if (s.matches("\\d+")) {
            return new Parsed(null, Integer.parseInt(s), data, hasData);
        }
        return new Parsed(s.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'), -1, data, hasData, namespaced);
    }

    /**
     * The running server's material for a {@link Material}, a numeric 1.12 id, or a string {@code "name"},
     * {@code "name:data"}, {@code "id"} or {@code "id:data"}. Returns null if there's no such material (like
     * {@code Material.getMaterial}).
     */
    @SuppressWarnings("deprecation")
    public static Material material(Object spec) {
        if (spec == null) {
            return null;
        }
        if (spec instanceof Material) {
            Material m = (Material) spec;
            return !ServerVersion.LEGACY && ModernApi.isLegacy(m) ? ModernApi.materialFromLegacy(m) : m;
        }
        Parsed p = spec instanceof Number ? new Parsed(null, ((Number) spec).intValue(), (byte) 0, false)
                : parse(spec.toString());
        if (ServerVersion.LEGACY) {
            return p.name == null ? Material.getMaterial(p.id) : Material.matchMaterial(p.name);
        }
        Material legacy = legacyFor(p);
        if (legacy == null) {
            return p.name == null ? null : modernByName(p.name);
        }
        Material modern = ModernApi.materialFromLegacy(legacy, p.data);
        return modern == null || modern == Material.AIR && legacy.getId() != 0 ? null : modern;
    }

    /**
     * 1.13+: the LEGACY_* material a spec means in 1.12 terms, or null if it should be read as a modern name: an
     * unprefixed name that existed in 1.12 (with or without data) or a numeric id gives its LEGACY_* constant; a
     * modern-only name or a {@code minecraft:} id without data gives null.
     */
    static Material legacyFor(Parsed p) {
        if (p.name == null) {
            return ModernApi.legacyMaterialById(p.id);
        }
        if (p.namespaced && !p.hasData) {
            Material modern = modernByName(p.name);
            if (modern != null) {
                return null;
            }
        }
        return ModernApi.legacyMaterial(p.name);
    }

    /** 1.13+: a non-legacy material by name, or null. */
    static Material modernByName(String name) {
        Material m = Material.matchMaterial(name);
        return m == null || ModernApi.isLegacy(m) ? null : m;
    }
}
