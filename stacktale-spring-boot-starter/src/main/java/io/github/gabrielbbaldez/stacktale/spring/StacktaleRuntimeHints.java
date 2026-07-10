package io.github.gabrielbbaldez.stacktale.spring;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM native-image hints for the Spring Boot path. stacktale's report pipeline is
 * reflection-free, so the only thing to register is the two classpath resources the
 * {@code env:} line reads directly — without them the native image drops the app version
 * and git sha. (stacktale-core also ships these as reachability metadata for non-Spring
 * native builds; registering here too is idempotent and keeps the Spring AOT path honest.)
 *
 * <p>Not registered on purpose: your exception types. The {@code fields:} section reflects
 * over <em>your</em> exceptions' getters, which stacktale cannot enumerate — register them
 * yourself (see {@code docs/native.md}). Everything else works in native with no metadata.
 */
class StacktaleRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("git.properties");
        hints.resources().registerPattern("META-INF/build-info.properties");
    }
}
