package com.seatreservation.system.api;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthApiTest {

    static final String SECRET = "dev-secret-change-me-dev-secret-change-me";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    MockMvc mvc;

    private String issue(String userId) throws Exception {
        String res = mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + userId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id", is(userId)))
                .andExpect(jsonPath("$.expires_in", is(3600)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.token");
    }

    private static String sign(String secret, String sub, long expiresInMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder().subject(sub).issuedAt(new Date(now - 10_000))
                .expiration(new Date(now + expiresInMs))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void issuedTokenAuthenticatesMe() throws Exception {
        String token = issue("alice");
        mvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id", is("alice")));
    }

    @Test
    void invalidUserIdIs400() throws Exception {
        String[] bad = {"{}", "{\"user_id\":\"  \"}", "{\"user_id\":\"" + "x".repeat(65) + "\"}", "{not json", ""};
        for (String b : bad) {
            mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(b))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", is("validation_error")));
        }
    }

    @Test
    void missingOrBadTokensAre401() throws Exception {
        mvc.perform(get("/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("unauthorized")))
                .andExpect(jsonPath("$.message").isString());
        mvc.perform(get("/me").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error", is("unauthorized")));
        mvc.perform(get("/me").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/me").header("Authorization", "Bearer " + sign(SECRET, "bob", -5_000)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/me").header("Authorization", "Bearer "
                        + sign("another-secret-another-secret-another!", "bob", 60_000)))
                .andExpect(status().isUnauthorized());
        // sanity: a correctly signed hand-made token is accepted
        mvc.perform(get("/me").header("Authorization", "Bearer " + sign(SECRET, "bob", 60_000)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user_id", is("bob")));
    }

    @Test
    void protectedReservationPathsRequireToken() throws Exception {
        mvc.perform(post("/shows/00000000-0000-0000-0000-000000000000/reserve"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/reservations/abc")).andExpect(status().isUnauthorized());
        mvc.perform(get("/shows/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void requestIdIsEchoedOrGenerated() throws Exception {
        mvc.perform(get("/me").header("X-Request-Id", "req-123"))
                .andExpect(header().string("X-Request-Id", "req-123"));
        mvc.perform(get("/me"))
                .andExpect(header().string("X-Request-Id", not(emptyOrNullString())));
    }
}
