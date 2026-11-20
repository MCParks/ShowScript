package us.mcparks.showcheck;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static us.mcparks.showcheck.ShowCheck.nameA;
import static us.mcparks.showcheck.ShowCheck.nameB;

/**
 * Turns the two worker runs into per-file findings and renders them.
 *
 * Rules (stable ids, used in JSON):
 * <ul>
 * <li>{@code missing} (error): a class, method, constructor, field, enum constant or property that resolves on
 *     side A doesn't exist on side B.</li>
 * <li>{@code compile} (error): compiles on A, not on B (e.g. unresolved import).</li>
 * <li>{@code stc} (error): a type-checking error that only appears on B.</li>
 * <li>{@code signature} (warning): the member exists on B, but no overload matches the call's parameter types.</li>
 * <li>{@code unknown-method} (error on a typed receiver, warning on an untyped one): no class on the classpath
 *     and no show declares a method of that name, so the call fails on every version (e.g. Kotlin's minByOrNull).</li>
 * <li>{@code legacy-api} (warning): still present on B but legacy pre-1.13 API that behaves differently
 *     (MaterialData, damage values).</li>
 * <li>{@code name-gone} (warning): a method name called on an untyped receiver exists on A's classpath but on no
 *     class of B's.</li>
 * <li>{@code unknown-constant} (warning): an enum constant looked up by string exists on neither side.</li>
 * <li>{@code ambiguous-material} (warning): a 1.12 material name that 1.13 reused for something else (MELON,
 *     GRASS, BRICK, ...), passed to a ShowScript helper (1.12 meaning everywhere) or to Bukkit's Material lookups
 *     (meaning differs by version).</li>
 * <li>{@code deprecated} (info, only listed with --deprecations): newly deprecated on B.</li>
 * <li>{@code broken-both} / {@code fails-on-a} (error): the show doesn't compile on A (already broken today).</li>
 * <li>{@code unresolved} (warning): a member couldn't be checked on one side because the class can't be linked
 *     there (a class it depends on is missing from that classpath). A gap in the inputs, not a confirmed removal.</li>
 * <li>{@code internal} (error/warning): show-check couldn't fully check the file (worker crash, type-checker crash,
 *     a crash while collecting the members a show uses, so its member checks are incomplete, or a sibling show the
 *     file needs doesn't compile, so the file is "incomplete" and not counted as compiling).</li>
 * </ul>
 */
final class Report {
    private static final Pattern UNRESOLVED_CLASS = Pattern.compile("unable to resolve class ([\\w.$]+)");
    private static final Map<String, String> KOTLIN = new HashMap<>();
    static {
        KOTLIN.put("minByOrNull", "Groovy: min { ... } (returns null for an empty collection)");
        KOTLIN.put("maxByOrNull", "Groovy: max { ... }");
        KOTLIN.put("firstOrNull", "Groovy: find { ... } or list ? list[0] : null");
        KOTLIN.put("lastOrNull", "Groovy: list ? list[-1] : null");
        KOTLIN.put("mapNotNull", "Groovy: collect { ... }.findAll { it != null }");
        KOTLIN.put("forEachIndexed", "Groovy: eachWithIndex { item, i -> ... }");
        KOTLIN.put("sumOf", "Groovy: sum { ... }");
        KOTLIN.put("joinToString", "Groovy: join(\", \")");
    }

    private final Map<String, List<ShowCheck.Loc>> locs;
    private final Map<String, Map<String, Object>> ra, rb;
    private final Map<String, String[]> ma, mb;
    private final Map<String, String> renames;
    private final boolean deprecations;
    final Map<String, List<ShowCheck.Finding>> findings = new TreeMap<>();
    final Map<String, Integer> deprecatedCount = new TreeMap<>();
    int compilesA, compilesB, stcCleanA, incompleteA, incompleteB;
    /** shows whose member facts are incomplete on that side because the fact collector crashed ({@code factsError}) */
    int factsIncompleteA, factsIncompleteB;
    /** member checks used by some show that couldn't be done, per side (status unresolved) */
    final Set<String> unresolvedA = new TreeSet<>(), unresolvedB = new TreeSet<>();

    Report(Map<String, List<ShowCheck.Loc>> locs, Map<String, Map<String, Object>> ra, Map<String, Map<String, Object>> rb,
           Map<String, String[]> ma, Map<String, String[]> mb, Map<String, String> renames, boolean deprecations) {
        this.locs = locs; this.ra = ra; this.rb = rb; this.ma = ma; this.mb = mb; this.renames = renames; this.deprecations = deprecations;
    }

    @SuppressWarnings("unchecked")
    void build() {
        for (String id : locs.keySet()) {
            Map<String, Object> a = ra.getOrDefault(id, Collections.emptyMap()), b = rb.getOrDefault(id, Collections.emptyMap());
            List<ShowCheck.Finding> fs = new ArrayList<>();
            boolean okA = Boolean.TRUE.equals(a.get("compiles")), okB = Boolean.TRUE.equals(b.get("compiles"));
            // partial: compiled only up to the phase where a sibling show it needs failed. Not counted as compiling,
            // but its members were still collected, so it's checked as far as possible and flagged `internal`.
            boolean partialA = Boolean.TRUE.equals(a.get("partial")), partialB = Boolean.TRUE.equals(b.get("partial"));
            boolean checkedA = okA || partialA, checkedB = okB || partialB;
            if (okA) compilesA++;
            if (okB) compilesB++;
            if (partialA) incompleteA++;
            if (partialB) incompleteB++;
            if (Boolean.TRUE.equals(a.get("stcClean"))) stcCleanA++;
            // The worker's AST walk crashed part-way: the members it collected (and so every member check below) may
            // be incomplete. Say so on either side, whether or not the show compiles, instead of reporting a clean file.
            for (Map<String, Object> side : Arrays.asList(a, b)) {
                if (side.get("factsError") == null) continue;
                String nm = side == a ? nameA : nameB;
                if (side == a) factsIncompleteA++; else factsIncompleteB++;
                fs.add(new ShowCheck.Finding(-1, "warning", "internal", "member analysis incomplete on " + nm
                        + ": show-check crashed while collecting the classes, methods and fields this show uses ("
                        + side.get("factsError") + "); uses after that point were not checked, so a clean result for them means nothing", null));
            }
            List<Map<String, Object>> ea = errs(a, "errors"), eb = errs(b, "errors");
            if (!checkedA) {
                int n = 0;
                for (Map<String, Object> e : ea) {
                    if (n++ == 5) break;
                    fs.add(new ShowCheck.Finding(line(e), "error", okB ? "fails-on-a" : "broken-both",
                            (okB ? "doesn't compile on " + nameA + " (compiles on " + nameB + "): " : "doesn't compile on " + nameA + " or " + nameB + ": ") + first(e),
                            null));
                }
                if (ea.isEmpty()) fs.add(new ShowCheck.Finding(-1, "error", "broken-both", "doesn't compile: " + a.getOrDefault("internal", a.getOrDefault("internalError", "?")), null));
            } else {
                Set<String> seen = new HashSet<>();
                for (Map<String, Object> e : ea) seen.add(line(e) + "|" + first(e));
                if (!checkedB && eb.isEmpty()) // the 1.21.4 worker crashed on this show (no compiler errors): don't drop it silently
                    fs.add(new ShowCheck.Finding(-1, "error", "internal", "show-check couldn't compile this show on " + nameB + " (internal error, not a compiler error): "
                            + b.getOrDefault("internal", b.getOrDefault("internalError", "no result from the worker")), null));
                if (!checkedB) for (Map<String, Object> e : eb) {
                    if (seen.contains(line(e) + "|" + first(e))) continue;
                    String msg = first(e), sug = null;
                    Matcher m = UNRESOLVED_CLASS.matcher(msg);
                    if (m.find()) sug = classAdvice(m.group(1));
                    fs.add(new ShowCheck.Finding(line(e), "error", "compile", "doesn't compile on " + nameB + ": " + msg, sug));
                }
                memberFindings((Map<String, List<Object>>) a.getOrDefault("members", Collections.emptyMap()), fs);
                // the tool itself couldn't check part of this show: say so instead of reporting a clean result
                for (Map<String, Object> side : Arrays.asList(a, b)) {
                    String nm = side == a ? nameA : nameB;
                    if (side.get("stcInternal") != null)
                        fs.add(new ShowCheck.Finding(-1, "warning", "internal", "the type-checking pass crashed on " + nm + " (" + side.get("stcInternal")
                                + "); calls after that point were only checked by name", null));
                    if (Boolean.TRUE.equals(side.get("partial"))) {
                        List<Map<String, Object>> se = errs(side, "siblingErrors");
                        String why = "";
                        if (!se.isEmpty()) {
                            List<ShowCheck.Loc> sl = locs.get(String.valueOf(se.get(0).get("id")));
                            String where = sl != null && !sl.isEmpty() ? sl.get(0).display() : String.valueOf(se.get(0).get("file"));
                            why = ": `" + where + "` line " + line(se.get(0)) + ": " + first(se.get(0));
                        }
                        fs.add(new ShowCheck.Finding(-1, "warning", "internal", "incomplete on " + nm + ": a sibling show this file needs doesn't compile"
                                + why + ". This file was only checked up to that compiler phase and isn't counted as compiling", null));
                    } else if (Boolean.TRUE.equals(side.get("stcPartial"))) {
                        fs.add(new ShowCheck.Finding(-1, "warning", "internal", "the type-checking pass on " + nm
                                + " stopped early because a sibling show failed it; calls after that point were only checked by name", null));
                    }
                }
                // type-checking errors new on B
                if (checkedB) {
                    Set<String> stcA = new HashSet<>();
                    for (Map<String, Object> e : errs(a, "stcErrors")) stcA.add(norm(e));
                    Set<Integer> covered = new HashSet<>();
                    for (ShowCheck.Finding f : fs) if (f.severity.equals("error")) covered.add(f.line);
                    for (Map<String, Object> e : errs(b, "stcErrors")) {
                        if (stcA.contains(norm(e)) || covered.contains(line(e))) continue;
                        // "Cannot call X with arguments [..., java.lang.Object, ...]": an untyped argument, decided at run time
                        if (first(e).matches("(?s).*Cannot call .* with arguments \\[.*java\\.lang\\.Object.*")) continue;
                        // constructors don't go through the type-checking extension: an untyped argument is decided at run time
                        if (first(e).matches("(?s).*Cannot find matching method \\S+#<init>\\(.*java\\.lang\\.Object.*")) continue;
                        fs.add(new ShowCheck.Finding(line(e), "error", "stc", "type check fails on " + nameB + " only: " + first(e).replace("[Static type checking] - ", ""), null));
                    }
                }
                Set<String> typedUnknown = new HashSet<>();
                for (Map<String, Object> u : errs(a, "unknownMethods"))
                    if (Boolean.TRUE.equals(u.get("typed"))) typedUnknown.add(line(u) + "|" + u.get("name"));
                for (Map<String, Object> u : errs(a, "unknownMethods")) {
                    boolean typed = Boolean.TRUE.equals(u.get("typed"));
                    String name = String.valueOf(u.get("name"));
                    if (!typed && typedUnknown.contains(line(u) + "|" + name)) continue;
                    if (!name.matches("[A-Za-z_$][\\w$]*")) continue; // GString/command-chain artefacts, not method names
                    String owner = String.valueOf(u.get("owner"));
                    String msg = typed
                            ? "`" + simple(owner) + "#" + name + "` doesn't exist: no class on the classpath (or the Groovy GDK) has a method named `" + name + "`, so this fails on every version"
                            : "no class on the classpath and no show defines a method named `" + name + "`; unless the receiver adds it at runtime, this call fails on every version";
                    fs.add(new ShowCheck.Finding(line(u), typed ? "error" : "warning", "unknown-method", msg, KOTLIN.get(name)));
                }
                for (Map<String, Object> m : errs(a, "ambiguousMaterials")) fs.add(ambiguousMaterial(m));
            }
            fs.sort(Comparator.comparingInt((ShowCheck.Finding f) -> f.line).thenComparing(f -> f.rule).thenComparing(f -> f.message));
            dedupe(fs);
            // an unresolved import is already reported by `compile`; don't repeat it as `missing` on the same line
            Set<String> compiled = new HashSet<>();
            for (ShowCheck.Finding f : fs) {
                Matcher m = UNRESOLVED_CLASS.matcher(f.message);
                if (f.rule.equals("compile") && m.find()) compiled.add(f.line + "|" + m.group(1));
            }
            fs.removeIf(f -> f.rule.equals("missing") && f.message.startsWith("class `")
                    && compiled.contains(f.line + "|" + f.message.substring(7, f.message.indexOf('`', 7))));
            if (!fs.isEmpty()) findings.put(id, fs);
        }
    }

    /** {@code ambiguous-material}: a 1.12 material name that 1.13 reused for something else (#221 A33). */
    static ShowCheck.Finding ambiguousMaterial(Map<String, Object> m) {
        String name = String.valueOf(m.get("name")), call = String.valueOf(m.get("call"));
        String[] meaning = Facts.REUSED_NAMES.getOrDefault(name,
                new String[]{"its 1.12 meaning", "something else", "minecraft:" + name.toLowerCase(Locale.ROOT)});
        String modernId = "\"" + meaning[2] + "\"";
        if ("helper".equals(m.get("how"))) {
            return new ShowCheck.Finding(line(m), "warning", "ambiguous-material", "`" + call + "(\"" + name + "\")`: `" + name
                    + "` is a 1.12 name that 1.13 reused. ShowScript's helpers read it as " + meaning[0]
                    + " on every version; on " + nameB + " the name itself means " + meaning[1],
                    "keep it if you mean " + meaning[0] + "; for " + meaning[1] + " on " + nameB + " write " + modernId
                            + " (docs/version-agnostic-helpers.md, \"Reused 1.12 names\")");
        }
        String what = call.equals("Material") ? "`Material." + name + "`" : "`" + call + "(\"" + name + "\")`";
        return new ShowCheck.Finding(line(m), "warning", "ambiguous-material", what + " means " + meaning[0] + " on " + nameA
                + " but " + meaning[1] + " on " + nameB + " (1.13 reused the name)",
                "use the material(\"" + name + "\") helper for " + meaning[0] + " on both versions, or " + modernId + " for " + meaning[1]);
    }

    private void memberFindings(Map<String, List<Object>> members, List<ShowCheck.Finding> fs) {
        for (Map.Entry<String, List<Object>> e : members.entrySet()) {
            String key = e.getKey();
            String[] k = key.split("\\|", -1);
            String kind = k[0], owner = k[1], name = k[2], params = k[3];
            String[] sa = ma.get(key), sb = mb.get(key);
            List<Integer> lines = new ArrayList<>();
            for (Object o : e.getValue()) lines.add(((Number) o).intValue());
            if (sa == null || sb == null) {
                // a worker didn't produce a status for a member a show uses: never drop that silently
                if (!kind.equals("U")) fs.add(new ShowCheck.Finding(lines.isEmpty() ? -1 : lines.get(0), "warning", "unresolved",
                        describe(kind, owner, name, params) + " wasn't checked on " + (sa == null ? nameA : nameB) + " (no result from that worker)", null));
                continue;
            }
            int rA = MemberCheck.rank(sa[0]), rB = MemberCheck.rank(sb[0]);
            if (!kind.equals("U") && (sa[0].equals(MemberCheck.UNRESOLVED) || sb[0].equals(MemberCheck.UNRESOLVED))) {
                // can't compare: report which side couldn't be checked and why, once per member and file
                for (int side = 0; side < 2; side++) {
                    String[] st = side == 0 ? sa : sb;
                    if (!st[0].equals(MemberCheck.UNRESOLVED)) continue;
                    (side == 0 ? unresolvedA : unresolvedB).add(key);
                    String other = (side == 0 ? sb : sa)[0];
                    fs.add(new ShowCheck.Finding(lines.isEmpty() ? -1 : lines.get(0), "warning", "unresolved",
                            describe(kind, owner, name, params) + " couldn't be checked on " + (side == 0 ? nameA : nameB) + ": "
                                    + (st.length > 2 && !st[2].isEmpty() ? st[2] : "class can't be linked")
                                    + ". A dependency is missing from that classpath; not a confirmed removal (on "
                                    + (side == 0 ? nameB : nameA) + ": " + other + ")"
                                    + (lines.size() > 1 ? "; " + lines.size() + " uses, lines " + joinLines(lines) : ""),
                            null));
                }
                continue;
            }
            if (kind.equals("U")) {
                if (sa[0].equals("ok") && !sb[0].equals("ok"))
                    for (int l : lines) fs.add(new ShowCheck.Finding(l, "warning", "name-gone",
                            "`" + name + "(...)` is called on an untyped value; a class on " + nameA + " has that method, but no class on " + nameB + " does", null));
                continue;
            }
            if (kind.equals("E")) {
                String desc = "`" + simple(owner) + "." + params + "(\"" + name + "\")`";
                if (rA == 0 && rB >= 3) {
                    for (int l : lines) fs.add(new ShowCheck.Finding(l, "error", "missing", desc + ": there's no constant `" + name + "` on " + nameB + " (returns null or throws)", suggestion(owner, name, "", sb[2])));
                } else if (rA >= 3 && rB >= 3) {
                    for (int l : lines) fs.add(new ShowCheck.Finding(l, "warning", "unknown-constant", desc + ": no constant `" + name + "` on " + nameA + " or " + nameB, suggestion(owner, name, "", sb[2])));
                }
                continue;
            }
            if (kind.equals("C") && owner.startsWith("org.bukkit.material.") && rB == 0) {
                for (int l : lines) fs.add(new ShowCheck.Finding(l, "warning", "legacy-api",
                        "`" + simple(owner) + "` (org.bukkit.material) is legacy pre-1.13 API; on " + nameB + " particles and blocks need BlockData",
                        "Material.X.createBlockData() on " + nameB + "; or a ShowScript helper that works on both versions (#31)"));
                continue;
            }
            if (rB == 0 && legacy(kind, owner, name, params)) {
                for (int l : lines) fs.add(new ShowCheck.Finding(l, "warning", "legacy-api", describe(kind, owner, name, params)
                        + " still exists on " + nameB + " but is legacy (damage values / data bytes); behaviour differs", null));
                continue;
            }
            boolean existsA = kind.equals("P") || kind.equals("F") || kind.equals("C") ? rA == 0 : rA >= 0 && rA <= 2;
            if (!existsA || rB < 0) continue;
            if (rB > rA && rB >= 3) {
                String what = sb[0].equals("missing-class") ? "class `" + owner + "` doesn't exist on " + nameB
                        : describe(kind, owner, name, params) + " doesn't exist on " + nameB;
                String sug = kind.equals("C") || sb[0].equals("missing-class") ? classAdvice(owner) : suggestion(owner, name, params, sb[2]);
                for (int l : lines) fs.add(new ShowCheck.Finding(l, "error", "missing", what, sug));
            } else if (rB > rA) {
                boolean coercible = sb[0].equals("arity") && coercible(params, sb[2]);
                for (int l : lines) fs.add(new ShowCheck.Finding(l, coercible ? "warning" : "error", "signature", describe(kind, owner, name, params)
                        + (coercible ? ": parameter types changed on " + nameB + " (Groovy may coerce the arguments); available: "
                        : ": no overload on " + nameB + " accepts these argument types; available: ") + sb[2], suggestion(owner, name, params, "")));
            } else if (rB == 0 && "0".equals(sa[1]) && "1".equals(sb[1])) {
                String d = describe(kind, owner, name, params);
                deprecatedCount.merge(d, lines.size(), Integer::sum);
                if (deprecations) for (int l : lines) fs.add(new ShowCheck.Finding(l, "info", "deprecated", d + " is deprecated on " + nameB, suggestion(owner, name, params, "")));
            }
        }
    }

    /** Whether some same-arity overload in {@code available} ("(a,b); (c,d)") takes arguments Groovy would coerce from {@code params}. */
    static boolean coercible(String params, String available) {
        String[] want = params.isEmpty() ? new String[0] : params.split(",");
        for (String sig : available.split("; ")) {
            String inner = sig.replaceAll("^\\(|\\)$", "");
            String[] have = inner.isEmpty() ? new String[0] : inner.split(",");
            if (have.length != want.length) continue;
            boolean ok = true;
            for (int i = 0; i < want.length && ok; i++) ok = coerces(want[i], have[i]);
            if (ok) return true;
        }
        return false;
    }

    private static final Set<String> NUM = new HashSet<>(Arrays.asList("int", "long", "short", "byte", "float", "double",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Float", "java.lang.Double",
            "java.lang.Number", "java.math.BigDecimal", "java.math.BigInteger"));

    private static boolean coerces(String from, String to) {
        if (from.equals(to) || to.equals("java.lang.Object")) return true;
        if (NUM.contains(from) && NUM.contains(to)) return true;
        return (from.equals("java.lang.String") || from.equals("groovy.lang.GString")) && (to.equals("java.lang.String") || to.equals("java.lang.CharSequence"));
    }

    private static boolean legacy(String kind, String owner, String name, String params) {
        if (kind.equals("K") && owner.equals("org.bukkit.inventory.ItemStack") && params.matches("org\\.bukkit\\.Material,int,short(,java\\.lang\\.Byte)?")) return true;
        if ((kind.equals("M")) && owner.equals("org.bukkit.inventory.ItemStack") && (name.equals("setDurability") || name.equals("getDurability") || name.equals("getData") || name.equals("setData"))) return true;
        if (kind.equals("M") && owner.equals("org.bukkit.block.Block") && name.equals("getData")) return true;
        if (kind.equals("M") && owner.equals("org.bukkit.block.BlockState") && (name.equals("getData") || name.equals("setData"))) return true;
        return false;
    }

    private String suggestion(String owner, String name, String params, String info) {
        String simpleParams = params.isEmpty() ? "" : Arrays.stream(params.split(",")).map(Report::simple).reduce((x, y) -> x + "," + y).orElse("");
        String r = renames.get(owner + "\t" + name + "(" + simpleParams + ")");
        if (r == null) r = renames.get(owner + "\t" + name);
        if (info != null && info.startsWith("LEGACY_")) {
            String legacy = "legacy name (" + nameB + " only has " + simple(owner) + "." + info + ")";
            return r == null ? legacy + ": use the flattened 1.13+ name" : r + "; " + legacy;
        }
        return r;
    }

    private static String classAdvice(String cls) {
        if (cls.contains(".v1_") || cls.startsWith("net.minecraft.server") || cls.startsWith("org.bukkit.craftbukkit"))
            return "version-specific NMS/CraftBukkit: use Paper API (works on both versions) or a ShowScript helper";
        if (cls.startsWith("com.sk89q.worldguard")) return "WorldGuard 7 API (WorldGuard.getInstance().getPlatform().getRegionContainer()); or ShowScript playersInRegion/playerRegions";
        if (cls.startsWith("me.lucko.luckperms")) return "LuckPerms 5 API (net.luckperms.api)";
        if (cls.startsWith("org.bukkit.material.")) return "BlockData (Material.X.createBlockData())";
        return null;
    }

    private static String describe(String kind, String owner, String name, String params) {
        String o = simple(owner);
        String ps = params.isEmpty() ? "" : Arrays.stream(params.split(",")).map(Report::simple).reduce((x, y) -> x + ", " + y).orElse("");
        switch (kind) {
            case "C": return "class `" + owner + "`";
            case "F": return "`" + o + "." + name + "`";
            case "P": return "property `" + o + "." + name + "`";
            case "K": return "constructor `new " + o + "(" + ps + ")`";
            case "N": return "`" + o + "#" + name + "(...)`";
            case "E": return "`" + o + "." + params + "(\"" + name + "\")`";
            default: return "`" + o + "#" + name + "(" + ps + ")`";
        }
    }

    private static String joinLines(List<Integer> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size() && i < 10; i++) sb.append(i > 0 ? ", " : "").append(lines.get(i));
        if (lines.size() > 10) sb.append(", …");
        return sb.toString();
    }

    static String simple(String cls) {
        if (cls.startsWith("[")) return cls;
        int i = cls.lastIndexOf('.');
        return (i < 0 ? cls : cls.substring(i + 1)).replace('$', '.');
    }

    private static void dedupe(List<ShowCheck.Finding> fs) {
        Set<String> seen = new HashSet<>();
        fs.removeIf(f -> !seen.add(f.line + "|" + f.rule + "|" + f.message));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errs(Map<String, Object> r, String key) {
        Object o = r.get(key);
        return o instanceof List ? (List<Map<String, Object>>) o : Collections.emptyList();
    }

    private static int line(Map<String, Object> e) {
        Object l = e.get("line");
        return l instanceof Number ? ((Number) l).intValue() : -1;
    }

    private static String first(Map<String, Object> e) {
        String m = String.valueOf(e.get("msg"));
        int i = m.indexOf('\n');
        m = i < 0 ? m : m.substring(0, i);
        return m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }

    private static String norm(Map<String, Object> e) {
        return line(e) + "|" + String.valueOf(e.get("msg")).replaceAll("\\s+", " ").replaceAll("S[0-9a-f]{16}", "S");
    }

    // ---------- rendering ----------

    private List<String> order(Set<String> focusIds, boolean onlyFocus) {
        List<String> ids = new ArrayList<>();
        for (String id : findings.keySet()) if (!onlyFocus || focusIds.contains(id)) ids.add(id);
        ids.sort(Comparator.comparing((String id) -> !focusIds.contains(id))
                .thenComparing(id -> severityRank(id) * -1)
                .thenComparing(id -> locs.get(id).get(0).display()));
        return ids;
    }

    private int severityRank(String id) {
        int w = 0;
        for (ShowCheck.Finding f : findings.getOrDefault(id, Collections.emptyList()))
            w = Math.max(w, f.severity.equals("error") ? 2 : f.severity.equals("warning") ? 1 : 0);
        return w;
    }

    int worst(Set<String> focusIds, boolean onlyFocus) {
        int w = 0;
        for (String id : order(focusIds, onlyFocus)) w = Math.max(w, severityRank(id));
        return w;
    }

    private Map<String, Integer> filesByRule() {
        Map<String, Integer> m = new TreeMap<>();
        for (List<ShowCheck.Finding> fs : findings.values()) {
            Set<String> rules = new HashSet<>();
            for (ShowCheck.Finding f : fs) rules.add(f.rule);
            for (String r : rules) m.merge(r, 1, Integer::sum);
        }
        return m;
    }

    private int filesWithBreaks() {
        int n = 0;
        for (List<ShowCheck.Finding> fs : findings.values()) {
            for (ShowCheck.Finding f : fs)
                if (isBreak(f)) { n++; break; }
        }
        return n;
    }

    static boolean isBreak(ShowCheck.Finding f) {
        return f.rule.equals("missing") || f.rule.equals("compile") || f.rule.equals("stc")
                || (f.rule.equals("signature") && f.severity.equals("error"));
    }

    String summaryLine() {
        return locs.size() + " unique shows; compile on " + nameA + ": " + compilesA + ", on " + nameB + ": " + compilesB
                + (incompleteA + incompleteB > 0 ? "; incomplete (sibling failed) " + nameA + ": " + incompleteA + ", " + nameB + ": " + incompleteB : "")
                + "; " + filesWithBreaks() + " with a " + nameB + " break; " + findings.size() + " with any finding"
                + (unresolvedA.size() + unresolvedB.size() > 0 ? "; unresolved member checks " + nameA + ": " + unresolvedA.size() + ", " + nameB + ": " + unresolvedB.size() : "")
                + (factsIncompleteA + factsIncompleteB > 0 ? "; member analysis incomplete (collector crashed) " + nameA + ": " + factsIncompleteA + ", " + nameB + ": " + factsIncompleteB : "");
    }

    Map<String, Object> toJson(Set<String> focusIds, boolean onlyFocus) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sideA", nameA); s.put("sideB", nameB);
        s.put("files", locs.values().stream().mapToInt(List::size).sum());
        s.put("uniqueFiles", locs.size());
        s.put("compilesA", compilesA); s.put("compilesB", compilesB); s.put("typeCleanA", stcCleanA);
        s.put("incompleteA", incompleteA); s.put("incompleteB", incompleteB);
        s.put("memberAnalysisIncompleteA", factsIncompleteA); s.put("memberAnalysisIncompleteB", factsIncompleteB);
        s.put("unresolvedMembersA", new ArrayList<>(unresolvedA)); s.put("unresolvedMembersB", new ArrayList<>(unresolvedB));
        s.put("filesWithBreakOnB", filesWithBreaks());
        s.put("filesWithFindings", findings.size());
        s.put("filesByRule", filesByRule());
        s.put("newlyDeprecatedUses", deprecatedCount);
        root.put("summary", s);
        List<Object> files = new ArrayList<>();
        for (String id : order(focusIds, onlyFocus)) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("sha256", id);
            List<String> paths = new ArrayList<>();
            for (ShowCheck.Loc l : locs.get(id)) paths.add(l.display());
            f.put("paths", paths);
            f.put("focus", focusIds.contains(id));
            Map<String, Object> a = ra.getOrDefault(id, Collections.emptyMap());
            f.put("compilesA", Boolean.TRUE.equals(a.get("compiles")));
            f.put("compilesB", Boolean.TRUE.equals(rb.getOrDefault(id, Collections.emptyMap()).get("compiles")));
            if (Boolean.TRUE.equals(a.get("partial"))) f.put("incompleteA", true);
            if (Boolean.TRUE.equals(rb.getOrDefault(id, Collections.emptyMap()).get("partial"))) f.put("incompleteB", true);
            if (a.get("factsError") != null) f.put("memberAnalysisIncompleteA", true);
            if (rb.getOrDefault(id, Collections.emptyMap()).get("factsError") != null) f.put("memberAnalysisIncompleteB", true);
            if (a.get("siblings") != null) f.put("compiledWithSiblings", a.get("siblings"));
            List<Object> fl = new ArrayList<>();
            for (ShowCheck.Finding x : findings.get(id)) fl.add(x.toMap());
            f.put("findings", fl);
            files.add(f);
        }
        root.put("files", files);
        return root;
    }

    /** GitLab Code Quality report: one entry per finding and copy of the file (paths prefixed, e.g. "shows/"). */
    List<Object> codeQuality(String prefix) {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, List<ShowCheck.Finding>> e : findings.entrySet()) {
            for (ShowCheck.Loc l : locs.get(e.getKey())) {
                // GitLab dedupes Code Quality entries by fingerprint: key on path, line, rule and message, plus an
                // occurrence index for identical findings on the same line, so every finding keeps its own entry
                Map<String, Integer> occurrence = new HashMap<>();
                for (ShowCheck.Finding f : e.getValue()) {
                    if (f.severity.equals("info")) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("description", "[show-check " + f.rule + "] " + f.message.replace("`", "") + (f.suggestion.isEmpty() ? "" : " Suggestion: " + f.suggestion));
                    m.put("check_name", "show-check/" + f.rule);
                    m.put("severity", f.severity.equals("error") ? "major" : "minor");
                    String path = prefix + l.rel;
                    String fp = (l.label.isEmpty() ? "" : l.label + ":") + path + "|" + f.line + "|" + f.rule + "|" + f.message;
                    int n = occurrence.merge(fp, 1, Integer::sum);
                    if (n > 1) fp += "|" + n;
                    try {
                        m.put("fingerprint", ShowCheck.sha256(fp.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    } catch (Exception ex) {
                        m.put("fingerprint", fp);
                    }
                    Map<String, Object> loc = new LinkedHashMap<>();
                    loc.put("path", path);
                    loc.put("lines", Collections.singletonMap("begin", Math.max(1, f.line)));
                    m.put("location", loc);
                    out.add(m);
                }
            }
        }
        return out;
    }

    String markdown(Set<String> focusIds, boolean onlyFocus) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Show check: ").append(nameA).append(" vs ").append(nameB).append("\n\n");
        int nfiles = locs.values().stream().mapToInt(List::size).sum();
        sb.append("Report only: nothing is blocked. ").append(nfiles).append(" `.groovy` files, ").append(locs.size()).append(" unique by content.\n\n");
        sb.append("| | files |\n|---|---:|\n");
        sb.append("| compile on ").append(nameA).append(" | ").append(compilesA).append(" |\n");
        sb.append("| compile on ").append(nameB).append(" | ").append(compilesB).append(" |\n");
        if (incompleteA + incompleteB > 0)
            sb.append("| incomplete: a sibling show it needs doesn't compile (").append(nameA).append(" / ").append(nameB).append(") | ")
                    .append(incompleteA).append(" / ").append(incompleteB).append(" |\n");
        if (factsIncompleteA + factsIncompleteB > 0)
            sb.append("| member analysis incomplete: show-check crashed collecting a show's members (").append(nameA).append(" / ").append(nameB).append(") | ")
                    .append(factsIncompleteA).append(" / ").append(factsIncompleteB).append(" |\n");
        sb.append("| **break on ").append(nameB).append("** (`missing`, `compile`, `stc`, `signature` errors) | **").append(filesWithBreaks()).append("** |\n");
        sb.append("| any finding | ").append(findings.size()).append(" |\n");
        for (Map.Entry<String, Integer> e : filesByRule().entrySet())
            sb.append("| rule `").append(e.getKey()).append("` | ").append(e.getValue()).append(" |\n");
        sb.append("| member checks that couldn't be done (`unresolved`: class can't be linked on that classpath), ").append(nameA).append(" / ").append(nameB)
                .append(" | ").append(unresolvedA.size()).append(" / ").append(unresolvedB.size()).append(" |\n");
        if (!deprecatedCount.isEmpty() && !deprecations)
            sb.append("| uses of API newly deprecated on ").append(nameB).append(" (works; list with `--deprecations`) | ")
                    .append(deprecatedCount.values().stream().mapToInt(Integer::intValue).sum()).append(" |\n");
        sb.append("\nRules: `missing` = resolves on ").append(nameA).append(", gone on ").append(nameB)
                .append("; `compile` = compiles only on ").append(nameA).append("; `stc` = type error only on ").append(nameB)
                .append("; `unknown-method` = no class has that method name (fails everywhere, e.g. Kotlin `minByOrNull`)")
                .append("; `legacy-api` = still there but pre-1.13 semantics; `ambiguous-material` = a 1.12 material name 1.13 reused (MELON, GRASS, BRICK, ...); `unresolved` = a member couldn't be checked on one side (missing dependency on that classpath, not a removal); `internal` = show-check itself couldn't fully check the file; `broken-both`/`fails-on-a` = doesn't compile on ")
                .append(nameA).append(" today. Calls on untyped (`def`) values are only checked by name; behaviour changes can't be ruled out statically.\n\n");
        List<String> ids = order(focusIds, onlyFocus);
        if (ids.isEmpty()) sb.append("No findings.\n");
        for (String id : ids) {
            List<ShowCheck.Loc> ls = locs.get(id);
            sb.append("### ").append(focusIds.contains(id) ? "▶ " : "").append('`').append(ls.get(0).display()).append("`\n\n");
            if (ls.size() > 1) {
                sb.append("Same content at: ");
                for (int i = 1; i < ls.size() && i < 8; i++) sb.append(i > 1 ? ", " : "").append('`').append(ls.get(i).display()).append('`');
                if (ls.size() > 8) sb.append(", … (").append(ls.size() - 8).append(" more)");
                sb.append("\n\n");
            }
            sb.append("| line | severity | rule | finding | suggestion |\n|---:|---|---|---|---|\n");
            for (ShowCheck.Finding f : findings.get(id)) {
                sb.append("| ").append(f.line > 0 ? String.valueOf(f.line) : "").append(" | ").append(f.severity).append(" | `").append(f.rule)
                        .append("` | ").append(cell(f.message)).append(" | ").append(cell(f.suggestion)).append(" |\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
