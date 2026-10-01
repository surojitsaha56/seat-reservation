package com.seatreservation.system.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final long expirySeconds;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.expiry-seconds}") long expirySeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("jwt.secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirySeconds = expirySeconds;
    }

    public long expirySeconds() { return expirySeconds; }

    public String issue(String userId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirySeconds * 1000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Returns the subject of a valid token; throws JwtException (or IllegalArgumentException) otherwise. */
    public String verify(String token) {
        String sub = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
        if (sub == null || sub.isBlank()) {
            throw new JwtException("Token has no subject");
        }
        return sub;
    }
}
