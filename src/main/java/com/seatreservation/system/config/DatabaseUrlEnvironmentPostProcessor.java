package com.seatreservation.system.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Accepts a Render-style DATABASE_URL ({@code postgres://user:pass@host[:port]/db[?params]}, also
 * {@code postgresql://}) and contributes the JDBC equivalents as spring.datasource.url/username/password with
 * top precedence, so Hikari, Flyway and DbHealthIndicator (via JdbcConnectionDetails) all see the same values.
 * {@code jdbc:...} URLs, a missing DATABASE_URL, DB_URL and DB_USER/DB_PASSWORD are left untouched.
 * The password is never logged.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "databaseUrlConversion";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> props = convert(environment.getProperty("DATABASE_URL"));
        if (!props.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, props));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE; // after config data, so addFirst wins over application.properties
    }

    /** Returns the properties to contribute, or an empty map when the value is not a postgres(ql):// URL. */
    static Map<String, Object> convert(String url) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (url == null) {
            return out;
        }
        String s = url.trim();
        String rest;
        if (s.regionMatches(true, 0, "postgres://", 0, 11)) {
            rest = s.substring(11);
        } else if (s.regionMatches(true, 0, "postgresql://", 0, 13)) {
            rest = s.substring(13);
        } else {
            return out;
        }
        String query = null;
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        String db = slash >= 0 ? rest.substring(slash + 1) : "";
        int at = authority.lastIndexOf('@');
        String hostPort = at >= 0 ? authority.substring(at + 1) : authority;
        String userInfo = at >= 0 ? authority.substring(0, at) : null;

        String host = hostPort;
        String port = "5432";
        int colon = hostPort.lastIndexOf(':');
        if (colon >= 0 && hostPort.indexOf(']', colon) < 0) { // not inside an IPv6 literal
            host = hostPort.substring(0, colon);
            if (colon + 1 < hostPort.length()) {
                port = hostPort.substring(colon + 1);
            }
        }
        if (host.isEmpty()) {
            return out; // not usable; let the normal configuration surface the error
        }

        // Render external hostnames are dotted (...render.com) and require TLS; internal ones (dpg-xxx-a) are
        // dotless and TLS is optional. Only add sslmode when the URL did not specify one.
        boolean hasSslMode = query != null && query.matches("(?i)(^|.*&)sslmode=.*");
        if (!hasSslMode && host.contains(".") && !host.equals("localhost")) {
            query = (query == null || query.isEmpty()) ? "sslmode=require" : query + "&sslmode=require";
        }

        out.put("spring.datasource.url", "jdbc:postgresql://" + host + ":" + port + "/" + db
                + (query == null || query.isEmpty() ? "" : "?" + query));
        if (userInfo != null) {
            int c = userInfo.indexOf(':');
            String user = c >= 0 ? userInfo.substring(0, c) : userInfo;
            if (!user.isEmpty()) {
                out.put("spring.datasource.username", decode(user));
            }
            if (c >= 0) {
                out.put("spring.datasource.password", decode(userInfo.substring(c + 1)));
            }
        }
        return out;
    }

    private static String decode(String v) {
        // URLDecoder would turn '+' into a space; a literal '+' in a password must survive
        return URLDecoder.decode(v.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
