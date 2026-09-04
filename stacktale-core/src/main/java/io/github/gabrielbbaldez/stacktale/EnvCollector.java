package io.github.gabrielbbaldez.stacktale;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Best-effort environment line: app name/version, git sha, java version, profile, os.
 * Sources in priority order: {@code stacktale.app.*} system properties, Spring Boot's
 * {@code META-INF/build-info.properties}, then {@code git.properties} for the sha.
 * Collected once, cached, and never allowed to fail the pipeline.
 */
final class EnvCollector {

    private final ClassLoader classLoader;
    private final String configuredAppName, configuredAppVersion;
    private volatile String cached;
    private volatile String cachedBuildId;

    EnvCollector(ClassLoader classLoader, String configuredAppName, String configuredAppVersion) {
        this.classLoader = classLoader;
        this.configuredAppName = configuredAppName;
        this.configuredAppVersion = configuredAppVersion;
    }

    String envLine() {
        String line = cached;
        if (line == null) {
            try {
                line = build();
            } catch (Throwable t) {
                // env info is best-effort: cache a minimal line so one bad metadata file
                // can never keep killing full reports for the lifetime of the app
                line = "app=? | java " + System.getProperty("java.version", "?");
            }
            cached = line;
        }
        return line;
    }

    /**
     * What identifies this build, for provenance (#137): the git sha when there is one, else the
     * application version, else empty.
     *
     * <p>Distinct from the {@code env:} line, which is for a human to read. This is a key — it
     * decides whether two runs are the same build, so the most specific value available wins and
     * an absent one is empty rather than the line's {@code ?} placeholder.
     */
    String buildId() {
        String id = cachedBuildId;
        if (id == null) {
            try {
                Properties git = load("git.properties");
                Properties buildInfo = load("META-INF/build-info.properties");
                id = firstNonBlank(
                        System.getProperty("stacktale.app.build"),
                        git.getProperty("git.commit.id.abbrev"),
                        System.getProperty("stacktale.app.version"),
                        buildInfo.getProperty("build.version"),
                        configuredAppVersion,
                        "");
            } catch (Throwable t) {
                id = ""; // provenance is enrichment; never let it cost a report
            }
            cachedBuildId = id;
        }
        return id;
    }

    private String build() {
        Properties buildInfo = load("META-INF/build-info.properties");
        Properties git = load("git.properties");

        String name = firstNonBlank(
                System.getProperty("stacktale.app.name"),
                buildInfo.getProperty("build.name"),
                configuredAppName,
                "?"
        );
        String version = firstNonBlank(
                System.getProperty("stacktale.app.version"),
                buildInfo.getProperty("build.version"),
                configuredAppVersion,
                ""
        );
        String sha = firstNonBlank(git.getProperty("git.commit.id.abbrev"), "");
        String profile = firstNonBlank(System.getProperty("spring.profiles.active"),
                System.getenv("SPRING_PROFILES_ACTIVE"), System.getenv("APP_ENV"), "");
        String java = System.getProperty("java.version", "?");
        String os = System.getProperty("os.name", "?").split(" ")[0].toLowerCase();

        StringBuilder sb = new StringBuilder("app=").append(name);
        if (!version.isEmpty()) sb.append(' ').append(version);
        if (!sha.isEmpty()) sb.append(" (git ").append(sha).append(')');
        sb.append(" | java ").append(java);
        if (!profile.isEmpty()) sb.append(" | profile=").append(profile);
        sb.append(" | ").append(os);
        return sb.toString();
    }

    private Properties load(String resource) {
        Properties p = new Properties();
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {
            // not just IOException: Properties.load throws IllegalArgumentException on
            // malformed \\uXXXX escapes; env info is best-effort either way
        }
        return p;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
