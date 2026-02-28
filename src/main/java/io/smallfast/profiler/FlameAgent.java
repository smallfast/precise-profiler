package io.smallfast.profiler;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.*;

public final class FlameAgent {

    public static void premain(String args, Instrumentation inst) {
        Config cfg = Config.parse(args);

        // expose config to profiler
        Profiler.setDryRun(cfg.dryRun);

        ElementMatcher<TypeDescription> typeMatcher = new ElementMatcher<>() {
            @Override
            public boolean matches(TypeDescription target) {
                return cfg.shouldInstrument(target.getName());
            }
        };

        new AgentBuilder.Default()
                .ignore(
                        nameStartsWith("net.bytebuddy.")
                                .or(nameStartsWith("io.smallfast.profiler."))
                )
                .type(typeMatcher)
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(TraceAdvice.class)
                                        .on(isMethod()
                                                .and(not(isConstructor()))
                                                .and(not(isAbstract()))
                                                .and(not(isNative()))
                                                .and(not(isSynthetic())))
                        )
                )
                .installOn(inst);
    }
}