package io.github.gabrielbbaldez.stacktale;

/**
 * Dedup verdict for one error occurrence. {@code totalOccurrences} and
 * {@code firstSeenMillis} track the fingerprint across dedup windows (session-scoped) so a
 * fresh report can tell the reader whether this error is brand new or recurring.
 */
record Decision(Kind kind, int count, long lastSeenMillis, int totalOccurrences, long firstSeenMillis) {}
