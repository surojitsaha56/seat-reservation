package com.seatreservation.system.observability;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

/**
 * Replaces Boot's DataSource health check (bean name dbHealthIndicator => component "db", part of the readiness
 * group). It uses a dedicated short-lived connection with 2s connect/socket timeouts instead of the pool, so:
 * - with Postgres down readiness answers 503 in ~2s (the pool's 30s connection-timeout is kept for burst traffic);
 * - a saturated pool under load cannot make readiness flap to DOWN.
 */
@Component("dbHealthIndicator")
public class DbHealthIndicator extends AbstractHealthIndicator {
    private final JdbcConnectionDetails details;

    public DbHealthIndicator(JdbcConnectionDetails details) {
        super("Database health check failed");
        this.details = details;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        Properties p = new Properties();
        p.setProperty("user", details.getUsername());
        p.setProperty("password", details.getPassword());
        p.setProperty("connectTimeout", "2");
        p.setProperty("loginTimeout", "2");
        p.setProperty("socketTimeout", "2");
        try (Connection c = DriverManager.getConnection(details.getJdbcUrl(), p);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT 1")) {
            if (rs.next()) {
                builder.up();
            } else {
                builder.down();
            }
        }
    }
}
