package us.mcparks.showcheck;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/** Report built from synthetic worker results (no classpath or Groovy compile needed). */
public class ReportTest {

    private static Map<String, Object> result(boolean compiles) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("compiles", compiles);
        r.put("members", new TreeMap<String, List<Object>>());
        return r;
    }

    private static Report report(Map<String, Object> a, Map<String, Object> b, Map<String, String[]> ma, Map<String, String[]> mb) {
        Map<String, List<ShowCheck.Loc>> locs = new TreeMap<>();
        locs.put("id1", Collections.singletonList(new ShowCheck.Loc("", "demo/Show.groovy", "/x/demo/Show.groovy")));
        Map<String, Map<String, Object>> ra = new HashMap<>(), rb = new HashMap<>();
        ra.put("id1", a);
        rb.put("id1", b);
        Report rep = new Report(locs, ra, rb, ma, mb, Collections.emptyMap(), false);
        rep.build();
        return rep;
    }

    private static List<ShowCheck.Finding> internal(Report rep) {
        List<ShowCheck.Finding> out = new ArrayList<>();
        for (ShowCheck.Finding f : rep.findings.getOrDefault("id1", Collections.emptyList()))
            if (f.rule.equals("internal")) out.add(f);
        return out;
    }

    /** Review of !28: a fact-collection crash on B must show up as an internal finding and in the counts. */
    @Test
    public void factsErrorOnBIsReported() {
        Map<String, Object> b = result(true);
        b.put("factsError", "java.lang.StackOverflowError");
        Report rep = report(result(true), b, new HashMap<>(), new HashMap<>());

        List<ShowCheck.Finding> fs = internal(rep);
        assertEquals(1, fs.size());
        assertEquals("warning", fs.get(0).severity);
        assertTrue(fs.get(0).message, fs.get(0).message.startsWith("member analysis incomplete on " + ShowCheck.nameB));
        assertTrue(fs.get(0).message.contains("StackOverflowError"));
        assertEquals(0, rep.factsIncompleteA);
        assertEquals(1, rep.factsIncompleteB);
        assertTrue(rep.summaryLine().contains("member analysis incomplete"));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) rep.toJson(Collections.emptySet(), false).get("summary");
        assertEquals(1, summary.get("memberAnalysisIncompleteB"));
        @SuppressWarnings("unchecked")
        Map<String, Object> file = (Map<String, Object>) ((List<Object>) rep.toJson(Collections.emptySet(), false).get("files")).get(0);
        assertEquals(Boolean.TRUE, file.get("memberAnalysisIncompleteB"));
        assertTrue(rep.markdown(Collections.emptySet(), false).contains("member analysis incomplete"));
    }

    /** The same on A, including for a show that doesn't compile there. */
    @Test
    public void factsErrorOnAIsReportedEvenWhenTheShowDoesNotCompile() {
        Map<String, Object> a = result(false);
        a.put("factsError", "java.lang.NullPointerException");
        Report rep = report(a, result(false), new HashMap<>(), new HashMap<>());

        assertEquals(1, internal(rep).size());
        assertTrue(internal(rep).get(0).message.startsWith("member analysis incomplete on " + ShowCheck.nameA));
        assertEquals(1, rep.factsIncompleteA);
    }

    /** No crash: no internal finding, nothing counted, no extra summary text. */
    @Test
    public void cleanShowHasNoInternalFinding() {
        Report rep = report(result(true), result(true), new HashMap<>(), new HashMap<>());
        assertTrue(rep.findings.isEmpty());
        assertEquals(0, rep.factsIncompleteA + rep.factsIncompleteB);
        assertFalse(rep.summaryLine().contains("member analysis incomplete"));
    }

    /** A member with no status from one worker is reported as `unresolved`, never dropped. */
    @Test
    public void memberWithoutStatusIsUnresolved() {
        Map<String, Object> a = result(true);
        @SuppressWarnings("unchecked")
        Map<String, List<Object>> members = (Map<String, List<Object>>) a.get("members");
        members.put("M|org.bukkit.World|spawnParticle|", Collections.singletonList(7));
        Report rep = report(a, result(true), new HashMap<>(), new HashMap<>());

        List<ShowCheck.Finding> fs = rep.findings.get("id1");
        assertNotNull(fs);
        assertTrue(fs.stream().anyMatch(f -> f.rule.equals("unresolved") && f.line == 7));
    }
}
