package dev.stockflow.warehouse.api;

import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 7807 problem+json error contract (common conventions in
 * initial-api-outline.md), shared by every warehouse controller and
 * consistent with catalog-service. The gateway maps its own failures
 * into the same shape in Phase 5.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource not found");
        return problem;
    }

    @ExceptionHandler(DuplicateResourceException.class)
    ProblemDetail handleDuplicate(DuplicateResourceException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Duplicate resource");
        return problem;
    }

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Insufficient stock");
        return problem;
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    ProblemDetail handleConcurrentUpdate(ConcurrentUpdateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Concurrent update");
        return problem;
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ProblemDetail handleLockTimeout(PessimisticLockingFailureException ex) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
        if (root instanceof SQLException sql && "40P01".equals(sql.getSQLState())) {
            ProblemDetail deadlock = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "The database rolled this request back to resolve a deadlock; nothing changed, retry it");
            deadlock.setTitle("Deadlock");
            return deadlock;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The row is locked by another request; retry shortly");
        problem.setTitle("Row locked");
        return problem;
    }

    /**
     * No connection could be obtained for the transaction: the pool is exhausted (the wait for a free connection ran
     * out, spring.datasource.hikari.connection-timeout) or the database is unreachable. Either way the request did
     * nothing, so 503 and the caller may retry.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class})
    ProblemDetail handleNoConnection(RuntimeException ex) {
        boolean poolTimeout = NestedExceptionUtils.getMostSpecificCause(ex) instanceof SQLTransientConnectionException;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                poolTimeout ? "All database connections are in use; retry shortly"
                        : "The database is not reachable; retry shortly");
        problem.setTitle(poolTimeout ? "Database busy" : "Database unavailable");
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleBodyValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body failed bean validation");
        problem.setTitle("Validation failed");
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleParameterValidation(ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request parameter failed validation");
        problem.setTitle("Validation failed");
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations()
                .forEach(violation -> errors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage()));
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Malformed or unreadable JSON body");
        problem.setTitle("Validation failed");
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Parameter '" + ex.getName() + "' has an invalid value");
        problem.setTitle("Validation failed");
        return problem;
    }

    /**
     * Safety net for unique-constraint races that slip past the explicit
     * existsBy... pre-checks (409, same as DuplicateResourceException).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
        String sqlState = root instanceof SQLException sql ? sql.getSQLState() : null;
        ProblemDetail problem;
        if ("23505".equals(sqlState)) {
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request violates a unique constraint");
            problem.setTitle("Duplicate resource");
        } else if ("23503".equals(sqlState)) {
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "The request refers to a row that does not exist or is still referenced by other rows");
            problem.setTitle("Reference conflict");
        } else {
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request violates a data constraint");
            problem.setTitle("Constraint violation");
        }
        return problem;
    }
}
