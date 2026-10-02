package com.seatreservation.system.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.seatreservation.system.exception.ReserveDeclinedException;
import com.seatreservation.system.model.CreateShowRequest;
import com.seatreservation.system.model.ReserveResult;
import com.seatreservation.system.service.ReservationService;
import com.seatreservation.system.service.ShowService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real threads against the real service and a real Postgres. Declines are results; anything else is a failure. */
@SpringBootTest
@Testcontainers
class ReserveConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired ReservationService reservations;
    @Autowired ShowService shows;
    @Autowired JdbcTemplate jdbc;

    /** Outcome of one call: "CONFIRMED", "REPLAY", "seat_taken", ... or "ERROR:<exception>". */
    record Call(String outcome, UUID reservationId) {}

    private UUID newShow(int limit, List<String> seats) {
        return shows.create(new CreateShowRequest("S", seats, 100, limit)).id();
    }

    private Call call(UUID show, String user, String key, List<String> seats) {
        try {
            ReserveResult r = reservations.reserve(show, user, key, seats);
            return new Call(r.outcome().name(), r.reservation().reservationId());
        } catch (ReserveDeclinedException e) {
            return new Call(e.reason().code(), null);
        } catch (Throwable t) {
            t.printStackTrace();
            return new Call("ERROR:" + t, null);
        }
    }

    private List<Call> race(List<Callable<Call>> tasks, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(Math.min(threads, tasks.size()));
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Call>> fs = new ArrayList<>();
        for (Callable<Call> t : tasks) {
            fs.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return t.call();
            }));
        }
        ready.await(30, TimeUnit.SECONDS);
        go.countDown();
        List<Call> out = new ArrayList<>();
        for (Future<Call> f : fs) out.add(f.get(120, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    private long count(List<Call> calls, String outcome) {
        return calls.stream().filter(c -> c.outcome().equals(outcome)).count();
    }

    private void assertNoErrors(List<Call> calls) {
        List<String> errs = calls.stream().map(Call::outcome).filter(o -> o.startsWith("ERROR")).toList();
        assertTrue(errs.isEmpty(), "unexpected errors: " + errs.stream().limit(5).toList());
    }

    /** available+held+confirmed == total and confirmed seats == seats of live reservations; holds consistent. */
    private void assertInvariant(UUID show, int total) {
        Map<String, Object> c = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status='available') a, count(*) FILTER (WHERE status='held') h,
                       count(*) FILTER (WHERE status='confirmed') c, count(*) t FROM seats WHERE show_id=?""", show);
        long a = (Long) c.get("a"), h = (Long) c.get("h"), conf = (Long) c.get("c");
        assertEquals(total, (Long) c.get("t"));
        assertEquals(total, a + h + conf);
        Long resSeats = jdbc.queryForObject(
                "SELECT coalesce(sum(cardinality(seats)),0) FROM reservations WHERE show_id=? AND status='confirmed'",
                Long.class, show);
        assertEquals(conf, resSeats, "confirmed seats must equal sum of reservation seats");
        Long orphan = jdbc.queryForObject("""
                SELECT count(*) FROM seats s LEFT JOIN reservations r ON r.id = s.reservation_id
                WHERE s.show_id=? AND s.status='confirmed' AND (r.id IS NULL OR r.user_id <> s.user_id)""",
                Long.class, show);
        assertEquals(0L, orphan);
        Long holds = jdbc.queryForObject(
                "SELECT coalesce(sum(seat_count),0) FROM user_show_holds WHERE show_id=?", Long.class, show);
        assertEquals(conf, holds, "sum of holds must equal confirmed seats");
    }

    @Test
    void manyUsersRaceForOneSeat() throws Exception {
        UUID show = newShow(4, List.of("A1", "A2"));
        List<Callable<Call>> tasks = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String u = "user-" + i;
            tasks.add(() -> call(show, u, "key-" + u, List.of("A1")));
        }
        List<Call> r = race(tasks, 100);
        assertNoErrors(r);
        assertEquals(1, count(r, "CONFIRMED"));
        assertEquals(399, count(r, "seat_taken"));
        assertInvariant(show, 2);
    }

    @Test
    void oneUserParallelDistinctSeatsRespectsLimit() throws Exception {
        List<String> labels = List.of("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10");
        UUID show = newShow(4, labels);
        List<Callable<Call>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String seat = labels.get(i);
            String key = "k-" + i;
            tasks.add(() -> call(show, "greedy", key, List.of(seat)));
        }
        List<Call> r = race(tasks, 10);
        assertNoErrors(r);
        assertEquals(4, count(r, "CONFIRMED"));
        assertEquals(6, count(r, "per_user_limit"));
        Integer hold = jdbc.queryForObject("SELECT seat_count FROM user_show_holds WHERE show_id=? AND user_id='greedy'",
                Integer.class, show);
        assertEquals(4, hold);
        assertInvariant(show, 10);
    }

    @Test
    void sameUserSameKeyInParallelCreatesOneReservation() throws Exception {
        UUID show = newShow(4, List.of("A1", "A2", "A3"));
        List<Callable<Call>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> call(show, "retrier", "same-key", List.of("A1", "A2")));
        }
        List<Call> r = race(tasks, 20);
        assertNoErrors(r);
        assertEquals(1, count(r, "CONFIRMED"));
        assertEquals(19, count(r, "REPLAY"));
        assertEquals(1, r.stream().map(Call::reservationId).distinct().count(), "all responses share one id");
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE show_id=?", Long.class, show));
        Integer hold = jdbc.queryForObject("SELECT seat_count FROM user_show_holds WHERE show_id=? AND user_id='retrier'",
                Integer.class, show);
        assertEquals(2, hold);
        assertInvariant(show, 3);
    }

    @Test
    void overlappingMultiSeatRequestsInOppositeOrdersDoNotDeadlock() throws Exception {
        UUID show = newShow(4, List.of("A1", "A2", "B1", "B2"));
        List<Callable<Call>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String u = "u" + i;
            List<String> seats = switch (i % 4) {
                case 0 -> List.of("A1", "A2");
                case 1 -> List.of("A2", "A1");
                case 2 -> List.of("A2", "B1");
                default -> List.of("B1", "A2", "A1");
            };
            tasks.add(() -> call(show, u, "k-" + u, seats));
        }
        List<Call> r = race(tasks, 60);
        assertNoErrors(r);
        long ok = count(r, "CONFIRMED");
        assertTrue(ok >= 1 && ok <= 2, "confirmed=" + ok);
        assertEquals(200 - ok, count(r, "seat_taken"));
        assertInvariant(show, 4);
    }

    @Test
    void manyDistinctSeatsManyUsersMixedStaysConsistent() throws Exception {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 60; i++) labels.add("Z" + i);
        UUID show = newShow(4, labels);
        Map<String, Integer> rnd = new ConcurrentHashMap<>();
        List<Callable<Call>> tasks = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String u = "m" + (i % 100);
            String key = "k" + i;
            int a = (i * 7) % 60, b = (i * 13 + 5) % 60;
            List<String> seats = a == b ? List.of(labels.get(a)) : List.of(labels.get(a), labels.get(b));
            tasks.add(() -> call(show, u, key, seats));
        }
        List<Call> r = race(tasks, 60);
        assertNoErrors(r);
        assertInvariant(show, 60);
    }
}
