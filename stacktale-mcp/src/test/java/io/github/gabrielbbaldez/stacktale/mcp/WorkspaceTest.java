package io.github.gabrielbbaldez.stacktale.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The walk is bounded, and the bound has to reach the answer.
 *
 * <p>{@code tests_covering}'s negative is the answer it is read for: "nothing names this method"
 * is what sends an agent off to write a reproduction test. A search that stopped early has not
 * earned that sentence, and used to say it anyway on any repository with more than 500 test
 * files (#244).
 */
class WorkspaceTest {

    private static final Workspace.Frame FRAME =
            new Workspace.Frame("PaymentService", "charge", "PaymentService.java", 118);

    @Test
    void aTruncatedSearchDoesNotClaimNothingNamesTheMethod(@TempDir Path root) throws IOException {
        fillerTests(root, 5);

        // Bound below the number of files, so the walk certainly stops early. Nothing here
        // matches, so the answer can only be the negative one — the question is which negative.
        String answer = new Workspace(root, 1).testsCovering(FRAME);

        assertThat(answer)
                .withFailMessage("a search that gave up must not answer as if it had finished: %s", answer)
                .doesNotStartWith("none");
        assertThat(answer).startsWith("inconclusive");
        assertThat(answer).doesNotContain("strong signal");
    }

    @Test
    void anExhaustiveSearchStillSaysNone(@TempDir Path root) throws IOException {
        fillerTests(root, 5);

        String answer = new Workspace(root, 100).testsCovering(FRAME);

        assertThat(answer).startsWith("none:");
        assertThat(answer).contains("Searched all 5 file(s)");
        // the sentence that makes the negative actionable survives, since it is earned here
        assertThat(answer).contains("strong signal");
    }

    @Test
    void aTestNamingTheMethodIsStillFound(@TempDir Path root) throws IOException {
        fillerTests(root, 3);
        Files.writeString(root.resolve("src/test/java/com/acme/PaymentServiceTest.java"),
                "package com.acme;\nclass PaymentServiceTest {\n  void t() { new PaymentService().charge(1); }\n}\n",
                StandardCharsets.UTF_8);

        String answer = new Workspace(root, 100).testsCovering(FRAME);

        assertThat(answer).contains("1 test file(s) name PaymentService.charge");
        assertThat(answer).contains("PaymentServiceTest.java");
    }

    /**
     * The depth bound is the quiet one: {@code Files.walk} simply does not descend past it, and
     * a repository laid out module-per-directory reaches twelve without being unusual. Before,
     * a test source below it produced the same confident {@code none}.
     */
    @Test
    void aSearchThatRanOutOfDepthIsAlsoInconclusive(@TempDir Path root) throws IOException {
        // shallow test sources, so the answer is the negative one rather than "no tests here"
        fillerTests(root, 3);

        Path deep = root.resolve("a/b/c/src/test/java/com/acme/x/y/z/w");
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("DeepTest.java"),
                "package com.acme;\nclass DeepTest { void t() { new PaymentService().charge(1); } }\n",
                StandardCharsets.UTF_8);

        String answer = new Workspace(root).testsCovering(FRAME);

        assertThat(answer)
                .withFailMessage("the walk stopped at its depth limit and still answered as if"
                        + " it had seen the whole tree: %s", answer)
                .doesNotStartWith("none");
        assertThat(answer).startsWith("inconclusive");
    }

    @Test
    void culpritSourceSaysTheSearchFailedRatherThanThatTheFileIsAbsent(@TempDir Path root) {
        // A root the walk cannot read at all: the server was launched somewhere that has since
        // gone. `filesNamed` applies its bound after the name filter, so a failed walk — not the
        // bound — is how this path is reached.
        String answer = new Workspace(root.resolve("gone")).sourceAround(FRAME, 5);

        assertThat(answer).startsWith("The search under");
        assertThat(answer).contains("did not complete");
        assertThat(answer)
                .withFailMessage("'No file named' states an absence the search never established: %s", answer)
                .doesNotContain("No file named");
    }

    @Test
    void testsCoveringSaysTheSameWhenTheWalkFails(@TempDir Path root) {
        String answer = new Workspace(root.resolve("gone")).testsCovering(FRAME);

        // No test sources found at all takes its own branch, and that one is honest already —
        // it names where it looked rather than concluding anything about coverage.
        assertThat(answer).doesNotStartWith("none");
    }

    /** {@code count} test sources, none of which names the culprit. */
    private static void fillerTests(Path root, int count) throws IOException {
        Path dir = root.resolve("src/test/java/com/acme");
        Files.createDirectories(dir);
        for (int i = 0; i < count; i++) {
            Files.writeString(dir.resolve("Filler" + i + "Test.java"),
                    "package com.acme;\nclass Filler" + i + "Test { void t() {} }\n",
                    StandardCharsets.UTF_8);
        }
    }
}
