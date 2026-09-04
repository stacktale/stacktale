package io.github.gabrielbbaldez.stacktale.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a report's {@code repro:} seed into a JUnit skeleton an agent can paste.
 *
 * <p>The seed is the one section stacktale writes for a machine: the throw site's fully
 * qualified class, its method, the declared parameter types and the values it was given.
 * TDD-Bench-Java measured agents writing reproduction tests at 4% on proprietary code with no
 * hints, rising to 20% once given concrete class names and method signatures (#135). The report
 * already prints those; this renders them in the form the agent would otherwise transcribe by
 * hand, which is the step where a type or an argument order goes quietly wrong.
 *
 * <p>The goal is "an agent can write the test from this", not "this compiles unmodified". How
 * the receiver was built is not something stacktale captured, and a value it had to redact
 * cannot become a literal. Both are explicit {@code TODO}s rather than guesses.
 */
final class ReproSkeleton {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code   com.acme.shop.PaymentService#charge(long orderId, java.math.BigDecimal amount)} */
    private static final Pattern SIGNATURE = Pattern.compile("^ {2}(\\S+)#(\\w+)\\((.*)\\)$");
    /** {@code   throws IllegalStateException: payment gateway refused} */
    private static final Pattern THROWS = Pattern.compile("^ {2}throws (\\S+?)(?::\\s(.*))?$");
    private static final String SECTION = "repro (throw site, via stacktale-agent):";

    private ReproSkeleton() {
    }

    /** One argument at the throw site, as the report recorded it. */
    private record Param(String type, String name, String value) {
    }

    /** What a skeleton needs: the call, and the outcome it has to assert. */
    private record Seed(String className, String methodName, List<Param> params,
                        String thrownType, String thrownMessage) {
    }

    /**
     * The skeleton for one report block, or an explanation of why there is none.
     *
     * <p>"No seed" is the ordinary case rather than an error — the section is opt-in and needs
     * the agent — so the answer says how to get one instead of reporting a failure.
     */
    static String render(String id, String block) {
        Seed seed = parse(block);
        if (seed == null) {
            return "Report #" + id + " carries no repro: seed, so there is nothing to build a test from.\n\n"
                    + "The seed is the throw site's typed signature and its argument values. It needs both:\n"
                    + "  - repro=true on the appender (stacktale.repro with the Spring starter or Quarkus)\n"
                    + "  - stacktale-agent on the command line: -javaagent:/path/to/stacktale-agent.jar\n\n"
                    + "It is off by default because it renders argument values against a named signature,\n"
                    + "a bigger privacy surface than the rest of the report. Errors captured before it was\n"
                    + "switched on stay without a seed; get_report still has the story, fields and stack.";
        }
        return skeleton(id, seed);
    }

    /** Text {@code st/1}, or the pretty-printed JSON the server holds for an {@code st-json/1} report. */
    private static Seed parse(String block) {
        String content = block == null ? "" : block;
        return content.strip().startsWith("{") ? parseJson(content.strip()) : parseText(content);
    }

    private static Seed parseJson(String block) {
        try {
            JsonNode node = JSON.readTree(block);
            JsonNode repro = node.path("repro");
            if (!repro.isObject()) {
                return null;
            }
            List<Param> params = new ArrayList<>();
            for (JsonNode p : repro.path("params")) {
                params.add(new Param(p.path("type").asText(""), p.path("name").asText(""),
                        p.path("value").asText("")));
            }
            String message = node.at("/error/message").asText("");
            return new Seed(repro.path("className").asText(""), repro.path("methodName").asText(""),
                    params, emptyToNull(node.at("/error/type").asText("")), emptyToNull(message));
        } catch (Exception e) {
            return null; // a block we cannot read is a block with no seed, not a failed tool call
        }
    }

    private static Seed parseText(String block) {
        String[] lines = block.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(SECTION)) {
                start = i;
                break;
            }
        }
        if (start < 0 || start + 1 >= lines.length) {
            return null;
        }

        Matcher signature = SIGNATURE.matcher(lines[start + 1]);
        if (!signature.matches()) {
            return null;
        }
        List<String> types = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String declared : signature.group(3).split(",")) {
            String trimmed = declared.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int space = trimmed.lastIndexOf(' ');
            if (space < 0) {
                return null;
            }
            types.add(trimmed.substring(0, space));
            names.add(trimmed.substring(space + 1));
        }

        List<Param> params = new ArrayList<>();
        String thrownType = null;
        String thrownMessage = null;
        for (int i = start + 2; i < lines.length; i++) {
            Matcher thrown = THROWS.matcher(lines[i]);
            if (thrown.matches()) {
                thrownType = thrown.group(1);
                thrownMessage = thrown.group(2);
                break;
            }
            if (!lines[i].startsWith("    ")) {
                break;
            }
            // one `name = value` line per argument, in declaration order; split on the FIRST
            // " = " so a value containing one survives
            String pair = lines[i].substring(4);
            int eq = pair.indexOf(" = ");
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 3);
            int index = names.indexOf(name);
            params.add(new Param(index < 0 ? "java.lang.Object" : types.get(index), name, value));
        }
        return new Seed(signature.group(1), signature.group(2), params, thrownType, thrownMessage);
    }

    private static String skeleton(String id, Seed seed) {
        String subject = simple(seed.className());
        StringBuilder b = new StringBuilder(512);

        b.append("// Reproduction skeleton for stacktale report #").append(id).append('\n');
        b.append("// Built from the repro: seed — the call recorded at the throw site.\n\n");

        for (String imported : imports(seed)) {
            b.append("import ").append(imported).append(";\n");
        }
        b.append("import org.junit.jupiter.api.Test;\n");
        b.append("import static org.junit.jupiter.api.Assertions.assertEquals;\n");
        b.append("import static org.junit.jupiter.api.Assertions.assertThrows;\n\n");

        b.append("class ").append(subject).append("ReproTest {\n\n");
        b.append("    @Test\n");
        b.append("    void ").append(seed.methodName()).append("Throws")
                .append(seed.thrownType() == null ? "" : simple(seed.thrownType())).append("() {\n");
        b.append("        // TODO: stacktale captured the call, not the object it was made on\n");
        b.append("        ").append(subject).append(" subject = new ").append(subject)
                .append("(/* dependencies */);\n\n");

        for (Param p : seed.params()) {
            b.append("        ").append(simple(p.type())).append(' ').append(p.name())
                    .append(" = ").append(literal(p)).append(";\n");
        }
        if (!seed.params().isEmpty()) {
            b.append('\n');
        }

        String args = String.join(", ", seed.params().stream().map(Param::name).toList());
        if (seed.thrownType() == null) {
            b.append("        subject.").append(seed.methodName()).append('(').append(args).append(");\n");
        } else {
            String thrown = simple(seed.thrownType());
            b.append("        ").append(thrown).append(" thrown = assertThrows(").append(thrown)
                    .append(".class,\n");
            b.append("                () -> subject.").append(seed.methodName()).append('(').append(args)
                    .append("));\n");
            if (seed.thrownMessage() != null && !seed.thrownMessage().isBlank()) {
                b.append('\n').append("        assertEquals(").append(quote(seed.thrownMessage()))
                        .append(", thrown.getMessage());\n");
            }
        }
        b.append("    }\n}\n");
        return b.toString();
    }

    /**
     * Imports for the types this names. The thrown type is deliberately absent: a report carries
     * its simple name only, so there is no package to import — an application's own exception
     * type has to be resolved by whoever pastes the skeleton.
     */
    private static Set<String> imports(Seed seed) {
        Set<String> out = new LinkedHashSet<>();
        addImport(out, seed.className());
        for (Param p : seed.params()) {
            addImport(out, p.type());
        }
        return out;
    }

    private static void addImport(Set<String> out, String type) {
        if (type != null && type.contains(".") && !type.startsWith("java.lang.")) {
            out.add(type);
        }
    }

    /**
     * A Java literal for a recorded value, or a TODO where one cannot be honest.
     *
     * <p>A redacted value never becomes a literal, whatever its declared type: {@code "███"} as
     * a String reads as data and would quietly make the test reproduce a different call. Same
     * for a type with no literal form — an explicit gap beats an approximate object.
     */
    private static String literal(Param p) {
        String value = p.value();
        if (value == null || value.isBlank()) {
            return "null /* TODO: no value recorded */";
        }
        if (value.contains("███")) {
            return "null /* TODO: redacted in the report */";
        }
        return switch (p.type()) {
            case "int", "short", "byte", "java.lang.Integer", "java.lang.Short", "java.lang.Byte" -> value;
            case "long", "java.lang.Long" -> value + "L";
            case "float", "java.lang.Float" -> value + "f";
            case "double", "java.lang.Double" -> value + "d";
            case "boolean", "java.lang.Boolean" -> value;
            case "char", "java.lang.Character" -> "'" + value + "'";
            case "java.lang.String" -> quote(value);
            case "java.math.BigDecimal" -> "new BigDecimal(" + quote(value) + ")";
            case "java.math.BigInteger" -> "new BigInteger(" + quote(value) + ")";
            default -> "null /* TODO: a " + simple(p.type()) + " equal to " + value + " */";
        };
    }

    private static String simple(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
