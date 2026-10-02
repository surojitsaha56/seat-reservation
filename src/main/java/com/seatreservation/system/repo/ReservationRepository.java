package com.seatreservation.system.repo;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain-SQL statements for the reserve path. Transaction boundaries are owned by the service. */
@Repository
public class ReservationRepository {

    public record ReservationRow(UUID id, UUID showId, String userId, List<String> seats,
                                 long amountPaise, String status, String requestHash) {
    }

    public record SeatState(String label, String status) {
    }

    private static final String COLS = "id, show_id, user_id, seats, amount_paise, status, request_hash";

    private static final RowMapper<ReservationRow> ROW = (rs, i) -> new ReservationRow(
            rs.getObject("id", UUID.class), rs.getObject("show_id", UUID.class), rs.getString("user_id"),
            List.of((String[]) rs.getArray("seats").getArray()), rs.getLong("amount_paise"),
            rs.getString("status"), rs.getString("request_hash"));

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Labels (of the requested ones) that exist in the show. Seat sets are immutable, so no lock needed. */
    public List<String> existingLabels(UUID showId, List<String> labels) {
        return jdbc.query("SELECT label FROM seats WHERE show_id = ? AND label = ANY(?)",
                ps -> bind(ps, showId, labels), (rs, i) -> rs.getString(1));
    }

    /** Lock-free read: requested seats already confirmed. */
    public List<String> confirmedAmong(UUID showId, List<String> labels) {
        return jdbc.query(
                "SELECT label FROM seats WHERE show_id = ? AND label = ANY(?) AND status = 'confirmed' ORDER BY label",
                ps -> bind(ps, showId, labels), (rs, i) -> rs.getString(1));
    }

    public Optional<ReservationRow> findByUserAndKey(String userId, String key) {
        return jdbc.query("SELECT " + COLS + " FROM reservations WHERE user_id = ? AND idempotency_key = ?",
                ROW, userId, key).stream().findFirst();
    }

    /** Idempotency claim. Empty when (user_id, idempotency_key) already exists. */
    public Optional<UUID> claim(UUID id, UUID showId, String userId, String key, String hash,
                                List<String> seats, long amountPaise) {
        return jdbc.query("""
                INSERT INTO reservations (id, show_id, user_id, idempotency_key, request_hash, seats, amount_paise, status)
                VALUES (?,?,?,?,?,?,?,'confirmed')
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                RETURNING id""",
                ps -> {
                    ps.setObject(1, id);
                    ps.setObject(2, showId);
                    ps.setString(3, userId);
                    ps.setString(4, key);
                    ps.setString(5, hash);
                    ps.setArray(6, ps.getConnection().createArrayOf("text", seats.toArray()));
                    ps.setLong(7, amountPaise);
                },
                (rs, i) -> rs.getObject(1, UUID.class)).stream().findFirst();
    }

    /** Atomically adds n to the user's hold count unless that would exceed the limit. */
    public boolean incrementHold(UUID showId, String userId, int n, int limit) {
        return !jdbc.query("""
                INSERT INTO user_show_holds (show_id, user_id, seat_count) VALUES (?,?,?)
                ON CONFLICT (show_id, user_id) DO UPDATE SET seat_count = user_show_holds.seat_count + ?
                WHERE user_show_holds.seat_count + ? <= ?
                RETURNING seat_count""",
                ps -> {
                    ps.setObject(1, showId);
                    ps.setString(2, userId);
                    ps.setInt(3, n);
                    ps.setInt(4, n);
                    ps.setInt(5, n);
                    ps.setInt(6, limit);
                },
                (rs, i) -> rs.getInt(1)).isEmpty();
    }

    /** Row-locks the seats in label order (deterministic, deadlock-free across requests). */
    public List<SeatState> lockSeats(UUID showId, List<String> labels) {
        return jdbc.query("""
                SELECT label, status FROM seats
                WHERE show_id = ? AND label = ANY(?)
                ORDER BY label FOR UPDATE""",
                ps -> bind(ps, showId, labels), (rs, i) -> new SeatState(rs.getString(1), rs.getString(2)));
    }

    /** Second guard: only flips seats that are still 'available'. Returns rows updated. */
    public int confirmSeats(UUID showId, List<String> labels, UUID reservationId, String userId) {
        return jdbc.update("""
                UPDATE seats SET status = 'confirmed', reservation_id = ?, user_id = ?
                WHERE show_id = ? AND label = ANY(?) AND status = 'available'""",
                ps -> {
                    ps.setObject(1, reservationId);
                    ps.setString(2, userId);
                    ps.setObject(3, showId);
                    ps.setArray(4, ps.getConnection().createArrayOf("text", labels.toArray()));
                });
    }

    public record CancelledRow(UUID showId, List<String> seats, long amountPaise) {
    }

    public record OwnerStatus(String userId, String status) {
    }

    /**
     * Cancel step 1: flips the caller's own confirmed reservation to cancelled (this takes the reservation
     * row lock). Empty when it does not exist, belongs to someone else, or is already cancelled.
     */
    public Optional<CancelledRow> markCancelled(UUID id, String userId) {
        return jdbc.query("""
                UPDATE reservations SET status = 'cancelled'
                WHERE id = ? AND user_id = ? AND status = 'confirmed'
                RETURNING show_id, seats, amount_paise""",
                ps -> {
                    ps.setObject(1, id);
                    ps.setString(2, userId);
                },
                (rs, i) -> new CancelledRow(rs.getObject(1, UUID.class),
                        List.of((String[]) rs.getArray(2).getArray()), rs.getLong(3))).stream().findFirst();
    }

    /** Used only to tell "not yours / not found" apart from "already cancelled" after markCancelled failed. */
    public Optional<OwnerStatus> findOwnerStatus(UUID id) {
        return jdbc.query("SELECT user_id, status FROM reservations WHERE id = ?",
                (rs, i) -> new OwnerStatus(rs.getString(1), rs.getString(2)), id).stream().findFirst();
    }

    /** Returns rows updated (must be 1; the row is locked by us). The check constraint forbids going below 0. */
    public int decrementHold(UUID showId, String userId, int n) {
        return jdbc.update("UPDATE user_show_holds SET seat_count = seat_count - ? WHERE show_id = ? AND user_id = ?",
                n, showId, userId);
    }

    /** Row-locks the reservation's seats in label order (same order as lockSeats). */
    public List<String> lockSeatsOf(UUID reservationId) {
        return jdbc.query("SELECT label FROM seats WHERE reservation_id = ? ORDER BY label FOR UPDATE",
                (rs, i) -> rs.getString(1), reservationId);
    }

    /** Frees the seats, guarded by reservation_id so another reservation's seats are never touched. */
    public int releaseSeats(UUID reservationId) {
        return jdbc.update("""
                UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL
                WHERE reservation_id = ?""", reservationId);
    }

    private static void bind(PreparedStatement ps, UUID showId, List<String> labels) throws SQLException {
        ps.setObject(1, showId);
        ps.setArray(2, ps.getConnection().createArrayOf("text", labels.toArray()));
    }
}
