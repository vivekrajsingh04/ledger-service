package com.ledger.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a trace id on every request, in the MDC (so it appears in every JSON log
 * line), in the response header (so a client can quote it), and in error bodies.
 *
 * <p>Honours an inbound {@code X-Trace-Id} so a trace started upstream survives
 * the hop, which is the difference between a trace id and a per-service request
 * id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String traceId = (inbound == null || inbound.isBlank())
                ? UUID.randomUUID().toString()
                : inbound;

        MDC.put(MDC_KEY, traceId);
        MDC.put("method", request.getMethod());
        MDC.put("path", request.getRequestURI());
        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey != null) {
            MDC.put("idempotencyKey", idempotencyKey);
        }
        response.setHeader(HEADER, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Servlet containers reuse threads; a stale MDC would attribute the
            // next request's logs to this one.
            MDC.clear();
        }
    }
}
