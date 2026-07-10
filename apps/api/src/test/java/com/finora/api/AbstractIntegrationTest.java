package com.finora.api;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base for API integration tests: full Spring context, MockMvc and a real
 * PostgreSQL via Testcontainers. Each test method rolls back its transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
public abstract class AbstractIntegrationTest {
}
