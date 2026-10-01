package io.github.gabrielbbaldez.stacktale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code docs/st-json-1.schema.json}, the published contract for st-json/1, loaded for tests.
 *
 * <p>Tests read the file integrators read, not a copy under {@code src/test/resources}, so the
 * schema cannot be edited in one place and tested in another.
 */
final class StJsonSchema {

    static final Path FILE = Path.of("../docs/st-json-1.schema.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode TREE;
    private static final Schema SCHEMA;

    static {
        try {
            TREE = MAPPER.readTree(Files.readString(FILE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(TREE);
    }

    private StJsonSchema() {}

    /** The schema as a tree, for checks that read the schema itself. */
    static JsonNode tree() {
        return TREE;
    }

    /** Validation errors for one NDJSON line or JSON document; empty means valid. */
    static List<String> errors(String json) {
        return SCHEMA.validate(json, InputFormat.JSON).stream().map(Object::toString).toList();
    }
}
