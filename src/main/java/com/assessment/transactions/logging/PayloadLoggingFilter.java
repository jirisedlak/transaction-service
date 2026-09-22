package com.assessment.transactions.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Logs request and response payloads on the {@code http.payload} logger at DEBUG, for debugging.
 * Runs right after {@link CorrelationIdFilter} so every line already carries the correlation id.
 *
 * <p>Bodies are buffered with Spring's content-caching wrappers, logged only for text-like content
 * types (JSON, problem+json, text/*), and truncated to {@code http.payload-logging.max-length}
 * characters. Disable with {@code http.payload-logging.enabled=false} or by raising the logger
 * level above DEBUG.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class PayloadLoggingFilter extends OncePerRequestFilter {

    static final String LOGGER_NAME = "http.payload";
    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private final PayloadLoggingProperties properties;

    public PayloadLoggingFilter(PayloadLoggingProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !log.isDebugEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cachedRequest, cachedResponse);
        } finally {
            logRequest(cachedRequest);
            logResponse(cachedResponse);
            cachedResponse.copyBodyToResponse(); // the wrapper swallowed the body; hand it to the client now
        }
    }

    private void logRequest(ContentCachingRequestWrapper request) {
        String idempotencyKey = request.getHeader("Idempotency-Key");
        log.debug("> {} {}{} content-type={} idempotency-key={} body={}",
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString() == null ? "" : "?" + request.getQueryString(),
                valueOrDash(request.getContentType()),
                valueOrDash(idempotencyKey),
                body(request.getContentAsByteArray(), request.getContentType(), request.getCharacterEncoding()));
    }

    private void logResponse(ContentCachingResponseWrapper response) {
        log.debug("< {} content-type={} body={}",
                response.getStatus(),
                valueOrDash(response.getContentType()),
                body(response.getContentAsByteArray(), response.getContentType(), response.getCharacterEncoding()));
    }

    private String body(byte[] content, String contentType, String encoding) {
        if (content.length == 0) {
            return "<empty>";
        }
        if (!isTextLike(contentType)) {
            return "<" + content.length + " bytes of " + contentType + ">";
        }
        Charset charset = charset(encoding);
        int max = properties.maxLength();
        String text = new String(content, 0, Math.min(content.length, max * 4), charset); // bound the decode, too
        if (text.length() > max) {
            return text.substring(0, max) + "...(" + content.length + " bytes total)";
        }
        return text;
    }

    private static boolean isTextLike(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return type.isCompatibleWith(MediaType.APPLICATION_JSON)
                    || type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    || "text".equals(type.getType())
                    || type.getSubtype().endsWith("+json");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Charset charset(String encoding) {
        try {
            return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String valueOrDash(String value) {
        return value == null ? "-" : value;
    }
}
