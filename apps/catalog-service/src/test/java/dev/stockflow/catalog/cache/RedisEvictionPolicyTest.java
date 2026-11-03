package dev.stockflow.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Eviction lab, automated. One Redis (redis:7.4.12-alpine) per policy, capped at 2 MB, receives 50 "hot" keys
 * that are read again and again and then up to 3000 "cold" keys of 1 KB that are written once. The measured
 * outcome per policy is what decides which one a cache may use.
 */
class RedisEvictionPolicyTest {

    private static final int HOT_KEYS = 50;
    private static final int COLD_KEYS = 3000;
    private static final String VALUE = "x".repeat(1024);

    private record Outcome(int coldWritten, boolean oom, long evictedKeys, int hotSurvivors, int coldSurvivors,
                           long usedMemory) {
    }

    private static Outcome run(String policy, boolean withTtl) {
        try (GenericContainer<?> redis = new GenericContainer<>("redis:7.4.12-alpine")
                .withExposedPorts(6379)
                .withCommand("redis-server", "--maxmemory", "2mb", "--maxmemory-policy", policy,
                        "--maxmemory-samples", "10", "--appendonly", "no")) {
            redis.start();
            RedisClient client = RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
            try (StatefulRedisConnection<String, String> connection = client.connect()) {
                RedisCommands<String, String> commands = connection.sync();
                int written = 0;
                boolean oom = false;
                try {
                    for (int h = 1; h <= HOT_KEYS; h++) {
                        write(commands, "hot:" + h, withTtl);
                    }
                    for (int i = 1; i <= COLD_KEYS; i++) {
                        write(commands, "cold:" + i, withTtl);
                        written = i;
                        if (i % 100 == 0) {
                            for (int h = 1; h <= HOT_KEYS; h++) {
                                commands.get("hot:" + h); // keeps the hot keys recently used
                            }
                        }
                    }
                } catch (RedisCommandExecutionException e) {
                    assertThat(e.getMessage()).startsWith("OOM");
                    oom = true;
                }
                int hot = 0;
                for (int h = 1; h <= HOT_KEYS; h++) {
                    hot += commands.exists("hot:" + h).intValue();
                }
                long dbSize = commands.dbsize();
                return new Outcome(written, oom, stat(commands.info("stats"), "evicted_keys"), hot,
                        (int) (dbSize - hot), stat(commands.info("memory"), "used_memory"));
            } finally {
                client.shutdown();
            }
        }
    }

    private static void write(RedisCommands<String, String> commands, String key, boolean withTtl) {
        if (withTtl) {
            commands.setex(key, 3600, VALUE);
        } else {
            commands.set(key, VALUE);
        }
    }

    private static long stat(String info, String name) {
        Matcher m = Pattern.compile("^" + name + ":(\\d+)", Pattern.MULTILINE).matcher(info);
        assertThat(m.find()).as(name).isTrue();
        return Long.parseLong(m.group(1));
    }

    @Test
    @DisplayName("allkeys-lru keeps accepting writes, evicts cold keys and keeps the hot ones")
    void allkeysLruKeepsTheHotKeys() {
        Outcome o = run("allkeys-lru", false);

        assertThat(o.oom()).isFalse();
        assertThat(o.coldWritten()).isEqualTo(COLD_KEYS);
        assertThat(o.evictedKeys()).isGreaterThan(500);
        assertThat(o.hotSurvivors()).isGreaterThanOrEqualTo(45);
        assertThat(o.coldSurvivors()).isLessThan(COLD_KEYS / 2);
        assertThat(o.usedMemory()).isLessThan(3L * 1024 * 1024);
    }

    @Test
    @DisplayName("allkeys-random evicts the hot keys as readily as the cold ones")
    void allkeysRandomLosesTheHotKeys() {
        Outcome o = run("allkeys-random", false);

        assertThat(o.oom()).isFalse();
        assertThat(o.evictedKeys()).isGreaterThan(500);
        assertThat(o.hotSurvivors()).isLessThan(30);
    }

    @Test
    @DisplayName("noeviction refuses writes with OOM once memory is full and evicts nothing")
    void noevictionRejectsWrites() {
        Outcome o = run("noeviction", false);

        assertThat(o.oom()).isTrue();
        assertThat(o.coldWritten()).isLessThan(COLD_KEYS);
        assertThat(o.evictedKeys()).isZero();
        assertThat(o.hotSurvivors()).isEqualTo(HOT_KEYS);
    }

    @Test
    @DisplayName("volatile-lru cannot make room when no key has a TTL")
    void volatileLruWithoutTtlBehavesLikeNoeviction() {
        Outcome o = run("volatile-lru", false);

        assertThat(o.oom()).isTrue();
        assertThat(o.evictedKeys()).isZero();
    }

    @Test
    @DisplayName("volatile-lru evicts like allkeys-lru once the keys carry a TTL")
    void volatileLruWithTtlEvicts() {
        Outcome o = run("volatile-lru", true);

        assertThat(o.oom()).isFalse();
        assertThat(o.evictedKeys()).isGreaterThan(500);
        assertThat(o.hotSurvivors()).isGreaterThanOrEqualTo(45);
    }
}
