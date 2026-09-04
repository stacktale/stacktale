package io.github.gabrielbbaldez.stacktale;

/**
 * What this error's history says about the current deploy (#137).
 *
 * <p>The first question of any triage is whether the change just shipped caused the failure, and
 * a report could not answer it: {@code seen:} resets on restart, and the {@code env:} line names
 * the build the error happened <em>on</em>, never the one it started on.
 *
 * @param newInThisBuild first seen on the build now running — the single most actionable thing
 *                       this project can print, and the reason the rest of this record exists
 * @param firstBuild the build it was first seen on
 * @param firstSeenMillis when that was
 * @param buildsAgo how many builds back that was, or {@code -1} when the store no longer has
 *                  that build on file — an evicted or copied-in sidecar legitimately does not,
 *                  and inventing a number would be worse than omitting one
 */
public record Provenance(boolean newInThisBuild, String firstBuild, long firstSeenMillis, int buildsAgo) {
}
