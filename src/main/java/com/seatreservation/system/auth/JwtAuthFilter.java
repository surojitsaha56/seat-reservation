package com.seatreservation.system.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Validates "Authorization: Bearer <jwt>" on protected user endpoints. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthFilter extends OncePerRequestFilter {
    private static final String[] PROTECTED = {"/shows/*/reserve", "/reservations/**", "/me"};
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String p : PROTECTED) {
            if (MATCHER.match(p, path)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            reject(res, "Missing bearer token");
            return;
        }
        String userId;
        try {
            userId = jwt.verify(header.substring(7).trim());
        } catch (RuntimeException e) {
            reject(res, "Invalid or expired token");
            return;
        }
        req.setAttribute(AuthContext.USER_ID_ATTR, userId);
        MDC.put("user_id", userId);
        chain.doFilter(req, res);
    }

    private static void reject(HttpServletResponse res, String message) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.setHeader("WWW-Authenticate", "Bearer");
        res.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}");
    }
}
