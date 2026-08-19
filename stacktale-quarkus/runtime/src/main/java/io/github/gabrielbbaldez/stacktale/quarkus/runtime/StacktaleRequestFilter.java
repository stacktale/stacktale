package io.github.gabrielbbaldez.stacktale.quarkus.runtime;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.inject.Inject;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.logging.Logger;

/**
 * Opens each request's story with its HTTP line — {@code POST /orders/889/checkout} — so that
 * when an error is reported, the story already shows which request was in flight. The Quarkus
 * counterpart of the Spring starter's servlet/WebFlux filters.
 *
 * <p>The line goes through the dedicated {@code stacktale.request} JUL logger, matching the
 * logger name the other adapters use so the story-correlation behaviour is consistent. This class
 * is only registered by the deployment step when a REST layer is present, so the hard reference to
 * the RESTEasy Reactive API here is safe.
 */
public class StacktaleRequestFilter {

    static final String REQUEST_LOGGER = "stacktale.request";

    private static final Logger LOG = Logger.getLogger(REQUEST_LOGGER);

    @Inject
    StacktaleConfig config;

    @ServerRequestFilter
    public void openStory(ContainerRequestContext ctx) {
        if (!config.requestLogging()) {
            return;
        }
        String query = ctx.getUriInfo().getRequestUri().getRawQuery();
        String line = ctx.getMethod() + " " + ctx.getUriInfo().getPath()
                + (query != null ? "?" + query : "");
        LOG.info(line);
    }
}
