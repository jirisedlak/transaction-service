package com.assessment.transactions.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id (taken from {@value TraceContext#CORRELATION_HEADER} if the
 * client sent one, otherwise generated), exposes it in the MDC for the duration of the request,
 * echoes it back in the response header, and writes one access-log line per request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("http.access");
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = incomingCorrelationId(request);
        long start = System.nanoTime();

        try (TraceContext.Scope ignored = TraceContext.with(TraceContext.CORRELATION_ID, correlationId)) {
            response.setHeader(TraceContext.CORRELATION_HEADER, correlationId);
            try {
                chain.doFilter(request, response);
            } finally {
                long millis = (System.nanoTime() - start) / 1_000_000;
                log.info("{} {} -> {} ({} ms)", request.getMethod(), requestPath(request), response.getStatus(), millis);
            }
        }
    }

    private static String incomingCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(TraceContext.CORRELATION_HEADER);
        if (header == null || header.isBlank() || header.length() > MAX_LENGTH) {
            return TraceContext.newCorrelationId();
        }
        return header.strip();
    }

    private static String requestPath(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }
}
