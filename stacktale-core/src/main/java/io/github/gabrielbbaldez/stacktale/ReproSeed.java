package io.github.gabrielbbaldez.stacktale;

import java.util.List;

/**
 * What a test needs to recreate one failure: the call that threw, with declared parameter
 * types and the values it was given.
 *
 * <p>The point is the <em>signature</em>. TDD-Bench-Java measured agents writing reproduction
 * tests at ~44% on public benchmarks and 4% on proprietary code with no hints — rising to 20%
 * once given concrete class names and method signatures. stacktale is holding exactly that at
 * the moment of failure, and until now rendered it as prose: {@code charge(orderId=889)} names
 * neither the class it lives on nor the type of 889.
 *
 * <p>Only the innermost captured frame becomes a seed. Captures are appended as the throwable
 * unwinds, so the first one is the closest to the throw — the culprit, and the call worth
 * reconstructing. The frames above it are context the {@code captured:} section already shows.
 *
 * @param className fully qualified, because a test has to import it
 * @param methodName the method that threw
 * @param params declared type, name and value per argument, in declaration order
 */
public record ReproSeed(String className, String methodName, List<Param> params) {

    /**
     * One argument at the throw site.
     *
     * @param type the declared parameter type, or the runtime class when the method could not
     *             be resolved unambiguously — an overload of the same arity is left unresolved
     *             rather than guessed, since a wrong type is worse than none
     * @param name the parameter name when compiled with {@code -parameters}, else {@code argN}
     * @param value already truncated and privacy-filtered by the agent; redacted again by the
     *              core before it reaches a report
     */
    public record Param(String type, String name, String value) {}

    /** The simple class name, for a rendering that has already stated the package. */
    public String simpleClassName() {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
