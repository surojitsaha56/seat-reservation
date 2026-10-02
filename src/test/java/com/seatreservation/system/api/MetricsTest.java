package com.seatreservation.system.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.seatreservation.system.auth.JwtService;
import com.seatreservation.system.exception.ReserveDeclinedException;
import com.seatreservation.system.model.CreateShowRequest;
import com.seatreservation.system.service.ReservationService;
import com.seatreservation.system.service.ShowService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Counters are asserted as DELTAS (the registry is shared by all tests in this class); the seats gauge is cached
 * ~1s, so tests wait past the TTL before reading it.
 */
@SpringBootTest(properties = {
        // Boot's test support turns metric export off by default; the prometheus endpoint needs it on
        "management.defaults.metrics.export.enabled=true",
        "management.prometheus.metrics.export.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers
class MetricsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;
    @Autowired ReservationService reservations;
    @Autowired ShowService shows;

    // ---- helpers ----

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        assertTrue(c != null, "counter missing: " + name + List.of(tags));
        return c.count();
    }

    private double gauge(String name, String... tags) throws Exception {
        Thread.sleep(1200); // past the 1s snapshot cache
        var g = registry.find(name).tags(tags).gauge();
        assertTrue(g != null, "gauge missing: " + name);
        return g.value();
    }

    /** All counters of interest in one array so deltas are easy to compare. */
    private static final String[][] SERIES = {
            {"reservations.confirmed"}, {"reservations.idempotent.replay"}, {"reservations.cancelled"},
            {"reservations.errors"}, {"cancels.errors"},
            {"reservations.declined", "reason", "seat_taken"}, {"reservations.declined", "reason", "per_user_limit"},
            {"reservations.declined", "reason", "idempotency_conflict"},
            {"reservations.declined", "reason", "validation"},
            {"cancels.declined", "reason", "already_cancelled"}, {"cancels.declined", "reason", "not_found"}};

    private double[] snap() {
        double[] d = new double[SERIES.length];
        for (int i = 0; i < SERIES.length; i++) {
            String[] s = SERIES[i];
            d[i] = counter(s[0], java.util.Arrays.copyOfRange(s, 1, s.length));
        }
        return d;
    }

    private String newShow(int limit, String... seats) throws Exception {
        String seatJson = "[\"" + String.join("\",\"", seats) + "\"]";
        String res = mvc.perform(post("/shows").header("X-Admin-Token", "dev-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"S\",\"seats\":" + seatJson + ",\"price_paise\":25000,\"per_user_limit\":" + limit + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.id");
    }

    private ResultActions reserve(String show, String user, String key, String body) throws Exception {
        return mvc.perform(post("/shows/" + show + "/reserve").header("Authorization", "Bearer " + jwt.issue(user))
                .header("Idempotency-Key", show + key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String book(String show, String user, String key, String... seats) throws Exception {
        String body = "{\"seats\":[\"" + String.join("\",\"", seats) + "\"]}";
        String res = reserve(show, user, key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.reservation_id");
    }

    private ResultActions cancel(String id, String user) throws Exception {
        return mvc.perform(post("/reservations/" + id + "/cancel").header("Authorization", "Bearer " + jwt.issue(user)));
    }

    private void assertDeltas(double[] before, double... expected) {
        double[] after = snap();
        for (int i = 0; i < SERIES.length; i++) {
            assertEquals(expected[i], after[i] - before[i], 1e-9,
                    "delta of " + String.join(",", SERIES[i]));
        }
    }

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---- tests ----

    @Test
    void scriptedSequenceBumpsEachCounterExactlyOnce() throws Exception {
        String show = newShow(2, "A1", "A2", "A3", "A4", "A5", "A6");
        double[] before = snap();

        String r1 = book(show, "u1", "k1", "A1");                       // confirmed
        book(show, "u2", "k2", "A2");                                   // confirmed
        reserve(show, "u1", "k1", "{\"seats\":[\"A1\"]}").andExpect(status().isOk());   // replay
        reserve(show, "u3", "k3", "{\"seats\":[\"A1\"]}").andExpect(status().isConflict()); // seat_taken
        book(show, "u1", "k4", "A3");                                   // confirmed (u1 now at limit 2)
        reserve(show, "u1", "k5", "{\"seats\":[\"A4\"]}").andExpect(status().isConflict()); // per_user_limit
        reserve(show, "u1", "k1", "{\"seats\":[\"A5\"]}").andExpect(status().isConflict()); // idempotency_conflict
        reserve(show, "u4", "k6", "{\"seats\":[]}").andExpect(status().isBadRequest());     // validation
        cancel(r1, "u1").andExpect(status().isOk());                     // cancelled
        cancel(r1, "u1").andExpect(status().isConflict());               // already_cancelled
        cancel(UUID.randomUUID().toString(), "u1").andExpect(status().isNotFound()); // not_found

        // order follows SERIES: confirmed, replay, cancelled, errors, cancel_errors,
        // seat_taken, per_user_limit, idempotency_conflict, validation, already_cancelled, not_found
        assertDeltas(before, 3, 1, 1, 0, 0, 1, 1, 1, 1, 1, 1);

        String text = scrape();
        for (String line : new String[] {
                "reservations_confirmed_total ", "reservations_idempotent_replay_total ",
                "reservations_cancelled_total ", "reservations_errors_total ",
                "reservations_declined_total{reason=\"seat_taken\"}",
                "reservations_declined_total{reason=\"per_user_limit\"}",
                "reservations_declined_total{reason=\"idempotency_conflict\"}",
                "reservations_declined_total{reason=\"validation\"}",
                "cancels_declined_total{reason=\"already_cancelled\"}",
                "cancels_declined_total{reason=\"not_found\"}",
                "seats{status=\"available\"}", "seats{status=\"held\"}", "seats{status=\"confirmed\"}",
                "seats_capacity "}) {
            assertTrue(text.contains("\n" + line) || text.startsWith(line), "missing in /actuator/prometheus: " + line);
        }

        // reconcile: delta(confirmed - cancelled) == delta(confirmed seats gauge) (1 seat per reservation here)
        assertEquals(2, gauge("seats", "status", "confirmed") - gaugeBase(show), 0.0);
    }

    /** Confirmed seats gauge minus this show's confirmed seats == confirmed seats of all other shows. */
    private double gaugeBase(String show) {
        Long here = jdbc.queryForObject("SELECT count(*) FROM seats WHERE show_id=? AND status='confirmed'",
                Long.class, UUID.fromString(show));
        Long all = jdbc.queryForObject("SELECT count(*) FROM seats WHERE status='confirmed'", Long.class);
        return all - here;
    }

    @Test
    void allSeriesArePreRegisteredAtZeroOrMore() throws Exception {
        String text = scrape();
        for (String reason : new String[] {"seat_taken", "per_user_limit", "idempotency_conflict", "validation"}) {
            assertTrue(text.contains("reservations_declined_total{reason=\"" + reason + "\"}"), reason);
        }
        assertTrue(text.contains("cancels_declined_total{reason=\"already_cancelled\"}"));
        assertTrue(text.contains("cancels_declined_total{reason=\"not_found\"}"));
    }

    @Test
    void declinedReserveRolledBackDoesNotBumpConfirmed() throws Exception {
        String show = newShow(1, "A1", "A2");
        book(show, "u1", "k1", "A1");
        long reservationsBefore = jdbc.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?",
                Long.class, UUID.fromString(show));
        double[] before = snap();
        // passes the pre-checks, claims the idempotency row, then fails the hold update => tx rolls back
        reserve(show, "u1", "k2", "{\"seats\":[\"A2\"]}").andExpect(status().isConflict());
        assertDeltas(before, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0);
        assertEquals(reservationsBefore, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?",
                Long.class, UUID.fromString(show)), "idempotency row must be rolled back");
    }

    @Test
    void twoHundredUsersRaceForFiveSeats() throws Exception {
        List<String> labels = List.of("S1", "S2", "S3", "S4", "S5");
        UUID show = shows.create(new CreateShowRequest("S", labels, 100, 4)).id();
        double[] before = snap();
        double availBefore = gauge("seats", "status", "available");
        double confBefore = gauge("seats", "status", "confirmed");
        double totalBefore = gauge("seats.capacity");

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String u = "racer-" + i;
            String seat = labels.get(i % 5);
            Callable<String> t = () -> {
                go.await();
                try {
                    return reservations.reserve(show, u, "key-" + u, List.of(seat)).outcome().name();
                } catch (ReserveDeclinedException e) {
                    return e.reason().code();
                }
            };
            fs.add(pool.submit(t));
        }
        go.countDown();
        int confirmed = 0, taken = 0;
        for (Future<String> f : fs) {
            String o = f.get(120, TimeUnit.SECONDS);
            if (o.equals("CONFIRMED")) confirmed++;
            else if (o.equals("seat_taken")) taken++;
        }
        pool.shutdown();
        assertEquals(5, confirmed);
        assertEquals(195, taken);

        assertDeltas(before, 5, 0, 0, 0, 0, 195, 0, 0, 0, 0, 0);

        double avail = gauge("seats", "status", "available");
        double conf = gauge("seats", "status", "confirmed");
        double held = gauge("seats", "status", "held");
        double total = gauge("seats.capacity");
        assertEquals(5, conf - confBefore, 0.0);
        assertEquals(-5, avail - availBefore, 0.0);
        assertEquals(totalBefore, total, 0.0);
        assertEquals(total, avail + held + conf, 0.0, "invariant at scrape");

        mvc.perform(get("/shows/" + show)).andExpect(status().isOk());
        Long dbConfirmed = jdbc.queryForObject("SELECT count(*) FROM seats WHERE show_id=? AND status='confirmed'",
                Long.class, show);
        assertEquals(5L, dbConfirmed);
    }
}
