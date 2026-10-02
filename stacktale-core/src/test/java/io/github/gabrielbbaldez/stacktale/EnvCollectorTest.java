package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.assertj.core.api.Assertions.assertThat;

class EnvCollectorTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("stacktale.app.name");
        System.clearProperty("stacktale.app.version");
        System.clearProperty("stacktale.app.build");
        System.clearProperty("spring.profiles.active");
    }

    @Test
    void readsBuildInfoAndGitPropertiesFromClasspath() {
        String line = new EnvCollector(getClass().getClassLoader(),"","").envLine();
        assertThat(line).contains("app=shop-api 1.4.2").contains("(git 7e3c1f)").contains("java ");
    }
    @Test
    void configuredAppNameIsUsedWhenBuildInfoIsMissing() {
        ClassLoader empty = new URLClassLoader(new URL[0], null);

        String line = new EnvCollector(empty, "demo-shop", "").envLine();

        assertThat(line).startsWith("app=demo-shop");
    }

    @Test
    void syspropsOverrideBuildInfo() {
        System.setProperty("stacktale.app.name", "override");
        System.setProperty("stacktale.app.version", "9.9.9");
        System.setProperty("spring.profiles.active", "dev");
        String line = new EnvCollector(getClass().getClassLoader(),"","").envLine();
        assertThat(line).contains("app=override 9.9.9").contains("profile=dev");
    }

    @Test
    void degradesGracefullyWithEmptyClasspath() {
        ClassLoader empty = new URLClassLoader(new URL[0], null);
        String line = new EnvCollector(empty,"","").envLine();
        assertThat(line).startsWith("app=?").doesNotContain("git").contains("java ");
    }

    @Test
    void survivesMalformedPropertiesFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // Properties.load throws IllegalArgumentException (not IOException) on a broken \-u escape
        java.nio.file.Files.writeString(dir.resolve("git.properties"), "git.commit.id.abbrev=\\uZZZZ");
        try (URLClassLoader cl = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null)) {
            EnvCollector collector = new EnvCollector(cl,"","");
            org.assertj.core.api.Assertions.assertThatCode(collector::envLine).doesNotThrowAnyException();
            assertThat(collector.envLine()).contains("java ");
        }
    }

    /**
     * A name or version someone wrote into their config is a decision; build-info is whatever
     * the build plugin happened to stamp. With build-info on the classpath (as every Spring Boot
     * app built with the build-info goal has), the configured values used to be ignored.
     */
    @Test
    void configuredNameAndVersionBeatBuildInfo() {
        String line = new EnvCollector(getClass().getClassLoader(), "checkout", "2.0.0").envLine();
        assertThat(line).startsWith("app=checkout 2.0.0 (git 7e3c1f)");
    }

    @Test
    void syspropsBeatTheConfiguredValues() {
        System.setProperty("stacktale.app.name", "override");
        System.setProperty("stacktale.app.version", "9.9.9");
        String line = new EnvCollector(getClass().getClassLoader(), "checkout", "2.0.0").envLine();
        assertThat(line).startsWith("app=override 9.9.9");
    }

    /** Each value falls back on its own: a configured name does not drag build-info's version away. */
    @Test
    void anUnsetConfiguredValueStillFallsBackToBuildInfo() {
        assertThat(new EnvCollector(getClass().getClassLoader(), "checkout", "").envLine())
                .startsWith("app=checkout 1.4.2");
        assertThat(new EnvCollector(getClass().getClassLoader(), "", "2.0.0").envLine())
                .startsWith("app=shop-api 2.0.0");
    }

    @Test
    void buildIdPrefersTheGitShaOverAnyVersion() {
        assertThat(new EnvCollector(getClass().getClassLoader(), "", "2.0.0").buildId()).isEqualTo("7e3c1f");
    }

    @Test
    void buildIdUsesTheConfiguredVersionBeforeBuildInfo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("META-INF"));
        java.nio.file.Files.writeString(dir.resolve("META-INF/build-info.properties"),
                "build.name=shop-api\nbuild.version=1.4.2\n");
        try (URLClassLoader cl = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null)) {
            assertThat(new EnvCollector(cl, "", "2.0.0").buildId()).isEqualTo("2.0.0");
            assertThat(new EnvCollector(cl, "", "").buildId()).isEqualTo("1.4.2");
            System.setProperty("stacktale.app.version", "9.9.9");
            assertThat(new EnvCollector(cl, "", "2.0.0").buildId()).isEqualTo("9.9.9");
            System.setProperty("stacktale.app.build", "abc123");
            assertThat(new EnvCollector(cl, "", "2.0.0").buildId()).isEqualTo("abc123");
        }
    }
}
