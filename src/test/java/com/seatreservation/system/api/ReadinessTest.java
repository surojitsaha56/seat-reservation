package com.seatreservation.system.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Own container (it gets paused): readiness fails closed quickly, liveness stays UP, readiness recovers. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReadinessTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired MockMvc mvc;

    @Test
    void readinessGoesDownWithTheDatabaseAndRecovers() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());

        String id = postgres.getContainerId();
        postgres.getDockerClient().pauseContainerCmd(id).exec();
        try {
            long t0 = System.nanoTime();
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("jdbc"))));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 10_000, "readiness took " + ms + "ms to report DOWN");
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        } finally {
            postgres.getDockerClient().unpauseContainerCmd(id).exec();
        }
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
