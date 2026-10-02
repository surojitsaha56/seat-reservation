package com.seatreservation.system.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
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
class ReserveApiTest {

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
        var req = post("/shows/" + show + "/reserve").header("Authorization", "Bearer " + jwt.issue(user))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        // keys are scoped per user, not per show: namespace by show so tests sharing a user don't collide
        if (key != null) req.header("Idempotency-Key", show + key);
        return mvc.perform(req);
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

    @Test
    void happyPathReturns201AndCountsReflect() throws Exception {
        String show = newShow(4, "A1", "A2", "A3");
        reserve(show, "u1", "k1", "{\"seats\":[\"A2\",\"A1\"]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.show_id", is(show)))
                .andExpect(jsonPath("$.user_id", is("u1")))
                .andExpect(jsonPath("$.seats", contains("A1", "A2")))
                .andExpect(jsonPath("$.amount_paise", is(50000)))
                .andExpect(jsonPath("$.status", is("confirmed")))
                .andExpect(jsonPath("$.reservation_id").isString());
        counts(show, 1, 2);
        org.junit.jupiter.api.Assertions.assertEquals(2, holds(show, "u1"));
    }

    @Test
    void sameKeyReplayReturnsSameReservationAndMovesNothing() throws Exception {
        String show = newShow(4, "A1", "A2", "A3");
        String first = reserve(show, "u1", "k1", "{\"seats\":[\"A1\"]}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        reserve(show, "u1", "k1", "{\"seats\":[\"A1\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation_id", is(JsonPath.<String>read(first, "$.reservation_id"))))
                .andExpect(jsonPath("$.status", is("confirmed")));
        counts(show, 2, 1);
        org.junit.jupiter.api.Assertions.assertEquals(1, holds(show, "u1"));
        // key may also arrive in the body
        reserve(show, "u1", null, "{\"seats\":[\"A1\"],\"idempotency_key\":\"" + show + "k1\"}")
                .andExpect(status().isOk());
    }

    @Test
    void sameKeyDifferentSeatsIs409KeyReused() throws Exception {
        String show = newShow(4, "A1", "A2");
        reserve(show, "u1", "k1", "{\"seats\":[\"A1\"]}").andExpect(status().isCreated());
        reserve(show, "u1", "k1", "{\"seats\":[\"A2\"]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("conflict")))
                .andExpect(jsonPath("$.reason", is("idempotency_key_reused")));
        counts(show, 1, 1);
    }

    @Test
    void sameKeyDifferentUsersAreIndependent() throws Exception {
        String show = newShow(4, "A1", "A2");
        reserve(show, "u1", "k1", "{\"seats\":[\"A1\"]}").andExpect(status().isCreated());
        reserve(show, "u2", "k1", "{\"seats\":[\"A2\"]}").andExpect(status().isCreated());
    }

    @Test
    void secondUserOnSameSeatGets409SeatTaken() throws Exception {
        String show = newShow(4, "A1", "A2");
        reserve(show, "u1", "k1", "{\"seats\":[\"A1\"]}").andExpect(status().isCreated());
        reserve(show, "u2", "k2", "{\"seats\":[\"A1\"]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("conflict")))
                .andExpect(jsonPath("$.reason", is("seat_taken")))
                .andExpect(jsonPath("$.seats", contains("A1")));
        org.junit.jupiter.api.Assertions.assertEquals(0, holds(show, "u2"));
        // a decline rolls back the idempotency row too: the same key can be used again for free seats
        reserve(show, "u2", "k2", "{\"seats\":[\"A2\"]}").andExpect(status().isCreated());
    }

    @Test
    void overLimitIs409PerUserLimit() throws Exception {
        String show = newShow(2, "A1", "A2", "A3", "A4");
        reserve(show, "u1", "k1", "{\"seats\":[\"A1\",\"A2\",\"A3\"]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason", is("per_user_limit")));
        reserve(show, "u1", "k2", "{\"seats\":[\"A1\",\"A2\"]}").andExpect(status().isCreated());
        reserve(show, "u1", "k3", "{\"seats\":[\"A3\"]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason", is("per_user_limit")));
        counts(show, 2, 2);
        org.junit.jupiter.api.Assertions.assertEquals(2, holds(show, "u1"));
    }

    @Test
    void multiSeatIsAllOrNothing() throws Exception {
        String show = newShow(4, "A1", "A2", "A3");
        reserve(show, "u1", "k1", "{\"seats\":[\"A2\"]}").andExpect(status().isCreated());
        reserve(show, "u2", "k2", "{\"seats\":[\"A1\",\"A2\",\"A3\"]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason", is("seat_taken")));
        counts(show, 2, 1);
        org.junit.jupiter.api.Assertions.assertEquals(0, holds(show, "u2"));
        org.junit.jupiter.api.Assertions.assertEquals(1, holds(show, "u1"));
    }

    @Test
    void spoofedUserIdInBodyIsIgnored() throws Exception {
        String show = newShow(4, "A1");
        reserve(show, "real-user", "k1", "{\"seats\":[\"A1\"],\"user_id\":\"mallory\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user_id", is("real-user")));
        org.junit.jupiter.api.Assertions.assertEquals(1, holds(show, "real-user"));
        org.junit.jupiter.api.Assertions.assertEquals(0, holds(show, "mallory"));
    }

    @Test
    void missingOrInvalidTokenIs401() throws Exception {
        String show = newShow(4, "A1");
        mvc.perform(post("/shows/" + show + "/reserve").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":[\"A1\"]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/shows/" + show + "/reserve").header("Idempotency-Key", "k")
                        .header("Authorization", "Bearer garbage")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":[\"A1\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validationErrors() throws Exception {
        String show = newShow(4, "A1", "A2");
        reserve(show, "u1", null, "{\"seats\":[\"A1\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("validation_error")));
        reserve(show, "u1", "k", "{\"seats\":[]}").andExpect(status().isBadRequest());
        reserve(show, "u1", "k", "{}").andExpect(status().isBadRequest());
        reserve(show, "u1", "k", "{\"seats\":[\"A1\",\"A1\"]}").andExpect(status().isBadRequest());
        reserve(show, "u1", "k", "{\"seats\":[\" \"]}").andExpect(status().isBadRequest());
        reserve(show, "u1", "k", "{\"seats\":[\"ZZ\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("ZZ")));
        reserve(show, "u1", "k", "{not json").andExpect(status().isBadRequest());
        reserve(UUID.randomUUID().toString(), "u1", "k", "{\"seats\":[\"A1\"]}")
                .andExpect(status().isNotFound());
        // header and body keys differ
        reserve(show, "u1", "k1", "{\"seats\":[\"A1\"],\"idempotency_key\":\"k2\"}")
                .andExpect(status().isBadRequest());
        counts(show, 2, 0);
    }
}
