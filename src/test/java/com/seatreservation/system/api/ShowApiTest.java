package com.seatreservation.system.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ShowApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    MockMvc mvc;

    private MockHttpServletRequestBuilder create(String body) {
        return post("/shows").header("X-Admin-Token", "dev-admin-token")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void createThenGetReportsAllAvailableAndCountsSumToTotal() throws Exception {
        String res = mvc.perform(create("""
                        {"name":"Hamilton","seats":["A1","A2","A10","B1"],"price_paise":25000}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Hamilton")))
                .andExpect(jsonPath("$.price_paise", is(25000)))
                .andExpect(jsonPath("$.per_user_limit", is(4)))
                .andExpect(jsonPath("$.total_seats", is(4)))
                .andExpect(jsonPath("$.seats", hasSize(4)))
                .andExpect(jsonPath("$.seats[0].label", is("A1")))
                .andExpect(jsonPath("$.seats[*].status", org.hamcrest.Matchers.everyItem(is("available"))))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(res, "$.id");

        mvc.perform(get("/shows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id)))
                .andExpect(jsonPath("$.counts.total", is(4)))
                .andExpect(jsonPath("$.counts.available", is(4)))
                .andExpect(jsonPath("$.counts.held", is(0)))
                .andExpect(jsonPath("$.counts.confirmed", is(0)))
                .andExpect(jsonPath("$.total_seats", is(4)))
                .andExpect(jsonPath("$.seats", hasSize(4)))
                .andExpect(jsonPath("$.seats[2].label", is("A10")));
    }

    @Test
    void customPerUserLimitIsStored() throws Exception {
        mvc.perform(create("""
                        {"name":"X","seats":["S1"],"price_paise":1,"per_user_limit":2}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.per_user_limit", is(2)));
    }

    @Test
    void missingOrWrongAdminTokenIs401() throws Exception {
        String body = "{\"name\":\"X\",\"seats\":[\"S1\"],\"price_paise\":100}";
        mvc.perform(post("/shows").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("unauthorized")));
        mvc.perform(post("/shows").header("X-Admin-Token", "nope")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidPayloadsAre400() throws Exception {
        String[] bad = {
                "{\"seats\":[\"A\"],\"price_paise\":100}",
                "{\"name\":\"  \",\"seats\":[\"A\"],\"price_paise\":100}",
                "{\"name\":\"n\",\"price_paise\":100}",
                "{\"name\":\"n\",\"seats\":[],\"price_paise\":100}",
                "{\"name\":\"n\",\"seats\":[\"A\",\"A\"],\"price_paise\":100}",
                "{\"name\":\"n\",\"seats\":[\"A\",\" \"],\"price_paise\":100}",
                "{\"name\":\"n\",\"seats\":[\"A\"]}",
                "{\"name\":\"n\",\"seats\":[\"A\"],\"price_paise\":0}",
                "{\"name\":\"n\",\"seats\":[\"A\"],\"price_paise\":-5}",
                "{\"name\":\"n\",\"seats\":[\"A\"],\"price_paise\":\"abc\"}",
                "{\"name\":\"n\",\"seats\":[\"A\"],\"price_paise\":10.5}",
                "{\"name\":\"n\",\"seats\":[\"A\"],\"price_paise\":100,\"per_user_limit\":0}",
                "{not json",
                ""
        };
        for (String b : bad) {
            mvc.perform(create(b))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", is("validation_error")))
                    .andExpect(jsonPath("$.message").isString());
        }
    }

    @Test
    void unknownShowIs404AndMalformedIdIs400() throws Exception {
        mvc.perform(get("/shows/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("not_found")));
        mvc.perform(get("/shows/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }
}
