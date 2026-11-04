package dev.stockflow.warehouse.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;

/**
 * Message queues, consumer retries, dead-letter queues, and broker queue length bounds on a real RabbitMQ broker and
 * PostgreSQL database (Phase 9, unit 9.2: topics 35, 36, 47).
 *
 * <p>Uses {@code rabbitmq:3.13.7-management-alpine} (same image as the {@code messaging} profile in
 * {@code compose.labs.yaml}) over its management HTTP API with the JDK {@link HttpClient}, so the service POM needs no
 * extra dependency.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RabbitMqDlqTest {

    private static GenericContainer<?> rabbit;
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static HttpClient http;
    private static String baseUrl;
    private static String basicAuth;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void startContainers() throws Exception {
        rabbit = new GenericContainer<>("rabbitmq:3.13.7-management-alpine")
                .withExposedPorts(15672)
                .waitingFor(Wait.forHttp("/api/overview").forPort(15672).withBasicCredentials("guest", "guest")
                        .forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));
        postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");
        Startables.deepStart(List.of(rabbit, postgres)).join();

        SingleConnectionDataSource ds = new SingleConnectionDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE processed_events (event_id VARCHAR(64) PRIMARY KEY, processed_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.execute("CREATE TABLE stock_ledger (stock_id BIGINT PRIMARY KEY, quantity INT NOT NULL)");
        jdbc.update("INSERT INTO stock_ledger (stock_id, quantity) VALUES (1, 10)");

        http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        baseUrl = "http://" + rabbit.getHost() + ":" + rabbit.getMappedPort(15672) + "/api";
        basicAuth = "Basic " + Base64.getEncoder().encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8));

        // Declare work exchange, dead-letter exchange (DLX), dead-letter queue (DLQ), and work queue wired to DLX
        put("/exchanges/%2F/stock.events", Map.of("type", "direct", "durable", true));
        put("/exchanges/%2F/stock.dlx", Map.of("type", "direct", "durable", true));
        put("/queues/%2F/stock.dlq", Map.of("durable", true));
        post("/bindings/%2F/e/stock.dlx/q/stock.dlq", Map.of("routing_key", "stock.dlq"));
        put("/queues/%2F/stock.work", Map.of(
                "durable", true,
                "arguments", Map.of(
                        "x-dead-letter-exchange", "stock.dlx",
                        "x-dead-letter-routing-key", "stock.dlq")));
        post("/bindings/%2F/e/stock.events/q/stock.work", Map.of("routing_key", "stock.changed"));
    }

    @AfterAll
    static void stopContainers() {
        if (rabbit != null) {
            rabbit.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void put(String path, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", basicAuth)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("PUT " + path + ": " + res.body()).isIn(201, 204);
    }

    private static JsonNode post(String path, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", basicAuth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("POST " + path + ": " + res.body()).isIn(200, 201, 204);
        return res.body() == null || res.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(res.body());
    }

    private static void publish(String exchange, String routingKey, String messageId, String payload) throws Exception {
        JsonNode result = post("/exchanges/%2F/" + exchange + "/publish", Map.of(
                "properties", Map.of("message_id", messageId),
                "routing_key", routingKey,
                "payload", payload,
                "payload_encoding", "string"));
        assertThat(result.path("routed").asBoolean()).isTrue();
    }

    private static JsonNode getOne(String queue, String ackmode) throws Exception {
        JsonNode array = post("/queues/%2F/" + queue + "/get", Map.of(
                "count", 1,
                "ackmode", ackmode,
                "encoding", "auto"));
        return array.isArray() && !array.isEmpty() ? array.get(0) : null;
    }

    /**
     * Worker step: peeks/takes one message. If valid, claims {@code message_id} in {@code processed_events} and updates
     * {@code stock_ledger} in one DB transaction. If invalid, retries up to {@code maxAttempts} times by requeueing
     * ({@code reject_requeue_true}); on the final attempt rejects with {@code reject_requeue_false} so RabbitMQ routes
     * the poison message to {@code stock.dlq}.
     */
    private static boolean consumeNextWithRetryAndDlq(Map<String, Integer> attemptsByMessageId, int maxAttempts) throws Exception {
        JsonNode peeked = getOne("stock.work", "reject_requeue_true");
        if (peeked == null) {
            return false;
        }
        String messageId = peeked.path("properties").path("message_id").asText();
        String payload = peeked.path("payload").asText();
        int attempt = attemptsByMessageId.merge(messageId, 1, Integer::sum);
        try {
            JsonNode json = mapper.readTree(payload);
            if (!json.hasNonNull("stockId") || !json.get("delta").isInt()) {
                throw new IllegalArgumentException("Malformed or non-integer delta in payload: " + payload);
            }
            long stockId = json.get("stockId").asLong();
            int delta = json.get("delta").asInt();
            if (delta == 0) {
                throw new IllegalArgumentException("Zero delta is invalid");
            }
            tx.executeWithoutResult(status -> {
                int claimed = jdbc.update(
                        "INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT (event_id) DO NOTHING",
                        messageId);
                if (claimed == 1) {
                    jdbc.update("UPDATE stock_ledger SET quantity = quantity + ? WHERE stock_id = ?", delta, stockId);
                }
            });
            // Successfully processed (or deduplicated): remove from the work queue with ack
            getOne("stock.work", "ack_requeue_false");
            return true;
        } catch (Exception ex) {
            if (attempt >= maxAttempts) {
                // Retry budget exhausted: reject with requeue=false so RabbitMQ dead-letters the message to stock.dlq
                getOne("stock.work", "reject_requeue_false");
            }
            return true;
        }
    }

    @Test
    @Order(1)
    @DisplayName("Idempotent consumer applies a duplicate message from RabbitMQ once")
    void duplicateMessagesAreAppliedOnce() throws Exception {
        publish("stock.events", "stock.changed", "evt-dup-1", "{\"stockId\":1,\"delta\":-3}");
        publish("stock.events", "stock.changed", "evt-dup-1", "{\"stockId\":1,\"delta\":-3}");
        publish("stock.events", "stock.changed", "evt-2", "{\"stockId\":1,\"delta\":5}");

        Map<String, Integer> attempts = new HashMap<>();
        while (consumeNextWithRetryAndDlq(attempts, 3)) {
            // drain queue
        }

        int quantity = jdbc.queryForObject("SELECT quantity FROM stock_ledger WHERE stock_id = 1", Integer.class);
        assertThat(quantity).as("10 - 3 (once) + 5 = 12").isEqualTo(12);
        assertThat(getOne("stock.dlq", "reject_requeue_true")).isNull();
    }

    @Test
    @Order(2)
    @DisplayName("A poison message is retried up to maxAttempts and then routed by RabbitMQ to the dead-letter queue without blocking later messages")
    void poisonMessageExhaustsRetriesAndRoutesToDlq() throws Exception {
        publish("stock.events", "stock.changed", "evt-poison-99", "{\"stockId\":1,\"delta\":\"not-a-number\"}");
        publish("stock.events", "stock.changed", "evt-valid-after-poison", "{\"stockId\":1,\"delta\":-2}");

        Map<String, Integer> attempts = new HashMap<>();
        while (consumeNextWithRetryAndDlq(attempts, 3)) {
            // process until work queue is empty
        }

        assertThat(attempts.get("evt-poison-99")).isEqualTo(3);
        assertThat(attempts.get("evt-valid-after-poison")).isEqualTo(1);

        int quantity = jdbc.queryForObject("SELECT quantity FROM stock_ledger WHERE stock_id = 1", Integer.class);
        assertThat(quantity).as("valid message behind the poison message ran: 12 - 2 = 10").isEqualTo(10);

        JsonNode deadLettered = getOne("stock.dlq", "ack_requeue_false");
        assertThat(deadLettered).isNotNull();
        assertThat(deadLettered.path("properties").path("message_id").asText()).isEqualTo("evt-poison-99");
        assertThat(deadLettered.path("properties").path("headers").path("x-first-death-queue").asText())
                .isEqualTo("stock.work");
        assertThat(deadLettered.path("properties").path("headers").path("x-first-death-reason").asText())
                .isEqualTo("rejected");
    }

    @Test
    @Order(3)
    @DisplayName("A queue bounded with x-max-length drops and dead-letters oldest messages (reason=maxlen) when a slow consumer falls behind")
    void boundedQueueCapsDepthAndDeadLettersOverflowWithMaxlenReason() throws Exception {
        put("/queues/%2F/stock.bounded", Map.of(
                "durable", true,
                "arguments", Map.of(
                        "x-max-length", 3,
                        "x-dead-letter-exchange", "stock.dlx",
                        "x-dead-letter-routing-key", "stock.dlq")));
        post("/bindings/%2F/e/stock.events/q/stock.bounded", Map.of("routing_key", "stock.bounded"));

        for (int i = 1; i <= 5; i++) {
            publish("stock.events", "stock.bounded", "evt-cap-" + i, "{\"seq\":" + i + "}");
        }

        // Only 3 messages remain in stock.bounded (evt-cap-3, 4, 5); the 2 oldest (evt-cap-1, 2) were evicted to stock.dlq
        JsonNode remaining = post("/queues/%2F/stock.bounded/get", Map.of(
                "count", 10,
                "ackmode", "ack_requeue_false",
                "encoding", "auto"));
        assertThat(remaining).hasSize(3);
        assertThat(remaining.get(0).path("properties").path("message_id").asText()).isEqualTo("evt-cap-3");
        assertThat(remaining.get(2).path("properties").path("message_id").asText()).isEqualTo("evt-cap-5");

        JsonNode evicted = post("/queues/%2F/stock.dlq/get", Map.of(
                "count", 10,
                "ackmode", "ack_requeue_false",
                "encoding", "auto"));
        assertThat(evicted).hasSize(2);
        assertThat(evicted.get(0).path("properties").path("message_id").asText()).isEqualTo("evt-cap-1");
        assertThat(evicted.get(0).path("properties").path("headers").path("x-first-death-reason").asText())
                .isEqualTo("maxlen");
    }
}
