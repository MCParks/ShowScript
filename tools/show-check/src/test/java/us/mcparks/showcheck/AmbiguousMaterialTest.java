package us.mcparks.showcheck;

import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/** {@code ambiguous-material} (ryan/1-12-2-to-1-21-4#221 A33): reused 1.12 material names are flagged. */
public class AmbiguousMaterialTest {

    /** Collects facts from a script after semantic analysis; {@code org.bukkit.Material} is a stand-in enum. */
    private static Facts facts(String script) {
        CompilationUnit cu = new CompilationUnit();
        cu.addSource("org/bukkit/Material.groovy", "package org.bukkit\nenum Material { MELON, STONE\n static Material matchMaterial(String s) { null } }");
        SourceUnit show = cu.addSource("Show.groovy", script);
        cu.compile(Phases.SEMANTIC_ANALYSIS);
        ModuleNode mn = show.getAST();
        Facts f = new Facts();
        f.module(mn);
        return f;
    }

    @Test
    public void helperCallsWithReusedNamesAreRecorded() {
        Facts f = facts(String.join("\n",
                "def material(s) { s }; def item(s, int a = 1) { s }; def blockData(s) { s }",
                "material('MELON')",                 // 2
                "item('grass:0', 3)",                // 3
                "blockData('nether brick')",         // 4
                "material('minecraft:melon')",       // 5: modern id on purpose, not flagged
                "material('STONE')",                 // 6: not reused
                "material(\"360\")",                 // 7: numeric id, not flagged
                "def x = 'MELON'; material(x)"));    // 8: not a constant, not flagged
        Map<String, TreeSet<Integer>> a = f.ambiguousMaterials;
        assertEquals(new TreeSet<>(Collections.singleton(2)), a.get("helper|material|MELON"));
        assertEquals(new TreeSet<>(Collections.singleton(3)), a.get("helper|item|GRASS"));
        assertEquals(new TreeSet<>(Collections.singleton(4)), a.get("helper|blockData|NETHER_BRICK"));
        assertEquals(a.toString(), 3, a.size());
    }

    @Test
    public void bukkitLookupsAndConstantsAreRecorded() {
        Facts f = facts(String.join("\n",
                "import org.bukkit.Material",
                "def m = Material.MELON",            // 2
                "Material.matchMaterial('brick')",   // 3
                "Material.matchMaterial('stone')"));
        assertEquals(new TreeSet<>(Collections.singleton(2)), f.ambiguousMaterials.get("bukkit|Material|MELON"));
        assertEquals(new TreeSet<>(Collections.singleton(3)), f.ambiguousMaterials.get("bukkit|Material.matchMaterial|BRICK"));
        assertEquals(2, f.ambiguousMaterials.size());
    }

    @Test
    public void reportsAWarningWithTheMeaningAndTheModernId() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", 12);
        m.put("how", "helper");
        m.put("call", "material");
        m.put("name", "MELON");
        ShowCheck.Finding helper = Report.ambiguousMaterial(m);
        assertEquals("warning", helper.severity);
        assertEquals("ambiguous-material", helper.rule);
        assertEquals(12, helper.line);
        assertTrue(helper.message, helper.message.contains("the melon slice on every version"));
        assertTrue(helper.suggestion, helper.suggestion.contains("\"minecraft:melon\""));

        m.put("how", "bukkit");
        m.put("call", "Material");
        ShowCheck.Finding bukkit = Report.ambiguousMaterial(m);
        assertTrue(bukkit.message, bukkit.message.startsWith("`Material.MELON` means the melon slice on " + ShowCheck.nameA));
    }
}
