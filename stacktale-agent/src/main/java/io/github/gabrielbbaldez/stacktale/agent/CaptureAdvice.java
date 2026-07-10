package io.github.gabrielbbaldez.stacktale.agent;

import net.bytebuddy.asm.Advice;

/**
 * Inlined into every instrumented app method: when the method exits with a throwable,
 * park its argument values in the {@link CaptureRegistry}. {@code suppress} guarantees
 * the advice itself can never alter the application's control flow.
 */
public final class CaptureAdvice {

    private CaptureAdvice() {}

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Origin("#t") String type,
                            @Advice.Origin("#m") String method,
                            @Advice.AllArguments Object[] args,
                            @Advice.Thrown Throwable thrown) {
        if (thrown != null) {
            CaptureRegistry.record(thrown, type, method, args);
        }
    }
}
