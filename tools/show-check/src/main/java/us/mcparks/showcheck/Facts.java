package us.mcparks.showcheck;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.stc.ExtensionMethodNode;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;

import java.util.*;

/**
 * Walks one resolved (and, if available, type-checked) show module and records, with line numbers,
 * every API member it uses. Member keys: {@code kind|owner|name|params}, where kind is
 * M (instance method), S (static method), K (constructor), F (static field/property via Class.X),
 * P (property on a typed receiver), C (class reference), N (method name on a typed receiver whose
 * static type lacks it), E (enum constant looked up by string, e.g. Material.valueOf("X")).
 * Calls on untyped receivers are recorded by name only (U).
 */
final class Facts extends ClassCodeVisitorSupport {
    /** key -> sorted lines */
    final Map<String, TreeSet<Integer>> members = new TreeMap<>();
    /** untyped call names -> lines */
    final Map<String, TreeSet<Integer>> untypedCalls = new TreeMap<>();
    final Set<String> localClasses = new HashSet<>();
    /**
     * Material names whose meaning changed in 1.13 (see {@link #REUSED_NAMES}), keyed {@code how|call|NAME}: how is
     * {@code helper} (a ShowScript helper: 1.12 meaning on every version) or {@code bukkit} (Bukkit's own lookup or
     * constant: 1.12 meaning on 1.12.2, the new one on 1.13+).
     */
    final Map<String, TreeSet<Integer>> ambiguousMaterials = new TreeMap<>();

    /**
     * 1.12 material names that 1.13 reused for something else: 1.12 meaning, 1.13+ meaning, modern id for the latter. Keep in step with
     * ShowsRebuild's {@code compat/MaterialSpec} and {@code docs/version-agnostic-helpers.md}.
     */
    static final Map<String, String[]> REUSED_NAMES = new LinkedHashMap<>();
    static {
        REUSED_NAMES.put("BRICK", new String[]{"the brick block", "the brick item", "minecraft:brick"});
        REUSED_NAMES.put("NETHER_BRICK", new String[]{"the nether brick block", "the nether brick item", "minecraft:nether_brick"});
        REUSED_NAMES.put("MELON", new String[]{"the melon slice", "the melon block", "minecraft:melon"});
        REUSED_NAMES.put("CARROT", new String[]{"the carrot crop block", "the carrot item", "minecraft:carrot"});
        REUSED_NAMES.put("POTATO", new String[]{"the potato crop block", "the potato item", "minecraft:potato"});
        REUSED_NAMES.put("GRASS", new String[]{"the grass block", "the short grass plant (SHORT_GRASS since 1.20.3)", "minecraft:short_grass"});
    }

    /** ShowScript helpers that take a material spec, with the index of that argument. */
    private static final Map<String, Integer> SPEC_HELPERS = new HashMap<>();
    static {
        SPEC_HELPERS.put("material", 0);
        SPEC_HELPERS.put("item", 0);
        SPEC_HELPERS.put("blockData", 0);
        SPEC_HELPERS.put("particle", 10); // particle(target, type, x, y, z, count, dx, dy, dz, extra, data)
    }
    private SourceUnit su;

    @Override
    protected SourceUnit getSourceUnit() {
        return su;
    }

    void module(ModuleNode mn) {
        su = mn.getContext();
        for (ClassNode cn : mn.getClasses()) localClasses.add(cn.getName());
        for (ImportNode in : mn.getImports()) add("C", in.getType().getName(), "", "", in.getLineNumber());
        for (ImportNode in : mn.getStaticImports().values()) add("F", in.getType().getName(), in.getFieldName(), "", in.getLineNumber());
        for (ImportNode in : mn.getStaticStarImports().values()) add("C", in.getType().getName(), "", "", in.getLineNumber());
        for (ClassNode cn : mn.getClasses()) visitClass(cn);
    }

    private void add(String kind, String owner, String name, String params, int line) {
        if (owner == null || owner.isEmpty() || localClasses.contains(owner)) return;
        if (owner.startsWith("us.mcparks.showcheck.dsl.")) owner = "us.mcparks.showscript.showscript.groovy.dsl.TimecodeDsl";
        members.computeIfAbsent(kind + "|" + owner + "|" + name + "|" + params, k -> new TreeSet<>()).add(line);
    }

    private void ref(ClassNode t, int line) {
        if (t == null) return;
        if (t.isArray()) {
            ref(t.getComponentType(), line);
            return;
        }
        if (ClassHelper.isPrimitiveType(t) || t.isGenericsPlaceHolder()) return;
        add("C", t.getName(), "", "", line);
        if (t.getGenericsTypes() != null)
            for (GenericsType g : t.getGenericsTypes())
                if (!g.isPlaceholder() && !g.isWildcard()) ref(g.getType(), line);
    }

    private static String params(Parameter[] ps) {
        StringBuilder sb = new StringBuilder();
        for (Parameter p : ps) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p.getType().getName());
        }
        return sb.toString();
    }

    private static ClassNode inferred(Expression e) {
        Object t = e.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);
        return t instanceof ClassNode ? (ClassNode) t : null;
    }

    private boolean target(Expression call, int line) {
        if (call.getNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION) != null) return false;
        Object t = call.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET);
        if (!(t instanceof MethodNode)) return false;
        MethodNode m = (MethodNode) t;
        if (m instanceof ExtensionMethodNode) return true; // GDK: same Groovy on both sides
        ClassNode dc = m.getDeclaringClass();
        if (dc == null) return false;
        String kind = m instanceof ConstructorNode ? "K" : m.isStatic() ? "S" : "M";
        add(kind, dc.getName(), m instanceof ConstructorNode ? "<init>" : m.getName(), params(m.getParameters()), line);
        return true;
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression call) {
        int line = call.getLineNumber();
        String name = call.getMethodAsString();
        if (!target(call, line) && name != null && !call.isImplicitThis()) {
            Expression recv = call.getObjectExpression();
            boolean isThis = recv instanceof VariableExpression && ((VariableExpression) recv).isThisExpression();
            if (!isThis && !(recv instanceof ClassExpression)) {
                untypedCalls.computeIfAbsent(name, k -> new TreeSet<>()).add(line);
            }
        }
        enumByString(call.getObjectExpression(), name, call.getArguments(), line);
        helperSpec(call, name, line);
        super.visitMethodCallExpression(call);
    }

    /** {@code material("MELON")}, {@code item("GRASS:0")}, ...: a reused 1.12 name passed to a ShowScript helper. */
    private void helperSpec(MethodCallExpression call, String name, int line) {
        Integer index = name == null ? null : SPEC_HELPERS.get(name);
        if (index == null || !(call.getArguments() instanceof TupleExpression)) return;
        Expression recv = call.getObjectExpression();
        boolean onThis = call.isImplicitThis() || recv instanceof VariableExpression && ((VariableExpression) recv).isThisExpression();
        if (!onThis) return;
        List<Expression> es = ((TupleExpression) call.getArguments()).getExpressions();
        if (es.size() <= index || !(es.get(index) instanceof ConstantExpression)) return;
        Object v = ((ConstantExpression) es.get(index)).getValue();
        if (!(v instanceof String)) return;
        String spec = ((String) v).trim();
        if (spec.toLowerCase(Locale.ROOT).startsWith("minecraft:")) return; // the modern id, on purpose
        String bare = spec.replaceAll(":\\d{1,2}$", "").toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (REUSED_NAMES.containsKey(bare))
            ambiguousMaterials.computeIfAbsent("helper|" + name + "|" + bare, k -> new TreeSet<>()).add(line);
    }

    private void bukkitMaterial(String call, String constant, int line) {
        String bare = constant.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (REUSED_NAMES.containsKey(bare))
            ambiguousMaterials.computeIfAbsent("bukkit|" + call + "|" + bare, k -> new TreeSet<>()).add(line);
    }

    private void enumByString(Expression recv, String name, Expression args, int line) {
        if (!(recv instanceof ClassExpression) || name == null || !(args instanceof TupleExpression)) return;
        String owner = recv.getType().getName();
        if (!owner.startsWith("org.bukkit.")) return;
        if (!(name.equals("valueOf") || name.equals("getMaterial") || name.equals("matchMaterial") || name.equals("getByName"))) return;
        List<Expression> es = ((TupleExpression) args).getExpressions();
        if (es.size() != 1 || !(es.get(0) instanceof ConstantExpression)) return;
        Object v = ((ConstantExpression) es.get(0)).getValue();
        if (v instanceof String) {
            add("E", owner, (String) v, name, line);
            if (owner.equals("org.bukkit.Material")) bukkitMaterial("Material." + name, (String) v, line);
        }
    }

    @Override
    public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
        target(call, call.getLineNumber());
        super.visitStaticMethodCallExpression(call);
    }

    @Override
    public void visitConstructorCallExpression(ConstructorCallExpression call) {
        ref(call.getType(), call.getLineNumber());
        if (!call.isSpecialCall()) target(call, call.getLineNumber());
        super.visitConstructorCallExpression(call);
    }

    @Override
    public void visitPropertyExpression(PropertyExpression pe) {
        Expression obj = pe.getObjectExpression();
        String prop = pe.getPropertyAsString();
        if (prop != null && pe.getNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION) == null) {
            if (obj instanceof ClassExpression) {
                if (!prop.equals("class")) add("F", obj.getType().getName(), prop, "", pe.getLineNumber());
                if (obj.getType().getName().equals("org.bukkit.Material")) bukkitMaterial("Material", prop, pe.getLineNumber());
            } else {
                ClassNode it = inferred(obj);
                if (it != null && !RelaxedTypeChecking.untyped(it) && !it.isArray() && !ClassHelper.isPrimitiveType(it)
                        && !pe.isSpreadSafe())
                    add("P", it.getName(), prop, "", pe.getLineNumber());
            }
        }
        super.visitPropertyExpression(pe);
    }

    @Override
    public void visitClassExpression(ClassExpression e) {
        ref(e.getType(), e.getLineNumber());
        super.visitClassExpression(e);
    }

    @Override
    public void visitDeclarationExpression(DeclarationExpression e) {
        if (!e.isMultipleAssignmentDeclaration()) {
            VariableExpression v = e.getVariableExpression();
            if (!v.isDynamicTyped()) ref(v.getOriginType(), e.getLineNumber());
        }
        super.visitDeclarationExpression(e);
    }

    @Override
    public void visitCastExpression(CastExpression e) {
        ref(e.getType(), e.getLineNumber());
        super.visitCastExpression(e);
    }

    @Override
    public void visitClosureExpression(ClosureExpression e) {
        if (e.getParameters() != null)
            for (Parameter p : e.getParameters()) if (!p.isDynamicTyped()) ref(p.getType(), e.getLineNumber());
        super.visitClosureExpression(e);
    }

    @Override
    public void visitCatchStatement(CatchStatement s) {
        ref(s.getVariable().getType(), s.getLineNumber());
        super.visitCatchStatement(s);
    }

    @Override
    public void visitMethod(MethodNode node) {
        if (!node.isScriptBody() && node.getLineNumber() < 1) return; // generated members
        if (!node.isScriptBody() && !node.isSynthetic()) {
            for (Parameter p : node.getParameters()) if (!p.isDynamicTyped()) ref(p.getType(), node.getLineNumber());
            if (!node.isDynamicReturnType()) ref(node.getReturnType(), node.getLineNumber());
        }
        super.visitMethod(node);
    }

    @Override
    public void visitField(FieldNode node) {
        if (node.getLineNumber() > 0 && !node.isDynamicTyped()) ref(node.getType(), node.getLineNumber());
        super.visitField(node);
    }

    @Override
    public void visitConstructor(ConstructorNode node) {
        if (node.getLineNumber() < 1) return;
        super.visitConstructor(node);
    }
}
