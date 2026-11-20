package us.mcparks.showcheck;

import groovy.json.JsonOutput;
import groovy.lang.GroovyClassLoader;
import groovy.transform.TypeChecked;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.control.*;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One classpath side of the check. Runs in its own JVM whose classpath is
 * (this tool + Groovy) followed by the server/plugin jars of one Minecraft version.
 *
 * For every show it compiles the file the way ShowScript does (GroovyShell semantics: script base
 * class TimecodeDsl, default imports, dynamic Groovy), then again with @TypeChecked + the
 * {@link RelaxedTypeChecking} extension to resolve each call to an exact member. Shows share one
 * GroovyClassLoader at runtime, so when a file only fails because it uses a class another show
 * declares (e.g. Vec3), it is recompiled together with that sibling file.
 * Finally every member any show uses (plus those passed in with --members-in) is checked by
 * reflection against this side's classpath.
 *
 * Usage: Worker --list files.tsv --out out.jsonl --members-out members.tsv [--members-in other.tsv] [--threads N]
 * files.tsv: id \t path \t comma-separated show names (for sibling selection)
 */
public final class Worker {
    static final String TIMECODE_DSL = "us.mcparks.showscript.showscript.groovy.dsl.TimecodeDsl";
    static final String ANALYSIS_DSL = "us.mcparks.showcheck.dsl.AnalysisDsl";
    /** "unable to resolve class Vec3" or, for imports and qualified references, "unable to resolve class demo.Packaged". */
    private static final Pattern UNRESOLVED_CLASS = Pattern.compile("unable to resolve class ([\\w$.]+)\\s*(?:@.*)?", Pattern.DOTALL);
    /** Most sibling files one show may pull in (transitively) before giving up. */
    static final int MAX_SIBLINGS = 16;
    private static final Pattern MISSING_METHOD = Pattern.compile("Cannot find matching method ([^#\\s]+)#(\\w+)\\(");

    static final class Show {
        final String id, path, text;
        final List<String> names;
        Show(String id, String path, String text, List<String> names) {
            this.id = id; this.path = path; this.text = text; this.names = names;
        }
    }

    private static GroovyClassLoader shared;
    private static String stcBase = ANALYSIS_DSL;
    private static NameIndex index;
    // class name -> ids of the shows declaring it; indexed by simple name and, for classes in a package, also by
    // fully qualified name (an `import demo.Packaged` or a qualified reference names the class that way)
    private static final Map<String, List<String>> classDecls = new ConcurrentHashMap<>();
    private static final Set<String> corpusNames = ConcurrentHashMap.newKeySet();
    private static final Map<String, Show> shows = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        String list = null, out = null, membersOut = null, membersIn = null;
        int threads = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--list": list = args[++i]; break;
                case "--out": out = args[++i]; break;
                case "--members-out": membersOut = args[++i]; break;
                case "--members-in": membersIn = args[++i]; break;
                case "--threads": threads = Integer.parseInt(args[++i]); break;
                default: throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        long t0 = System.currentTimeMillis();
        for (String l : Files.readAllLines(Paths.get(list), StandardCharsets.UTF_8)) {
            if (l.trim().isEmpty()) continue;
            String[] p = l.split("\t", -1);
            String text = new String(Files.readAllBytes(Paths.get(p[1])), StandardCharsets.UTF_8);
            shows.put(p[0], new Show(p[0], p[1], text, Arrays.asList(p[2].split(","))));
        }
        if (Worker.class.getClassLoader().getResource(TIMECODE_DSL.replace('.', '/') + ".class") == null)
            throw new IllegalStateException("ShowsRebuild.jar (" + TIMECODE_DSL + ") is not on the classpath");

        List<String> cp = Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator));
        index = NameIndex.build(cp);
        RelaxedTypeChecking.index = index;
        log("indexed " + index.size() + " method names from " + index.classes() + " classes in "
                + (System.currentTimeMillis() - t0) / 1000 + "s");

        shared = new GroovyClassLoader(Worker.class.getClassLoader(), baseConfig(null));
        try (InputStream in = Worker.class.getResourceAsStream("AnalysisDsl.groovy")) {
            String src = new String(readAll(in), StandardCharsets.UTF_8);
            shared.parseClass(src, "AnalysisDsl.groovy");
        } catch (Throwable t) {
            stcBase = TIMECODE_DSL;
            log("AnalysisDsl didn't compile against this ShowsRebuild.jar, using TimecodeDsl for the typed pass: " + t);
        }

        ExecutorService ex = Executors.newFixedThreadPool(threads);
        // pre-pass: classes each show declares, and names shows define (for the untyped-call check)
        List<Future<?>> fs = new ArrayList<>();
        for (Show s : shows.values()) fs.add(ex.submit(() -> prepass(s)));
        for (Future<?> f : fs) f.get();

        PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8));
        Map<String, Boolean> keys = new ConcurrentHashMap<>();
        int[] done = {0};
        fs.clear();
        for (Show s : shows.values()) {
            fs.add(ex.submit(() -> {
                Map<String, Object> r;
                try {
                    r = analyze(s);
                } catch (Throwable t) {
                    r = new LinkedHashMap<>();
                    r.put("id", s.id);
                    r.put("internalError", String.valueOf(t));
                }
                @SuppressWarnings("unchecked")
                Map<String, ?> ms = (Map<String, ?>) r.get("members");
                if (ms != null) for (String k : ms.keySet()) keys.put(k, true);
                String json = JsonOutput.toJson(r);
                synchronized (pw) {
                    pw.println(json);
                    if (++done[0] % 500 == 0) log(done[0] + "/" + shows.size());
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get();
        ex.shutdown();
        pw.close();

        if (membersIn != null)
            for (String l : Files.readAllLines(Paths.get(membersIn), StandardCharsets.UTF_8))
                if (!l.isEmpty()) keys.put(l.split("\t", 2)[0], true);
        ClassLoader cl = Worker.class.getClassLoader();
        try (PrintWriter mw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(membersOut), StandardCharsets.UTF_8))) {
            for (String k : new TreeSet<>(keys.keySet())) {
                MemberCheck m;
                if (k.startsWith("U|")) { // untyped call name: does any class on this side declare it?
                    String n = k.split("\\|", -1)[2];
                    m = null;
                    mw.println(k + "\t" + (index.has(n) ? "ok" : "missing-member") + "\t0\t");
                    continue;
                }
                m = MemberCheck.check(k, cl);
                mw.println(k + "\t" + m.status + "\t" + (m.deprecated ? 1 : 0) + "\t" + m.info.replace('\t', ' ').replace('\n', ' '));
            }
        }
        log("done: " + shows.size() + " shows, " + keys.size() + " members in " + (System.currentTimeMillis() - t0) / 1000 + "s");
    }

    static void log(String s) {
        System.err.println("[show-check worker] " + s);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static CompilerConfiguration baseConfig(String scriptBase) {
        CompilerConfiguration cfg = new CompilerConfiguration();
        if (scriptBase != null) cfg.setScriptBaseClass(scriptBase);
        cfg.setTolerance(1000);
        return cfg;
    }

    private static String clsName(String prefix, String id) {
        return prefix + id.replaceAll("[^A-Za-z0-9]", "").substring(0, Math.min(16, id.length()));
    }

    // ---------- pre-pass ----------

    private static void prepass(Show s) {
        try {
            CompilationUnit cu = new CompilationUnit(baseConfig(TIMECODE_DSL), null, new GroovyClassLoader(shared));
            cu.addSource(clsName("P", s.id) + ".groovy", s.text);
            cu.compile(Phases.CONVERSION);
            for (ModuleNode mn : cu.getAST().getModules()) {
                for (ClassNode cn : mn.getClasses()) {
                    if (!cn.isScript()) {
                        classDecls.computeIfAbsent(cn.getNameWithoutPackage(), k -> Collections.synchronizedList(new ArrayList<>())).add(s.id);
                        if (!cn.getName().equals(cn.getNameWithoutPackage()))
                            classDecls.computeIfAbsent(cn.getName(), k -> Collections.synchronizedList(new ArrayList<>())).add(s.id);
                    }
                    for (MethodNode m : cn.getMethods()) corpusNames.add(m.getName());
                    for (FieldNode f : cn.getFields()) corpusNames.add(f.getName());
                    for (PropertyNode p : cn.getProperties()) corpusNames.add(p.getName());
                    new NameCollector(mn.getContext()).visitClass(cn);
                }
            }
        } catch (Throwable ignored) {
            // syntax errors are reported by the real pass
        }
    }

    /** Names a show defines that dynamic calls may legally target: variables, map keys, exports, assigned properties. */
    static final class NameCollector extends ClassCodeVisitorSupport {
        private final SourceUnit su;
        NameCollector(SourceUnit su) { this.su = su; }
        @Override protected SourceUnit getSourceUnit() { return su; }

        @Override
        public void visitDeclarationExpression(DeclarationExpression e) {
            if (e.isMultipleAssignmentDeclaration()) {
                for (Expression x : e.getTupleExpression().getExpressions())
                    if (x instanceof VariableExpression) corpusNames.add(((VariableExpression) x).getName());
            } else corpusNames.add(e.getVariableExpression().getName());
            super.visitDeclarationExpression(e);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression e) {
            if (e.getOperation().getText().equals("=")) {
                Expression l = e.getLeftExpression();
                if (l instanceof PropertyExpression && ((PropertyExpression) l).getPropertyAsString() != null)
                    corpusNames.add(((PropertyExpression) l).getPropertyAsString());
                if (l instanceof VariableExpression) corpusNames.add(((VariableExpression) l).getName());
            }
            super.visitBinaryExpression(e);
        }

        @Override
        public void visitMapEntryExpression(MapEntryExpression e) {
            if (e.getKeyExpression() instanceof ConstantExpression) corpusNames.add(String.valueOf(((ConstantExpression) e.getKeyExpression()).getValue()));
            super.visitMapEntryExpression(e);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String n = call.getMethodAsString();
            if (n != null && (n.equals("export") || n.equals("setGlobalVariable") || n.equals("put") || n.equals("setProperty"))
                    && call.getArguments() instanceof TupleExpression) {
                List<Expression> a = ((TupleExpression) call.getArguments()).getExpressions();
                if (!a.isEmpty() && a.get(0) instanceof ConstantExpression) corpusNames.add(String.valueOf(((ConstantExpression) a.get(0)).getValue()));
            }
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitClosureExpression(ClosureExpression e) {
            if (e.getParameters() != null) for (Parameter p : e.getParameters()) corpusNames.add(p.getName());
            super.visitClosureExpression(e);
        }
    }

    // ---------- per show ----------

    static Map<String, Object> analyze(Show s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", s.id);
        String main = clsName("S", s.id) + ".groovy";

        List<Show> siblings = new ArrayList<>();
        Unit u1 = compile(s, main, siblings, false);
        // Transitive sibling resolution: while the only errors (in this file or in siblings already added) are
        // "unable to resolve class X" for classes other shows declare, add those shows and compile again. Covers
        // chains (Alpha uses Beta, Beta uses Gamma) and qualified names (import demo.Packaged).
        while (!u1.ok || u1.partial) {
            List<Show> more = siblingsFor(s, siblings, u1);
            if (more.isEmpty() || siblings.size() + more.size() > MAX_SIBLINGS) break;
            siblings.addAll(more);
            u1 = compile(s, main, siblings, false);
        }
        if (!siblings.isEmpty()) {
            List<List<String>> used = new ArrayList<>();
            for (Show x : siblings) used.add(Arrays.asList(x.id, x.path));
            r.put("siblings", used);
        }
        // Success only when the requested phase completed for this file. "partial" = it stopped early because a
        // sibling failed: the file wasn't fully checked, so it isn't counted as compiling (the report says why).
        r.put("compiles", u1.ok && !u1.partial);
        if (u1.partial) {
            r.put("partial", true);
            if (!u1.siblingErrors.isEmpty()) r.put("siblingErrors", u1.siblingErrors);
        }
        if (!u1.errors.isEmpty()) r.put("errors", u1.errors);
        Unit factsFrom = u1;
        List<Object[]> typedUnresolved = Collections.emptyList();
        if (u1.ok) {
            RelaxedTypeChecking.TYPED_UNRESOLVED.get().clear();
            Unit u2 = compile(s, main, siblings, true);
            typedUnresolved = new ArrayList<>(RelaxedTypeChecking.TYPED_UNRESOLVED.get());
            RelaxedTypeChecking.TYPED_UNRESOLVED.get().clear();
            // a typed pass that stopped early because only a sibling failed isn't a clean type check of this file
            r.put("stcClean", u2.ok && !u2.partial);
            if (u2.partial) r.put("stcPartial", true);
            if (!u2.errors.isEmpty()) r.put("stcErrors", u2.errors);
            if (u2.internal != null) r.put("stcInternal", u2.internal);
            // a type-checker crash leaves u2's AST half annotated: take the facts from the plain compile instead
            if (u2.module != null && u2.internal == null) factsFrom = u2;
        }
        if (u1.internal != null) r.put("internal", u1.internal);

        Facts facts = new Facts();
        List<Map<String, Object>> unknown = new ArrayList<>();
        if (factsFrom.module != null) {
            try {
                facts.module(factsFrom.module);
            } catch (Throwable t) {
                // keep what was collected, but the Report flags this show's member analysis as incomplete
                r.put("factsError", String.valueOf(t));
            }
            for (Object[] tu : typedUnresolved)
                facts.members.computeIfAbsent("N|" + tu[1] + "|" + tu[2] + "|", k -> new TreeSet<>()).add((Integer) tu[0]);
            for (Map.Entry<String, TreeSet<Integer>> e : facts.untypedCalls.entrySet()) {
                String n = e.getKey();
                if (!index.has(n) && !corpusNames.contains(n) && n.matches("[A-Za-z_$][\\w$]*")) {
                    for (int line : e.getValue()) unknown.add(unk(line, "?", n, false));
                }
                facts.members.computeIfAbsent("U|?|" + n + "|", k -> new TreeSet<>()).addAll(e.getValue());
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stc = (List<Map<String, Object>>) r.getOrDefault("stcErrors", Collections.emptyList());
        for (Map<String, Object> e : stc) {
            Matcher m = MISSING_METHOD.matcher(String.valueOf(e.get("msg")));
            if (m.find() && !index.has(m.group(2))) unknown.add(unk((Integer) e.get("line"), m.group(1), m.group(2), true));
        }
        if (!unknown.isEmpty()) r.put("unknownMethods", unknown);
        List<Map<String, Object>> ambiguous = new ArrayList<>();
        for (Map.Entry<String, TreeSet<Integer>> e : facts.ambiguousMaterials.entrySet()) {
            String[] k = e.getKey().split("\\|", -1);
            for (int line : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("line", line);
                m.put("how", k[0]);
                m.put("call", k[1]);
                m.put("name", k[2]);
                ambiguous.add(m);
            }
        }
        if (!ambiguous.isEmpty()) r.put("ambiguousMaterials", ambiguous);
        Map<String, Object> ms = new TreeMap<>();
        for (Map.Entry<String, TreeSet<Integer>> e : facts.members.entrySet()) ms.put(e.getKey(), new ArrayList<>(e.getValue()));
        r.put("members", ms);
        return r;
    }

    private static Map<String, Object> unk(int line, String owner, String name, boolean typed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", line);
        m.put("owner", owner);
        m.put("name", name);
        m.put("typed", typed);
        return m;
    }

    /**
     * More siblings to compile with: every error of the last compile, in this file and in the siblings already
     * added, must be "unable to resolve class X" for an X (simple or qualified name) another show declares.
     * Returns the shows to add (none if any error is something else, or nothing new can be resolved).
     */
    static List<Show> siblingsFor(Show s, List<Show> have, Unit last) {
        List<Map<String, Object>> errors = new ArrayList<>(last.errors);
        errors.addAll(last.siblingErrors);
        if (errors.isEmpty() || last.internal != null) return Collections.emptyList();
        Set<String> need = new TreeSet<>();
        for (Map<String, Object> e : errors) {
            Matcher m = UNRESOLVED_CLASS.matcher(String.valueOf(e.get("msg")).trim());
            if (!m.matches()) return Collections.emptyList();
            need.add(m.group(1));
        }
        List<Show> out = new ArrayList<>();
        for (String n : need) {
            List<String> ids = classDecls.get(n);
            if (ids == null && n.contains(".")) ids = classDecls.get(n.substring(n.lastIndexOf('.') + 1));
            if (ids == null) return Collections.emptyList();
            Show best = null;
            int bestScore = Integer.MAX_VALUE;
            boolean alreadyHave = false;
            for (Show h : have) if (ids.contains(h.id)) alreadyHave = true;
            if (alreadyHave) continue; // declared by a sibling we already compile with: its own error, not ours
            for (String id : ids) {
                if (id.equals(s.id)) continue;
                Show c = shows.get(id);
                boolean loads = false;
                for (String nm : s.names) if (!nm.isEmpty() && c.text.contains("\"" + nm + "\"")) loads = true;
                boolean sameDir = parent(c.path).equals(parent(s.path));
                int score = (loads ? 0 : 2) + (sameDir ? 0 : 1);
                if (score < bestScore || (score == bestScore && best != null && c.path.compareTo(best.path) < 0)) {
                    best = c;
                    bestScore = score;
                }
            }
            if (best == null) return Collections.emptyList();
            if (!out.contains(best) && !have.contains(best)) out.add(best);
        }
        return out;
    }

    /** The sibling compiled as source unit {@code unitName}, or null. */
    private static Show siblingFor(String unitName, List<Show> siblings) {
        for (int i = 0; i < siblings.size(); i++)
            if (unitName.equals(clsName("Sib" + i + "_", siblings.get(i).id) + ".groovy")) return siblings.get(i);
        return null;
    }

    private static String parent(String p) {
        int i = p.replace('\\', '/').lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i);
    }

    static final class Unit {
        boolean ok;
        String internal;
        /** compilation stopped early because only a sibling failed: the main file's later phases didn't run */
        boolean partial;
        final List<Map<String, Object>> errors = new ArrayList<>();
        /** errors located in sibling files (with "file"); used to resolve further siblings and to explain partial results */
        final List<Map<String, Object>> siblingErrors = new ArrayList<>();
        ModuleNode module;
    }

    private static Unit compile(Show s, String mainName, List<Show> siblings, boolean typed) {
        CompilerConfiguration cfg = baseConfig(typed ? stcBase : TIMECODE_DSL);
        if (typed) {
            Map<String, Object> params = new HashMap<>();
            params.put("extensions", new String[]{RelaxedTypeChecking.class.getName()});
            cfg.addCompilationCustomizers(new ASTTransformationCustomizer(params, TypeChecked.class));
        }
        GroovyClassLoader gcl = new GroovyClassLoader(shared, cfg);
        CompilationUnit cu = new CompilationUnit(cfg, null, gcl);
        cu.addSource(mainName, s.text);
        int i = 0;
        for (Show sib : siblings) cu.addSource(clsName("Sib" + (i++) + "_", sib.id) + ".groovy", sib.text);
        Unit u = new Unit();
        u.ok = true;
        try {
            cu.compile(typed ? Phases.INSTRUCTION_SELECTION : Phases.CLASS_GENERATION);
        } catch (CompilationFailedException e) {
            u.ok = false;
        } catch (Throwable t) {
            u.ok = false;
            u.internal = t.toString();
        }
        List<? extends Message> msgs = cu.getErrorCollector().getErrors();
        boolean mainHasError = false;
        if (msgs != null) for (Message m : msgs) {
            Map<String, Object> e = new LinkedHashMap<>();
            if (m instanceof SyntaxErrorMessage) {
                SyntaxException se = ((SyntaxErrorMessage) m).getCause();
                String src = se.getSourceLocator();
                if (src != null && !src.equals(mainName) && !siblings.isEmpty()) { // sibling's own problem
                    Map<String, Object> se2 = new LinkedHashMap<>();
                    Show sib = siblingFor(src, siblings);
                    se2.put("id", sib == null ? src : sib.id);
                    se2.put("file", sib == null ? src : sib.path);
                    se2.put("line", se.getLine());
                    se2.put("msg", se.getOriginalMessage().trim());
                    if (u.siblingErrors.size() < 50) u.siblingErrors.add(se2);
                    continue;
                }
                e.put("line", se.getLine());
                e.put("msg", se.getOriginalMessage().trim());
            } else if (m instanceof ExceptionMessage) {
                e.put("line", -1);
                e.put("msg", String.valueOf(((ExceptionMessage) m).getCause()));
            } else {
                StringWriter sw = new StringWriter();
                m.write(new PrintWriter(sw));
                e.put("line", -1);
                e.put("msg", sw.toString().trim());
            }
            mainHasError = true;
            if (u.errors.size() < 200) u.errors.add(e);
        }
        if (!u.ok && !siblings.isEmpty() && !mainHasError && u.internal == null) { // only the sibling failed
            u.ok = true;
            u.partial = true;
        }
        try {
            for (ModuleNode mn : cu.getAST().getModules())
                if (mn.getContext() != null && mainName.equals(mn.getContext().getName())) u.module = mn;
        } catch (Throwable ignored) {
        }
        return u;
    }
}
