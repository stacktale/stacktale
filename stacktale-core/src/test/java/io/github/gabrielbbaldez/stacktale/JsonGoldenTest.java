package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * st-json/1 pinned byte for byte, from the same reports as the st/1 goldens.
 *
 * <p>The text format has had golden files since it shipped; the JSON format had none, so a
 * renderer change could reorder, rename or drop a member and every test would stay green while
 * every integrator's parser broke. These files are what the renderer writes today. A diff here
 * is a change to the format (FORMAT.md §6), not a test to update.
 *
 * <p>On a mismatch the actual bytes are written to {@code target/golden-actual/} so the change
 * can be read as a diff. Nothing ever overwrites a golden.
 */
class JsonGoldenTest {

    private final Renderer renderer = new JsonReportRenderer(ZoneOffset.UTC, Redactor.withDefaults(List.of()));

    private static void assertGolden(String name, String rendered) throws Exception {
        Path file = Path.of("src/test/resources/golden/" + name);
        String golden = Files.exists(file)
                ? Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n")
                : "";
        if (!rendered.equals(golden)) {
            Path actual = Path.of("target/golden-actual/" + name);
            Files.createDirectories(actual.getParent());
            Files.writeString(actual, rendered, StandardCharsets.UTF_8);
        }
        assertThat(rendered).isEqualTo(golden);
    }

    @Test
    void fullReportMatchesGolden() throws Exception {
        assertGolden("full-report.ndjson", renderer.render(GoldenFixtures.full()));
    }

    @Test
    void noThrowableReportMatchesGolden() throws Exception {
        assertGolden("no-throwable.ndjson", renderer.render(GoldenFixtures.noThrowable()));
    }

    @Test
    void richReportMatchesGolden() throws Exception {
        assertGolden("rich-report.ndjson", renderer.render(GoldenFixtures.rich()));
    }

    @Test
    void reproReportMatchesGolden() throws Exception {
        assertGolden("repro-report.ndjson", renderer.render(GoldenFixtures.repro()));
    }

    /** The only golden that reaches {@code firstSeen} and {@code stack.suppressed}. */
    @Test
    void provenanceReportMatchesGolden() throws Exception {
        assertGolden("provenance-report.ndjson", renderer.render(GoldenFixtures.provenance()));
    }

    /** The four non-report line types, in the order a file meets them. */
    @Test
    void headerSessionRepeatAndStormLinesMatchGolden() throws Exception {
        String lines = renderer.fileHeader()
                + renderer.sessionMarker(1_000_000L, 4242L)
                + renderer.renderSummary("a1b2", 3, 1_000_412L)
                + renderer.stormLine(17, 30);
        assertGolden("lines.ndjson", lines);
    }

    /** NDJSON: each entry is exactly one line, so a reader can split on {@code \n} and nothing else. */
    @Test
    void everyEntryIsOneLineEndingInNewline() {
        for (Report r : GoldenFixtures.all()) {
            String rendered = renderer.render(r);
            assertThat(rendered).endsWith("\n");
            assertThat(rendered.substring(0, rendered.length() - 1)).doesNotContain("\n", "\r");
        }
    }
}
