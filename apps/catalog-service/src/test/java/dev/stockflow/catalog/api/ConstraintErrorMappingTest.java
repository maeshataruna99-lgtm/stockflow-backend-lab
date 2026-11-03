package dev.stockflow.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;

/** What the API says about a database constraint violation must match the violation (see warehouse ConstraintTest). */
class ConstraintErrorMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private ProblemDetail map(String sqlState) {
        return handler.handleDataIntegrity(
                new DataIntegrityViolationException("violation", new SQLException("violation", sqlState)));
    }

    @Test
    @DisplayName("A unique violation (23505) is a duplicate, a foreign key violation (23503) a reference conflict")
    void uniqueAndForeignKeyAreToldApart() {
        ProblemDetail unique = map("23505");
        ProblemDetail foreignKey = map("23503");

        assertThat(unique.getStatus()).isEqualTo(409);
        assertThat(unique.getTitle()).isEqualTo("Duplicate resource");
        assertThat(unique.getDetail()).containsIgnoringCase("unique");
        assertThat(foreignKey.getStatus()).isEqualTo(409);
        assertThat(foreignKey.getTitle()).isEqualTo("Reference conflict");
        assertThat(foreignKey.getDetail()).doesNotContainIgnoringCase("unique");
    }

    @Test
    @DisplayName("Any other constraint violation is a conflict that does not claim to be a duplicate")
    void otherViolationsAreGeneric() {
        ProblemDetail check = map("23514");

        assertThat(check.getStatus()).isEqualTo(409);
        assertThat(check.getTitle()).isEqualTo("Constraint violation");
        assertThat(check.getDetail()).doesNotContainIgnoringCase("unique");
    }
}
