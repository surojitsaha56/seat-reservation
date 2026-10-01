package com.seatreservation.system;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// No DB required: Flyway off and Hikari does not fail/connect at startup.
@SpringBootTest(properties = {
		"spring.flyway.enabled=false",
		"spring.datasource.hikari.initialization-fail-timeout=-1",
		"spring.datasource.hikari.minimum-idle=0"
})
class SystemApplicationTests {

	@Test
	void contextLoads() {
	}

}
