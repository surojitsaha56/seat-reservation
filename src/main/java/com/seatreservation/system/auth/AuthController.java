package com.seatreservation.system.auth;

import com.seatreservation.system.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
            throw ApiException.notFound("Not found");
        }
        String userId = req == null ? null : req.user_id();
        if (userId == null || userId.isBlank()) {
            throw ApiException.validation("user_id is required");
        }
        if (userId.length() > MAX_USER_ID) {
            throw ApiException.validation("user_id must be at most " + MAX_USER_ID + " characters");
        }
        return Map.of("token", jwt.issue(userId), "user_id", userId, "expires_in", jwt.expirySeconds());
    }

    @GetMapping("/me")
    public Map<String, String> me(HttpServletRequest request) {
        return Map.of("user_id", AuthContext.userId(request));
    }
}
