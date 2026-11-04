package dev.stockflow.warehouse.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a write safe to send twice. The first request with a key claims it (INSERT ... ON CONFLICT DO NOTHING) and runs
 * the write in the same transaction, then stores the response. A second request with the same key finds the row and
 * gets the stored response back instead of running the write again, unless its caller, operation or request differs:
 * then it is refused, because the client would think it got what it asked for. Two requests at the same moment: the
 * second INSERT waits for the first transaction (bounded by lock_timeout) and then sees its row. A write that fails
 * rolls the claim back with it, so the same key may be sent again. Only successful responses are stored.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_.:-]{8,80}");

    /** The body to answer with, and whether it is the stored answer of an earlier request. */
    public record Outcome<T>(T body, boolean replayed) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StockProperties properties;

    IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper, StockProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Transactional
    public <T> Outcome<T> execute(String key, String operation, Map<String, Object> request, Class<T> responseType,
                                  Supplier<T> action) {
        if (!VALID_KEY.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must be 8 to 80 characters of letters, digits and _ . : -");
        }
        String fingerprint = fingerprint(operation, request);
        jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class,
                properties.lockTimeout().toMillis() + "ms");
        int claimed = jdbc.update("INSERT INTO idempotency_keys (idempotency_key, fingerprint, response_body) "
                + "VALUES (?, ?, '') ON CONFLICT (idempotency_key) DO NOTHING", key, fingerprint);
        if (claimed == 1) {
            T result = action.get();
            jdbc.update("UPDATE idempotency_keys SET response_body = ? WHERE idempotency_key = ?", toJson(result), key);
            return new Outcome<>(result, false);
        }
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT fingerprint, response_body FROM idempotency_keys WHERE idempotency_key = ?", key);
        if (!fingerprint.equals(String.valueOf(stored.get("fingerprint")).trim())) {
            throw new IdempotencyKeyReuseException(
                    "this Idempotency-Key was already used for a different request or caller");
        }
        try {
            return new Outcome<>(mapper.readValue((String) stored.get("response_body"), responseType), true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored response for the idempotency key cannot be read", e);
        }
    }

    /** Deletes keys older than the retention; a key sent again after that is executed as a new request. */
    @Transactional
    public int purgeOlderThan(Duration retention) {
        return jdbc.update("DELETE FROM idempotency_keys WHERE created_at < now() - (? * interval '1 second')",
                retention.toSeconds());
    }

    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    void purgeExpired() {
        int purged = purgeOlderThan(properties.idempotencyRetention());
        if (purged > 0) {
            log.info("Purged {} idempotency keys older than {}", purged, properties.idempotencyRetention());
        }
    }

    private String fingerprint(String operation, Map<String, Object> request) {
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        String who = caller == null ? "anonymous" : caller.getName();
        try {
            String text = who + "\n" + operation + "\n" + mapper.writeValueAsString(new TreeMap<>(request));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot fingerprint the request", e);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot store the response", e);
        }
    }
}
