package com.seatreservation.system.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.seatreservation.system.auth.JwtService;
import java.util.List;
import java.util.UUID;
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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CancelApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

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

    /** Reserves and returns the reservation id. */
    private String book(String show, String user, String key, String... seats) throws Exception {
        String body = "{\"seats\":[\"" + String.join("\",\"", seats) + "\"]}";
        String res = reserve(show, user, key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.reservation_id");
    }

    private ResultActions cancel(String reservationId, String user) throws Exception {
        return mvc.perform(post("/reservations/" + reservationId + "/cancel")
                .header("Authorization", "Bearer " + jwt.issue(user)));
    }

    private void counts(String show, int available, int confirmed) throws Exception {
        mvc.perform(get("/shows/" + show))
                .andExpect(jsonPath("$.counts.available", is(available)))
                .andExpect(jsonPath("$.counts.confirmed", is(confirmed)))
                .andExpect(jsonPath("$.counts.held", is(0)));
    }

    private int holds(String show, String user) {
        List<Integer> l = jdbc.queryForList("SELECT seat_count FROM user_show_holds WHERE show_id=? AND user_id=?",
                Integer.class, UUID.fromString(show), user);
        return l.isEmpty() ? 0 : l.get(0);
    }

    private String dbStatus(String reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class,
                UUID.fromString(reservationId));
    }

    @Test
    void cancelFreesSeatsDecrementsHoldsAndMarksCancelled() throws Exception {
        String show = newShow(4, "A1", "A2", "A3");
        String id = book(show, "alice", "k1", "A2", "A1");
        counts(show, 1, 2);

        cancel(id, "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation_id", is(id)))
                .andExpect(jsonPath("$.show_id", is(show)))
                .andExpect(jsonPath("$.user_id", is("alice")))
                .andExpect(jsonPath("$.seats", contains("A1", "A2")))
                .andExpect(jsonPath("$.amount_paise", is(50000)))
                .andExpect(jsonPath("$.status", is("cancelled")));

        counts(show, 3, 0);
        assertEquals(0, holds(show, "alice"));
        assertEquals("cancelled", dbStatus(id));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE show_id=? AND (reservation_id IS NOT NULL OR user_id IS NOT NULL)",
                Long.class, UUID.fromString(show)));
    }

    @Test
    void cancelledSeatsCanBeRebookedByAnotherUserAndByOwner() throws Exception {
        String show = newShow(4, "A1", "A2");
        String id = book(show, "alice", "k1", "A1");
        cancel(id, "alice").andExpect(status().isOk());
        String bobRes = book(show, "bob", "k1", "A1");
        counts(show, 1, 1);
        assertEquals(1, holds(show, "bob"));
        assertEquals(0, holds(show, "alice"));
        // the old reservation does not free bob's seat
        cancel(id, "alice").andExpect(status().isConflict());
        counts(show, 1, 1);
        cancel(bobRes, "bob").andExpect(status().isOk());
        // owner can rebook as well
        book(show, "alice", "k2", "A1");
        counts(show, 1, 1);
        assertEquals(1, holds(show, "alice"));
    }

    @Test
    void cancelByAnotherUserIs404AndChangesNothing() throws Exception {
        String show = newShow(4, "A1", "A2");
        String id = book(show, "alice", "k1", "A1");
        cancel(id, "mallory")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("not_found")));
        counts(show, 1, 1);
        assertEquals(1, holds(show, "alice"));
        assertEquals("confirmed", dbStatus(id));
    }

    @Test
    void foreignAndUnknownReservationsGiveIdenticalResponses() throws Exception {
        String show = newShow(4, "A1");
        String id = book(show, "alice", "k1", "A1");
        String foreign = cancel(id, "mallory").andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String unknown = cancel(UUID.randomUUID().toString(), "mallory").andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertEquals(JsonPath.<String>read(foreign, "$.error"), JsonPath.<String>read(unknown, "$.error"));
        // an already-cancelled reservation of someone else must not leak either
        cancel(id, "alice").andExpect(status().isOk());
        cancel(id, "mallory").andExpect(status().isNotFound()).andExpect(jsonPath("$.error", is("not_found")));
    }

    @Test
    void malformedIdIs400() throws Exception {
        cancel("not-a-uuid", "alice")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void cancelTwiceSecondIs409AlreadyCancelledAndNothingMoves() throws Exception {
        String show = newShow(4, "A1", "A2");
        String id = book(show, "alice", "k1", "A1");
        book(show, "alice", "k2", "A2");
        cancel(id, "alice").andExpect(status().isOk());
        cancel(id, "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("conflict")))
                .andExpect(jsonPath("$.reason", is("already_cancelled")));
        counts(show, 1, 1);
        assertEquals(1, holds(show, "alice"));
    }

    @Test
    void missingOrInvalidTokenIs401() throws Exception {
        String show = newShow(4, "A1");
        String id = book(show, "alice", "k1", "A1");
        mvc.perform(post("/reservations/" + id + "/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(post("/reservations/" + id + "/cancel").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
        counts(show, 0, 1);
    }

    @Test
    void userAtLimitCancelsOneThenCanReserveOneMore() throws Exception {
        String show = newShow(2, "A1", "A2", "A3", "A4");
        String r1 = book(show, "alice", "k1", "A1");
        book(show, "alice", "k2", "A2");
        reserve(show, "alice", "k3", "{\"seats\":[\"A3\"]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason", is("per_user_limit")));
        cancel(r1, "alice").andExpect(status().isOk());
        book(show, "alice", "k4", "A3");
        reserve(show, "alice", "k5", "{\"seats\":[\"A4\"]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason", is("per_user_limit")));
        assertEquals(2, holds(show, "alice"));
        counts(show, 2, 2);
    }

    @Test
    void replayOfOriginalKeyAfterCancelReturnsOriginalWithCancelledStatus() throws Exception {
        String show = newShow(4, "A1", "A2");
        String id = book(show, "alice", "k1", "A1");
        cancel(id, "alice").andExpect(status().isOk());
        reserve(show, "alice", "k1", "{\"seats\":[\"A1\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation_id", is(id)))
                .andExpect(jsonPath("$.status", is("cancelled")));
        counts(show, 2, 0);
        assertEquals(0, holds(show, "alice"));
    }
}
