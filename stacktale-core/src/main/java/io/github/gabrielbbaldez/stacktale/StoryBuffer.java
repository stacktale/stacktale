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
 * worker thread). Old contexts are evicted by an approximate sample-based LRU size-cap; old
 * entries fall out of the ring and out of the time window.
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
    private static final int EVICTION_SAMPLE_SIZE = 8;

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
    // Sample-based LRU (similar to Redis allkeys-lru) updates lastTouchedNanos on record & read,
    // sampling up to 8 candidate keys on overflow to evict the coldest key.
    private final ConcurrentHashMap<String, ContextHolder> perCorrelation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ContextHolder> perThreadName = new ConcurrentHashMap<>();

    static final class ContextHolder {
        final Deque<StoryEntry> deque = new ArrayDeque<>();
        volatile long lastTouchedNanos = System.nanoTime();

        void touch() {
            lastTouchedNanos = System.nanoTime();
        }
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
            ContextHolder holder = perCorrelation.get(key);
            if (holder != null) holder.touch();
            snapshot = snapshot(holder);
            label = key;
        } else {
            String tk = ThreadKey.of(errorEvent);
            if (tk == null) return new Story(List.of(), "thread unidentified", 0);
            ContextHolder holder = perThreadName.get(tk);
            if (holder != null) holder.touch();
            snapshot = snapshot(holder);
            label = "thread " + tk;
        }
        List<StoryEntry> kept = snapshot.stream().filter(e -> e.epochMillis() >= cutoff).toList();
        int omittedByAge = snapshot.size() - kept.size();
        return new Story(kept, label, omittedByAge);
    }

    // ── internal helpers ────────────────────────────────────────────────────────────────

    /**
     * Appends {@code entry} to the deque for {@code key}, updating {@code lastTouchedNanos} and
     * evicting the coldest sample key if {@code MAX_CONTEXTS} is exceeded.
     */
    private void pushAndEnsure(
            ConcurrentHashMap<String, ContextHolder> map, String key, StoryEntry entry) {
        ContextHolder holder = map.computeIfAbsent(key, k -> new ContextHolder());
        holder.touch();
        synchronized (holder.deque) {
            if (holder.deque.size() >= capacity) holder.deque.pollFirst();
            holder.deque.addLast(entry);
        }
        // Ensure the active key remains present even if another thread evicted it during push
        map.putIfAbsent(key, holder);

        // Approximate LRU: sample candidate keys on overflow and evict the coldest
        if (map.size() > MAX_CONTEXTS) {
            evictColdest(map, key);
        }
    }

    private static void evictColdest(ConcurrentHashMap<String, ContextHolder> map, String activeKey) {
        String coldestKey = null;
        ContextHolder coldestHolder = null;
        long oldestNanos = Long.MAX_VALUE;

        int sampled = 0;
        var iterator = map.entrySet().iterator();
        while (iterator.hasNext() && sampled < EVICTION_SAMPLE_SIZE) {
            var entry = iterator.next();
            String k = entry.getKey();
            ContextHolder v = entry.getValue();
            if (!k.equals(activeKey)) {
                sampled++;
                long t = v.lastTouchedNanos;
                if (t < oldestNanos) {
                    oldestNanos = t;
                    coldestKey = k;
                    coldestHolder = v;
                }
            }
        }

        if (coldestKey != null && coldestHolder != null) {
            map.remove(coldestKey, coldestHolder);
        }
    }

    /** Takes an atomic snapshot of {@code holder}'s deque contents for read-only iteration. */
    private static List<StoryEntry> snapshot(ContextHolder holder) {
        if (holder == null) return List.of();
        synchronized (holder.deque) {
            return new ArrayList<>(holder.deque);
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
