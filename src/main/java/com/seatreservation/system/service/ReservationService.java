package com.seatreservation.system.service;

import com.seatreservation.system.exception.ApiException;
import com.seatreservation.system.exception.ReserveDeclinedException;
import com.seatreservation.system.exception.ReserveDeclinedException.Reason;
import com.seatreservation.system.model.ReservationResponse;
import com.seatreservation.system.model.ReserveResult;
import com.seatreservation.system.repo.ReservationRepository;
import com.seatreservation.system.repo.ReservationRepository.ReservationRow;
import com.seatreservation.system.repo.ReservationRepository.SeatState;
import com.seatreservation.system.repo.ShowRepository;
import com.seatreservation.system.repo.ShowRepository.ShowRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    private static final int MAX_KEY_LENGTH = 255;

    private final ReservationRepository repo;
    private final ShowRepository shows;
    private final TransactionTemplate tx;

    public ReservationService(ReservationRepository repo, ShowRepository shows, PlatformTransactionManager tm) {
        this.repo = repo;
        this.shows = shows;
        this.tx = new TransactionTemplate(tm);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Single outcome log point (step 6 hooks metrics here). */
    private void outcome(String outcome, String reason, UUID showId, String userId, Object detail) {
        log.info("reserve outcome={} reason={} show_id={} user_id={} detail={}", outcome, reason, showId, userId, detail);
    }

    public ReserveResult reserve(UUID showId, String userId, String key, List<String> rawSeats) {
        try {
            ReserveResult r = doReserve(showId, userId, key, rawSeats);
            outcome(r.outcome().name(), r.outcome() == ReserveResult.Outcome.REPLAY ? "idempotent_replay" : "ok",
                    showId, userId, r.reservation().reservationId());
            return r;
        } catch (ReserveDeclinedException e) {
            outcome(ReserveResult.Outcome.DECLINED.name(), e.reason().code(), showId, userId, e.seats());
            throw e;
        } catch (ApiException e) {
            outcome("REJECTED", e.error(), showId, userId, e.getMessage());
            throw e;
        }
    }

    private ReserveResult doReserve(UUID showId, String userId, String key, List<String> rawSeats) {
        // 1. validate
        if (key == null || key.isBlank()) throw ApiException.validation("Idempotency key is required");
        if (key.length() > MAX_KEY_LENGTH) throw ApiException.validation("Idempotency key too long");
        if (rawSeats == null || rawSeats.isEmpty()) throw ApiException.validation("seats must be non-empty");
        Set<String> seen = new HashSet<>();
        for (String s : rawSeats) {
            if (s == null || s.isBlank()) throw ApiException.validation("seat labels must be non-blank");
            if (!seen.add(s)) throw ApiException.validation("duplicate seat label: " + s);
        }
        ShowRow show = shows.findShow(showId)
                .orElseThrow(() -> ApiException.notFound("show not found: " + showId));
        List<String> seats = rawSeats.stream().sorted().toList();
        int n = seats.size();
        if (n > show.perUserLimit()) {
            throw new ReserveDeclinedException(Reason.PER_USER_LIMIT,
                    "At most " + show.perUserLimit() + " seats per user for this show", List.of());
        }
        Set<String> existing = new HashSet<>(repo.existingLabels(showId, seats));
        for (String s : seats) {
            if (!existing.contains(s)) throw ApiException.validation("unknown seat for this show: " + s);
        }
        String hash = requestHash(showId, seats);

        // Ordering matters: the read-only idempotency lookup comes BEFORE the lock-free fast path.
        // A retry of an already-successful request finds its seats 'confirmed' (by itself), so running
        // the fast path first would wrongly answer 409 seat_taken instead of replaying the original.
        // The fast path is load shedding only; the transaction below is the correctness mechanism.
        Optional<ReservationRow> prior = repo.findByUserAndKey(userId, key);
        if (prior.isPresent()) {
            return ReserveResult.replay(replayOrReject(prior.get(), hash));
        }
        List<String> taken = repo.confirmedAmong(showId, seats);
        if (!taken.isEmpty()) {
            // The original request may have committed between the lookup above and this read (the
            // "taken" seats can be our own). Re-check before declining so concurrent duplicates replay.
            Optional<ReservationRow> raced = repo.findByUserAndKey(userId, key);
            if (raced.isPresent()) {
                return ReserveResult.replay(replayOrReject(raced.get(), hash));
            }
            throw seatTaken(taken);
        }

        // 2-6. the transaction, retried on deadlock / serialization failure
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> reserveTx(show, userId, key, hash, seats));
            } catch (DataAccessException e) {
                if (attempt < MAX_TX_ATTEMPTS && isRetryable(e)) {
                    log.warn("reserve retry attempt={} after transient conflict: {}", attempt, e.getMessage());
                    continue;
                }
                throw e;
            }
        }
    }

    private ReserveResult reserveTx(ShowRow show, String userId, String key, String hash, List<String> seats) {
        int n = seats.size();
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

        if (!repo.incrementHold(show.id(), userId, n, show.perUserLimit())) {
            throw new ReserveDeclinedException(Reason.PER_USER_LIMIT,
                    "Per-user limit of " + show.perUserLimit() + " seats reached for this show", List.of());
        }

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
