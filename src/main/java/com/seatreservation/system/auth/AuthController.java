package com.seatreservation.system.auth;

import com.seatreservation.system.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class AuthController {
    private static final int MAX_USER_ID = 64;

    private final JwtService jwt;
    private final boolean tokenEndpointEnabled;

    public AuthController(JwtService jwt, @Value("${auth.token-endpoint.enabled}") boolean enabled) {
        this.jwt = jwt;
        this.tokenEndpointEnabled = enabled;
    }

    public record TokenRequest(String user_id) {}

    @PostMapping("/auth/token")
    public Map<String, Object> token(@RequestBody(required = false) TokenRequest req) {
        if (!tokenEndpointEnabled) {
            log.warn("token requested but the token endpoint is disabled");
            throw ApiException.notFound("Not found");
        }
        String userId = req == null ? null : req.user_id();
        if (userId == null || userId.isBlank()) {
            throw ApiException.validation("user_id is required");
        }
        if (userId.length() > MAX_USER_ID) {
            throw ApiException.validation("user_id must be at most " + MAX_USER_ID + " characters");
        }
        String token = jwt.issue(userId);
        log.info("token issued user_id={} expires_in={}", userId, jwt.expirySeconds()); // never log the token itself
        return Map.of("token", token, "user_id", userId, "expires_in", jwt.expirySeconds());
    }

    @GetMapping("/me")
    public Map<String, String> me(HttpServletRequest request) {
        return Map.of("user_id", AuthContext.userId(request));
    }
}
