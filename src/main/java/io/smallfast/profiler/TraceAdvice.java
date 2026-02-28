package io.smallfast.profiler;

import net.bytebuddy.asm.Advice;

public final class TraceAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@MethodId int methodId) {
        Profiler.enter(methodId);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        Profiler.exit();
    }
}