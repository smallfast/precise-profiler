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

        Profiler.setDryRun(cfg.dryRun);
        Profiler.setHistogramEnabled(cfg.histogram);

        ElementMatcher<TypeDescription> typeMatcher = new ElementMatcher<>() {
            @Override
            public boolean matches(TypeDescription target) {
                return cfg.shouldInstrument(target.getName());
            }
        };

        new AgentBuilder.Default()
                .with(AgentBuilder.Listener.StreamWriting.toSystemOut())
                .ignore(
                        nameStartsWith("net.bytebuddy.")
                                .or(nameStartsWith("com.ppb.instrumentation."))
                                .or(nameStartsWith("java."))
                                .or(nameStartsWith("jdk."))
                                .or(nameStartsWith("sun."))
                )
                .type(typeMatcher)
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.withCustomMapping()
                                        .bind(new MethodIdMapping.Factory())
                                        .to(TraceAdvice.class)
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