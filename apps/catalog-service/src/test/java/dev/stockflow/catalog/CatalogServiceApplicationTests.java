package dev.stockflow.catalog;

class CatalogServiceApplicationTests extends AbstractCatalogIntegrationTest {
    // context loading assertion lives in the base class: with JPA on the
    // classpath the context only starts when the real database is up,
    // Flyway has migrated, and Hibernate validation passed.
}
