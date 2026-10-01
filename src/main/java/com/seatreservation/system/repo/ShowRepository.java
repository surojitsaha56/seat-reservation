package com.seatreservation.system.repo;

import com.seatreservation.system.model.CountsDto;
import com.seatreservation.system.model.SeatDto;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

    public record ShowRow(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
    }

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertShow(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
        jdbc.update("INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats) VALUES (?,?,?,?,?)",
                id, name, pricePaise, perUserLimit, totalSeats);
    }

    /** Batch-inserts all seats as 'available'; ord records the supplied order. */
    public void insertSeats(UUID showId, List<String> labels) {
        jdbc.batchUpdate("INSERT INTO seats (show_id, label, ord, status) VALUES (?,?,?,'available')",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ps.setObject(1, showId);
                        ps.setString(2, labels.get(i));
                        ps.setInt(3, i);
                    }

                    @Override
                    public int getBatchSize() {
                        return labels.size();
                    }
                });
    }

    public Optional<ShowRow> findShow(UUID id) {
        return jdbc.query("SELECT id, name, price_paise, per_user_limit, total_seats FROM shows WHERE id = ?",
                (rs, i) -> new ShowRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getLong("price_paise"), rs.getInt("per_user_limit"), rs.getInt("total_seats")),
                id).stream().findFirst();
    }

    /** Seats in insertion (creation) order. */
    public List<SeatDto> findSeats(UUID showId) {
        return jdbc.query("SELECT label, status FROM seats WHERE show_id = ? ORDER BY ord",
                (rs, i) -> new SeatDto(rs.getString("label"), rs.getString("status")), showId);
    }

    /** Single GROUP BY snapshot of seat statuses. */
    public CountsDto countSeats(UUID showId) {
        int[] c = new int[3]; // available, held, confirmed
        jdbc.query("SELECT status, count(*) AS n FROM seats WHERE show_id = ? GROUP BY status", rs -> {
            int n = rs.getInt("n");
            switch (rs.getString("status")) {
                case "available" -> c[0] = n;
                case "held" -> c[1] = n;
                case "confirmed" -> c[2] = n;
                default -> { }
            }
        }, showId);
        return new CountsDto(c[0] + c[1] + c[2], c[0], c[1], c[2]);
    }
}
