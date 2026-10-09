# Notes for Claude

A Spring Boot 4 demo: an order is saved in PostgreSQL and sent to Artemis, and
we crash the JVM on purpose at exact points to show what Atomikos XA (and its
crash recovery) changes. The README tells the whole story; this file is how to
run it on macOS and how to help record a demo video.

## Running it on macOS (Terminal, zsh or bash)

- Needs a JDK 17+ (`JAVA_HOME`, e.g. via `brew install openjdk@21` or SDKMAN!)
  and Docker Desktop running. The app starts PostgreSQL and Artemis itself
  (`compose.yaml`) and leaves them running.
- Start the app: `./mvnw spring-boot:run`. With a profile:
  `SPRING_PROFILES_ACTIVE=naive ./mvnw spring-boot:run` (or `local-tx`), as in
  the README. It's ready when the log says `Started Application`.
- Helper: `./demo.sh order [product]`, `./demo.sh crash before-send|before-commit|after-prepare`,
  `./demo.sh status`, `./demo.sh reset` (`demo.bat` is its Windows twin).
- Demo page: http://localhost:8080/ has the same order and crash buttons, and
  the `status` tables refreshed every second. It shows the running mode
  ("no XA", "@Transactional without XA", "Atomikos"), says when the app is
  down, and picks up again after a restart.

Things that will surprise you:

- **Every crash button or `./demo.sh crash` halts the JVM. That's the demo**, not a
  bug. The app has to be restarted with the same command (and profile).
- The page reads through the app, so while the app is down only `./demo.sh status`
  shows the database. That's the moment for act 3's in-doubt transaction.
- **Check before you start or stop things.** The user often runs the app
  themselves. Before starting it, check port 8080 is free
  (`lsof -nP -iTCP:8080 -sTCP:LISTEN`); if it isn't, ask.
  `./demo.sh reset` runs `docker compose down` and deletes `transaction-logs/`:
  it wipes all orders and pulls the database out from under a running app.
  Ask before running it unless the user asked for a fresh start.
- Never POST to `/orders` just to probe whether the app is up: that places a
  real order. Use `GET /status`.
- Order ids can jump (1, 2, 102, ...): Hibernate allocates ids in blocks, and
  a crash or restart skips the rest of the block.
- `./mvnw test` runs the crash tests with Testcontainers (Docker needed, ~1-2 min).

## Recording the video

Claude can't record the screen; the user does, with the macOS screenshot
toolbar (Cmd+Shift+5, "Record Entire Screen" or "Record Selected Portion") or
QuickTime Player, and trims in QuickTime (Cmd+T) or iMovie. Claude's job:
rehearse each act with the user, and before each take get the state ready
(reset + start the app with the right profile) so the user only clicks,
restarts and talks.

Screen layout: the terminal running the app on the left (the audience sees the
`💣` and `💥 CRASH!` log lines and the restart), the browser on
http://localhost:8080/ on the right. Zoom both (Cmd+= in Terminal and the
browser) so the text reads on a small screen. Do a dry run of every act before recording.

### Act 1: the dual write (no XA)

- Prep: `./demo.sh reset`, then `SPRING_PROFILES_ACTIVE=naive ./mvnw spring-boot:run`.
  The page says "Running: no XA: the classic dual write".
- Click **Crash before the send**. The app dies; the page says it's down.
- Restart with the same command. The page comes back: the order is
  **❌ NOT shipped**, and it stays that way.
- Point: the insert committed, the message was never sent, and nothing will
  ever fix it. A customer paid and never gets their book.

### Act 2: one `@Transactional` + Atomikos

- Prep: `./demo.sh reset`, then `./mvnw spring-boot:run` (default profile). The page
  says "Running: Atomikos: one XA transaction".
- Optionally click **Place an order** first, to show the happy path (✅ shipped).
- Click **Crash before the commit** (after the send this time). Restart.
- The crashed order is nowhere: no order, no shipment. The message was part of
  the transaction, so it rolled back with the insert. Both or neither.
- Optional contrast ("why not just `@Transactional`?"): the same with the
  `local-tx` profile gives **👻 shipped, but the order does not exist**: the
  database rolled back, but the message was already out.

### Act 3: crash in the middle of the commit

- Prep: `./demo.sh reset`, then `./mvnw spring-boot:run`.
- Click **Crash after prepare**. The log says both resources PREPARED and the
  commit decision is logged, but PostgreSQL wasn't committed yet.
- While the app is down, run `./demo.sh status` in another terminal tab: no order, but one
  row under **in-doubt (prepared) transactions**. Point: PostgreSQL holds a
  prepared transaction and its locks, and waits for someone to decide.
- Restart. Atomikos reads `transaction-logs/`, finds the commit decision and
  completes it: the log says `📦 shipped order #...`, and on the page the
  order shows **✅ shipped** (it may show ❌ for a second until the shipment
  lands). No in-doubt transactions left. No application code involved.

### Wrap-up

Everything shown is the free, open-source Atomikos TransactionsEssentials;
running it in Kubernetes is where the commercial editions come in
(https://www.atomikos.com/golive). `VIDEO.md` has title ideas, a description
and keywords for publishing a recording.
