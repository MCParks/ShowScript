package us.mcparks.showcheck;

import java.lang.reflect.*;
import java.util.*;

/**
 * Checks one member key (see {@link Facts}) against the classes visible to a class loader, by
 * reflection, without initialising classes. Status values, best first:
 * ok, arity (same name and parameter count, different types), name (name only),
 * missing-member, missing-class.
 *
 * <p>{@value #UNRESOLVED} (rank -1) means the check itself couldn't be done: the class exists but can't be
 * linked or reflected on, typically because a class it depends on (superclass, interface, a type in a member
 * signature) is missing from that classpath. That is not a confirmed removal; {@link #info} names the missing
 * class / exception, and the report lists such checks explicitly instead of dropping them.
 */
final class MemberCheck {
    static final List<String> RANK = Arrays.asList("ok", "arity", "name", "missing-member", "missing-class");
    static final String UNRESOLVED = "unresolved";

    final String status;
    final boolean deprecated;
    final String info;

    private MemberCheck(String status, boolean deprecated, String info) {
        this.status = status;
        this.deprecated = deprecated;
        this.info = info == null ? "" : info;
    }

    static MemberCheck check(String key, ClassLoader cl) {
        String[] k = key.split("\\|", -1);
        String kind = k[0], owner = k[1], name = k[2], params = k[3];
        Class<?> c;
        try {
            c = Class.forName(owner, false, cl);
        } catch (ClassNotFoundException e) {
            return new MemberCheck("missing-class", false, null); // confirmed: no such class on this classpath
        } catch (LinkageError e) {
            // NoClassDefFoundError & co.: the class file is there, but something it needs isn't
            return unresolved(e);
        } catch (Throwable t) {
            return unresolved(t);
        }
        boolean classDep = c.isAnnotationPresent(Deprecated.class);
        try {
            switch (kind) {
                case "C":
                    return new MemberCheck("ok", classDep, null);
                case "E":
                    return lookup(c, name, params, classDep);
                case "F": {
                    Field f = field(c, name);
                    if (f != null) return new MemberCheck("ok", classDep || f.isAnnotationPresent(Deprecated.class), null);
                    Method g = getter(c, name, true);
                    if (g != null) return new MemberCheck("ok", classDep || g.isAnnotationPresent(Deprecated.class), null);
                    for (Class<?> inner : c.getClasses()) if (inner.getSimpleName().equals(name)) return new MemberCheck("ok", classDep, null);
                    // `import static X.someMethod` is keyed as F too: a static method of that name counts
                    for (Method m : methods(c))
                        if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers()))
                            return new MemberCheck("ok", classDep || m.isAnnotationPresent(Deprecated.class), null);
                    return new MemberCheck("missing-member", false, field(c, "LEGACY_" + name) != null ? "LEGACY_" + name : null);
                }
                case "P": {
                    Field f = field(c, name);
                    if (f != null) return new MemberCheck("ok", classDep || f.isAnnotationPresent(Deprecated.class), null);
                    Method g = getter(c, name, false);
                    if (g != null) return new MemberCheck("ok", classDep || g.isAnnotationPresent(Deprecated.class), null);
                    return new MemberCheck("missing-member", false, null);
                }
                case "N": {
                    for (Method m : methods(c)) if (m.getName().equals(name)) return new MemberCheck("ok", false, null);
                    return new MemberCheck("missing-member", false, null);
                }
                case "K": {
                    return best(Arrays.asList(c.getConstructors()), params, classDep);
                }
                default: { // M, S
                    List<Executable> cands = new ArrayList<>();
                    for (Method m : methods(c)) if (m.getName().equals(name)) cands.add(m);
                    return best(cands, params, classDep);
                }
            }
        } catch (Throwable t) {
            return unresolved(t);
        }
    }

    private static MemberCheck unresolved(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String what = root instanceof NoClassDefFoundError || root instanceof ClassNotFoundException
                ? "needs " + String.valueOf(root.getMessage()).replace('/', '.') + ", which isn't on this classpath"
                : root.toString();
        return new MemberCheck(UNRESOLVED, false, what + (root == t ? "" : " (" + t.getClass().getSimpleName() + ")"));
    }

    // ---------- constants looked up by string ----------

    /**
     * A constant looked up by string ({@code Material.valueOf("X")}, {@code Material.matchMaterial("x")}, ...).
     * Each lookup method is modelled with the normalisation it applies on that server version, then matched
     * against the class's public static fields (enum constants or constant fields):
     * <ul>
     * <li>{@code valueOf}, {@code getMaterial}: exact name;</li>
     * <li>{@code matchMaterial}: upper-cased (English locale), whitespace to {@code _}, other non-word characters
     * removed; 1.13+ (a {@code LEGACY_AIR} constant exists) also strips a {@code minecraft:} prefix first, and
     * 1.12 also accepts a numeric id (checked against {@code Material.getId()});</li>
     * <li>{@code getByName}: case-insensitive (PotionEffectType's lookup is; for other classes this errs on the
     * side of not reporting).</li>
     * </ul>
     * {@link #info} carries the normalised name when it differs from the literal.
     */
    private static MemberCheck lookup(Class<?> c, String literal, String method, boolean classDep) throws Exception {
        String name = literal;
        boolean ignoreCase = false;
        if (method.equals("matchMaterial")) {
            // 1.13+ Material keeps its pre-flattening constants as LEGACY_*; a field lookup needs fewer linked types than getMethods()
            boolean modern = field(c, "LEGACY_AIR") != null;
            if (modern && name.toLowerCase(Locale.ENGLISH).startsWith("minecraft:")) name = name.substring("minecraft:".length());
            name = name.toUpperCase(Locale.ENGLISH).replaceAll("\\s+", "_").replaceAll("\\W", "");
            if (!modern && literal.trim().matches("\\d+")) return legacyId(c, Integer.parseInt(literal.trim()), classDep);
        } else if (method.equals("getByName")) {
            ignoreCase = true;
        }
        Field f = constant(c, name, ignoreCase);
        String norm = name.equals(literal) ? null : "looked up as " + name;
        if (f != null) return new MemberCheck("ok", classDep || f.isAnnotationPresent(Deprecated.class), norm);
        Field legacy = constant(c, "LEGACY_" + name, ignoreCase);
        return new MemberCheck("missing-member", false, legacy != null ? "LEGACY_" + name : norm);
    }

    private static Field constant(Class<?> c, String name, boolean ignoreCase) {
        Field f = field(c, name);
        if (f != null && Modifier.isStatic(f.getModifiers())) return f;
        if (!ignoreCase) return null;
        for (Field x : c.getFields())
            if (Modifier.isStatic(x.getModifiers()) && x.getName().equalsIgnoreCase(name)) return x;
        return null;
    }

    /** 1.12 matchMaterial("35"): a material with that numeric id. Needs the enum initialised (it is, offline, on 1.12). */
    private static MemberCheck legacyId(Class<?> c, int id, boolean classDep) throws Exception {
        Object[] constants = c.getEnumConstants();
        Method getId = c.getMethod("getId");
        if (constants != null)
            for (Object o : constants)
                if (((Number) getId.invoke(o)).intValue() == id) return new MemberCheck("ok", classDep, "id " + id + " = " + o);
        return new MemberCheck("missing-member", false, "no material with id " + id);
    }

    private static MemberCheck best(List<? extends Executable> cands, String params, boolean classDep) {
        if (cands.isEmpty()) return new MemberCheck("missing-member", false, null);
        int want = params.isEmpty() ? 0 : params.split(",").length;
        boolean arity = false;
        StringBuilder sigs = new StringBuilder();
        TreeSet<String> available = new TreeSet<>(); // sorted: reflection order varies between JVMs and runs
        for (Executable e : cands) {
            String p = sig(e.getParameterTypes());
            if (p.equals(params)) return new MemberCheck("ok", classDep || e.isAnnotationPresent(Deprecated.class), null);
            if (e.getParameterCount() == want) arity = true;
            available.add(p);
        }
        for (String p : available)
            if (sigs.length() < 300) sigs.append(sigs.length() > 0 ? "; " : "").append('(').append(p).append(')');
        return new MemberCheck(arity ? "arity" : "name", false, sigs.toString());
    }

    private static List<Method> methods(Class<?> c) {
        List<Method> ms = new ArrayList<>(Arrays.asList(c.getMethods()));
        if (c.isInterface()) ms.addAll(Arrays.asList(Object.class.getMethods()));
        return ms;
    }

    private static Field field(Class<?> c, String name) {
        try {
            return c.getField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Method getter(Class<?> c, String name, boolean wantStatic) {
        if (name.isEmpty()) return null;
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Method m : methods(c)) {
            if (Modifier.isStatic(m.getModifiers()) != wantStatic) continue;
            String n = m.getName();
            if ((n.equals("get" + cap) || n.equals("is" + cap)) && m.getParameterCount() == 0) return m;
            if (!wantStatic && n.equals("set" + cap) && m.getParameterCount() == 1) return m;
        }
        return null;
    }

    static String sig(Class<?>[] ps) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : ps) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p.getName());
        }
        return sb.toString();
    }

    static int rank(String status) {
        return RANK.indexOf(status); // unresolved -> -1
    }
}
