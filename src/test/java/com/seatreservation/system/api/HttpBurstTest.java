package com.seatreservation.system.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP-level concurrency tests: a real Tomcat on a random port, real filters, JSON and error handler, the real
 * Hikari pool (20), and bursts of requests from a JDK HttpClient. Every test creates its own show. Idempotency keys
 * are scoped per user across shows, so every key is namespaced by show id.
 *
 * Correctness bar: never a double-sell, never a 5xx, every response is valid JSON with a documented status.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Boot's test support turns metric export off by default; the prometheus endpoint needs it on
        "management.defaults.metrics.export.enabled=true",
        "management.prometheus.metrics.export.enabled=true"})
@Testcontainers
class HttpBurstTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Set<Integer> RESERVE_OK = Set.of(201, 200, 409, 400);

    static HttpClient client;
    static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @BeforeAll
    static void startClient() {
        // HTTP/1.1 only (no h2c upgrade attempts). The JDK client has no connection cap for HTTP/1.1.
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(60)).build();
    }

    @AfterAll
    static void stopClient() {
        client.close();
    }

    // ---------------------------------------------------------------- helpers

    /** One observed HTTP exchange. status -1 means the client itself failed (connect error / timeout). */
    record Resp(String op, int status, String body, String error) {
        JsonNode json() {
            return JSON.readTree(body);
        }

        String reason() {
            return json().path("reason").asString("");
        }

        /** e.g. "reserve:201", "reserve:409:seat_taken", "cancel:404". */
        String label() {
            if (status == 409 && op.equals("reserve")) return op + ":409:" + reason();
            return op + ":" + status;
        }
    }

    /** Count of responses per label, plus convenience accessors. */
    record Tally(Map<String, Long> counts) {
        static Tally of(List<Resp> rs) {
            return new Tally(rs.stream().collect(Collectors.groupingBy(Resp::label, TreeMap::new, Collectors.counting())));
        }

        long get(String label) {
            return counts.getOrDefault(label, 0L);
        }

        long prefix(String p) {
            return counts.entrySet().stream().filter(e -> e.getKey().startsWith(p)).mapToLong(Map.Entry::getValue).sum();
        }

        @Override
        public String toString() {
            return counts.toString();
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Resp send(String op, HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = client.send(b.timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Resp(op, r.statusCode(), r.body(), null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Resp(op, -1, "", e.toString());
        }
    }

    private Resp postJson(String op, String path, String bearer, String idemKey, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        return send(op, b);
    }

    private Resp get(String path) {
        return send("get", HttpRequest.newBuilder(URI.create(url(path))).GET());
    }

    /** Token for a user, minted once through POST /auth/token. */
    private String token(String user) {
        String t = TOKENS.get(user);
        if (t != null) return t;
        Resp r = postJson("auth", "/auth/token", null, null, "{\"user_id\":\"" + user + "\"}");
        assertEquals(200, r.status(), "auth/token for " + user + ": " + r);
        t = r.json().path("token").asString();
        TOKENS.put(user, t);
        return t;
    }

    /** Mint tokens for users prefix-0 .. prefix-(n-1) in parallel (also exercises /auth/token concurrently). */
    private List<String> users(String prefix, int n) throws Exception {
        List<String> us = IntStream.range(0, n).mapToObj(i -> prefix + "-" + i).toList();
        burst(us.stream().<Callable<Boolean>>map(u -> () -> token(u) != null).toList(), 64);
        return us;
    }

    private String newShow(int seats, int limit) {
        String seatJson = IntStream.rangeClosed(1, seats).mapToObj(i -> "\"S" + i + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url("/shows")))
                .header("Content-Type", "application/json").header("X-Admin-Token", "dev-admin-token")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Burst\",\"seats\":" + seatJson
                        + ",\"price_paise\":25000,\"per_user_limit\":" + limit + "}"));
        Resp r = send("create", b);
        assertEquals(201, r.status(), "create show: " + r);
        return r.json().path("id").asString();
    }

    private static String seatsJson(List<String> seats, String extra) {
        return "{\"seats\":" + seats.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",", "[", "]"))
                + extra + "}";
    }

    private Resp reserve(String show, String user, String key, List<String> seats, String extraBodyFields) {
        return postJson("reserve", "/shows/" + show + "/reserve", token(user), show + "-" + key,
                seatsJson(seats, extraBodyFields));
    }

    private Resp cancel(String reservationId, String user) {
        return postJson("cancel", "/reservations/" + reservationId + "/cancel", token(user), null, "");
    }

    /**
     * One virtual thread per task, all released together by a start latch; at most `maxInFlight` HTTP calls are
     * outstanding at any time (a single JVM opening 2000 sockets in one instant measures the OS accept backlog,
     * not the server).
     */
    private <T> List<T> burst(List<Callable<T>> tasks, int maxInFlight) throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            Semaphore inFlight = new Semaphore(maxInFlight);
            List<Future<T>> fs = new ArrayList<>();
            for (Callable<T> t : tasks) {
                fs.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    inFlight.acquire();
                    try {
                        return t.call();
                    } finally {
                        inFlight.release();
                    }
                }));
            }
            assertTrue(ready.await(60, TimeUnit.SECONDS), "workers did not all start");
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) out.add(f.get(180, TimeUnit.SECONDS));
            return out;
        }
    }

    /** All tasks in flight at once. */
    private <T> List<T> burst(List<Callable<T>> tasks) throws Exception {
        return burst(tasks, Integer.MAX_VALUE >> 1);
    }

    /** No client failure, no 5xx, only allowed statuses, every body is valid JSON. */
    private void assertClean(List<Resp> rs, Set<Integer> allowed) {
        List<String> bad = new ArrayList<>();
        for (Resp r : rs) {
            if (r.status() == -1) bad.add("client error: " + r.error());
            else if (r.status() >= 500) bad.add("5xx: " + r);
            else if (!allowed.contains(r.status())) bad.add("unexpected status: " + r);
            else {
                try {
                    JsonNode n = r.json();
                    if (!n.isObject()) bad.add("not a JSON object: " + r);
                } catch (Exception e) {
                    bad.add("invalid JSON: " + r);
                }
            }
        }
        assertTrue(bad.isEmpty(), bad.size() + " bad responses of " + rs.size() + ", first: "
                + bad.subList(0, Math.min(5, bad.size())));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /**
     * DB-level correctness bar for one show: no seat sold twice, every confirmed seat belongs to exactly one
     * confirmed reservation of the same user, hold counters match, nothing left 'held'.
     */
    private void assertDbConsistent(String showId) {
        UUID show = UUID.fromString(showId);
        long confirmedSeats = count("SELECT count(*) FROM seats WHERE show_id=? AND status='confirmed'", show);
        assertEquals(0, count("SELECT count(*) FROM seats WHERE show_id=? AND status NOT IN ('available','confirmed')", show),
                "no seat may stay held");
        assertEquals(0, count("SELECT count(*) FROM seats WHERE show_id=? AND status='available' "
                + "AND (reservation_id IS NOT NULL OR user_id IS NOT NULL)", show), "available seats carry no owner");
        // each confirmed seat joins to exactly one confirmed reservation of the same user/show that lists it
        assertEquals(confirmedSeats, count("SELECT count(*) FROM seats s JOIN reservations r ON r.id=s.reservation_id "
                + "WHERE s.show_id=? AND s.status='confirmed' AND r.status='confirmed' AND r.show_id=s.show_id "
                + "AND r.user_id=s.user_id AND s.label = ANY(r.seats)", show), "confirmed seats <-> reservations");
        // sum of seats of confirmed reservations == confirmed seats (so no seat is claimed by two reservations)
        assertEquals(confirmedSeats, count("SELECT coalesce(sum(cardinality(seats)),0) FROM reservations "
                + "WHERE show_id=? AND status='confirmed'", show), "sum of confirmed reservation seats");
        assertEquals(0, count("SELECT count(*) FROM seats s JOIN reservations r ON r.id=s.reservation_id "
                + "WHERE s.show_id=? AND r.status='cancelled'", show), "cancelled reservation still owns a seat");
        // hold counters == confirmed seats per user
        assertEquals(0, count("SELECT count(*) FROM (SELECT coalesce(h.user_id, c.user_id) u, "
                + "coalesce(h.seat_count,0) hc, coalesce(c.n,0) cn FROM "
                + "(SELECT user_id, seat_count FROM user_show_holds WHERE show_id=?) h FULL JOIN "
                + "(SELECT user_id, count(*) n FROM seats WHERE show_id=? AND status='confirmed' GROUP BY user_id) c "
                + "ON c.user_id=h.user_id) x WHERE hc <> cn", show, show), "hold counter == confirmed seats per user");
        // the public counts agree with the DB and add up
        Resp r = get("/shows/" + showId);
        assertEquals(200, r.status(), r.toString());
        JsonNode c = r.json().path("counts");
        assertEquals(confirmedSeats, c.path("confirmed").asLong(), "GET /shows counts.confirmed");
        assertEquals(count("SELECT count(*) FROM seats WHERE show_id=? AND status='available'", show),
                c.path("available").asLong());
        assertEquals(0, c.path("held").asLong());
        assertEquals(c.path("total").asLong(), c.path("available").asLong() + c.path("held").asLong()
                + c.path("confirmed").asLong(), "available+held+confirmed == total");
    }

    // ---------------------------------------------------------------- tests

    @Test
    void hotSeatStormOverHttpSellsExactlyOnce() throws Exception {
        String show = newShow(50, 4);
        List<String> users = users("hot", 500);
        List<Callable<Resp>> tasks = users.stream().<Callable<Resp>>map(
                u -> () -> reserve(show, u, "k", List.of("S7"), "")).toList();

        List<Resp> rs = burst(tasks);
        Tally t = Tally.of(rs);
        System.out.println("[HttpBurst] hot seat: " + t);

        assertClean(rs, Set.of(201, 409));
        assertEquals(1, t.get("reserve:201"), "exactly one winner: " + t);
        assertEquals(499, t.get("reserve:409:seat_taken"), "everyone else seat_taken: " + t);
        assertEquals(500, t.prefix("reserve:"));

        Resp winner = rs.stream().filter(r -> r.status() == 201).findFirst().orElseThrow();
        assertEquals("S7", winner.json().path("seats").get(0).asString());
        assertEquals(1, count("SELECT count(*) FROM reservations WHERE show_id=?", UUID.fromString(show)),
                "declines leave no reservation row");
        assertDbConsistent(show);
    }

    @Test
    void zero5xxStampedeOverRandomSeats() throws Exception {
        String show = newShow(200, 4);
        List<String> users = users("stamp", 300);
        Random rnd = new Random(42);
        List<Callable<Resp>> tasks = new ArrayList<>();
        List<Callable<Resp>> retries = new ArrayList<>();
        for (int i = 0; i < 1800; i++) {
            String u = users.get(rnd.nextInt(users.size()));
            String key = "r" + i;
            List<String> seats;
            if (i % 100 == 99) {
                seats = List.of(); // validation error
            } else {
                int n = 1 + rnd.nextInt(3);
                Set<String> picked = new java.util.LinkedHashSet<>();
                while (picked.size() < n) picked.add("S" + (1 + rnd.nextInt(200)));
                seats = new ArrayList<>(picked);
            }
            tasks.add(() -> reserve(show, u, key, seats, ""));
            if (i % 9 == 0) retries.add(() -> reserve(show, u, key, seats, "")); // client retry, same key
        }
        tasks.addAll(retries); // >= 2000 requests in total
        Collections.shuffle(tasks, rnd);
        assertTrue(tasks.size() >= 2000, "request count " + tasks.size());

        long t0 = System.nanoTime();
        List<Resp> rs = burst(tasks, 300);
        Tally t = Tally.of(rs);
        System.out.println("[HttpBurst] stampede " + rs.size() + " requests in "
                + (System.nanoTime() - t0) / 1_000_000 + " ms: " + t);

        assertClean(rs, RESERVE_OK);
        assertTrue(t.get("reserve:201") > 0, "some reservations must succeed: " + t);
        assertEquals(rs.stream().filter(r -> r.status() == 400).count(), t.get("reserve:400"));
        assertDbConsistent(show);
        // every 201 is a distinct reservation row, and nothing but the 201s created rows
        assertEquals(t.get("reserve:201"), count("SELECT count(*) FROM reservations WHERE show_id=?", UUID.fromString(show)));
    }

    @Test
    void bodyUserIdIsIgnoredAndForeignCancelsAre404() throws Exception {
        String show = newShow(100, 4);
        List<String> users = users("ident", 100);
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            String u = users.get(i);
            List<String> seat = List.of("S" + (i + 1));
            tasks.add(() -> reserve(show, u, "k", seat, ",\"user_id\":\"mallory\",\"userId\":\"mallory\""));
        }
        List<Resp> rs = burst(tasks);
        assertClean(rs, Set.of(201));
        for (int i = 0; i < rs.size(); i++) {
            assertEquals(users.get(i), rs.get(i).json().path("user_id").asString(), "response user_id " + rs.get(i));
        }
        UUID showId = UUID.fromString(show);
        assertEquals(0, count("SELECT count(*) FROM reservations WHERE user_id='mallory'"));
        assertEquals(0, count("SELECT count(*) FROM seats WHERE user_id='mallory'"));
        assertEquals(0, count("SELECT count(*) FROM user_show_holds WHERE user_id='mallory'"));
        assertEquals(100, count("SELECT count(*) FROM seats s JOIN reservations r ON r.id=s.reservation_id "
                + "WHERE s.show_id=? AND s.user_id=r.user_id AND r.user_id LIKE 'ident-%'", showId));

        // eve concurrently tries to cancel every reservation she does not own: all 404, nothing changes
        token("eve");
        List<Callable<Resp>> cancels = rs.stream().<Callable<Resp>>map(
                r -> () -> cancel(r.json().path("reservation_id").asString(), "eve")).toList();
        List<Resp> cs = burst(cancels);
        assertClean(cs, Set.of(404));
        assertEquals(100, count("SELECT count(*) FROM reservations WHERE show_id=? AND status='confirmed'", showId));
        assertDbConsistent(show);
    }

    @Test
    void oneUserParallelReservesRespectPerUserLimit() throws Exception {
        String show = newShow(30, 4);
        users("solo", 2);

        // single-seat requests: exactly 4 of 10 fit
        List<Callable<Resp>> a = IntStream.range(0, 10).<Callable<Resp>>mapToObj(
                i -> () -> reserve(show, "solo-0", "k" + i, List.of("S" + (i + 1)), "")).toList();
        List<Resp> ra = burst(a);
        assertClean(ra, Set.of(201, 409));
        Tally ta = Tally.of(ra);
        assertEquals(4, ta.get("reserve:201"), ta.toString());
        assertEquals(6, ta.get("reserve:409:per_user_limit"), ta.toString());
        assertEquals(4, count("SELECT seat_count FROM user_show_holds WHERE show_id=? AND user_id='solo-0'",
                UUID.fromString(show)));

        // two-seat requests: never more than 4 seats in total (exactly 2 requests fit)
        List<Callable<Resp>> b = IntStream.range(0, 10).<Callable<Resp>>mapToObj(
                i -> () -> reserve(show, "solo-1", "k" + i, List.of("S" + (11 + 2 * i), "S" + (12 + 2 * i)), "")).toList();
        List<Resp> rb = burst(b);
        assertClean(rb, Set.of(201, 409));
        Tally tb = Tally.of(rb);
        assertEquals(2, tb.get("reserve:201"), tb.toString());
        assertEquals(8, tb.get("reserve:409:per_user_limit"), tb.toString());
        assertEquals(4, count("SELECT seat_count FROM user_show_holds WHERE show_id=? AND user_id='solo-1'",
                UUID.fromString(show)));
        System.out.println("[HttpBurst] limit: single=" + ta + " double=" + tb);
        assertDbConsistent(show);
    }

    // ---- metrics reconciliation

    private double metric(String text, String series) {
        for (String line : text.split("\n")) {
            if (line.startsWith(series + " ")) return Double.parseDouble(line.substring(series.length() + 1).trim());
        }
        throw new AssertionError("series missing in /actuator/prometheus: " + series);
    }

    private static final String[] SERIES = {
            "reservations_confirmed_total", "reservations_idempotent_replay_total", "reservations_cancelled_total",
            "reservations_errors_total", "cancels_errors_total",
            "reservations_declined_total{reason=\"seat_taken\"}", "reservations_declined_total{reason=\"per_user_limit\"}",
            "reservations_declined_total{reason=\"idempotency_conflict\"}",
            "reservations_declined_total{reason=\"validation\"}",
            "cancels_declined_total{reason=\"already_cancelled\"}", "cancels_declined_total{reason=\"not_found\"}",
            "seats{status=\"confirmed\"}"};

    private Map<String, Double> scrape() throws Exception {
        Resp r = get("/actuator/prometheus");
        assertEquals(200, r.status());
        Map<String, Double> m = new TreeMap<>();
        for (String s : SERIES) m.put(s, metric(r.body(), s));
        return m;
    }

    private Map<String, Double> settledScrape() throws Exception {
        Thread.sleep(1500); // seats gauge snapshot is cached ~1s
        return scrape();
    }

    @Test
    void metricsReconcileWithClientObservationsAfterConcurrentBurst() throws Exception {
        String show = newShow(120, 4);
        List<String> hotUsers = users("mh", 400);
        List<String> users = users("mu", 150);
        Map<String, Double> before = settledScrape();

        Random rnd = new Random(7);
        List<Callable<Resp>> wave1 = new ArrayList<>();
        for (String u : hotUsers) wave1.add(() -> reserve(show, u, "hot", List.of("S1"), "")); // hot seat storm
        for (int i = 0; i < 700; i++) {                                                           // stampede
            String u = users.get(rnd.nextInt(users.size()));
            String key = "s" + i;
            int n = 1 + rnd.nextInt(3);
            Set<String> picked = new java.util.LinkedHashSet<>();
            while (picked.size() < n) picked.add("S" + (2 + rnd.nextInt(119)));
            List<String> seats = new ArrayList<>(picked);
            wave1.add(() -> reserve(show, u, key, seats, ""));
            if (i % 7 == 0) wave1.add(() -> reserve(show, u, key, seats, ""));                    // replay candidates
            if (i % 50 == 0) wave1.add(() -> reserve(show, u, key, List.of("S120"), ""));        // maybe key reuse
            if (i % 60 == 0) wave1.add(() -> reserve(show, u, "bad" + key, List.of(), ""));      // validation
        }
        Collections.shuffle(wave1, rnd);
        List<Resp> all = new ArrayList<>(burst(wave1, 300));

        // wave 2: cancel a sample of the confirmed reservations over HTTP (some twice, some by a stranger)
        // while a second round of reserves is in flight
        List<Resp> created = all.stream().filter(r -> r.op().equals("reserve") && r.status() == 201).toList();
        List<Callable<Resp>> wave2 = new ArrayList<>();
        for (int i = 0; i < created.size(); i += 2) {
            JsonNode j = created.get(i).json();
            String id = j.path("reservation_id").asString(), owner = j.path("user_id").asString();
            wave2.add(() -> cancel(id, owner));
            if (i % 8 == 0) wave2.add(() -> cancel(id, owner));          // double cancel -> 409 already_cancelled
            if (i % 10 == 0) wave2.add(() -> cancel(id, "mu-stranger")); // -> 404 not_found
        }
        token("mu-stranger");
        for (int i = 0; i < 300; i++) {
            String u = users.get(rnd.nextInt(users.size()));
            String key = "w2-" + i;
            List<String> seats = List.of("S" + (1 + rnd.nextInt(120)));
            wave2.add(() -> reserve(show, u, key, seats, ""));
        }
        Collections.shuffle(wave2, rnd);
        all.addAll(burst(wave2, 300));

        Tally t = Tally.of(all);
        System.out.println("[HttpBurst] metrics burst " + all.size() + " requests: " + t);
        assertClean(all.stream().filter(r -> r.op().equals("reserve")).toList(), RESERVE_OK);
        assertClean(all.stream().filter(r -> r.op().equals("cancel")).toList(), Set.of(200, 404, 409));
        assertEquals(0, all.stream().filter(r -> r.status() >= 500 || r.status() < 0).count());

        Map<String, Double> after = settledScrape();
        Map<String, Double> d = new TreeMap<>();
        before.forEach((k, v) -> d.put(k, after.get(k) - v));
        System.out.println("[HttpBurst] metric deltas: " + d);

        assertEquals(t.get("reserve:201"), d.get("reservations_confirmed_total"), "confirmed");
        assertEquals(t.get("reserve:200"), d.get("reservations_idempotent_replay_total"), "replay");
        assertEquals(t.get("reserve:409:seat_taken"), d.get("reservations_declined_total{reason=\"seat_taken\"}"));
        assertEquals(t.get("reserve:409:per_user_limit"), d.get("reservations_declined_total{reason=\"per_user_limit\"}"));
        assertEquals(t.get("reserve:409:idempotency_key_reused"),
                d.get("reservations_declined_total{reason=\"idempotency_conflict\"}"));
        assertEquals(t.get("reserve:400"), d.get("reservations_declined_total{reason=\"validation\"}"));
        assertEquals(0.0, d.get("reservations_errors_total"));
        assertEquals(0.0, d.get("cancels_errors_total"));
        assertEquals(t.get("cancel:200"), d.get("reservations_cancelled_total"), "cancelled");
        assertEquals(t.get("cancel:409"), d.get("cancels_declined_total{reason=\"already_cancelled\"}"));
        assertEquals(t.get("cancel:404"), d.get("cancels_declined_total{reason=\"not_found\"}"));
        // seats confirmed by 201s minus seats released by cancel 200s == delta of the confirmed-seats gauge
        long soldSeats = all.stream().filter(r -> r.op().equals("reserve") && r.status() == 201)
                .mapToLong(r -> r.json().path("seats").size()).sum();
        long releasedSeats = all.stream().filter(r -> r.op().equals("cancel") && r.status() == 200)
                .mapToLong(r -> r.json().path("seats").size()).sum();
        assertEquals(soldSeats - releasedSeats, d.get("seats{status=\"confirmed\"}"), "confirmed seats gauge delta");
        assertTrue(t.get("reserve:201") > 0 && t.get("cancel:200") > 0, t.toString());
        assertDbConsistent(show);
    }
}
