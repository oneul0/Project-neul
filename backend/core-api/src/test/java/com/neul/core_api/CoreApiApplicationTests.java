package com.neul.core_api;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires Docker & running Postgres/Redis/Kafka")
class CoreApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
