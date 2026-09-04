package io.github.gabrielbbaldez.stacktale.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StacktaleMcpServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ST_FILE = """
            # AI-oriented error reports (format st/1, https://github.com/stacktale/stacktale)
            # header lines...
            ━━━ ERROR #aaaa1111 ━━━ 2026-07-10 10:00:00.000 thread=main ━━━
            NullPointerException: customer is null
            at Svc.run(Svc.java:1) ← YOUR CODE

            env: app=demo | java 21 | linux
            ━━━ END #aaaa1111 ━━━
            ━ #aaaa1111 repeated 4× (last 10:00:05.000) ━
            ─── app start 2026-07-10 11:00:00.000 (pid 1) ───
            ━━━ ERROR #bbbb2222 ━━━ 2026-07-10 11:30:00.000 thread=worker ━━━
            IllegalStateException: gateway timeout
            at Pay.charge(Pay.java:9) ← YOUR CODE

            env: app=demo | java 21 | linux
            ━━━ END #bbbb2222 ━━━
            ━━━ ERROR #dddd4444 ━━━ 2026-07-10 12:00:00.000 thread=http-1 ━━━
            IllegalStateException: payment gateway refused
            at PaymentService.charge(PaymentService.java:118) ← YOUR CODE
            repro (throw site, via stacktale-agent):
              com.acme.shop.PaymentService#charge(long orderId, java.math.BigDecimal amount, java.lang.String token)
                orderId = 889
                amount = 149.90
                token = ███
              throws IllegalStateException: payment gateway refused

            env: app=demo | java 21 | linux
            ━━━ END #dddd4444 ━━━
            """;

    private Path file;

    @BeforeEach
    void writeFile(@TempDir Path dir) throws Exception {
        file = dir.resolve("errors-ai.log");
        Files.writeString(file, ST_FILE, StandardCharsets.UTF_8);
    }

    private JsonNode[] roundTrip(String... requests) throws Exception {
        String input = String.join("\n", requests) + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StacktaleMcpServer(file).serve(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");
        JsonNode[] responses = new JsonNode[lines.length];
        for (int i = 0; i < lines.length; i++) responses[i] = JSON.readTree(lines[i]);
        return responses;
    }

    @Test
    void initializeAndListTools() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        assertThat(r[0].at("/result/serverInfo/name").asText()).isEqualTo("stacktale");
        assertThat(r[0].at("/result/capabilities/resources/subscribe").asBoolean()).isTrue();
        assertThat(r[0].at("/result/capabilities/prompts").isObject()).isTrue();
        assertThat(r[1].at("/result/tools")).hasSize(10);
        assertThat(r[1].at("/result/tools/0/name").asText()).isEqualTo("list_errors");
        assertThat(r[1].at("/result/tools/3/name").asText()).isEqualTo("find_similar_errors");
        assertThat(r[1].at("/result/tools/4/name").asText()).isEqualTo("errors_since_last_check");
        assertThat(r[1].at("/result/tools/5/name").asText()).isEqualTo("repro_for");
        assertThat(r[1].at("/result/tools/6/name").asText()).isEqualTo("culprit_source");
        assertThat(r[1].at("/result/tools/7/name").asText()).isEqualTo("tests_covering");
        assertThat(r[1].at("/result/tools/8/name").asText()).isEqualTo("audit_redaction");
        assertThat(r[1].at("/result/tools/9/name").asText()).isEqualTo("match_report");
    }

    @Test
    void matchReportReturnsTheFullBlockForAPastedTrace() throws Exception {
        // a raw trace with a Caused-by whose root cause is the #aaaa1111 NPE in ST_FILE
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"match_report\","
                + "\"arguments\":{\"trace\":\"jakarta.servlet.ServletException: request failed\\n"
                + "    at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:100)\\n"
                + "Caused by: java.lang.NullPointerException: customer is null\\n"
                + "    at com.acme.Svc.run(Svc.java:1)\"}}}");
        String text = r[0].at("/result/content/0/text").asText();
        assertThat(text)
                .contains("#aaaa1111")                       // matched the root cause, not the wrapper
                .contains("━━━ ERROR #aaaa1111")             // full report block returned
                .contains("← YOUR CODE");
    }

    @Test
    void matchReportWithNoMatchSaysSo() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"match_report\","
                + "\"arguments\":{\"trace\":\"com.acme.WidgetException: the frobnicator jammed\"}}}");
        assertThat(r[0].at("/result/content/0/text").asText()).contains("No captured report matches");
    }

    /**
     * The point of the tool: the agent gets the signature and the inputs rather than
     * transcribing them out of prose, which is the step where a declared type or an argument
     * order goes quietly wrong.
     */
    @Test
    void reproForBuildsATestSkeletonFromTheSeed() throws Exception {
        String skeleton = reproFor("dddd4444");

        assertThat(skeleton)
                .contains("import com.acme.shop.PaymentService;")
                .contains("import java.math.BigDecimal;")
                .contains("class PaymentServiceReproTest")
                .contains("void chargeThrowsIllegalStateException()")
                .contains("long orderId = 889L;")                          // declared type drives the literal
                .contains("BigDecimal amount = new BigDecimal(\"149.90\");")
                .contains("() -> subject.charge(orderId, amount, token)")  // declaration order preserved
                .contains("assertEquals(\"payment gateway refused\", thrown.getMessage());");

        // java.lang types need no import line
        assertThat(skeleton).doesNotContain("import java.lang.String;");
    }

    /**
     * A masked value must never be handed over as a literal. `String token = "███"` compiles
     * and reads as data, and the test would then reproduce a call that never happened.
     */
    @Test
    void aRedactedArgumentBecomesATodoRatherThanAStringLiteral() throws Exception {
        String skeleton = reproFor("dddd4444");

        assertThat(skeleton).contains("String token = null /* TODO: redacted in the report */;");
        assertThat(skeleton).doesNotContain("\"███\"");
    }

    /** No seed is the ordinary case — opt-in and needs the agent — so the answer teaches instead of failing. */
    @Test
    void reproForSaysHowToGetASeedWhenTheReportHasNone() throws Exception {
        assertThat(reproFor("aaaa1111"))
                .contains("carries no repro: seed")
                .contains("repro=true")
                .contains("-javaagent:");
    }

    @Test
    void reproForAnUnknownIdPointsAtListErrors() throws Exception {
        assertThat(reproFor("nosuchid")).contains("No report with id 'nosuchid'");
    }

    /**
     * st-json/1 reports reach the server as JSON rather than as the text block, and the seed has
     * to survive that route too — the format written for parsers is the one an agent is most
     * likely to be reading.
     */
    @Test
    void reproForReadsAnStJsonReportAsWell(@TempDir Path dir) throws Exception {
        Path jsonFile = dir.resolve("errors-ai.log");
        Files.writeString(jsonFile, """
                {"type":"header","format":"st-json/1"}
                {"type":"report","id":"eeee5555","ts":"2026-07-10T12:00:00.000Z","thread":"http-1",                "error":{"type":"IllegalStateException","message":"payment gateway refused"},                "log":{"pattern":"charge failed","logger":"com.acme.shop.PaymentService"},                "repro":{"className":"com.acme.shop.PaymentService","methodName":"charge",                "params":[{"type":"long","name":"orderId","value":"889"}]}}
                """, StandardCharsets.UTF_8);
        file = jsonFile;

        String skeleton = reproFor("eeee5555");

        assertThat(skeleton)
                .contains("class PaymentServiceReproTest")
                .contains("long orderId = 889L;")
                .contains("assertEquals(\"payment gateway refused\", thrown.getMessage());");
    }

    private String reproFor(String id) throws Exception {
        JsonNode[] r = roundTrip("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"repro_for\",\"arguments\":{\"id\":\"" + id + "\"}}}");
        return r[0].at("/result/content/0/text").asText();
    }

    // --- redaction audit (#95) ---

    /**
     * The gap this covers, measured against the core redactor: a prefixed credential sitting in
     * a message with no keyword beside it is not `password=…`, not a JSON member, not hex and
     * not a JWT, so the redactor has nothing to recognise and the value travels intact.
     */
    @Test
    void auditFlagsACredentialTheRedactorHadNoContextToCatch(@TempDir Path dir) throws Exception {
        file = dir.resolve("errors-ai.log");
        Files.writeString(file, """
                ━━━ ERROR #leak0001 ━━━ 2026-07-10 10:00:00.000 thread=main ━━━
                IllegalStateException: upload rejected
                log: "upload failed for key AKIAIOSFODNN7EXAMPLE" logger=c.a.S3
                ━━━ END #leak0001 ━━━
                """, StandardCharsets.UTF_8);

        String text = audit();

        assertThat(text)
                .contains("1 possible un-redacted credential")
                .contains("#leak0001")
                .contains("an AWS access key id")
                .contains("aws-access-key-id")
                .contains("line 3");
    }

    /**
     * The answer goes into an assistant's context and a transcript, so quoting the secret would
     * move it somewhere new — which is the thing being warned about.
     */
    @Test
    void auditNeverPrintsTheValueItFound(@TempDir Path dir) throws Exception {
        file = dir.resolve("errors-ai.log");
        Files.writeString(file, """
                ━━━ ERROR #leak0002 ━━━ 2026-07-10 10:00:00.000 thread=main ━━━
                IllegalStateException: clone failed
                log: "ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789 expired" logger=c.a.Git
                ━━━ END #leak0002 ━━━
                """, StandardCharsets.UTF_8);

        String text = audit();

        assertThat(text).contains("a GitHub token").contains("starts ghp_…");
        assertThat(text).doesNotContain("aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789");
    }

    @Test
    void auditPassesAFileWhoseSecretsWereMasked(@TempDir Path dir) throws Exception {
        file = dir.resolve("errors-ai.log");
        Files.writeString(file, """
                ━━━ ERROR #safe0001 ━━━ 2026-07-10 10:00:00.000 thread=main ━━━
                IllegalStateException: login failed
                log: "login failed password=███" logger=c.a.Auth
                ━━━ END #safe0001 ━━━
                """, StandardCharsets.UTF_8);

        assertThat(audit())
                .startsWith("✓ No un-redacted credential shapes")
                .contains("redaction is running")   // the mask is evidence the redactor ran
                .contains("evidence rather than proof");
    }

    /**
     * A file with nothing masked anywhere looks identical whether nothing sensitive was logged
     * or redaction was switched off — the reading someone most needs prompting about.
     */
    @Test
    void auditSaysWhenNothingInTheFileIsMaskedAtAll(@TempDir Path dir) throws Exception {
        file = dir.resolve("errors-ai.log");
        Files.writeString(file, """
                ━━━ ERROR #safe0002 ━━━ 2026-07-10 10:00:00.000 thread=main ━━━
                IllegalStateException: nothing sensitive here
                ━━━ END #safe0002 ━━━
                """, StandardCharsets.UTF_8);

        assertThat(audit()).contains("redactionEnabled=false");
    }

    /** The ordinary fixture is stack traces and log lines; an audit that fires on those is noise. */
    @Test
    void auditDoesNotFireOnOrdinaryReports() throws Exception {
        assertThat(audit()).startsWith("✓ No un-redacted credential shapes");
    }

    private String audit() throws Exception {
        JsonNode[] r = roundTrip("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"audit_redaction\",\"arguments\":{}}}");
        return r[0].at("/result/content/0/text").asText();
    }

    // --- workspace tools (#138) ---

    /** A tree shaped like the one the client has open, holding the class #aaaa1111 blames. */
    private Path workspaceWithSvc(Path dir, int lines) throws Exception {
        Path src = dir.resolve("src/main/java/com/acme");
        Files.createDirectories(src);
        StringBuilder body = new StringBuilder("package com.acme;\n\nclass Svc {\n");
        for (int i = 4; i <= lines; i++) {
            body.append("    // line ").append(i).append('\n');
        }
        Files.writeString(src.resolve("Svc.java"), body.toString(), StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    void culpritSourceReadsTheLineFromTheWorkingTree(@TempDir Path dir) throws Exception {
        Path workspace = workspaceWithSvc(dir, 40);

        String text = workspaceTool(workspace, "culprit_source", "aaaa1111", ",\"radius\":3");

        assertThat(text)
                .contains("Svc.java")
                .contains("Svc.run, line 1")
                .contains("1 > package com.acme;")  // the culprit line is marked, the rest are not
                .contains("2 | ")
                .doesNotContain("5 | ");            // radius honoured
    }

    /**
     * A stack frame can name a file this tree does not have — a dependency, generated code, or
     * another service sharing the log. That has to be an answer: a failed tool call leaves the
     * agent with nothing to do next.
     */
    @Test
    void culpritSourceSaysSoWhenTheFileIsNotInTheTree(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java"));

        assertThat(workspaceTool(dir, "culprit_source", "aaaa1111", ""))
                .contains("No file named Svc.java")
                .contains("dependency");
    }

    /** The log can be days older than the tree; pointing past the end of the file must say why. */
    @Test
    void culpritSourceFlagsAReportOlderThanTheFile(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/main/java/com/acme");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Pay.java"), "package com.acme;\n", StandardCharsets.UTF_8);

        // #bbbb2222 blames Pay.charge(Pay.java:9); the file here has one line
        assertThat(workspaceTool(dir, "culprit_source", "bbbb2222", ""))
                .contains("points at line 9")
                .contains("changed since the error was captured");
    }

    @Test
    void culpritSourceHasNoFrameForAnErrorLoggedWithoutAThrowable(@TempDir Path dir) throws Exception {
        file = dir.resolve("errors-ai.log");
        Files.writeString(file, """
                ━━━ ERROR #eeee5555 ━━━ 2026-07-10 10:00:00.000 thread=main ━━━
                ERROR (no exception): payment queue is backing up
                ━━━ END #eeee5555 ━━━
                """, StandardCharsets.UTF_8);

        assertThat(workspaceTool(dir, "culprit_source", "eeee5555", ""))
                .contains("no culprit frame")
                .contains("logged without a throwable");
    }

    /**
     * The negative answer is the one worth having: ORACLE-SWE ranks a reproduction test above
     * every other signal an agent can be given, so "nothing names this method" tells it to write
     * one rather than spend turns hunting for a test that does not exist.
     */
    @Test
    void testsCoveringSaysNoneWhenNothingNamesTheMethod(@TempDir Path dir) throws Exception {
        Path workspace = workspaceWithSvc(dir, 10);
        Path tests = workspace.resolve("src/test/java/com/acme");
        Files.createDirectories(tests);
        Files.writeString(tests.resolve("OtherTest.java"),
                "package com.acme;\nclass OtherTest { void checkout() {} }\n", StandardCharsets.UTF_8);

        assertThat(workspaceTool(workspace, "tests_covering", "aaaa1111", ""))
                .startsWith("none: no test source names Svc.run")
                .contains("repro_for");             // points at the tool that helps write one
    }

    @Test
    void testsCoveringListsTheFilesThatNameTheCulprit(@TempDir Path dir) throws Exception {
        Path workspace = workspaceWithSvc(dir, 10);
        Path tests = workspace.resolve("src/test/java/com/acme");
        Files.createDirectories(tests);
        Files.writeString(tests.resolve("SvcTest.java"),
                "package com.acme;\nclass SvcTest {\n    void runReturnsTheCustomer() { new Svc().run(); }\n}\n",
                StandardCharsets.UTF_8);

        assertThat(workspaceTool(workspace, "tests_covering", "aaaa1111", ""))
                .contains("1 test file(s) name Svc.run")
                .contains("SvcTest.java")
                .contains("3: void runReturnsTheCustomer")  // the line, so the agent can open it
                .contains("not coverage");                  // the caveat travels with the answer
    }

    /** Build output holds copies of the sources; answering from target/ describes the wrong tree. */
    @Test
    void theWalkIgnoresBuildOutput(@TempDir Path dir) throws Exception {
        Path stale = dir.resolve("target/classes/com/acme");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("Svc.java"), "package com.acme;\n// STALE COPY\n",
                StandardCharsets.UTF_8);

        assertThat(workspaceTool(dir, "culprit_source", "aaaa1111", ""))
                .contains("No file named Svc.java")
                .doesNotContain("STALE COPY");
    }

    private String workspaceTool(Path workspaceRoot, String tool, String id, String extraArgs)
            throws Exception {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":{\"id\":\"" + id + "\"" + extraArgs + "}}}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StacktaleMcpServer(file, workspaceRoot).serve(
                new ByteArrayInputStream(req.getBytes(StandardCharsets.UTF_8)), out);
        return JSON.readTree(out.toString(StandardCharsets.UTF_8).trim())
                .at("/result/content/0/text").asText();
    }

    private static final String NEW_BLOCK = """
            ━━━ ERROR #cccc3333 ━━━ 2026-07-10 12:00:00.000 thread=main ━━━
            IllegalArgumentException: bad input
            ━━━ END #cccc3333 ━━━
            """;

    // One fix-loop check on a persistent server instance; the cursor lives on the instance,
    // so calling serve() again (a fresh stdio "turn") keeps it while the file changes between.
    private static String loopCheck(StacktaleMcpServer server, int id, boolean reset) throws Exception {
        String args = reset ? "{\"reset\":true}" : "{}";
        String req = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"errors_since_last_check\",\"arguments\":" + args + "}}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serve(new ByteArrayInputStream(req.getBytes(StandardCharsets.UTF_8)), out);
        return JSON.readTree(out.toString(StandardCharsets.UTF_8).trim()).at("/result/content/0/text").asText();
    }

    @Test
    void fixLoopReportsNewRecurringAndClean(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("errors-ai.log");
        Files.writeString(f, ST_FILE, StandardCharsets.UTF_8); // #aaaa1111 (×4) and #bbbb2222
        StacktaleMcpServer server = new StacktaleMcpServer(f);

        // first call baselines and shows what's already there
        assertThat(loopCheck(server, 1, false))
                .contains("currently on file").contains("aaaa1111").contains("bbbb2222");

        // nothing changed → the loop's clean signal
        assertThat(loopCheck(server, 2, false)).contains("No new errors");

        // a brand-new error appears
        Files.writeString(f, ST_FILE + NEW_BLOCK, StandardCharsets.UTF_8);
        String afterNew = loopCheck(server, 3, false);
        assertThat(afterNew).contains("new").contains("cccc3333");
        assertThat(afterNew).doesNotContain("still occurring");

        // an already-seen error recurs (a repeated line lifts #aaaa1111 above its baseline count)
        Files.writeString(f, ST_FILE + NEW_BLOCK
                + "━ #aaaa1111 repeated 9× (last 10:00:09.000) ━\n", StandardCharsets.UTF_8);
        String afterRecur = loopCheck(server, 4, false);
        assertThat(afterRecur).contains("still occurring").contains("aaaa1111");
        assertThat(afterRecur).doesNotContain("cccc3333"); // cccc3333 was already reported, not new again

        // reset re-baselines: the current file becomes the new starting point
        assertThat(loopCheck(server, 5, true)).contains("currently on file");
    }

    @Test
    void negotiatesProtocolAndReportsARealVersion() throws Exception {
        // the client's offered revision is echoed back, not overridden
        JsonNode[] echoed = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}");
        assertThat(echoed[0].at("/result/protocolVersion").asText()).isEqualTo("2025-03-26");
        assertThat(echoed[0].at("/result/serverInfo/version").asText())
                .isNotBlank().isNotEqualTo("0.4.0"); // real version, not the old hard-coded string

        // no offer → our own preferred revision
        JsonNode[] fallback = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(fallback[0].at("/result/protocolVersion").asText()).isEqualTo("2025-06-18");
    }

    @Test
    void listsAndGetsPrompts() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"prompts/list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/get\",\"params\":{\"name\":\"fix_loop\"}}");
        assertThat(r[0].at("/result/prompts")).hasSize(2);
        assertThat(r[0].at("/result/prompts/0/name").asText()).isEqualTo("fix_loop");
        JsonNode message = r[1].at("/result/messages/0");
        assertThat(message.at("/role").asText()).isEqualTo("user");
        assertThat(message.at("/content/text").asText()).contains("errors_since_last_check");
    }

    @Test
    void toolsCarryStructuredContent() throws Exception {
        // list_errors → { reports: [ {id, headline, repeats, timestamp} ] }
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_errors\",\"arguments\":{}}}");
        JsonNode reports = r[0].at("/result/structuredContent/reports");
        assertThat(reports.isArray()).isTrue();
        assertThat(reports).isNotEmpty();
        assertThat(reports.get(0).at("/id").asText()).isNotBlank();
        assertThat(reports.get(0).at("/headline").asText()).isNotBlank();

        // errors_since_last_check → { clean, new, recurring }; ST_FILE has errors so not clean
        JsonNode[] loop = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"errors_since_last_check\",\"arguments\":{}}}");
        JsonNode sc = loop[0].at("/result/structuredContent");
        assertThat(sc.at("/clean").asBoolean()).isFalse();
        assertThat(sc.at("/new").isArray()).isTrue();
        assertThat(sc.at("/recurring").isArray()).isTrue();
    }

    @Test
    void toolsAdvertiseReadOnlyAnnotations() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        JsonNode tools = r[0].at("/result/tools");
        tools.forEach(t -> {
            assertThat(t.at("/annotations/readOnlyHint").asBoolean()).isTrue();  // all read the file only
            assertThat(t.at("/annotations/title").asText()).isNotBlank();
        });
        // the loop tool declares an outputSchema for its structured result
        JsonNode loopTool = null;
        for (JsonNode t : tools) {
            if ("errors_since_last_check".equals(t.get("name").asText())) loopTool = t;
        }
        assertThat(loopTool.at("/outputSchema/properties/clean").isObject()).isTrue();
        JsonNode loop = null;
        for (JsonNode t : tools) {
            if ("errors_since_last_check".equals(t.get("name").asText())) loop = t;
        }
        assertThat(loop).isNotNull();
        // the cursor tool mutates session state, so it must NOT claim idempotence
        assertThat(loop.at("/annotations/idempotentHint").asBoolean()).isFalse();
    }

    @Test
    void listsAndReadsTheReportsResource() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/read\",\"params\":{\"uri\":\"stacktale://reports\"}}");
        assertThat(r[0].at("/result/resources/0/uri").asText()).isEqualTo("stacktale://reports");
        assertThat(r[1].at("/result/contents/0/text").asText()).contains("#bbbb2222");
    }

    @Test
    void subscribePushesAnUpdateNotificationWhenTheFileChanges(@TempDir Path dir) throws Exception {
        Path watched = dir.resolve("errors-ai.log");
        Files.writeString(watched, ST_FILE, StandardCharsets.UTF_8);
        StacktaleMcpServer server = new StacktaleMcpServer(watched);

        java.io.PipedOutputStream toServer = new java.io.PipedOutputStream();
        java.io.PipedInputStream serverIn = new java.io.PipedInputStream(toServer, 8192);
        ByteArrayOutputStream serverOut = new ByteArrayOutputStream();

        Thread serving = new Thread(() -> {
            try { server.serve(serverIn, new java.io.FilterOutputStream(serverOut) {
                @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
                    synchronized (serverOut) { super.out.write(b, off, len); }
                }
            }); } catch (Exception ignored) {}
        });
        serving.setDaemon(true);
        serving.start();

        toServer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/subscribe\",\"params\":{}}\n"
                .getBytes(StandardCharsets.UTF_8));
        toServer.flush();
        Thread.sleep(400); // let the watcher register

        Files.writeString(watched, ST_FILE + "extra append\n", StandardCharsets.UTF_8);

        String out = "";
        for (int i = 0; i < 60 && !out.contains("notifications/resources/updated"); i++) {
            Thread.sleep(100);
            synchronized (serverOut) { out = serverOut.toString(StandardCharsets.UTF_8); }
        }
        toServer.close();
        assertThat(out).contains("notifications/resources/updated").contains("stacktale://reports");
    }

    @Test
    void listErrorsNewestFirstWithRepeats() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_errors\",\"arguments\":{}}}");
        String text = r[0].at("/result/content/0/text").asText();
        assertThat(text.lines().findFirst().orElse("")).contains("#dddd4444"); // newest first (12:00)
        assertThat(text).contains("(×4)");                                     // repeat count folded in
        assertThat(text).contains("NullPointerException: customer is null");
    }

    @Test
    void recurrenceSurvivesASecondBlockOfTheSameFingerprint(@TempDir Path dir) throws Exception {
        // the same error reported twice (dedup window expired between) — the MCP view must
        // not reset its recurrence to 1 and must keep the earliest timestamp
        Path f = dir.resolve("errors-ai.log");
        Files.writeString(f, """
                ━━━ ERROR #dupe1234 ━━━ 2026-07-10 09:00:00.000 thread=main ━━━
                RuntimeException: recurring
                ━━━ END #dupe1234 ━━━
                ━ #dupe1234 repeated 3× (last 09:00:05.000) ━
                ━━━ ERROR #dupe1234 ━━━ 2026-07-10 09:30:00.000 thread=main ━━━
                RuntimeException: recurring
                ━━━ END #dupe1234 ━━━
                """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StacktaleMcpServer(f).serve(new ByteArrayInputStream(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_errors\",\"arguments\":{}}}\n"
                        .getBytes(StandardCharsets.UTF_8)), out);
        String text = JSON.readTree(out.toString(StandardCharsets.UTF_8).trim())
                .at("/result/content/0/text").asText();
        assertThat(text).contains("(×3)");                 // count carried forward, not reset to 1
        assertThat(text).contains("2026-07-10 09:00:00");  // earliest timestamp kept
    }

    @Test
    void readsTheStJsonFormatToo(@TempDir Path dir) throws Exception {
        // a format=json errors-ai.log (st-json/1 NDJSON) must work through the same tools
        Path f = dir.resolve("errors-ai.log");
        Files.writeString(f, """
                {"type":"header","format":"st-json/1"}
                {"type":"report","id":"json1234","ts":"2026-07-10T20:16:40.412Z","thread":"main","error":{"type":"IllegalStateException","message":"payment gateway refused"}}
                {"type":"repeat","id":"json1234","count":5,"last":"2026-07-10T20:17:00.000Z"}
                """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StacktaleMcpServer(f).serve(new ByteArrayInputStream((
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_errors\",\"arguments\":{}}}\n"
              + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_report\",\"arguments\":{\"id\":\"json1234\"}}}\n")
                .getBytes(StandardCharsets.UTF_8)), out);
        String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");

        String list = JSON.readTree(lines[0]).at("/result/content/0/text").asText();
        assertThat(list)
                .contains("json1234")
                .contains("IllegalStateException: payment gateway refused") // headline built from the JSON error
                .contains("(×5)");                                          // the repeat entry folded in
        String report = JSON.readTree(lines[1]).at("/result/content/0/text").asText();
        assertThat(report).contains("payment gateway refused"); // the report block served from JSON
    }

    @Test
    void getReportReturnsTheFullBlock() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_report\",\"arguments\":{\"id\":\"aaaa1111\"}}}");
        String text = r[0].at("/result/content/0/text").asText();
        assertThat(text).contains("━━━ ERROR #aaaa1111").contains("← YOUR CODE").contains("occurred 4×");
    }

    @Test
    void errorsSinceFiltersByTimestamp() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"errors_since\",\"arguments\":{\"since\":\"2026-07-10 11:00:00\"}}}");
        String text = r[0].at("/result/content/0/text").asText();
        assertThat(text).contains("#bbbb2222").doesNotContain("#aaaa1111");
    }

    @Test
    void unknownToolReturnsJsonRpcError() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\",\"arguments\":{}}}");
        assertThat(r[0].has("error")).isTrue();
    }

    @Test
    void unknownMethodUsesMethodNotFoundCode() throws Exception {
        JsonNode[] r = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"does/not/exist\",\"params\":{}}");
        assertThat(r[0].at("/error/code").asInt()).isEqualTo(-32601); // Method not found, per JSON-RPC 2.0
    }

    @Test
    void scansContiguousBackupsBeyondNine(@TempDir Path dir) throws Exception {
        // a report living in .12 (maxBackups > 9) must still be visible
        Files.writeString(dir.resolve("errors-ai.log.12"), """
                ━━━ ERROR #old01234 ━━━ 2026-07-01 09:00:00.000 thread=main ━━━
                RuntimeException: ancient failure
                ━━━ END #old01234 ━━━
                """, StandardCharsets.UTF_8);
        for (int i = 1; i <= 12; i++) {
            if (i == 12) continue;
            Files.writeString(dir.resolve("errors-ai.log." + i), "filler\n", StandardCharsets.UTF_8);
        }
        Files.writeString(dir.resolve("errors-ai.log"), ST_FILE, StandardCharsets.UTF_8);

        StacktaleMcpServer server = new StacktaleMcpServer(dir.resolve("errors-ai.log"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serve(new ByteArrayInputStream(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_report\",\"arguments\":{\"id\":\"old01234\"}}}\n"
                        .getBytes(StandardCharsets.UTF_8)), out);
        JsonNode r = JSON.readTree(out.toString(StandardCharsets.UTF_8).trim());
        assertThat(r.at("/result/content/0/text").asText()).contains("ancient failure");
    }

    @Test
    void findSimilarRanksByExceptionTypeAndNormalizedMessage() {
        // #67: same root-cause type + digit-normalized message ranks first; unrelated errors
        // score 0 and drop out entirely.
        List<StReportFile.StReport> reports = List.of(
                report("aaa11111", "NullPointerException: customer is null"),
                report("bbb22222", "NullPointerException: Cannot invoke \"Customer.tier()\" because \"customer\" is null"),
                report("ccc33333", "IllegalStateException: payment gateway refused"),
                report("ddd44444", "SQLException: connection timed out"));

        List<StReportFile.StReport> hits = StacktaleMcpServer.rank(
                "NullPointerException: customer 8842 is null", reports, 5);

        assertThat(hits).extracting(r -> r.id()).containsExactly("aaa11111", "bbb22222");
    }

    private static StReportFile.StReport report(String id, String headline) {
        return new StReportFile.StReport(id, "2026-07-14 10:00:00.000", headline, 1, "block");
    }
}
