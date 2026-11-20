package us.mcparks.showcheck;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * show-check: compile every ShowScript 3 (Groovy) show against two server classpaths, e.g. Paper
 * 1.12.2 and Paper 1.21.4, and report what breaks on the second. Report only: the exit code is 0
 * unless --fail-on is given.
 *
 * <pre>
 * show-check --shows [label=]&lt;dir&gt; [--shows ...]
 *            --classpath-112 &lt;jars/dirs, ':'-separated or @file&gt;
 *            --classpath-121 &lt;jars/dirs&gt;
 *            [--format md|json] [--out FILE] [--json FILE] [--focus PATH[,PATH]] [--focus-file FILE]
 *            [--only-focus] [--deprecations] [--java-112 JAVA] [--java-121 JAVA] [--xmx 8g]
 *            [--threads N] [--work DIR] [--fail-on error|warning|never] [--codequality FILE [--path-prefix P]]
 * </pre>
 * The two classpaths must each contain the server jar and ShowsRebuild.jar (TimecodeDsl); add
 * plugin jars (or the plugins/ directory) so shows that use plugin APIs resolve.
 */
public final class ShowCheck {

    static final class Loc {
        final String label, rel, abs;
        Loc(String label, String rel, String abs) { this.label = label; this.rel = rel; this.abs = abs; }
        String display() { return label.isEmpty() ? rel : label + ":" + rel; }
    }

    static final class Finding {
        final int line;
        final String severity, rule, message, suggestion;
        Finding(int line, String severity, String rule, String message, String suggestion) {
            this.line = line; this.severity = severity; this.rule = rule; this.message = message;
            this.suggestion = suggestion == null ? "" : suggestion;
        }
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("line", line); m.put("severity", severity); m.put("rule", rule); m.put("message", message);
            if (!suggestion.isEmpty()) m.put("suggestion", suggestion);
            return m;
        }
    }

    static final String A = "1.12.2", B = "1.21.4";
    static String nameA = A, nameB = B;

    public static void main(String[] args) throws Exception {
        List<String> showRoots = new ArrayList<>();
        String cpA = null, cpB = null, format = "md", out = null, jsonOut = null, javaA = null, javaB = null;
        String xmx = "8g", work = null, failOn = "never", codequality = null, pathPrefix = "";
        int threads = Runtime.getRuntime().availableProcessors();
        Set<String> focus = new LinkedHashSet<>();
        boolean onlyFocus = false, deprecations = false, skipWorkers = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--shows": showRoots.add(args[++i]); break;
                case "--classpath-112": case "--classpath-a": cpA = args[++i]; break;
                case "--classpath-121": case "--classpath-b": cpB = args[++i]; break;
                case "--name-a": nameA = args[++i]; break;
                case "--name-b": nameB = args[++i]; break;
                case "--format": format = args[++i]; break;
                case "--out": out = args[++i]; break;
                case "--json": jsonOut = args[++i]; break;
                case "--java-112": case "--java-a": javaA = args[++i]; break;
                case "--java-121": case "--java-b": javaB = args[++i]; break;
                case "--xmx": xmx = args[++i]; break;
                case "--threads": threads = Integer.parseInt(args[++i]); break;
                case "--work": work = args[++i]; break;
                case "--focus": for (String f : args[++i].split(",")) if (!f.trim().isEmpty()) focus.add(f.trim()); break;
                case "--focus-file":
                    for (String f : Files.readAllLines(Paths.get(args[++i]), StandardCharsets.UTF_8)) if (!f.trim().isEmpty()) focus.add(f.trim());
                    break;
                case "--only-focus": onlyFocus = true; break;
                case "--deprecations": deprecations = true; break;
                case "--rerender": skipWorkers = true; break; // reuse the worker output in --work
                case "--fail-on": failOn = args[++i]; break;
                case "--codequality": codequality = args[++i]; break;
                case "--path-prefix": pathPrefix = args[++i]; break;
                case "-h": case "--help": usage(0); break;
                default: System.err.println("unknown option " + args[i]); usage(2);
            }
        }
        if (showRoots.isEmpty() || cpA == null || cpB == null) usage(2);
        String self = System.getProperty("java.class.path");
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Path wd = work != null ? Files.createDirectories(Paths.get(work)) : Files.createTempDirectory("show-check");

        // 1. collect shows, dedupe by content
        Map<String, List<Loc>> byHash = new TreeMap<>();
        for (String root : showRoots) {
            String label = "", dir = root;
            int eq = root.indexOf('=');
            if (eq > 0 && !new File(root).exists()) { label = root.substring(0, eq); dir = root.substring(eq + 1); }
            Path base = Paths.get(dir);
            if (!Files.isDirectory(base)) throw new IllegalArgumentException("not a directory: " + dir);
            final String lab = label;
            try (Stream<Path> s = Files.walk(base)) {
                for (Path p : s.filter(p -> p.toString().endsWith(".groovy") && Files.isRegularFile(p)).sorted().collect(Collectors.toList())) {
                    String rel = base.relativize(p).toString().replace('\\', '/');
                    byHash.computeIfAbsent(sha256(Files.readAllBytes(p)), k -> new ArrayList<>()).add(new Loc(lab, rel, p.toAbsolutePath().toString()));
                }
            }
        }
        int files = byHash.values().stream().mapToInt(List::size).sum();
        System.err.println("[show-check] " + files + " .groovy files, " + byHash.size() + " unique; work dir " + wd);
        Path list = wd.resolve("files.tsv");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(list, StandardCharsets.UTF_8))) {
            for (Map.Entry<String, List<Loc>> e : byHash.entrySet()) {
                Set<String> names = new LinkedHashSet<>();
                for (Loc l : e.getValue()) names.addAll(showNames(l.rel));
                pw.println(e.getKey() + "\t" + e.getValue().get(0).abs + "\t" + String.join(",", names));
            }
        }

        // 2. one worker JVM per classpath
        if (!skipWorkers) {
            runWorker(javaA != null ? javaA : java, self, classpath(cpA), xmx, threads, list, wd.resolve("a.jsonl"), wd.resolve("a-members.tsv"), null);
            runWorker(javaB != null ? javaB : java, self, classpath(cpB), xmx, threads, list, wd.resolve("b.jsonl"), wd.resolve("b-members.tsv"), wd.resolve("a-members.tsv"));
        }

        // 3. diff
        Map<String, Map<String, Object>> ra = readRuns(wd.resolve("a.jsonl")), rb = readRuns(wd.resolve("b.jsonl"));
        Map<String, String[]> ma = readMembers(wd.resolve("a-members.tsv")), mb = readMembers(wd.resolve("b-members.tsv"));
        Map<String, String> renames = loadRenames();
        Report rep = new Report(byHash, ra, rb, ma, mb, renames, deprecations);
        rep.build();

        Set<String> focusIds = new HashSet<>();
        for (Map.Entry<String, List<Loc>> e : byHash.entrySet())
            for (Loc l : e.getValue()) for (String f : focus) if (matchesFocus(l, f)) focusIds.add(e.getKey());

        String json = JsonOutput.prettyPrint(JsonOutput.toJson(rep.toJson(focusIds, onlyFocus)));
        String main = format.equals("json") ? json : rep.markdown(focusIds, onlyFocus);
        if (out == null) System.out.print(main);
        else Files.write(Paths.get(out), main.getBytes(StandardCharsets.UTF_8));
        if (jsonOut != null) Files.write(Paths.get(jsonOut), json.getBytes(StandardCharsets.UTF_8));
        if (codequality != null)
            Files.write(Paths.get(codequality), JsonOutput.prettyPrint(JsonOutput.toJson(rep.codeQuality(pathPrefix))).getBytes(StandardCharsets.UTF_8));
        System.err.println("[show-check] " + rep.summaryLine());

        int worst = rep.worst(focusIds, onlyFocus); // 2 error, 1 warning, 0 none
        if ((failOn.equals("error") && worst >= 2) || (failOn.equals("warning") && worst >= 1)) System.exit(1);
    }

    static boolean matchesFocus(Loc l, String f) {
        String rel = l.rel, abs = l.abs.replace('\\', '/');
        return f.equals(rel) || f.equals(l.display()) || abs.endsWith("/" + f) || rel.endsWith("/" + f);
    }

    static void usage(int code) {
        System.err.println("usage: show-check --shows [label=]<dir> [--shows ...] --classpath-112 <jars|dirs|@file> --classpath-121 <jars|dirs|@file>\n"
                + "       [--format md|json] [--out FILE] [--json FILE] [--focus PATH,...] [--focus-file FILE] [--only-focus]\n"
                + "       [--deprecations] [--java-112 JAVA] [--java-121 JAVA] [--xmx 8g] [--threads N] [--work DIR]\n"
                + "       [--fail-on error|warning|never] [--name-a 1.12.2 --name-b 1.21.4]\n"
                + "       [--codequality FILE [--path-prefix shows/]]   (GitLab Code Quality report, for the MR widget)\n"
                + "Each classpath needs the server jar and ShowsRebuild.jar; directories are searched for *.jar recursively.");
        System.exit(code);
    }

    static List<String> showNames(String rel) {
        String n = rel.endsWith(".groovy") ? rel.substring(0, rel.length() - 7) : rel;
        List<String> out = new ArrayList<>();
        out.add(n);
        if (n.startsWith("Shows/")) n = n.substring(6);
        out.add(n);
        int i = n.indexOf('/');
        if (i > 0 && !n.startsWith("shared/")) out.add(n.substring(i + 1)); // technicians repo: shows/<server>/x -> x
        return out;
    }

    static String classpath(String spec) throws IOException {
        List<String> entries = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        if (spec.startsWith("@")) {
            for (String l : Files.readAllLines(Paths.get(spec.substring(1)), StandardCharsets.UTF_8)) if (!l.trim().isEmpty()) parts.add(l.trim());
        } else parts.addAll(Arrays.asList(spec.split(File.pathSeparator)));
        for (String p : parts) {
            File f = new File(p);
            if (f.isDirectory()) {
                try (Stream<Path> s = Files.walk(f.toPath())) {
                    s.filter(x -> x.toString().endsWith(".jar")).map(Path::toString).sorted().forEach(entries::add);
                }
            } else if (f.isFile()) entries.add(f.getAbsolutePath());
            else System.err.println("[show-check] classpath entry not found: " + p);
        }
        // ShowsRebuild.jar shades its own Groovy; the tool's Groovy comes first anyway, but keep it first among
        // the shows' jars so TimecodeDsl resolves from it.
        entries.sort(Comparator.comparing((String e) -> !new File(e).getName().toLowerCase().startsWith("showsrebuild")));
        return String.join(File.pathSeparator, entries);
    }

    static void runWorker(String java, String self, String cp, String xmx, int threads, Path list, Path out, Path members, Path membersIn)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(Arrays.asList(java, "-Xmx" + xmx, "-cp", self + File.pathSeparator + cp,
                Worker.class.getName(), "--list", list.toString(), "--out", out.toString(), "--members-out", members.toString(),
                "--threads", String.valueOf(threads)));
        if (membersIn != null) { cmd.add("--members-in"); cmd.add(membersIn.toString()); }
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        int rc = p.waitFor();
        if (rc != 0) throw new IllegalStateException("worker failed (exit " + rc + "): " + String.join(" ", cmd.subList(0, 3)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> readRuns(Path p) throws IOException {
        Map<String, Map<String, Object>> m = new HashMap<>();
        JsonSlurper js = new JsonSlurper();
        for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (l.isEmpty()) continue;
            Map<String, Object> r = (Map<String, Object>) js.parseText(l);
            m.put((String) r.get("id"), r);
        }
        return m;
    }

    static Map<String, String[]> readMembers(Path p) throws IOException {
        Map<String, String[]> m = new HashMap<>();
        for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (l.isEmpty()) continue;
            String[] f = l.split("\t", -1);
            m.put(f[0], new String[]{f[1], f[2], f.length > 3 ? f[3] : ""});
        }
        return m;
    }

    static Map<String, String> loadRenames() throws IOException {
        Map<String, String> m = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ShowCheck.class.getResourceAsStream("renames.tsv"), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (l.startsWith("#") || l.trim().isEmpty()) continue;
                String[] f = l.split("\t");
                if (f.length >= 3) m.put(f[0] + "\t" + f[1], f[2]);
            }
        }
        return m;
    }

    static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
