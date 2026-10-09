# Crash a Spring Boot app mid-transaction — and watch Atomikos recover

A Spring Boot 4 app that saves an order in **PostgreSQL** and sends an order
message to **ActiveMQ Artemis**. We kill the JVM at the worst possible moment,
three times:

| Act | Setup | Crash | After the crash | After a restart |
|-----|-------|-------|-----------------|-----------------|
| 1 | no XA (classic dual write) | between the insert and the send | order saved, message lost | **still broken, forever** |
| 2 | Atomikos + one `@Transactional` | after the send, before the commit | nothing happened | consistent |
| 3 | Atomikos + one `@Transactional` | in the middle of the commit, after both resources *prepared* | in-doubt: visible in `pg_prepared_xacts` | **Atomikos recovery completes it** — consistent |

The crashes are not a random `kill -9` (which almost always misses the window):
a crash switch halts the JVM at the exact point, every time:
`./demo.sh crash <point>` places one order that crashes there.

Everything here is the free, open-source **Atomikos TransactionsEssentials**.


## Run it

You need Java 17+ and Docker. Nothing else — Maven comes with the wrapper,
and the app starts PostgreSQL and Artemis itself (`compose.yaml`).

```bash
git clone <this repo> && cd <this repo>
./mvnw spring-boot:run
```

In a second terminal:

```bash
./demo.sh order book    # POST localhost:8080/orders?product=book
./demo.sh status        # orders, shipments and in-doubt transactions, straight from PostgreSQL
```

On Windows, use `demo.bat` instead of `demo.sh` (same commands: `.\demo order book`,
`.\demo crash before-send`, `.\demo status`, `.\demo reset`) and `.\mvnw` instead
of `./mvnw`. To pick a profile, run `.\mvnw spring-boot:run "-Dspring-boot.run.profiles=naive"`
instead of putting `SPRING_PROFILES_ACTIVE=...` in front of the command.

Each order is a row in PostgreSQL plus a message on the `orders` queue; the
`ShippingListener` turns the message into a shipment. Every order should be
shipped exactly once.

## Act 1: the dual write (no XA)

```bash
./demo.sh reset                              # fresh database, fresh broker
SPRING_PROFILES_ACTIVE=naive ./mvnw spring-boot:run
./demo.sh crash before-send
```

```
💥 CRASH! after orders.save(..), before the JMS send - halting the JVM now (no shutdown hooks, no cleanup)
```

```bash
./demo.sh status
```

```
 order | product |    shipment
-------+---------+----------------
     1 | book    | ❌ NOT shipped
```

Restart (`SPRING_PROFILES_ACTIVE=naive ./mvnw spring-boot:run`) and check again:
order 1 is **still** not shipped. Nothing knows the message was ever meant to
be sent. That's a permanent half-state — a customer who paid and never gets
their book.

The `naive` profile runs [`NaiveOrderService`](src/main/java/com/atomikos/demo/NaiveOrderService.java)
with Atomikos switched off: the insert commits on its own, the send happens on
its own.

## Act 2: one `@Transactional`

[`TransactionalOrderService`](src/main/java/com/atomikos/demo/TransactionalOrderService.java) is
the exact same code plus `@Transactional`:

```java
@Transactional
public long placeOrder(String product) {
    PurchaseOrder order = orders.save(new PurchaseOrder(product)); // 1. PostgreSQL
    crashSwitch.beforeSend();                                      //    ./demo.sh crash before-send
    jms.convertAndSend("orders", order.getId());                   // 2. Artemis
    crashSwitch.beforeCommit();                                    //    ./demo.sh crash before-commit
    return order.getId();
}
```

With `transactions-spring-boot4-starter` on the classpath, that's one XA
transaction across both. This time we crash *after* the send:

```bash
./demo.sh reset
./mvnw spring-boot:run
./demo.sh crash before-commit
./demo.sh status                             # no order, no shipment
```

Restart (`./mvnw spring-boot:run`): still no order and no shipment. The message
was part of the transaction, so the crash rolled it back together with the
insert. Either both happen or neither does.

### "Why not just `@Transactional`?"

Without Atomikos, `@Transactional` is a local database transaction, and the
JMS send happens on its own. Try it with the `local-tx` profile:

```bash
./demo.sh reset
SPRING_PROFILES_ACTIVE=local-tx ./mvnw spring-boot:run
./demo.sh crash before-commit
SPRING_PROFILES_ACTIVE=local-tx ./mvnw spring-boot:run      # restart
./demo.sh status
```

```
 order | product |                 shipment
-------+---------+------------------------------------------
     1 |         | 👻 shipped, but the order does not exist
```

The crash rolled back the order, but the message was already out. On restart
the listener ships it: a book goes out for an order nobody has. (A crash
*before* the send is harmless here: it's the other half-state that bites.)

## Act 3: crash in the middle of the commit

The scary one. Two-phase commit: first both resources *prepare* (promise they
can commit), then Atomikos logs its decision and tells each to *commit*. We halt
the JVM after the prepares and after the decision — but before PostgreSQL got
its commit.

```bash
./demo.sh reset
./mvnw spring-boot:run
./demo.sh crash after-prepare
```

```
💥 CRASH! PostgreSQL and Artemis both PREPARED, commit decision logged, PostgreSQL NOT committed yet - halting the JVM now (no shutdown hooks, no cleanup)
```

```bash
./demo.sh status
```

```
 order | product | shipment
-------+---------+----------
(0 rows)

 in-doubt (prepared) transactions                       |           prepared
--------------------------------------------------------+-------------------------------
 1096044365_eGEtY3Jhc2gtcmVjb3ZlcnktZGVtbzE3...         | 2026-10-09 08:09:25.582132+00
```

The order is invisible, the message is invisible — but both are *prepared*,
holding locks, waiting for someone to decide. That someone is Atomikos. Restart:

```bash
./mvnw spring-boot:run
```

```
Started Application in 12.62 seconds
📦 shipped order #1
```

Atomikos read its transaction log (`transaction-logs/`), found the
`COMMITTING` decision, and finished the job on both resources — no application
code involved. `./demo.sh status` now shows order 1 shipped and no in-doubt
transactions.

## Break it yourself

- **Try a real `kill -9`** while placing orders in a loop
  (`while ./demo.sh order; do :; done`). You'll rarely hit the window between
  prepare and commit — which is exactly why these bugs survive testing and show up in production.
- **Wonder why recovery doesn't crash too?** The crash switch arms only the
  one request that asked for it (`?crash=after-prepare`), not the phase-2
  commit that Atomikos recovery replays after the restart.
- **Delete `transaction-logs/` before restarting in act 3.** Now nobody
  knows the decision was "commit": the order is never completed, and the
  prepared transaction sits in `pg_prepared_xacts`, holding its locks. The
  transaction log is what makes recovery possible — keep it on durable storage.
- **Look at the crash switch**: [`CrashSwitch`](src/main/java/com/atomikos/demo/CrashSwitch.java)
  and [`CrashAfterPrepareConfiguration`](src/main/java/com/atomikos/demo/CrashAfterPrepareConfiguration.java),
  which subclasses the PostgreSQL `XAConnection` to get a call right before
  the phase-2 commit. Demo plumbing only — a real app needs none of it.
  (That hook needs Atomikos, so `crash after-prepare` does not crash under the
  `naive` and `local-tx` profiles.)

## How it's wired

The only Atomikos dependency:

```xml
<dependency>
    <groupId>com.atomikos</groupId>
    <artifactId>transactions-spring-boot4-starter</artifactId>
    <version>6.0.1</version>
</dependency>
```

and in [`application.properties`](src/main/resources/application.properties):

```properties
# PostgreSQL through its XA driver, so it can join a distributed transaction
spring.datasource.xa.data-source-class-name=org.postgresql.xa.PGXADataSource

# where Atomikos keeps the log that recovery reads after a crash
atomikos.properties.log-base-dir=transaction-logs
atomikos.properties.transaction-manager-unique-name=xa-crash-recovery-demo
```

PostgreSQL needs `max_prepared_transactions > 0` for XA (it's 0 by default);
[`compose.yaml`](compose.yaml) sets it. The timeouts are shortened so
recovery happens within seconds on camera; production would use its real values.

## Tests

```bash
./mvnw test
```

Testcontainers starts PostgreSQL and Artemis (tests are skipped without
Docker). The crash switch throws instead of halting there, since a halt would
kill the test runner too:

- `AtomicOrderServiceTest`: an order is stored and shipped; a crash before
  or after the send leaves neither an order nor a shipment; a crash after the
  prepare leaves an in-doubt transaction in `pg_prepared_xacts`, which Atomikos
  then commits (without a JVM restart, unlike act 3).
- `NaiveOrderServiceTest`: without a transaction, a crash before the send
  leaves an order that never ships.
- `LocalTransactionOrderServiceTest`: with `@Transactional` but without
  Atomikos, a crash after the send ships an order that does not exist. This
  is the negative control: it proves the Atomikos test would catch a missing
  XA transaction.

## Going to production?

The crash recovery you saw is in the open-source edition. Running it in
Kubernetes, where a crashed pod may come back with a different name and
without its local disk, is where the commercial editions (LogCloud, high
availability) come in. See [atomikos.com/golive](https://www.atomikos.com/golive).

## Reset

```bash
./demo.sh reset    # stops the containers and deletes the Atomikos transaction log
```
