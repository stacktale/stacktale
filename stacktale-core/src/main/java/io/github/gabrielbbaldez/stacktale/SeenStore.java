package io.github.gabrielbbaldez.stacktale;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers which build each error id was first seen on, so a report can answer the first
 * question of any triage: <em>did my change cause this?</em> (#137)
 *
 * <p>Nothing else in stacktale survives a restart. {@code seen:} is session-scoped by design, so
 * "3× this session" says nothing about whether the error predates the deploy, and the {@code
 * env:} line names the build the error happened <em>on</em>, never the build it started on.
 *
 * <p>Strictly local: a sibling file beside the report, no network, no account. That is a large
 * part of why anyone adopts this, and it is also what makes the feature cheap — the id already
 * survives edits to the source (it is fingerprinted on the culprit frame without its line
 * number), which is the only reason cross-build history means anything.
 *
 * <p>Never fatal. A missing, unreadable, corrupt or unwritable sidecar degrades to no
 * provenance line at all; it is enrichment, and enrichment does not get to cost a report.
 *
 * <h2>Format</h2>
 *
 * <p>One record per line, appended:
 *
 * <pre>
 * b &lt;build&gt; &lt;firstSeenMillis&gt;              a build this application has run as
 * e &lt;id&gt; &lt;build&gt; &lt;firstSeenMillis&gt;        an error id, and the build it was first seen on
 * </pre>
 *
 * <p>Lines rather than JSON because {@code stacktale-core} has no JSON parser and a report must
 * not depend on one — and because an append is atomic enough to survive a kill, where a
 * rewritten document is not. An unparseable line is skipped, exactly as {@code st-json/1}
 * requires of its readers.
 */
final class SeenStore {

    /** Distinct builds kept. Enough to answer "how many deploys ago" for any useful window. */
    private static final int MAX_BUILDS = 50;
    /**
     * Distinct error ids kept. Bounded like every other piece of state here: a file that grows
     * with distinct errors forever is a slow leak, and the oldest ids are the least useful —
     * an error nobody has seen in two thousand fingerprints is not the one being triaged.
     */
    private static final int MAX_ERRORS = 2_000;

    private final Path file;
    private final String build;
    private final java.util.function.BiConsumer<String, Throwable> warn;

    /** build -> first seen, insertion-ordered oldest-first. */
    private final Map<String, Long> builds = new LinkedHashMap<>();
    /** error id -> the build it was first seen on, and when. Access-ordered so eviction is LRU. */
    private final Map<String, Entry> errors = new LinkedHashMap<>(64, 0.75f, true);

    private record Entry(String build, long firstSeenMillis) {
    }

    private boolean usable = true;
    private boolean warned;

    private SeenStore(Path file, String build, java.util.function.BiConsumer<String, Throwable> warn) {
        this.file = file;
        this.build = build;
        this.warn = warn;
    }

    /**
     * Opens the sidecar beside {@code reportFile}, or returns {@code null} when provenance is off
     * or cannot work.
     *
     * <p>A blank build id is not a failure but it is not provenance either: without something to
     * compare, every run looks like the same build and "NEW in this build" would be a lie.
     */
    static SeenStore open(Path reportFile, String build,
                          java.util.function.BiConsumer<String, Throwable> warn) {
        if (reportFile == null || build == null || build.isBlank()) {
            return null;
        }
        try {
            Path sidecar = reportFile.resolveSibling(reportFile.getFileName() + ".seen");
            SeenStore store = new SeenStore(sidecar, build.trim(), warn);
            store.load();
            return store;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Records that {@code id} was seen now, and says what the report should print.
     *
     * <p>The first call for an unknown id is the one that matters: it is written through to disk
     * straight away, because the interesting case is a process that crashes shortly after
     * producing the error, and a record kept only in memory would be lost exactly then.
     */
    synchronized Provenance record(String id, long nowMillis) {
        Entry existing = errors.get(id);
        if (existing != null) {
            return provenance(existing);
        }
        Entry entry = new Entry(build, nowMillis);
        errors.put(id, entry);
        evict();
        append("e " + id + " " + build + " " + nowMillis);
        return provenance(entry);
    }

    /** Notes the current build, so "how many builds ago" has something to count against. */
    synchronized void noteBuild(long nowMillis) {
        if (builds.containsKey(build)) {
            return;
        }
        builds.put(build, nowMillis);
        evict();
        append("b " + build + " " + nowMillis);
    }

    private Provenance provenance(Entry entry) {
        boolean isNew = build.equals(entry.build());
        return new Provenance(isNew, entry.build(), entry.firstSeenMillis(),
                isNew ? 0 : buildsSince(entry.build()));
    }

    /**
     * How many builds ago {@code firstBuild} was, or {@code -1} when this store never saw it.
     *
     * <p>A store that has been evicted, or one carried over from another machine, legitimately
     * has an error whose first build is not in its build list. Counting from an unknown position
     * would invent a number, so the renderer is told there is none.
     */
    private int buildsSince(String firstBuild) {
        List<String> order = new ArrayList<>(builds.keySet());
        int at = order.indexOf(firstBuild);
        return at < 0 ? -1 : order.size() - 1 - at;
    }

    private void evict() {
        while (errors.size() > MAX_ERRORS) {
            errors.remove(errors.keySet().iterator().next());
        }
        while (builds.size() > MAX_BUILDS) {
            builds.remove(builds.keySet().iterator().next());
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // Unreadable: run without provenance rather than without reports — but say so. The
            // user switched this on; silently giving them a report with no `first seen:` line
            // looks like the feature does nothing.
            degrade(e);
            return;
        }
        for (String line : lines) {
            String[] parts = line.strip().split(" ");
            try {
                if (parts.length == 3 && "b".equals(parts[0])) {
                    builds.put(parts[1], Long.parseLong(parts[2]));
                } else if (parts.length == 4 && "e".equals(parts[0])) {
                    errors.put(parts[1], new Entry(parts[2], Long.parseLong(parts[3])));
                }
            } catch (RuntimeException malformed) {
                // a torn line from a kill mid-append: skip it, keep the rest — the same rule
                // st-json/1 puts on its own readers
            }
        }
        evict();
        if (lines.size() > (MAX_ERRORS + MAX_BUILDS) * 2) {
            compact();
        }
    }

    /** Rewrites the file from memory once the append log has grown past what it represents. */
    private void compact() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> b : builds.entrySet()) {
            out.append("b ").append(b.getKey()).append(' ').append(b.getValue()).append('\n');
        }
        for (Map.Entry<String, Entry> e : errors.entrySet()) {
            out.append("e ").append(e.getKey()).append(' ').append(e.getValue().build())
                    .append(' ').append(e.getValue().firstSeenMillis()).append('\n');
        }
        try {
            Path pending = file.resolveSibling(file.getFileName() + ".compacting");
            Files.writeString(pending, out.toString(), StandardCharsets.UTF_8);
            Files.move(pending, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            degrade(e); // keep serving from memory; the file stays as it was
        }
    }

    private void append(String line) {
        if (!usable) {
            return;
        }
        try {
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            degrade(e);
        }
    }

    /**
     * Stops writing after a failure, and says so once.
     *
     * <p>In-memory answers stay correct for the rest of this run; only the memory across restarts
     * is lost. Warning once matters because the failure is usually permanent — a read-only
     * directory does not become writable between two errors — and a warning per report would
     * flood the very log this is meant to make readable.
     */
    private void degrade(Throwable t) {
        usable = false;
        if (!warned && warn != null) {
            warned = true;
            warn.accept("stacktale could not write " + file.getFileName()
                    + "; provenance stays in memory for this run only", t);
        }
    }
}
