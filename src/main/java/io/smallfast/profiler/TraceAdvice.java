package io.smallfast.profiler;

import net.bytebuddy.asm.Advice;

public final class TraceAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin("#t.#m") String method) {
        // NOTE: @Advice.Origin string allocates per call.
        // This is still "working" and correct. We can remove this later by injecting methodId as an int constant.
        int mid = ProfilerIds.idFor(method);
        Profiler.enter(mid);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        Profiler.exit();
    }
}