package io.github.gabrielbbaldez.stacktale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@code docs/st-json-1.schema.json} in step with {@link JsonReportRenderer}.
 *
 * <p>The schema is open on purpose: integrators must accept members it does not know, so
 * validation alone would pass a renderer that started writing an undocumented member. This is
 * the closed check, and it lives on the test side only. Every member the renderer writes, at
 * every depth, must be declared under {@code properties} in the schema; a new member fails here
 * until the schema (and FORMAT.md §7) say what it is.
 */
class StJsonSchemaDriftTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonReportRenderer renderer =
            new JsonReportRenderer(ZoneOffset.UTC, Redactor.withDefaults(List.of()));

    /** One of every line the renderer writes, every report fixture included. */
    private List<String> everyLine() {
        List<String> lines = new ArrayList<>();
        lines.add(renderer.fileHeader());
        GoldenFixtures.all().forEach(r -> lines.add(renderer.render(r)));
        lines.add(renderer.renderSummary("a1b2", 47, 1_000_000L));
        lines.add(renderer.sessionMarker(1_000_000L, 4242L));
        lines.add(renderer.stormLine(12, 100));
        return lines;
    }

    @Test
    void everyMemberTheRendererWritesIsDeclaredInTheSchema() throws Exception {
        List<String> undeclared = new ArrayList<>();
        for (String line : everyLine()) {
            undeclared.addAll(undeclaredMembers(MAPPER.readTree(line)));
        }

        assertThat(undeclared).as("members written by JsonReportRenderer but absent from %s", StJsonSchema.FILE)
                .isEmpty();
    }

    @Test
    void everyLineTheRendererWritesValidates() {
        for (String line : everyLine()) {
            assertThat(StJsonSchema.errors(line)).as(line).isEmpty();
        }
    }

    /**
     * The guard catches what it is for. A member the schema does not declare is valid (the
     * schema is open) and still reported here, wherever it sits.
     */
    @Test
    void anUndeclaredMemberIsCaughtAtAnyDepth() throws Exception {
        ObjectNode line = (ObjectNode) MAPPER.readTree(renderer.render(GoldenFixtures.rich()));
        line.put("severity", "high");
        ((ObjectNode) line.get("error")).put("hint", "check the gateway");
        ((ObjectNode) line.get("story").get("events").get(0)).put("spanId", "b7");
        ((ObjectNode) line.get("error").get("culprit")).put("line", 44);

        assertThat(StJsonSchema.errors(line.toString())).isEmpty();
        assertThat(undeclaredMembers(line)).containsExactlyInAnyOrder(
                "report.severity", "report.error.hint", "report.story.events[0].spanId",
                "report.error.culprit.line");
    }

    @Test
    void aLineTypeTheSchemaDoesNotDescribeIsCaught() throws Exception {
        assertThat(undeclaredMembers(MAPPER.readTree("{\"type\":\"heartbeat\",\"ts\":\"x\"}")))
                .containsExactly("heartbeat (line type)");
    }

    /** Member paths in {@code line} with no {@code properties} entry in the schema for its type. */
    private static List<String> undeclaredMembers(JsonNode line) {
        List<String> out = new ArrayList<>();
        String type = line.path("type").asText();
        JsonNode def = StJsonSchema.tree().path("$defs").path(type);
        if (def.isMissingNode()) {
            out.add(type + " (line type)");
            return out;
        }
        walk(line, def, type, out);
        return out;
    }

    private static void walk(JsonNode value, JsonNode schema, String path, List<String> out) {
        schema = resolve(schema);
        if (value.isObject()) {
            JsonNode properties = schema.path("properties");
            JsonNode map = schema.path("additionalProperties");
            for (Map.Entry<String, JsonNode> member : value.properties()) {
                String at = path + "." + member.getKey();
                if (properties.has(member.getKey())) {
                    walk(member.getValue(), properties.get(member.getKey()), at, out);
                } else if (map.isObject()) {
                    // a map (mdc, fields): its keys are the application's data, not members
                    walk(member.getValue(), map, at, out);
                } else {
                    out.add(at);
                }
            }
        } else if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) {
                walk(value.get(i), schema.path("items"), path + "[" + i + "]", out);
            }
        }
    }

    private static JsonNode resolve(JsonNode schema) {
        while (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            assertThat(ref).as("only local $defs refs are followed").startsWith("#/$defs/");
            schema = StJsonSchema.tree().path("$defs").path(ref.substring("#/$defs/".length()));
        }
        return schema;
    }
}
