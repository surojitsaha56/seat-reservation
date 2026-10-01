package com.seatreservation.system.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// No DB required (same settings as SystemApplicationTests).
@SpringBootTest(properties = {
        "auth.token-endpoint.enabled=false",
        "spring.flyway.enabled=false",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.datasource.hikari.minimum-idle=0"
})
@AutoConfigureMockMvc
class AuthTokenDisabledTest {

    @Autowired
    MockMvc mvc;

    @Test
    void tokenEndpointIs404WhenDisabled() throws Exception {
        mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"alice\"}"))
                .andExpect(status().isNotFound());
    }
}
