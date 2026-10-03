package com.seatreservation.system.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class DatabaseUrlEnvironmentPostProcessorTest {

    private static Map<String, Object> c(String url) {
        return DatabaseUrlEnvironmentPostProcessor.convert(url);
    }

    @Test
    void withPort() {
        Map<String, Object> m = c("postgres://seats:seats@localhost:5432/seats");
        assertEquals("jdbc:postgresql://localhost:5432/seats", m.get("spring.datasource.url"));
        assertEquals("seats", m.get("spring.datasource.username"));
        assertEquals("seats", m.get("spring.datasource.password"));
    }

    @Test
    void withoutPortDefaultsTo5432AndInternalHostGetsNoSsl() {
        Map<String, Object> m = c("postgres://u:p@dpg-abc123-a/mydb");
        assertEquals("jdbc:postgresql://dpg-abc123-a:5432/mydb", m.get("spring.datasource.url"));
    }

    @Test
    void postgresqlSchemeAndExternalHostGetsSslRequire() {
        Map<String, Object> m = c("postgresql://u:p@dpg-abc123-a.oregon-postgres.render.com/mydb");
        assertEquals("jdbc:postgresql://dpg-abc123-a.oregon-postgres.render.com:5432/mydb?sslmode=require",
                m.get("spring.datasource.url"));
    }

    @Test
    void encodedPasswordIsDecoded() {
        Map<String, Object> m = c("postgres://us%40er:p%40ss%3Aw%2Ford%2B%20x@h:6543/d");
        assertEquals("us@er", m.get("spring.datasource.username"));
        assertEquals("p@ss:w/ord+ x", m.get("spring.datasource.password"));
        assertEquals("jdbc:postgresql://h:6543/d", m.get("spring.datasource.url"));
    }

    @Test
    void literalPlusInPasswordSurvives() {
        assertEquals("a+b", c("postgres://u:a+b@h/d").get("spring.datasource.password"));
    }

    @Test
    void queryParamsPreservedAndExplicitSslModeRespected() {
        Map<String, Object> m = c("postgres://u:p@a.b.com:5432/d?sslmode=disable&application_name=x");
        assertEquals("jdbc:postgresql://a.b.com:5432/d?sslmode=disable&application_name=x",
                m.get("spring.datasource.url"));
        Map<String, Object> n = c("postgres://u:p@a.b.com/d?application_name=x");
        assertEquals("jdbc:postgresql://a.b.com:5432/d?application_name=x&sslmode=require",
                n.get("spring.datasource.url"));
    }

    @Test
    void jdbcAndAbsentAreUntouched() {
        assertTrue(c("jdbc:postgresql://db:5432/seats").isEmpty());
        assertTrue(c(null).isEmpty());
        assertTrue(c("").isEmpty());
    }

    @Test
    void noCredentialsLeavesDbUserAlone() {
        Map<String, Object> m = c("postgres://h/d");
        assertFalse(m.containsKey("spring.datasource.username"));
        assertFalse(m.containsKey("spring.datasource.password"));
    }

    @Test
    void postProcessorTakesPrecedenceOverEarlierSources() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("t",
                Map.of("DATABASE_URL", "postgres://u:p@h:1/d", "spring.datasource.username", "other")));
        new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertEquals("jdbc:postgresql://h:1/d", env.getProperty("spring.datasource.url"));
        assertEquals("u", env.getProperty("spring.datasource.username"));
    }

    @Test
    void absentLeavesEnvironmentUntouched() {
        StandardEnvironment env = new StandardEnvironment();
        new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertNull(env.getPropertySources().get(DatabaseUrlEnvironmentPostProcessor.SOURCE_NAME));
    }
}
