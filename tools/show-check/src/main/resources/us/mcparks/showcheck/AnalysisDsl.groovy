package us.mcparks.showcheck.dsl

import us.mcparks.showscript.showscript.groovy.dsl.ShowActionDsl
import us.mcparks.showscript.showscript.groovy.dsl.TimecodeDsl

/**
 * Analysis-only script base class, used only for the type-checked pass.
 *
 * At runtime ShowScript runs every ticks()/seconds() closure with a ShowActionDsl delegate and
 * DELEGATE_FIRST, so cmd/text/show/startSelf resolve there. TimecodeDsl declares no @DelegatesTo,
 * so a static checker can't see that. These overrides only add the metadata; they call super.
 * Compiled at run time against whichever ShowsRebuild.jar is on the checked classpath.
 */
abstract class AnalysisDsl extends TimecodeDsl {
    @Override
    void ticks(int tick, @DelegatesTo(value = ShowActionDsl, strategy = Closure.DELEGATE_FIRST) Closure<?> c) {
        super.ticks(tick, c)
    }

    @Override
    void ticks(Map<String, ?> o, int tick, @DelegatesTo(value = ShowActionDsl, strategy = Closure.DELEGATE_FIRST) Closure<?> c) {
        super.ticks(o, tick, c)
    }

    @Override
    void seconds(double s, @DelegatesTo(value = ShowActionDsl, strategy = Closure.DELEGATE_FIRST) Closure<?> c) {
        super.seconds(s, c)
    }

    @Override
    void seconds(Map<String, ?> o, double s, @DelegatesTo(value = ShowActionDsl, strategy = Closure.DELEGATE_FIRST) Closure<?> c) {
        super.seconds(o, s, c)
    }
}
