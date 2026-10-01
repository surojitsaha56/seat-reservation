package com.seatreservation.system.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Sets request_id in MDC, echoes X-Request-Id and emits one access log line per request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    private static final Logger access = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader(HEADER);
        if (id == null || id.isBlank() || id.length() > 128 || !id.matches("[A-Za-z0-9._\\-]+")) {
            id = UUID.randomUUID().toString();
        }
        MDC.put("request_id", id);
        res.setHeader(HEADER, id);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            String path = req.getRequestURI();
            if (!path.startsWith("/actuator")) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                access.info("request method={} path={} status={} duration_ms={}",
                        req.getMethod(), path, res.getStatus(), ms);
            }
            MDC.clear();
        }
    }
}
