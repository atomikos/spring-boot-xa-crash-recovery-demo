# Video description and end card

For the clip from *A Bootiful Podcast* (Coffee + Software). Replace
`<REPO URL>` with the final public repo link.

## Title ideas

- Kill -9 your Spring Boot app mid-transaction (and lose nothing)
- What happens when Spring Boot crashes in the middle of a two-phase commit?
- The dual-write bug, fixed with one @Transactional

## Description

We crash a Spring Boot 4 app on purpose, right between a PostgreSQL insert
and a JMS send. Without XA, the order is saved but the message is gone, and a
restart doesn't fix it. With Atomikos and one @Transactional, the database and
the broker commit or roll back together. Then the hard case: we kill the JVM in
the middle of the two-phase commit, after both resources prepared. The
transaction is in doubt (pg_prepared_xacts), and on restart Atomikos crash
recovery completes it automatically.

Run it yourself (Java 17 + Docker, one command): <REPO URL>
Going to production with distributed transactions: https://www.atomikos.com/golive

## Chapters (adjust to the final cut)

- 0:00 The crash: an order saved, its message lost
- 0:00 Restart doesn't heal it: the dual-write problem
- 0:00 One @Transactional + Atomikos: both or neither
- 0:00 Crash in the middle of the commit: an in-doubt transaction
- 0:00 Restart: Atomikos recovery completes it

## End card

- Repo: <REPO URL>
- https://www.atomikos.com/golive

## Keywords (say these out loud, so they're in the transcript)

distributed transactions, Spring Boot, JTA, XA, two-phase commit, in-doubt
transaction, crash recovery, exactly-once, dual write, Saga, outbox pattern,
idempotent consumer, PostgreSQL, ActiveMQ Artemis, JMS, Atomikos
