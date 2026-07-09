package io.github.gabrielbbaldez.stacktale;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Short, stable id for "the same error": exception type + culprit frame + message with
 * volatile parts (numbers, hex) normalized away.
 */
final class Fingerprinter {

    private Fingerprinter() {}

    static String fingerprint(String rootType, String culpritLine, String message) {
        String normalized = nz(rootType) + "|" + nz(culpritLine) + "|"
                + nz(message).replaceAll("(0x[0-9a-fA-F]+|\\d+)", "#");
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x%02x", d[0], d[1]);
        } catch (NoSuchAlgorithmException e) {
            return "0000"; // SHA-1 is always present; keep the never-throw guarantee anyway
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
