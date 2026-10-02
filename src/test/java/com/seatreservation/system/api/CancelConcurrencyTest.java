package com.seatreservation.system.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.seatreservation.system.exception.ApiException;
import com.seatreservation.system.exception.ReserveDeclinedException;
import com.seatreservation.system.model.CreateShowRequest;
import com.seatreservation.system.model.ReserveResult;
import com.seatreservation.system.service.ReservationService;
import com.seatreservation.system.service.ShowService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real threads, real service, real Postgres: cancel racing cancel and reserve. */
@SpringBootTest
@Testcontainers
class CancelConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired ReservationService reservations;
    @Autowired ShowService shows;
    @Autowired JdbcTemplate jdbc;

    /** Outcome of one call: CONFIRMED, CANCELLED, a decline/not_found code, or "ERROR:<exception>". */
    record Call(String outcome, UUID reservationId) {}

    private UUID newShow(int limit, List<String> seats) {
        return shows.create(new CreateShowRequest("S", seats, 100, limit)).id();
    }

    private Call reserve(UUID show, String user, String key, List<String> seats) {
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

    private Call cancel(UUID reservationId, String user) {
        try {
            reservations.cancel(reservationId, user);
            return new Call("CANCELLED", reservationId);
        } catch (ReserveDeclinedException e) {
            return new Call(e.reason().code(), reservationId);
        } catch (ApiException e) {
            return new Call(e.error(), reservationId);
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
        assertEquals(conf, resSeats, "confirmed seats must equal sum of confirmed reservation seats");
        Long orphan = jdbc.queryForObject("""
                SELECT count(*) FROM seats s LEFT JOIN reservations r ON r.id = s.reservation_id
                WHERE s.show_id=? AND ((s.status='confirmed' AND (r.id IS NULL OR r.status <> 'confirmed'
                                                                  OR r.user_id <> s.user_id))
                                    OR (s.status='available' AND (s.reservation_id IS NOT NULL OR s.user_id IS NOT NULL)))""",
                Long.class, show);
        assertEquals(0L, orphan, "orphan or inconsistent seats");
        Long holds = jdbc.queryForObject(
                "SELECT coalesce(sum(seat_count),0) FROM user_show_holds WHERE show_id=?", Long.class, show);
        assertEquals(conf, holds, "sum of holds must equal confirmed seats");
    }

    @Test
    void sameReservationCancelledByManyThreadsSucceedsExactlyOnce() throws Exception {
        UUID show = newShow(4, List.of("A1", "A2", "A3"));
        UUID id = reserve(show, "owner", "k-" + show, List.of("A1", "A2")).reservationId();
        List<Callable<Call>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) tasks.add(() -> cancel(id, "owner"));
        List<Call> r = race(tasks, 20);
        System.out.println("cancel x20: CANCELLED=" + count(r, "CANCELLED") + " already_cancelled="
                + count(r, "already_cancelled"));
        assertNoErrors(r);
        assertEquals(1, count(r, "CANCELLED"));
        assertEquals(19, count(r, "already_cancelled"));
        assertEquals(0, jdbc.queryForObject("SELECT seat_count FROM user_show_holds WHERE show_id=? AND user_id='owner'",
                Integer.class, show));
        assertInvariant(show, 3);
    }

    @Test
    void cancelRacingWithRebookersNeverDoubleSellsTheSeat() throws Exception {
        int rounds = 10;
        long totalRebooked = 0;
        for (int round = 0; round < rounds; round++) {
            UUID show = newShow(4, List.of("A1", "A2"));
            UUID id = reserve(show, "owner", "k-" + show, List.of("A1")).reservationId();
            List<Callable<Call>> tasks = new ArrayList<>();
            tasks.add(() -> cancel(id, "owner"));
            for (int i = 0; i < 50; i++) {
                String u = "rebooker-" + i;
                tasks.add(() -> reserve(show, u, "key-" + show + u, List.of("A1")));
            }
            List<Call> r = race(tasks, 51);
            assertNoErrors(r);
            assertEquals(1, count(r, "CANCELLED"), "round " + round + " outcomes=" + r.stream().map(Call::outcome).distinct().toList());
            long won = count(r, "CONFIRMED");
            assertTrue(won <= 1, "rebookers confirmed=" + won);
            assertEquals(50 - won, count(r, "seat_taken"));
            totalRebooked += won;
            Long a1Owners = jdbc.queryForObject(
                    "SELECT count(*) FROM reservations WHERE show_id=? AND status='confirmed' AND 'A1' = ANY(seats)",
                    Long.class, show);
            assertEquals(won, a1Owners);
            assertInvariant(show, 2);
        }
        System.out.println("cancel+rebook race: rounds=" + rounds + " rebooker wins=" + totalRebooked);
    }

    @Test
    void mixedReserveAndCancelStormKeepsInvariants() throws Exception {
        int seatsTotal = 40;
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < seatsTotal; i++) labels.add("Z" + i);
        UUID show = newShow(4, labels);
        int tasksN = 600;
        List<Callable<Call>> tasks = new ArrayList<>();
        for (int i = 0; i < tasksN; i++) {
            final int n = i;
            tasks.add(() -> {
                Random rnd = new Random(n);
                String user = "u" + (n % 25);
                int a = rnd.nextInt(seatsTotal), b = rnd.nextInt(seatsTotal);
                List<String> seats = a == b ? List.of(labels.get(a)) : List.of(labels.get(a), labels.get(b));
                Call res = reserve(show, user, "k" + n, seats);
                if (res.reservationId() == null) return res;
                // cancel own reservation (maybe twice, maybe as a stranger), then optionally book again
                Call out = res;
                if (rnd.nextInt(10) < 7) {
                    out = cancel(res.reservationId(), user);
                    if (rnd.nextBoolean()) {
                        Call dup = cancel(res.reservationId(), user);
                        if (!dup.outcome().equals("already_cancelled")) return new Call("ERROR:dup " + dup.outcome(), null);
                    }
                }
                Call stranger = cancel(res.reservationId(), "stranger");
                if (!stranger.outcome().equals("not_found")) return new Call("ERROR:stranger " + stranger.outcome(), null);
                if (rnd.nextInt(10) < 3) {
                    Call again = reserve(show, user, "k" + n + "b", seats);
                    if (again.outcome().startsWith("ERROR")) return again;
                }
                return out;
            });
        }
        List<Call> r = race(tasks, 60);
        System.out.println("mixed storm: tasks=" + tasksN + " confirmed-only=" + count(r, "CONFIRMED")
                + " cancelled=" + count(r, "CANCELLED") + " seat_taken=" + count(r, "seat_taken")
                + " per_user_limit=" + count(r, "per_user_limit"));
        assertNoErrors(r);
        assertTrue(count(r, "CANCELLED") > 0, "storm should include successful cancels");
        assertInvariant(show, seatsTotal);
    }
}
