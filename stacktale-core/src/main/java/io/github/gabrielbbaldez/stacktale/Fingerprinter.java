package io.github.gabrielbbaldez.stacktale;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Short, stable id for "the same error": exception type + culprit frame + message with
 * volatile parts (numbers, hex) normalized away. 8 hex chars (32 bits): with the dedup
 * map tracking up to 1024 distinct errors, 16 bits would collide with ~50% probability
 * (birthday bound) — 32 bits keeps that around 0.01%.
 */
final class Fingerprinter {

    private Fingerprinter() {}

    /**
     * Drops the source line from a culprit frame: {@code Svc.confirm(Svc.java:87)} becomes
     * {@code Svc.confirm(Svc.java)}.
     *
     * <p>The line number makes the id change when nothing about the error did. An agent
     * working the fix-loop adds an import or a guard clause above the throw site, the frame
     * shifts to :88, and the *same unfixed error* mints a new id — so
     * {@code errors_since_last_check} reports it as 🆕 new instead of 🔁 still occurring,
     * precisely when the loop is supposed to be telling the agent whether its fix landed.
     * Repeat counters and {@code seen:} inherit the same reset.
     *
     * <p>The trade: two distinct throw sites in the same method, raising the same exception
     * type with the same message shape, now share an id. That is a fair description of one
     * bug, and a far smaller cost than an identity that dissolves on every edit.
     */
    private static String stableCulprit(String culpritLine) {
        if (culpritLine == null) return "";
        int colon = culpritLine.lastIndexOf(':');
        int close = culpritLine.lastIndexOf(')');
        if (colon < 0 || close < colon) return culpritLine;
        // only strip when what sits between them is a line number
        String tail = culpritLine.substring(colon + 1, close);
        if (tail.isEmpty()) return culpritLine;
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (!Character.isDigit(c) && !(i == 0 && c == '-')) return culpritLine;
        }
        return culpritLine.substring(0, colon) + culpritLine.substring(close);
    }

    static String fingerprint(String rootType, String culpritLine, String message) {
        // Bound what the regex sees before running it. The message can carry a request body,
        // and dedup only needs enough of it to tell two errors apart — hashing megabytes to
        // decide whether we have seen this before is work nobody asked for. This runs per
        // occurrence, before dedup has decided anything, so it is the hottest of the two.
        String head = nz(message);
        if (head.length() > StackDistiller.MAX_FINGERPRINT_MSG) {
            head = head.substring(0, StackDistiller.MAX_FINGERPRINT_MSG);
        }
        String normalized = nz(rootType) + "|" + stableCulprit(nz(culpritLine)) + "|"
                + head.replaceAll("(0x[0-9a-fA-F]+|\\d+)", "#");
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x%02x%02x%02x", d[0], d[1], d[2], d[3]);
        } catch (NoSuchAlgorithmException e) {
            return "00000000"; // SHA-1 is always present; keep the never-throw guarantee anyway
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
