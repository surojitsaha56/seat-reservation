package com.seatreservation.system.observability;

import com.seatreservation.system.exception.ReserveDeclinedException.Reason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Business counters, exposed at /actuator/prometheus as:
 * reservations_confirmed_total, reservations_declined_total{reason}, reservations_idempotent_replay_total,
 * reservations_cancelled_total, cancels_declined_total{reason}, reservations_errors_total, cancels_errors_total.
 *
 * These must only be called AFTER an outcome is final (transaction committed, or decline/validation decided),
 * never inside a transaction callback: callbacks can roll back or be retried (40P01/40001) and would double count.
 * ReservationService calls them from its reserve()/cancel() wrappers, which run after tx.execute returns/throws.
 *
 * Reconciliation: reservations_confirmed_total - reservations_cancelled_total == number of reservations holding
 * seats, i.e. seats{status="confirmed"} when every reservation holds one seat, only within one process lifetime
 * that started on an empty DB. Counters reset on restart, so otherwise compare deltas.
 * Each handled reserve request lands in exactly one of: confirmed, declined, idempotent_replay, errors.
 */
@Component
public class ReservationMetrics {
    public static final List<String> RESERVE_DECLINE_REASONS =
            List.of("seat_taken", "per_user_limit", "idempotency_conflict", "validation");
    public static final List<String> CANCEL_DECLINE_REASONS = List.of("already_cancelled", "not_found");

    private final Counter confirmed;
    private final Counter replay;
    private final Counter cancelled;
    private final Counter errors;
    private final Counter cancelErrors;
    private final Map<String, Counter> declined = new ConcurrentHashMap<>();
    private final Map<String, Counter> cancelDeclined = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry registry) {
        // Pre-register every series so it is exported (as 0) from the first scrape.
        confirmed = Counter.builder("reservations.confirmed").description("Reserve requests that confirmed seats")
                .register(registry);
        replay = Counter.builder("reservations.idempotent.replay")
                .description("Reserve requests answered from an earlier identical request").register(registry);
        cancelled = Counter.builder("reservations.cancelled").description("Reservations cancelled")
                .register(registry);
        errors = Counter.builder("reservations.errors")
                .description("Reserve requests that failed with an unexpected/infrastructure error")
                .register(registry);
        cancelErrors = Counter.builder("cancels.errors")
                .description("Cancel requests that failed with an unexpected/infrastructure error")
                .register(registry);
        for (String r : RESERVE_DECLINE_REASONS) {
            declined.put(r, Counter.builder("reservations.declined").tag("reason", r)
                    .description("Reserve requests declined or rejected").register(registry));
        }
        for (String r : CANCEL_DECLINE_REASONS) {
            cancelDeclined.put(r, Counter.builder("cancels.declined").tag("reason", r)
                    .description("Cancel requests declined").register(registry));
        }
    }

    public void confirmed() { confirmed.increment(); }

    public void replay() { replay.increment(); }

    public void cancelled() { cancelled.increment(); }

    public void error() { errors.increment(); }

    public void cancelError() { cancelErrors.increment(); }

    /** Reserve decline by domain reason. */
    public void declined(Reason reason) {
        declined.get(switch (reason) {
            case SEAT_TAKEN -> "seat_taken";
            case PER_USER_LIMIT -> "per_user_limit";
            case IDEMPOTENCY_KEY_REUSED -> "idempotency_conflict";
            default -> "validation";
        }).increment();
    }

    /** 400/404-class rejection of a reserve request (bad input, unknown show/seat). */
    public void validationRejected() { declined.get("validation").increment(); }

    public void cancelDeclined(Reason reason) {
        cancelDeclined.get(reason == Reason.ALREADY_CANCELLED ? "already_cancelled" : "not_found").increment();
    }

    public void cancelNotFound() { cancelDeclined.get("not_found").increment(); }
}
