package us.mcparks.showcheck;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.transform.stc.AbstractTypeCheckingExtension;
import org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport;
import org.codehaus.groovy.transform.stc.StaticTypeCheckingVisitor;

import java.util.*;

/**
 * Type-checking extension that keeps only version-relevant errors.
 *
 * Shows are dynamic Groovy; a strict @TypeChecked pass rejects ~60% of them for reasons that have
 * nothing to do with the server version (issue #15). This extension:
 * <ul>
 *   <li>makes calls/properties on untyped receivers (def/Object, Map, Closure, script or
 *       script-declared classes) dynamic, and binding variables dynamic;</li>
 *   <li>accepts BigDecimal/Number arguments for double/float/int parameters, GString for String,
 *       and Object for anything, then resolves the call to the real member so it can be checked
 *       against the other classpath;</li>
 *   <li>makes a missing method on a typed receiver dynamic when some class on the classpath declares
 *       that name (the runtime object may be a subtype, e.g. Entity#getEquipment), but leaves the
 *       error when no class declares it at all (e.g. Kotlin's minByOrNull).</li>
 * </ul>
 * Typed members that don't exist (e.g. Particle.BLOCK_DUST on 1.21.4) stay errors.
 */
public class RelaxedTypeChecking extends AbstractTypeCheckingExtension {

    /** Calls on typed receivers made dynamic because the static type lacks the name: [line, owner, name]. */
    static final ThreadLocal<List<Object[]>> TYPED_UNRESOLVED = ThreadLocal.withInitial(ArrayList::new);
    static volatile NameIndex index;

    public RelaxedTypeChecking(StaticTypeCheckingVisitor v) {
        super(v);
    }

    static boolean untyped(ClassNode t) {
        if (t == null) return true;
        if (t.equals(ClassHelper.OBJECT_TYPE) || t.equals(ClassHelper.DYNAMIC_TYPE) || t.isGenericsPlaceHolder()) return true;
        if (t.equals(ClassHelper.CLOSURE_TYPE) || t.equals(ClassHelper.GROOVY_OBJECT_TYPE)) return true;
        if (t.isScript() || t.getModule() != null) return true; // the script or a class declared in show source
        if (t.equals(ClassHelper.MAP_TYPE) || StaticTypeCheckingSupport.implementsInterfaceOrIsSubclassOf(t, ClassHelper.MAP_TYPE)) return true;
        if (t.getName().startsWith("us.mcparks.showcheck.dsl.")) return true;
        return false;
    }

    @Override
    public boolean handleUnresolvedVariableExpression(VariableExpression vexp) {
        makeDynamic(vexp);
        return true;
    }

    @Override
    public boolean handleUnresolvedProperty(PropertyExpression pexp) {
        if (untyped(getType(pexp.getObjectExpression()))) {
            makeDynamic(pexp);
            return true;
        }
        return false;
    }

    @Override
    public boolean handleUnresolvedAttribute(AttributeExpression aexp) {
        if (untyped(getType(aexp.getObjectExpression()))) {
            makeDynamic(aexp);
            return true;
        }
        return false;
    }

    @Override
    public List<MethodNode> handleMissingMethod(ClassNode receiver, String name, ArgumentListExpression argumentList,
                                                ClassNode[] argumentTypes, MethodCall call) {
        if (name == null) return Collections.singletonList(makeDynamic(call));
        boolean implicitThis = call instanceof MethodCallExpression && ((MethodCallExpression) call).isImplicitThis();
        if (implicitThis || untyped(receiver)) return Collections.singletonList(makeDynamic(call));

        List<MethodNode> relaxed = relaxedMatch(receiver, name, argumentTypes);
        if (!relaxed.isEmpty()) return Collections.singletonList(relaxed.get(0));

        NameIndex idx = index;
        if (idx != null && !idx.has(name)) return Collections.emptyList(); // no class has it: keep the error
        int line = call instanceof ASTNode ? ((ASTNode) call).getLineNumber() : -1;
        TYPED_UNRESOLVED.get().add(new Object[]{line, receiver.getName(), name});
        return Collections.singletonList(makeDynamic(call));
    }

    @Override
    public boolean handleIncompatibleAssignment(ClassNode lhsType, ClassNode rhsType, Expression assignmentExpression) {
        return untyped(rhsType) || (numeric(lhsType) && numeric(rhsType))
                || (rhsType != null && rhsType.getName().equals("groovy.lang.GString"));
    }

    @Override
    public boolean handleIncompatibleReturnType(ReturnStatement returnStatement, ClassNode inferredReturnType) {
        return true;
    }

    @Override
    public List<MethodNode> handleAmbiguousMethods(List<MethodNode> nodes, Expression origin) {
        return nodes.isEmpty() ? nodes : nodes.subList(0, 1);
    }

    // ---------- relaxed overload matching ----------

    private static List<MethodNode> relaxedMatch(ClassNode receiver, String name, ClassNode[] args) {
        List<MethodNode> out = new ArrayList<>();
        if (name.equals("<init>")) {
            for (ConstructorNode k : receiver.redirect().getDeclaredConstructors())
                if (k.isPublic() && fits(k.getParameters(), args)) out.add(k);
            return out;
        }
        Set<ClassNode> seen = new HashSet<>();
        Deque<ClassNode> todo = new ArrayDeque<>();
        todo.add(receiver.redirect());
        if (receiver.isInterface()) todo.add(ClassHelper.OBJECT_TYPE);
        boolean isClassLiteral = receiver.equals(ClassHelper.CLASS_Type) && receiver.getGenericsTypes() != null;
        if (isClassLiteral) todo.add(receiver.getGenericsTypes()[0].getType().redirect());
        while (!todo.isEmpty()) {
            ClassNode c = todo.poll();
            if (c == null || !seen.add(c)) continue;
            for (MethodNode m : c.getMethods(name)) {
                if (!m.isPublic()) continue;
                if (fits(m.getParameters(), args)) out.add(m);
            }
            if (c.getSuperClass() != null) todo.add(c.getSuperClass().redirect());
            for (ClassNode i : c.getInterfaces()) todo.add(i.redirect());
        }
        return out;
    }

    private static boolean fits(Parameter[] ps, ClassNode[] args) {
        int n = ps.length;
        boolean varargs = n > 0 && ps[n - 1].getType().isArray();
        if (args.length != n && !(varargs && args.length >= n - 1)) return false;
        for (int i = 0; i < args.length; i++) {
            ClassNode p = i < n - 1 || !varargs ? ps[Math.min(i, n - 1)].getType()
                    : (args.length == n && args[i] != null && args[i].isArray() ? ps[n - 1].getType() : ps[n - 1].getType().getComponentType());
            if (!compatible(args[i], p)) return false;
        }
        return true;
    }

    private static boolean compatible(ClassNode arg, ClassNode param) {
        if (arg == null || untyped(arg) && !arg.isScript()) return true;
        if (param.equals(ClassHelper.OBJECT_TYPE)) return true;
        if (numeric(param) && numeric(arg)) return true;
        if (arg.getName().equals("groovy.lang.GString") && (param.equals(ClassHelper.STRING_TYPE) || param.getName().equals("java.lang.CharSequence"))) return true;
        if (ClassHelper.isPrimitiveType(param) && ClassHelper.getWrapper(param).equals(arg)) return true;
        if (ClassHelper.isPrimitiveType(arg) && ClassHelper.getWrapper(arg).equals(param)) return true;
        if (arg.equals(ClassHelper.CLOSURE_TYPE) && param.isInterface()) return true; // closure -> SAM coercion
        return StaticTypeCheckingSupport.implementsInterfaceOrIsSubclassOf(arg, param);
    }

    static boolean numeric(ClassNode t) {
        if (t == null) return false;
        ClassNode w = ClassHelper.isPrimitiveType(t) ? ClassHelper.getWrapper(t) : t;
        String n = w.getName();
        return n.equals("java.lang.Integer") || n.equals("java.lang.Long") || n.equals("java.lang.Double")
                || n.equals("java.lang.Float") || n.equals("java.lang.Short") || n.equals("java.lang.Byte")
                || n.equals("java.math.BigDecimal") || n.equals("java.math.BigInteger") || n.equals("java.lang.Number");
    }
}
