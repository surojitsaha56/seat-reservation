package com.seatreservation.system.observability;

import com.seatreservation.system.repo.ShowRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gauges seats{status=available|held|confirmed} and seats_capacity, summed over ALL shows (low cardinality, always
 * present). All four come from one snapshot (a single GROUP BY statement), so available+held+confirmed == total
 * at every scrape. The snapshot is cached ~1s. A scrape never throws and never blocks long: the refresh runs on
 * its own thread and a scrape waits at most REFRESH_WAIT_MS for it, otherwise it serves the last snapshot (so a
 * DB outage or saturated pool does not make /actuator/prometheus time out).
 *
 * Per-show gauges (show_seats{show_id,status}) are intentionally not implemented: they would need a
 * register/remove lifecycle for shows (stale series, unbounded cardinality) and one more query; the per-show
 * view is served by GET /shows/{id}.
 */
@Component
public class SeatGauges {
    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);
    static final long TTL_MS = 1000;
    static final long REFRESH_WAIT_MS = 1500;

    private record Snapshot(long available, long held, long confirmed) {}

    private final ShowRepository shows;
    private final AtomicReference<Snapshot> last = new AtomicReference<>(new Snapshot(0, 0, 0));
    private final ExecutorService refresher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seat-gauge-refresh");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();
    private CompletableFuture<Void> inflight; // guarded by lock
    private long lastAttemptMs;                // guarded by lock

    public SeatGauges(ShowRepository shows, MeterRegistry registry) {
        this.shows = shows;
        for (String status : new String[] {"available", "held", "confirmed"}) {
            Gauge.builder("seats", this, g -> g.value(status)).tag("status", status)
                    .description("Seats by status, summed over all shows").register(registry);
        }
        Gauge.builder("seats.capacity", this, g -> g.value("total")).description("Total seats over all shows (not named seats_total: client_java strips _total and collides with seats)")
                .register(registry);
    }

    private double value(String what) {
        try {
            Snapshot s = snapshot();
            return switch (what) {
                case "available" -> s.available;
                case "held" -> s.held;
                case "confirmed" -> s.confirmed;
                default -> s.available + s.held + s.confirmed;
            };
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private Snapshot snapshot() {
        CompletableFuture<Void> wait = null;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (now - lastAttemptMs >= TTL_MS && inflight == null) {
                lastAttemptMs = now; // also throttles retries while the DB is down
                CompletableFuture<Void> f = CompletableFuture.runAsync(this::refresh, refresher);
                inflight = f;
                f.whenComplete((v, t) -> {
                    synchronized (lock) {
                        if (inflight == f) inflight = null;
                    }
                });
                wait = f;
            }
        }
        if (wait != null) {
            try {
                wait.get(REFRESH_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // timeout / failure: serve the last snapshot
            }
        }
        return last.get();
    }

    private void refresh() {
        try {
            long[] c = shows.countAllSeats(); // available, held, confirmed (one statement)
            last.set(new Snapshot(c[0], c[1], c[2]));
        } catch (RuntimeException e) {
            log.warn("seat gauge refresh failed, serving last snapshot: {}", e.toString());
        }
    }
}
