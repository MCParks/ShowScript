package us.mcparks.showscript.showscript.groovy;

import org.junit.Test;
import us.mcparks.showscript.showscript.framework.ShowArgs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Groovy shows started without arguments (tracker ryan/1-12-2-to-1-21-4#83). */
public class GroovyShowArgsTest {
    private static final String ZERO_ARG_SHOW = "show { ->\n  ticks(0) { cmd { 'say a' } }\n  ticks(20) { cmd { 'say b' } }\n}\n";
    private static final String ONE_ARG_SHOW = "show { name ->\n  ticks(0) { cmd { \"say $name\" } }\n}\n";

    @Test
    public void zeroArgumentShowStartsWithoutArgs() throws Exception {
        // show start <name> without --args
        GroovyShowConfig cfg = new GroovyShowConfig(ZERO_ARG_SHOW, "test/zero", new ShowArgs((String) null));
        assertEquals(2, cfg.getMap().size());
        // show start <name> through code paths that pass no ShowArgs at all
        assertEquals(2, new GroovyShowConfig(ZERO_ARG_SHOW, "test/zero", null).getMap().size());
    }

    @Test
    public void createShowAcceptsNullArgs() throws Exception {
        // show startasync <name> passes a null ShowArgs to createShow
        GroovyShowConfig cfg = new GroovyShowConfig(ZERO_ARG_SHOW, "test/zero", null);
        assertNotNull(cfg.createShow(0, null));
        assertNotNull(cfg.createShow(0, new ShowArgs((String) null)));
    }

    @Test
    public void showThatNeedsArgumentsStillSaysSo() throws Exception {
        try {
            new GroovyShowConfig(ONE_ARG_SHOW, "test/one", new ShowArgs((String) null));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Expected 1 arguments, but got 0"));
        }
        assertEquals(1, new GroovyShowConfig(ONE_ARG_SHOW, "test/one", new ShowArgs("'x'")).getMap().size());
    }
}
