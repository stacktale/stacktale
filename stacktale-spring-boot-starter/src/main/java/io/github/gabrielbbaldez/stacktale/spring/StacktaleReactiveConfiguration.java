package io.github.gabrielbbaldez.stacktale.spring;

import io.micrometer.context.ContextRegistry;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Hooks;

/**
 * Reactive story support: registers a {@code traceId} MDC accessor with micrometer's
 * {@link ContextRegistry} and enables Reactor's automatic context propagation, so the
 * traceId planted by {@link StacktaleWebFluxFilter} in the Reactor Context is restored
 * into the MDC across every operator and scheduler hop — keeping the story whole in
 * WebFlux pipelines.
 */
@AutoConfiguration(after = StacktaleAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass({ContextRegistry.class, Hooks.class})
@ConditionalOnProperty(prefix = "stacktale", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StacktaleReactiveConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "stacktale", name = "request-logging", havingValue = "true", matchIfMissing = true)
    public StacktaleWebFluxFilter stacktaleWebFluxFilter() {
        registerMdcPropagation();
        return new StacktaleWebFluxFilter();
    }

    static void registerMdcPropagation() {
        ContextRegistry registry = ContextRegistry.getInstance();
        boolean alreadyRegistered = registry.getThreadLocalAccessors().stream()
                .anyMatch(a -> StacktaleWebFluxFilter.TRACE_KEY.equals(a.key()));
        if (!alreadyRegistered) {
            registry.registerThreadLocalAccessor(StacktaleWebFluxFilter.TRACE_KEY,
                    () -> MDC.get(StacktaleWebFluxFilter.TRACE_KEY),
                    value -> MDC.put(StacktaleWebFluxFilter.TRACE_KEY, value),
                    () -> MDC.remove(StacktaleWebFluxFilter.TRACE_KEY));
        }
        Hooks.enableAutomaticContextPropagation();
    }
}
