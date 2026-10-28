package dev.stockflow.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that need the real engine: a fresh PostgreSQL container
 * (image pinned per ADR 0001) on which Flyway runs V1+V2 from version 0
 * and Hibernate validates the JPA mappings against that schema.
 *
 * Singleton container pattern: the container starts once per JVM in the
 * static initializer and is deliberately NOT managed by @Testcontainers
 * per-class lifecycle. Spring caches the application context across test
 * classes, so a per-class container would be stopped after the first
 * class while the cached context still points at its dead port (observed
 * as "Connection to localhost:50297 refused" in the first run - evidence
 * in docs/STATUS.md). Ryuk reaps the container when the JVM exits.
 */
@SpringBootTest
public abstract class AbstractCatalogIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.15-alpine");

    static {
        POSTGRES.start();
    }

    @Test
    void contextLoadsWithRealDatabase() {
        // passing context is the assertion: Flyway migrated, Hibernate
        // validated every mapping against the migrated schema
    }
}
