package dev.stockflow.warehouse.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lab only (connection pool lab, behind stockflow.lab.unsafe-stock): keeps one database connection busy for a while. */
@Service
@ConditionalOnProperty(name = "stockflow.lab.unsafe-stock", havingValue = "true")
public class PoolLabService {

    static final long MAX_HOLD_MILLIS = 10_000;

    private final JdbcTemplate jdbcTemplate;

    PoolLabService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public void hold(long millis) {
        jdbcTemplate.queryForObject("SELECT pg_sleep(?)::text", String.class,
                Math.max(0, Math.min(millis, MAX_HOLD_MILLIS)) / 1000.0);
    }
}
