# Guidebook 18: Transactions, ACID and Isolation Levels (PostgreSQL)

## 1. Concept

A transaction groups statements so they succeed or fail together. ACID names what a database promises about it:
- Atomicity: all of the transaction's changes happen or none do (a refused transfer in guidebook 17 changes neither row).
- Consistency: a committed transaction leaves the data satisfying the constraints the database knows (`CHECK (quantity >= 0)`, foreign keys, unique keys). Rules the database does not know, such as "total across two rows stays at least 1", are the application's job.
- Isolation: concurrent transactions do not see each other's half-done work. How much they see is set by the isolation level.
- Durability: after commit, the change survives a crash (write-ahead log).

Isolation levels trade protection for concurrency. What each one allows in PostgreSQL (the SQL standard allows more than PostgreSQL does at some levels):

| Level | Dirty read | Non-repeatable read | Phantom read | Lost update | Write skew |
|---|---|---|---|---|---|
| READ UNCOMMITTED | no (behaves as READ COMMITTED) | yes | yes | yes | yes |
| READ COMMITTED (default) | no | yes | yes | yes, if the write uses a stale value | yes |
| REPEATABLE READ | no | no | no (snapshot) | rejected with 40001 | yes |
| SERIALIZABLE | no | no | no | rejected with 40001 | rejected with 40001 |

The table is the claim; section 3 shows which cells the tests proved on PostgreSQL 16.15.

## 2. Implementation

No production code changed. `IsolationLevelTest` (warehouse-service) drives two JDBC connections from one thread in a fixed order against the real PostgreSQL container, so every interleaving is exact and the test never depends on timing. T1 is the transaction under test at the isolation level named; T2 changes data in between and commits.

## 3. Measured observation

| Scenario (T1 reads, T2 commits a change, T1 reads or writes again) | Level of T1 | Observed |
|---|---|---|
| T2 has an uncommitted update, T1 reads | READ UNCOMMITTED | T1 sees the old value: no dirty read |
| Row read twice, T2 sets 10 to 7 in between | READ COMMITTED | 10, then 7 |
| Same | REPEATABLE READ | 10, then 10 (7 after T1 ends) |
| Rows of one product counted twice, T2 inserts a third row in between | READ COMMITTED | 2, then 3 |
| Same | REPEATABLE READ | 2, then 2 |
| T1 withdraws 3 from the value it read (10), T2 already withdrew 3 | READ COMMITTED | T1's write succeeds, the row ends at 7 instead of 4: one withdrawal lost |
| Same | REPEATABLE READ | T1's UPDATE fails with SQLSTATE 40001, row stays at 7 |
| Two rows with 1 unit each, rule: at least 1 left. Both transactions read total 2, T1 takes row A and commits, T2 takes row B | REPEATABLE READ | both commit, total 0, rule broken |
| Same | SERIALIZABLE | T2's UPDATE fails with 40001, total stays 1 |

The SERIALIZABLE failure message PostgreSQL returned: `ERROR: could not serialize access due to read/write dependencies among transactions`, detail `Reason code: Canceled on identification as a pivot, during write.` It arrived at T2's UPDATE, before the commit.

10 tests (9 scenarios and the inherited context test), 0 failures, two runs. This unit has no compose lab: isolation anomalies need controlled interleaving between two sessions, which the tests give exactly and a script of background processes would not.

## 4. Reproduction

Automated (real PostgreSQL container):

```bash
./mvnw --batch-mode test -Dtest=IsolationLevelTest
```

## 5. Failure modes and edge cases

- REPEATABLE READ and SERIALIZABLE turn conflicts into errors (SQLSTATE 40001). The application must retry the whole transaction, with a bound on the number of attempts. A caller that does not retry sees failures that READ COMMITTED would have hidden (and silently got wrong).
- Write skew passes REPEATABLE READ because T1 and T2 write different rows. Fixes: SERIALIZABLE with retry, or lock the rows the decision depends on (`SELECT ... FOR UPDATE` on all of them) so the second transaction waits and then reads the new state. Only the first fix is tested here; the lock-based one is shown for a single row in guidebook 16.
- PostgreSQL's REPEATABLE READ is stronger than the SQL standard requires (no phantoms). Another database with the same level name can behave differently.
- Long transactions at REPEATABLE READ or SERIALIZABLE keep an old snapshot alive, which delays vacuum and holds predicate locks.
- Spring's `@Transactional(isolation = ...)` sets the level on the connection for that transaction; the tests use JDBC directly to keep the interleaving explicit.

## 6. Interview questions

- What does each letter of ACID guarantee, and which one does the `CHECK` constraint serve?
- Which anomalies does READ COMMITTED allow, and why is it still the default?
- What is write skew, and why does REPEATABLE READ not stop it?
- Why must code that uses SERIALIZABLE retry, and how should the retry be bounded?
