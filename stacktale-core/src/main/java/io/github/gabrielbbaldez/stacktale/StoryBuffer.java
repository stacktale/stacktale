package io.github.gabrielbbaldez.stacktale;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded ring buffers of recent log events. Events carrying a correlation MDC key are
 * grouped by that key (so the story survives thread hops); everything else falls back to a
 * ring keyed by the event's logical thread name (which survives Logback's AsyncAppender
 * worker thread). Old contexts are evicted by a probabilistic size-cap; old entries fall
 * out of the ring and out of the time window.
 *
 * <p><b>Concurrency model</b>: Both context maps are {@link ConcurrentHashMap}s — no global
 * monitor is taken on the map itself. {@code computeIfAbsent} is atomic, so concurrent
 * callers for the same key race to insert at most once. Each per-context deque carries its
 * own lightweight {@code synchronized} guard; this gives fine-grained striped locking
 * instead of a single global bottleneck. On JDK 21+ with virtual threads this avoids
 * pinning carrier threads on a hot global monitor.</p>
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
    // hand-off and keeps each origin thread's story separate.
    //
    // ConcurrentHashMap replaces the previous access-ordered LinkedHashMap whose get()
    // mutated internal state and therefore required a global exclusive lock on every read.
    // Eviction skips the newly added/active key so a just-recorded event is never lost.
    private final ConcurrentHashMap<String, Deque<StoryEntry>> perCorrelation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<StoryEntry>> perThreadName = new ConcurrentHashMap<>();

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
            pushAndEnsure(perCorrelation, key, entry);
        } else {
            String tk = ThreadKey.of(event);
            // an unidentifiable thread gets no bucket: a shared one would mix requests
            if (tk == null) return;
            pushAndEnsure(perThreadName, tk, entry);
        }
    }

    Story storyFor(LogEventData errorEvent) {
        long cutoff = errorEvent.epochMillis() - windowMillis;
        String key = correlationKey(errorEvent);
        List<StoryEntry> snapshot;
        String label;
        if (key != null) {
            Deque<StoryEntry> deque = perCorrelation.get(key);
            snapshot = snapshot(deque);
            label = key;
        } else {
            String tk = ThreadKey.of(errorEvent);
            if (tk == null) return new Story(List.of(), "thread unidentified", 0);
            Deque<StoryEntry> deque = perThreadName.get(tk);
            snapshot = snapshot(deque);
            label = "thread " + tk;
        }
        List<StoryEntry> kept = snapshot.stream().filter(e -> e.epochMillis() >= cutoff).toList();
        int omittedByAge = snapshot.size() - kept.size();
        return new Story(kept, label, omittedByAge);
    }

    // ── internal helpers ────────────────────────────────────────────────────────────────

    /**
     * Appends {@code entry} to the deque for {@code key}, ensuring the key remains in
     * {@code map} and evicting a distinct candidate key if {@code MAX_CONTEXTS} is exceeded.
     */
    private void pushAndEnsure(
            ConcurrentHashMap<String, Deque<StoryEntry>> map, String key, StoryEntry entry) {
        Deque<StoryEntry> deque = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            if (deque.size() >= capacity) deque.pollFirst();
            deque.addLast(entry);
        }
        // Ensure the active key remains present even if another thread evicted it during push
        map.putIfAbsent(key, deque);

        // Evict a different key if capacity exceeds MAX_CONTEXTS
        if (map.size() > MAX_CONTEXTS) {
            var iterator = map.keySet().iterator();
            while (iterator.hasNext()) {
                String candidate = iterator.next();
                if (!candidate.equals(key)) {
                    map.remove(candidate);
                    break;
                }
            }
        }
    }

    /** Takes an atomic snapshot of {@code deque} contents for read-only iteration. */
    private static List<StoryEntry> snapshot(Deque<StoryEntry> deque) {
        if (deque == null) return List.of();
        synchronized (deque) {
            return new ArrayList<>(deque);
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
