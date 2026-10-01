package io.github.gabrielbbaldez.stacktale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code docs/st-json-1.schema.json} accepts and what it refuses.
 *
 * <p>Three promises, each checked from the integrator's side. Every line stacktale writes (the
 * goldens) is valid. A line that breaks the contract is not, so an integrator who validates gets a
 * real answer instead of a schema that passes everything. And the schema stays open: a member or a
 * line type added later is still valid, because additive changes do not bump the format (§6).
 *
 * <p>Invalid lines are made by breaking one thing in a real rendered line, not written by hand, so
 * each case fails for the reason it names and nothing else.
 */
class StJsonSchemaCasesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN = Path.of("src/test/resources/golden");
    private static final Renderer RENDERER =
            new JsonReportRenderer(ZoneOffset.UTC, Redactor.withDefaults(List.of()));

    private static List<String> goldenLines() throws Exception {
        List<String> lines = new ArrayList<>();
        try (Stream<Path> files = Files.list(GOLDEN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".ndjson")).sorted().toList()) {
                for (String line : Files.readString(f, StandardCharsets.UTF_8).split("\n")) {
                    if (!line.isBlank()) lines.add(line.strip());
                }
            }
        }
        return lines;
    }

    /** A real rendered line, parsed so one thing in it can be broken. */
    private static ObjectNode parse(String rendered) throws Exception {
        return (ObjectNode) MAPPER.readTree(rendered);
    }

    private static String with(String rendered, Consumer<ObjectNode> change) throws Exception {
        ObjectNode node = parse(rendered);
        change.accept(node);
        return MAPPER.writeValueAsString(node);
    }

    private static String report() {
        return RENDERER.render(GoldenFixtures.rich());
    }

    private static String noExceptionReport() {
        return RENDERER.render(GoldenFixtures.noThrowable());
    }

    private static void assertValid(String line) {
        assertThat(StJsonSchema.errors(line)).as(line).isEmpty();
    }

    private static void assertInvalid(String line) {
        assertThat(StJsonSchema.errors(line)).as(line).isNotEmpty();
    }

    // ---- every line stacktale writes is valid --------------------------------------------------

    @Test
    void everyGoldenLineValidates() throws Exception {
        List<String> lines = goldenLines();
        // 5 report goldens + header, session, repeat, storm: a missing golden is not a pass
        assertThat(lines).hasSizeGreaterThanOrEqualTo(9);
        assertThat(lines).allSatisfy(line ->
                assertThat(StJsonSchema.errors(line)).as(line).isEmpty());
    }

    @Test
    void goldensCoverEveryLineType() throws Exception {
        List<String> types = new ArrayList<>();
        for (String line : goldenLines()) types.add(parse(line).get("type").asText());
        assertThat(types).contains("header", "report", "repeat", "session", "storm");
    }

    // ---- lines that break the contract are refused ---------------------------------------------

    @Test
    void aLineWithoutTypeIsInvalid() throws Exception {
        assertInvalid(with(report(), n -> n.remove("type")));
        assertInvalid(with(RENDERER.fileHeader(), n -> n.remove("type")));
    }

    @Test
    void aHeaderNamingAnotherFormatIsInvalid() throws Exception {
        assertInvalid(with(RENDERER.fileHeader(), n -> n.put("format", "st-json/2")));
        assertInvalid(with(RENDERER.fileHeader(), n -> n.put("format", "st/1")));
        assertInvalid(with(RENDERER.fileHeader(), n -> n.remove("format")));
    }

    @Test
    void aReportWithoutErrorIsInvalid() throws Exception {
        assertInvalid(with(report(), n -> n.remove("error")));
        assertInvalid(with(noExceptionReport(), n -> n.remove("error")));
    }

    @Test
    void aCountSentAsAStringIsInvalid() throws Exception {
        assertInvalid(with(RENDERER.renderSummary("a1b2", 3, 1_000_412L), n -> n.put("count", "3")));
        assertInvalid(with(RENDERER.stormLine(17, 30), n -> n.put("suppressed", "17")));
        assertInvalid(with(report(), n -> ((ObjectNode) n.get("recurrence")).put("count", "3")));
    }

    @Test
    void aTimestampOfTheWrongTypeOrShapeIsInvalid() throws Exception {
        assertInvalid(with(report(), n -> n.put("ts", 1_000_412L)));
        assertInvalid(with(RENDERER.sessionMarker(1_000_000L, 4242L), n -> n.put("ts", 1_000_000L)));
        // the format promises fixed millisecond precision and an offset
        assertInvalid(with(report(), n -> n.put("ts", "1970-01-01T00:16:40Z")));
        assertInvalid(with(report(), n -> n.put("ts", "1970-01-01T00:16:40.412")));
    }

    @Test
    void aStackTotalOfTheWrongTypeIsInvalid() throws Exception {
        assertInvalid(with(report(), n -> ((ObjectNode) n.get("stack")).put("total", "32")));
        assertInvalid(with(report(), n -> ((ObjectNode) n.get("stack")).put("total", 32.5)));
    }

    @Test
    void aNoExceptionReportCarryingAStackIsInvalid() throws Exception {
        String stack = parse(report()).get("stack").toString();
        assertInvalid(with(noExceptionReport(), n -> {
            try {
                n.set("stack", MAPPER.readTree(stack));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    /** §7: an optional member that has nothing to say is left out, never sent as null. */
    @ParameterizedTest
    @ValueSource(strings = {"mdc", "fields", "captured", "recurrence", "story", "stack", "env"})
    void anOptionalMemberSentAsNullIsInvalid(String member) throws Exception {
        assertInvalid(with(report(), n -> n.putNull(member)));
    }

    @Test
    void aThrownErrorWithoutItsTypeIsInvalid() throws Exception {
        assertInvalid(with(report(), n -> ((ObjectNode) n.get("error")).remove("type")));
    }

    // ---- the schema stays open to what comes later ---------------------------------------------

    @Test
    void anUnknownMemberIsValid() throws Exception {
        assertValid(with(report(), n -> n.put("spanId", "00f067aa0ba902b7")));
        assertValid(with(report(), n -> ((ObjectNode) n.get("error")).put("code", "E42")));
        assertValid(with(report(), n -> ((ObjectNode) n.get("stack")).putArray("causes").add("x")));
        assertValid(with(RENDERER.fileHeader(), n -> n.put("generator", "stacktale 9.9")));
        assertValid(with(RENDERER.renderSummary("a1b2", 3, 1_000_412L), n -> n.put("window", "5m")));
    }

    @Test
    void anUnknownLineTypeIsValid() {
        assertValid("{\"type\":\"deploy\"}");
        assertValid("{\"type\":\"deploy\",\"build\":\"7e3c1f\",\"ts\":1000,\"anything\":{\"nested\":[1,2]}}");
    }

    /**
     * An unknown type escapes the known types' rules — and only an unknown one. A known type that
     * merely gains a member must still meet every rule it had.
     */
    @Test
    void aKnownTypeWithAnExtraMemberIsStillHeldToItsRules() throws Exception {
        assertInvalid(with(report(), n -> {
            n.put("spanId", "00f067aa0ba902b7");
            n.remove("thread");
        }));
    }
}
