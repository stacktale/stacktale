package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code report} example in FORMAT.md §7 is what most integrators copy from, so it has to be
 * a line the schema accepts. Without this, the example and the schema are two descriptions of
 * the format that nothing compares.
 */
class FormatExampleSchemaTest {

    @Test
    void theSection7ReportExampleValidatesAgainstTheSchema() throws Exception {
        String format = Files.readString(Path.of("../docs/FORMAT.md")).replace("\r\n", "\n");
        int section = format.indexOf("\n## 7.");
        assertThat(section).as("FORMAT.md §7").isPositive();
        int start = format.indexOf("```json\n", section);
        assertThat(start).as("a ```json block in §7").isPositive();
        start += "```json\n".length();
        String example = format.substring(start, format.indexOf("\n```", start));

        assertThat(example).contains("\"type\": \"report\"");
        assertThat(StJsonSchema.errors(example)).isEmpty();
    }

    @Test
    void section7LinksTheSchema() throws Exception {
        String format = Files.readString(Path.of("../docs/FORMAT.md"));
        assertThat(format.substring(format.indexOf("## 7."))).contains("st-json-1.schema.json");
    }
}
