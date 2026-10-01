package com.example.demo.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a {@code traceId} in the MDC for the whole request — the job Micrometer Tracing or
 * the OpenTelemetry agent does in a real app. It runs first, so the request line stacktale
 * logs and the error logged by {@link ErrorHandler} all land in the same story.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put("traceId", UUID.randomUUID().toString().substring(0, 8));
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
