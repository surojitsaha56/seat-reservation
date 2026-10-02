package com.seatreservation.system.service;

import com.seatreservation.system.exception.ApiException;
import com.seatreservation.system.exception.ReserveDeclinedException;
import com.seatreservation.system.exception.ReserveDeclinedException.Reason;
import com.seatreservation.system.model.ReservationResponse;
import com.seatreservation.system.model.ReserveResult;
import com.seatreservation.system.observability.ReservationMetrics;
import com.seatreservation.system.repo.ReservationRepository;
import com.seatreservation.system.repo.ReservationRepository.CancelledRow;
import com.seatreservation.system.repo.ReservationRepository.OwnerStatus;
import com.seatreservation.system.repo.ReservationRepository.ReservationRow;
import com.seatreservation.system.repo.ReservationRepository.SeatState;
import com.seatreservation.system.repo.ShowRepository;
import com.seatreservation.system.repo.ShowRepository.ShowRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reserve = one READ COMMITTED transaction (programmatic, so retry and rollback-on-decline are explicit):
 * idempotency claim -> atomic per-user limit -> ordered seat row locks -> guarded UPDATE -> commit.
 * A domain decline throws ReserveDeclinedException inside the callback, which rolls back everything.
 */
@Service
public class ReservationService {
    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    // retries in case of deadlock
    private static final int MAX_TX_ATTEMPTS = 3;

    // idempotency key that client will send.
    private static final int MAX_KEY_LENGTH = 255;

    private final ReservationRepository repo;
    private final ShowRepository shows;
    private final TransactionTemplate tx;

    private final ReservationMetrics metrics;

    public ReservationService(ReservationRepository repo, ShowRepository shows, PlatformTransactionManager tm,
            ReservationMetrics metrics) {
        this.repo = repo;
        this.shows = shows;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(tm);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Single outcome log point for reserve. */
    private void outcome(String outcome, String reason, UUID showId, String userId, Object detail) {
        log.info("reserve outcome={} reason={} show_id={} user_id={} detail={}", outcome, reason, showId, userId, detail);
    }

    /**
     * Metrics are bumped only here, after doReserve (and thus tx.execute, including any 40P01/40001 retries) has
     * returned or thrown: a retried or rolled-back attempt never reaches this point, so nothing double counts.
     */
    public ReserveResult reserve(UUID showId, String userId, String key, List<String> rawSeats) {
        try {
            ReserveResult r = doReserve(showId, userId, key, rawSeats);
            outcome(r.outcome().name(), r.outcome() == ReserveResult.Outcome.REPLAY ? "idempotent_replay" : "ok",
                    showId, userId, r.reservation().reservationId());
            if (r.outcome() == ReserveResult.Outcome.REPLAY) metrics.replay();
            else metrics.confirmed();
            return r;
        } catch (ReserveDeclinedException e) {
            outcome(ReserveResult.Outcome.DECLINED.name(), e.reason().code(), showId, userId, e.seats());
            metrics.declined(e.reason());
            throw e;
        } catch (ApiException e) {
            outcome("REJECTED", e.error(), showId, userId, e.getMessage());
            metrics.validationRejected();
            throw e;
        } catch (RuntimeException e) {
            // DB trouble after retries, or a bug: not a decline; the controller advice turns it into 503/500
            metrics.error();
            throw e;
        }
    }

    private ReserveResult doReserve(UUID showId, String userId, String key, List<String> rawSeats) {
        // validating idempotency keys and seats.
        if (key == null || key.isBlank()) throw ApiException.validation("Idempotency key is required");
        if (key.length() > MAX_KEY_LENGTH) throw ApiException.validation("Idempotency key too long");
        if (rawSeats == null || rawSeats.isEmpty()) throw ApiException.validation("seats must be non-empty");
        Set<String> seen = new HashSet<>();

        // if duplicate seats in request throw exception
        for (String s : rawSeats) {
            if (s == null || s.isBlank()) throw ApiException.validation("seat labels must be non-blank");
            if (!seen.add(s)) throw ApiException.validation("duplicate seat label: " + s);
        }

        // checking if show id is valid
        ShowRow show = shows.findShow(showId)
                .orElseThrow(() -> ApiException.notFound("show not found: " + showId));

        // sorting to list to prevent deadlock
        List<String> seats = rawSeats.stream().sorted().toList();

        // seats in request should not be greater than permissible value
        // of seats a person can book in show
        int n = seats.size();
        if (n > show.perUserLimit()) {
            throw new ReserveDeclinedException(Reason.PER_USER_LIMIT,
                    "At most " + show.perUserLimit() + " seats per user for this show", List.of());
        }

        // eliminates unknown seats
        Set<String> existing = new HashSet<>(repo.existingLabels(showId, seats));
        for (String s : seats) {
            if (!existing.contains(s)) throw ApiException.validation("unknown seat for this show: " + s);
        }

        // hash is generated so that if user tries two same request at the same time.
        // only 1 txn commits, then the second txn should show booked for that
        // user not seat unavailable
        String hash = requestHash(showId, seats);

        // Ordering matters: the read-only idempotency lookup comes BEFORE the lock-free fast path.
        // A retry of an already-successful request finds its seats 'confirmed' (by itself), so running
        // the fast path first would wrongly answer 409 seat_taken instead of replaying the original.
        // The fast path is load shedding only; the transaction below is the correctness mechanism.

        // check if user has already booked or not.
        Optional<ReservationRow> prior = repo.findByUserAndKey(userId, key);
        if (prior.isPresent()) {
            // if user has already booked, and user again hit with same request and
            // idempotency key. if both match then show them seats booked else
            // reject
            return ReserveResult.replay(replayOrReject(prior.get(), hash));
        }

        // this function checks seats from db. if they are booked then show failure to
        // users.
        List<String> taken = repo.confirmedAmong(showId, seats);
        if (!taken.isEmpty()) {
            // The original request may have committed between the lookup above and this read (the
            // "taken" seats can be our own). Re-check before declining so concurrent duplicates replay.

            // case where user hits 2 requests. 1st one takes time so hits 2nd
            // handling that scenario
            Optional<ReservationRow> raced = repo.findByUserAndKey(userId, key);
            if (raced.isPresent()) {
                return ReserveResult.replay(replayOrReject(raced.get(), hash));
            }
            throw seatTaken(taken);
        }

        // 2-6. the transaction, retried on deadlock / serialization failure
        // deadlock may throw 500x error to handle that
        return inTx("reserve", () -> reserveTx(show, userId, key, hash, seats));
    }

    /** Runs the callback in one READ COMMITTED transaction, retrying on deadlock / serialization failure. */
    private <T> T inTx(String op, Supplier<T> body) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> body.get());
            } catch (DataAccessException e) {
                if (attempt < MAX_TX_ATTEMPTS && isRetryable(e)) {
                    log.warn("{} retry attempt={} after transient conflict: {}", op, attempt, e.getMessage());
                    continue;
                }
                throw e;
            }
        }
    }

    /** Single outcome log point for cancel (step 6 hooks metrics here). */
    private void cancelOutcome(CancelOutcome outcome, String reason, UUID reservationId, String userId) {
        log.info("cancel outcome={} reason={} reservation_id={} user_id={}", outcome, reason, reservationId, userId);
    }

    public enum CancelOutcome { CANCELLED, DECLINED, NOT_FOUND }

    /**
     * Cancel = one READ COMMITTED transaction: reservation row -> user_show_holds row -> seat rows (by label).
     *
     * Lock order vs reserve (reserve: INSERT reservation row -> hold row -> seat rows):
     * - Reserve's reservation row is brand new and invisible to others, so nobody can wait on it; the only
     *   transaction that ever locks it is its creator (cancel needs it committed). The reservation-row lock
     *   therefore can never sit in a wait cycle with reserve.
     * - Both then take exactly one hold row, and only after that the seat rows, sorted by label. Nobody
     *   waits for a hold row while holding a seat lock, and seat locks are always acquired in label order.
     * - Two cancels (or cancel vs. cancel) of the same reservation serialize on its row lock; the loser
     *   re-evaluates status and finds 0 rows. Different reservations share only seats, in sorted order.
     * So every wait edge goes forward in the order reservation -> hold -> seats(label); no cycles.
     */
    public ReservationResponse cancel(UUID reservationId, String userId) {
        try {
            ReservationResponse r = inTx("cancel", () -> cancelTx(reservationId, userId));
            cancelOutcome(CancelOutcome.CANCELLED, "ok", reservationId, userId);
            metrics.cancelled(); // after commit
            return r;
        } catch (ReserveDeclinedException e) {
            cancelOutcome(CancelOutcome.DECLINED, e.reason().code(), reservationId, userId);
            metrics.cancelDeclined(e.reason());
            throw e;
        } catch (ApiException e) {
            cancelOutcome(CancelOutcome.NOT_FOUND, e.error(), reservationId, userId);
            metrics.cancelNotFound();
            throw e;
        } catch (RuntimeException e) {
            metrics.cancelError();
            throw e;
        }
    }

    private ReservationResponse cancelTx(UUID reservationId, String userId) {

        // update the reservation table mark row as cancelled
        Optional<CancelledRow> cancelled = repo.markCancelled(reservationId, userId);
        if (cancelled.isEmpty()) {
            // Not found and "owned by someone else" are deliberately indistinguishable (404 for both).
            Optional<OwnerStatus> cur = repo.findOwnerStatus(reservationId);
            if (cur.isPresent() && cur.get().userId().equals(userId) && "cancelled".equals(cur.get().status())) {
                throw new ReserveDeclinedException(Reason.ALREADY_CANCELLED,
                        "Reservation is already cancelled", List.of());
            }
            throw ApiException.notFound("reservation not found: " + reservationId);
        }
        CancelledRow row = cancelled.get();
        int n = row.seats().size();

        if (repo.decrementHold(row.showId(), userId, n) != 1) {
            throw new IllegalStateException("user_show_holds row missing for reservation " + reservationId);
        }

        // reservation table has no of seats which is checked by seats in seats table
        List<String> locked = repo.lockSeatsOf(reservationId);
        if (locked.size() != n) {
            throw new IllegalStateException("reservation " + reservationId + " owns " + locked.size()
                    + " seats, expected " + n);
        }

        // update status of seats in seats table
        if (repo.releaseSeats(reservationId) != n) {
            throw new IllegalStateException("released seat count mismatch for reservation " + reservationId);
        }
        return new ReservationResponse(reservationId, row.showId(), userId, row.seats(), row.amountPaise(),
                "cancelled");
    }

    private ReserveResult reserveTx(ShowRow show, String userId, String key, String hash, List<String> seats) {
        int n = seats.size();
        // unique id for reservation
        UUID id = UUID.randomUUID();
        long amount = show.pricePaise() * (long) n;

        // idempotency claim; a concurrent duplicate blocks on the unique index until the first txn ends
        // If the key's first txn rolled back, the blocked insert simply succeeds; if it committed, the
        // insert returns nothing and (READ COMMITTED, fresh snapshot per statement) the row is visible.
        if (repo.claim(id, show.id(), userId, key, hash, seats, amount).isEmpty()) {
            ReservationRow existing = repo.findByUserAndKey(userId, key)
                    .orElseThrow(() -> new org.springframework.dao.TransientDataAccessResourceException(
                            "idempotency row vanished after conflict"));
            return ReserveResult.replay(replayOrReject(existing, hash));
        }

        // update seats within user limit
        if (!repo.incrementHold(show.id(), userId, n, show.perUserLimit())) {
            throw new ReserveDeclinedException(Reason.PER_USER_LIMIT,
                    "Per-user limit of " + show.perUserLimit() + " seats reached for this show", List.of());
        }

        // update seats table.
        // the booked seats check before the transaction could be stale at this moment.
        // that is why another time this check is done
        List<SeatState> locked = repo.lockSeats(show.id(), seats);
        List<String> unavailable = locked.stream().filter(s -> !"available".equals(s.status()))
                .map(SeatState::label).toList();
        if (!unavailable.isEmpty()) throw seatTaken(unavailable);
        if (locked.size() != n) throw ApiException.validation("unknown seat for this show");

        int updated = repo.confirmSeats(show.id(), seats, id, userId);
        if (updated != n) throw seatTaken(seats);

        return ReserveResult.confirmed(new ReservationResponse(id, show.id(), userId, seats, amount, "confirmed"));
    }

    private ReservationResponse replayOrReject(ReservationRow row, String hash) {
        if (!row.requestHash().equals(hash)) {
            throw new ReserveDeclinedException(Reason.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key was already used with a different request", List.of());
        }
        return new ReservationResponse(row.id(), row.showId(), row.userId(), row.seats(), row.amountPaise(),
                row.status());
    }

    private static ReserveDeclinedException seatTaken(List<String> seats) {
        return new ReserveDeclinedException(Reason.SEAT_TAKEN,
                "Seat(s) not available: " + String.join(", ", seats), List.copyOf(seats));
    }

    static String requestHash(UUID showId, List<String> sortedSeats) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(showId.toString().getBytes(StandardCharsets.UTF_8));
            for (String s : sortedSeats) {
                md.update((byte) 0);
                md.update(s.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SQLSTATE 40P01 (deadlock) or 40001 (serialization failure) anywhere in the cause chain. */
    static boolean isRetryable(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException se
                    && ("40P01".equals(se.getSQLState()) || "40001".equals(se.getSQLState()))) {
                return true;
            }
            if (c.getCause() == c) break;
        }
        return false;
    }
}
