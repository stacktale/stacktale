package io.github.gabrielbbaldez.stacktale;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded ring buffers of recent log events. Events carrying a correlation MDC key are
 * grouped by that key (so the story survives thread hops); everything else falls back to a
 * ring keyed by the event's logical thread name (which survives Logback's AsyncAppender
 * worker thread). Old contexts are evicted LRU; old entries fall out of the ring and out of
 * the time window.
 */
final class StoryBuffer {

    private static final int MAX_CONTEXTS = 256;

    private final int capacity;
    private final long windowMillis;
    private final List<String> correlationKeys;
    private final int maxMessageLength;

    // Events are grouped by correlation key when present; otherwise by the event's LOGICAL
    // thread name — NOT the physical thread. Under Logback AsyncAppender every event is
    // processed on one worker thread, so keying on the physical thread would collapse all
    // requests into one ring and mislabel it. event.threadName() is preserved across the
    // hand-off and keeps each origin thread's story separate. Both are bounded LRU maps.
    private final Map<String, Deque<StoryEntry>> perCorrelation = boundedContexts();
    private final Map<String, Deque<StoryEntry>> perThreadName = boundedContexts();

    private static Map<String, Deque<StoryEntry>> boundedContexts() {
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<StoryEntry>> e) {
                return size() > MAX_CONTEXTS;
            }
        };
    }

    StoryBuffer(int capacity, long windowMillis, List<String> correlationKeys, int maxMessageLength) {
        this.capacity = capacity;
        this.windowMillis = windowMillis;
        this.correlationKeys = correlationKeys;
        this.maxMessageLength = maxMessageLength;
    }

    void record(LogEventData event) {
        StoryEntry entry = toEntry(event);
        String key = correlationKey(event);
        if (key != null) {
            synchronized (perCorrelation) {
                push(perCorrelation.computeIfAbsent(key, k -> new ArrayDeque<>()), entry);
            }
        } else {
            String tk = threadKey(event);
            synchronized (perThreadName) {
                push(perThreadName.computeIfAbsent(tk, k -> new ArrayDeque<>()), entry);
            }
        }
    }

    Story storyFor(LogEventData errorEvent) {
        long cutoff = errorEvent.epochMillis() - windowMillis;
        String key = correlationKey(errorEvent);
        List<StoryEntry> snapshot;
        String label;
        if (key != null) {
            synchronized (perCorrelation) {
                Deque<StoryEntry> deque = perCorrelation.get(key);
                snapshot = deque == null ? List.of() : new ArrayList<>(deque);
            }
            label = key;
        } else {
            String tk = threadKey(errorEvent);
            synchronized (perThreadName) {
                Deque<StoryEntry> deque = perThreadName.get(tk);
                snapshot = deque == null ? List.of() : new ArrayList<>(deque);
            }
            label = "thread " + tk;
        }
        List<StoryEntry> kept = snapshot.stream().filter(e -> e.epochMillis() >= cutoff).toList();
        int omittedByAge = snapshot.size() - kept.size();
        return new Story(kept, label, omittedByAge);
    }

    private void push(Deque<StoryEntry> deque, StoryEntry entry) {
        synchronized (deque) {
            if (deque.size() >= capacity) deque.pollFirst();
            deque.addLast(entry);
        }
    }

    private StoryEntry toEntry(LogEventData event) {
        String logger = event.loggerName();
        int dot = logger.lastIndexOf('.');
        if (dot >= 0) logger = logger.substring(dot + 1);
        String msg = String.valueOf(event.formattedMessage());
        if (msg.length() > maxMessageLength) msg = msg.substring(0, maxMessageLength) + "…";
        return new StoryEntry(event.epochMillis(), event.level(), logger, msg);
    }

    /** Logical thread name, with a stable fallback for unnamed (e.g. virtual) threads. */
    private static String threadKey(LogEventData event) {
        String t = event.threadName();
        return (t == null || t.isBlank()) ? "<unnamed>" : t;
    }

    private String correlationKey(LogEventData event) {
        Map<String, String> mdc = event.mdc();
        if (mdc == null || mdc.isEmpty()) return null;
        for (String k : correlationKeys) {
            String v = mdc.get(k);
            if (v != null && !v.isBlank()) return k + "=" + v;
        }
        return null;
    }
}
