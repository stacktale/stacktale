package io.github.gabrielbbaldez.stacktale;

/**
 * The identity a story is filed under when there is no correlation key.
 *
 * <p>Thread <em>name</em> is the right key: it survives Logback's {@code AsyncAppender},
 * where every event is processed on one worker thread and only the recorded name still
 * points at the thread that logged.
 *
 * <p>Unnamed threads break that. {@code Thread} stores {@code ""} when no name is given and
 * the virtual-thread factory supplies none, so a thread from
 * {@code Executors.newVirtualThreadPerTaskExecutor()}, {@code Thread.startVirtualThread} or
 * {@code StructuredTaskScope.fork} reports a blank name. Filing every one of them under a
 * single shared bucket would interleave unrelated requests into one story — in a file
 * written to be pasted at an assistant.
 *
 * <p>Which adapters can hit that, measured rather than assumed: Logback synthesizes a name
 * ({@code virtual-49}) for unnamed virtual threads, so its events never arrive blank. The
 * JUL handler and the JUnit listener pass {@code Thread.currentThread().getName()} straight
 * through, and Log4j2 captures the same, so those do.
 *
 * <p>So a blank name falls back to the physical thread's id — but only while we are still
 * <em>on</em> the thread that logged. The check for that is the current thread's own name
 * being blank too: an {@code AsyncAppender} worker, an executor thread, any thread that
 * would make the id the wrong answer, carries a real name. When the fallback cannot be
 * trusted the key is {@code null}, and the caller attaches no story rather than a wrong one.
 */
final class ThreadKey {

    private ThreadKey() {
    }

    /** A key for this event's thread, or {@code null} when the thread cannot be identified. */
    static String of(LogEventData event) {
        String recorded = event.threadName();
        if (recorded != null && !recorded.isBlank()) return recorded;
        Thread current = Thread.currentThread();
        String currentName = current.getName();
        if (currentName != null && !currentName.isBlank()) return null; // handed off — id would lie
        // getId(), not threadId(): the compile baseline is 17 and threadId() arrived in 19
        return "vt-" + current.getId();
    }
}
