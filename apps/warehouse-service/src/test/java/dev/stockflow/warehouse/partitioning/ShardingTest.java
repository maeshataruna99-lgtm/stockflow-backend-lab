package dev.stockflow.warehouse.partitioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * Sharding lab (unit 7.11), an isolated experiment: two independent PostgreSQL containers are the shards and a small
 * router in this test decides which shard holds an account by hashing its id. Nothing here is part of the services.
 * It shows what sharding gives (data and load spread over servers) and what the application then has to live
 * without: global uniqueness, cross-shard transactions, queries that do not carry the shard key, and cheap
 * resharding. There is no distributed ACID transaction in this lab and none is claimed. The tests share the shard
 * databases and run in a fixed order.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShardingTest {

    private static final int SHARDS = 2;
    private static List<PostgreSQLContainer<?>> containers;
    private static List<JdbcTemplate> shard;
    private static final AtomicInteger[] calls = {new AtomicInteger(), new AtomicInteger()};

    @BeforeAll
    static void startShards() {
        containers = new ArrayList<>();
        for (int i = 0; i < SHARDS; i++) {
            containers.add(new PostgreSQLContainer<>("postgres:16.15-alpine"));
        }
        Startables.deepStart(containers).join();
        shard = new ArrayList<>();
        for (PostgreSQLContainer<?> c : containers) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(c.getJdbcUrl(), c.getUsername(),
                    c.getPassword(), true));
            jdbc.execute("CREATE TABLE accounts (id BIGINT PRIMARY KEY, email TEXT NOT NULL UNIQUE, "
                    + "balance BIGINT NOT NULL CHECK (balance >= 0))");
            shard.add(jdbc);
        }
    }

    @AfterAll
    static void stop() {
        containers.forEach(PostgreSQLContainer::stop);
    }

    // ---- the router: stable hash of the shard key, modulo the number of shards -------------------------------

    static long crc(long key) {
        CRC32 crc = new CRC32();
        crc.update(ByteBuffer.allocate(Long.BYTES).putLong(key).array());
        return crc.getValue();
    }

    static int shardFor(long id, int shardCount) {
        return (int) (crc(id) % shardCount);
    }

    private static JdbcTemplate on(int index) {
        calls[index].incrementAndGet();
        return shard.get(index);
    }

    private static void resetCalls() {
        for (AtomicInteger c : calls) {
            c.set(0);
        }
    }

    private static void insert(long id, String email, long balance) {
        on(shardFor(id, SHARDS)).update("INSERT INTO accounts (id, email, balance) VALUES (?, ?, ?)", id, email, balance);
    }

    private static Long balanceOf(long id) {
        return on(shardFor(id, SHARDS)).queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, id);
    }

    private static long idOnShard(int wanted, long from) {
        long id = from;
        while (shardFor(id, SHARDS) != wanted) {
            id++;
        }
        return id;
    }

    // ---- experiments -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("Hashing the id spreads 10,000 accounts evenly over the two shards")
    @Order(1)
    void distributionIsEven() {
        for (long id = 1; id <= 10_000; id++) {
            insert(id, "user" + id + "@example.test", 100);
        }

        int a = shard.get(0).queryForObject("SELECT count(*) FROM accounts", Integer.class);
        int b = shard.get(1).queryForObject("SELECT count(*) FROM accounts", Integer.class);
        System.out.println("10,000 accounts over 2 shards: " + a + " and " + b);
        assertThat(a + b).isEqualTo(10_000);
        assertThat(a).isBetween(4_700, 5_300);
    }

    @Test
    @DisplayName("A lookup by the shard key asks one shard; a lookup by anything else asks every shard")
    @Order(2)
    void pointLookupVersusScatterGather() {
        resetCalls();
        assertThat(balanceOf(77)).isEqualTo(100);
        int pointCalls = calls[0].get() + calls[1].get();

        resetCalls();
        List<Long> matches = new ArrayList<>();
        for (int s = 0; s < SHARDS; s++) { // the email says nothing about the shard: ask all of them
            matches.addAll(on(s).queryForList("SELECT id FROM accounts WHERE email = ?", Long.class, "user77@example.test"));
        }
        int scatterCalls = calls[0].get() + calls[1].get();

        System.out.println("by id: " + pointCalls + " shard asked; by email: " + scatterCalls + " shards asked");
        assertThat(pointCalls).isEqualTo(1);
        assertThat(scatterCalls).isEqualTo(SHARDS);
        assertThat(matches).containsExactly(77L);
    }

    @Test
    @DisplayName("An aggregate over all accounts is computed by the application from one partial result per shard")
    @Order(3)
    void aggregateIsMergedByTheApplication() {
        long expected = 0;
        for (int s = 0; s < SHARDS; s++) {
            expected += shard.get(s).queryForObject("SELECT coalesce(sum(balance), 0) FROM accounts", Long.class);
        }
        assertThat(expected).isEqualTo(shard.get(0).queryForObject("SELECT sum(balance) FROM accounts", Long.class)
                + shard.get(1).queryForObject("SELECT sum(balance) FROM accounts", Long.class));
    }

    @Test
    @DisplayName("A UNIQUE constraint only holds inside one shard: the same email can exist on two shards")
    @Order(4)
    void uniquenessStopsAtTheShardBoundary() {
        String email = "same@example.test";
        long first = idOnShard(0, 500_000);
        long second = idOnShard(1, 500_000);
        long sameShard = idOnShard(0, first + 1);
        insert(first, email, 1);

        insert(second, email, 1); // a different shard: nothing stops it
        assertThatThrownBy(() -> insert(sameShard, email, 1)) // the same shard: the constraint does
                .satisfies(e -> assertThat(((SQLException) NestedExceptionUtils.getMostSpecificCause(e)).getSQLState())
                        .isEqualTo("23505"));

        int copies = 0;
        for (int s = 0; s < SHARDS; s++) {
            copies += shard.get(s).queryForObject("SELECT count(*) FROM accounts WHERE email = ?", Integer.class, email);
        }
        System.out.println("the email '" + email + "' exists " + copies + " times across the shards");
        assertThat(copies).isEqualTo(2);
    }

    private static void transferAcrossShards(long from, long to, long amount, boolean crashBetween) {
        on(shardFor(from, SHARDS)).update("UPDATE accounts SET balance = balance - ? WHERE id = ?", amount, from); // commits
        if (crashBetween) {
            throw new IllegalStateException("the process died between the two commits");
        }
        on(shardFor(to, SHARDS)).update("UPDATE accounts SET balance = balance + ? WHERE id = ?", amount, to);
    }

    @Test
    @DisplayName("A transfer across two shards is two transactions: a failure in between loses money until it is compensated")
    @Order(5)
    void crossShardTransferIsNotAtomic() {
        long from = idOnShard(0, 800_000);
        long to = idOnShard(1, 800_000);
        insert(from, "from@example.test", 100);
        insert(to, "to@example.test", 100);

        assertThatThrownBy(() -> transferAcrossShards(from, to, 30, true)).isInstanceOf(IllegalStateException.class);
        long total = balanceOf(from) + balanceOf(to);
        System.out.println("after a crash between the two commits the two balances add up to " + total + " (should be 200)");
        assertThat(total).as("30 units vanished").isEqualTo(170);

        on(shardFor(from, SHARDS)).update("UPDATE accounts SET balance = balance + 30 WHERE id = ?", from); // compensation
        assertThat(balanceOf(from) + balanceOf(to)).isEqualTo(200);
    }

    @Test
    @DisplayName("A transfer inside one shard is one transaction: a failure rolls everything back")
    @Order(6)
    void sameShardTransferIsAtomic() {
        long from = idOnShard(0, 900_000);
        long to = idOnShard(0, from + 1);
        insert(from, "a@example.test", 100);
        insert(to, "b@example.test", 100);

        JdbcTemplate db = shard.get(0);
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()))
                .executeWithoutResult(status -> {
                    db.update("UPDATE accounts SET balance = balance - 30 WHERE id = ?", from);
                    throw new IllegalStateException("fails after the debit");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(balanceOf(from) + balanceOf(to)).isEqualTo(200);
    }

    // ---- resharding ------------------------------------------------------------------------------------------

    /** Consistent hashing: every shard owns many points on a ring of CRC32 values; a key goes to the next point. */
    private static TreeMap<Long, Integer> ring(int shardCount, int pointsPerShard) {
        TreeMap<Long, Integer> ring = new TreeMap<>();
        for (int s = 0; s < shardCount; s++) {
            for (int p = 0; p < pointsPerShard; p++) {
                CRC32 crc = new CRC32();
                crc.update(("shard-" + s + "-point-" + p).getBytes());
                ring.put(crc.getValue(), s);
            }
        }
        return ring;
    }

    private static int ringShard(TreeMap<Long, Integer> ring, long id) {
        Map.Entry<Long, Integer> next = ring.ceilingEntry(crc(id));
        return (next != null ? next : ring.firstEntry()).getValue();
    }

    @Test
    @DisplayName("Going from 2 to 3 shards moves about two thirds of the keys with modulo and about a third with a hash ring")
    @Order(7)
    void reshardingCost() {
        int keys = 100_000;
        TreeMap<Long, Integer> before = ring(2, 128);
        TreeMap<Long, Integer> after = ring(3, 128);
        int moduloMoved = 0;
        int ringMoved = 0;
        for (long id = 1; id <= keys; id++) {
            if (shardFor(id, 2) != shardFor(id, 3)) {
                moduloMoved++;
            }
            if (ringShard(before, id) != ringShard(after, id)) {
                ringMoved++;
            }
        }
        System.out.printf("2 to 3 shards, %d keys: modulo moves %.1f%% of them, a hash ring with 128 points per shard moves %.1f%%%n",
                keys, 100.0 * moduloMoved / keys, 100.0 * ringMoved / keys);
        assertThat(moduloMoved / (double) keys).isBetween(0.60, 0.72);
        assertThat(ringMoved / (double) keys).isBetween(0.20, 0.45);
    }
}
