# Seat Reservation at Scale

A small JSON HTTP service that sells assigned seats for a show. Spring Boot 4.1.1, Java 21, Postgres 16, Flyway migrations, Micrometer/Prometheus metrics. Built for the Paytm Money "Deploy & Observe" round.

Correctness guarantees, all enforced inside Postgres transactions rather than in Java memory: a seat is confirmed to at most one user (row lock plus a guarded `UPDATE ... WHERE status='available'`); a user never holds more than `per_user_limit` seats per show (atomic upsert with a `WHERE` guard); a retried request with the same idempotency key creates one reservation and the same key with different seats is rejected with 409 (unique constraint on `(user_id, idempotency_key)`); identity comes only from the JWT, never from the request body; multi-seat requests are all-or-nothing. Money is an integer number of paise (`bigint`), never a float.

## Live deployment

- Base URL: https://seat-reservation-viak.onrender.com
- Hosted on Render's free tier. The instance sleeps after about 15 minutes without traffic and takes about a minute to wake. The first request after a sleep can take around 55 seconds (one measured cold start: "ready after 55.5 s"). Send a request to `/actuator/health/readiness` and wait for `200` before judging anything.
- Get a user token (open dev issuer, no password, by design: any `user_id` of 1 to 64 characters gets a token valid for 3600 s by default):

```
Request:
curl -s -X POST https://seat-reservation-viak.onrender.com/auth/token \
  -H 'Content-Type: application/json' -d '{"user_id":"alice"}'

Response:
{"token":"<jwt>","user_id":"alice","expires_in":3600}
```

- Creating a show (`POST /shows`) is admin-only and needs the header `X-Admin-Token`. The deployed value is `dev-admin-token`.
- Logs on Render are in the Render dashboard (Logs tab) and need the owner's login; there is no public log URL. `<LOGS ACCESS FOR GRADERS (screen recording or dashboard invite) — owner to fill in>`

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/shows` | `X-Admin-Token` header | Create a show with all seats `available` |
| GET | `/shows/{id}` | none | Per-seat status and counts |
| POST | `/auth/token` | none | Issue a JWT for a `user_id` (404 if `AUTH_TOKEN_ENDPOINT_ENABLED=false`) |
| GET | `/me` | Bearer JWT | Echo the token's user id |
| POST | `/shows/{id}/reserve` | Bearer JWT | Reserve one or more seats (confirmed immediately) |
| POST | `/reservations/{id}/cancel` | Bearer JWT | Owner cancels a reservation, seats become available |
| GET | `/actuator/health/liveness` | none | Process is up |
| GET | `/actuator/health/readiness` | none | 200 only if the DB answers `SELECT 1`; 503 otherwise |
| GET | `/actuator/prometheus` | none | Prometheus metrics |

Examples (`BASE=https://seat-reservation-viak.onrender.com`; for a local run use `http://localhost:8080`):

```
# create a show (admin). per_user_limit is optional, default 4. price_paise must be an integer > 0.
Request:
curl -s -X POST $BASE/shows -H 'Content-Type: application/json' -H "X-Admin-Token: $ADMIN_TOKEN" \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A4","A5"],"price_paise":25000,"per_user_limit":4}'
Response:
{"id":"<show-id>","name":"friday-night","price_paise":25000,"per_user_limit":4,"total_seats":5,"seats":[{"label":"A1","status":"available"},...]}

# show state
Request:
curl -s $BASE/shows/$SHOW
Response:
{"id":...,"total_seats":5,"counts":{"total":5,"available":5,"held":0,"confirmed":0},"seats":[{"label":"A1","status":"available"},...]}

# token
TOKEN=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

# reserve, idempotency key in the header
curl -s -X POST $BASE/shows/$SHOW/reserve -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: order-1' -d '{"seats":["A1"]}'
# 201 {"reservation_id":"...","show_id":"...","user_id":"alice","seats":["A1"],"amount_paise":25000,"status":"confirmed"}

# reserve, idempotency key in the body (if both are given they must be equal, else 400)
curl -s -X POST $BASE/shows/$SHOW/reserve -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"seats":["A2","A3"],"idempotency_key":"order-2"}'

# cancel (owner only)
curl -s -X POST $BASE/reservations/$RESERVATION/cancel -H "Authorization: Bearer $TOKEN"
# 200 {... "status":"cancelled"}

# health and metrics
curl -s $BASE/actuator/health/readiness        # {"status":"UP"}
curl -s $BASE/actuator/prometheus | grep -E '^(reservations|cancels|seats)'
```

### Status codes

| Status | When |
|---|---|
| 201 | Reserve confirmed (first time for this user + key) |
| 200 | Reserve replay: same user, same key, same show and seats. Returns the original reservation (including `status:"cancelled"` if it was cancelled since). Cancel success also returns 200. |
| 409 | Domain decline. Body `{"error":"conflict","reason":...,"message":...}`, plus `"seats":[...]` for `seat_taken`. Reasons: `seat_taken`, `per_user_limit`, `idempotency_key_reused` (same key, different show/seats), `already_cancelled` (cancel only) |
| 400 | `validation_error`: missing/blank/over-255-char key, empty or duplicate seats, unknown seat label for the show, header and body key differ, missing or malformed body or id |
| 401 | Missing/invalid/expired bearer token, or wrong/missing `X-Admin-Token` |
| 404 | Unknown show; cancel of an unknown reservation or one owned by someone else (deliberately indistinguishable); `/auth/token` when disabled |
| 503 | Database trouble (pool timeout, connection loss, exhausted deadlock retries). Header `Retry-After: 1`, body `service_unavailable` |
| 500 | Unexpected bug (`internal_error`) |

Behaviour decisions:

- Multi-seat is all-or-nothing: if any requested seat is taken, nothing is booked and the response is 409 `seat_taken` listing the unavailable seats. The `seats` in a reserve response are sorted by label, not in request order.
- A reservation is `confirmed` immediately; there are no timed holds. Release is the explicit `POST /reservations/{id}/cancel`. Consequently `counts.held` (and the `seats{status="held"}` gauge) is always 0. The column and status value exist in the schema but nothing writes them.
- A request for more seats than `per_user_limit` is declined up front with 409 `per_user_limit`. Cancelling decrements the user's count, so they can reserve again.
- Idempotency keys are scoped per user: two users can use the same key independently.
- Seats are matched by exact label string. Prices are `price_paise` (integer) and `amount_paise = price_paise * seats`.

## Run locally

Prerequisites: Docker (for compose and for the tests). JDK 21 only if you run outside Docker or run the burst client.

```
docker compose up --build
# app on http://localhost:8080, Postgres on localhost:5432 (db/user/password: seats/seats/seats)
curl -s localhost:8080/actuator/health/readiness
```

The compose app container is capped at `mem_limit: 512m` to mimic Render's free instance and uses the default `ADMIN_TOKEN` (`dev-admin-token`) and the default dev `JWT_SECRET`. Those defaults are for local use only.

Running the app from the host or an IDE against the compose database:

```
docker compose up -d db
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.jvmArguments=-Duser.timezone=UTC
```

`-Duser.timezone=UTC` is needed on a Windows machine whose JVM default zone is a legacy id such as `Asia/Calcutta`: Postgres rejects that id from the JDBC driver (the Maven surefire config sets the same flag for tests). The `dev` profile only exists to switch console logging to plain text; see the logging note below.

Tests: `./mvnw verify`. Docker must be running, because most test classes start a `postgres:16` container through Testcontainers.

## The burst

`src/main/java/Burst.java` is a single-file Java program (JDK 21, no dependencies) that runs the on-sale stampede against any base URL and prints the outcome distribution and a reconciliation. Run it as:

```
./burst.sh <BASE_URL> [options]          # Linux/macOS/Git Bash; checks the java version first
burst.cmd <BASE_URL> [options]           # Windows cmd
make burst BASE_URL=<BASE_URL>           # BASE_URL defaults to http://localhost:8080; no options pass-through
java src/main/java/Burst.java <BASE_URL> [options]    # direct, from the repo root
```

Against a deployment that has a non-default admin token, set it first: `export ADMIN_TOKEN=...` (bash), `$env:ADMIN_TOKEN='...'` (PowerShell), `set ADMIN_TOKEN=...` (cmd), or pass `--admin-token`. The client waits up to 120 s for `/actuator/health/readiness` to return 200, so a cold start is tolerated.

Options (also accepted as `--name=value`):

| Option | Default | Meaning |
|---|---|---|
| `--seats N` | 1000 | seats in the general-stampede show |
| `--stampede N` | 20000 | requests in the general stampede |
| `--users N` | 4000 | distinct users in the stampede |
| `--hot-users N` | 500 | users fighting for each hot seat |
| `--hot-seats N` | 5 | number of hot seats (storm size = hot-users x hot-seats, all released at once) |
| `--max-inflight N` | 500 | cap on concurrent requests in the stampede only |
| `--admin-token T` | `dev-admin-token` | admin token for `POST /shows` |
| `--timeout-seconds N` | 60 | per-request client timeout |
| `--seed N` | 42 | RNG seed for the stampede plan |

Each scenario runs on its own fresh show:

- (a) Hot-seat storm: `hot-users` users each try each of `hot-seats` seats, all requests released together behind a latch. Checks: per seat exactly one 201 and every other request 409 `seat_taken`.
- (b) General stampede: `--stampede` requests over random 1 to 3 seat sets, 70% fresh requests, 15% exact retries (same key and seats), 15% same key with different seats. Checks: only documented outcomes (no 5xx, no unexpected 4xx), confirmed seats never exceed total, no key maps to more than one reservation.
- (c) Idempotency: 50 parallel requests with one key (exactly one 201, the rest 200 with the same `reservation_id`), then 20 parallel requests with the same key and different seats (all 409 `idempotency_key_reused`).
- (d) Per-user limit: one user, 10 parallel single-seat reserves, limit 4: exactly 4 succeed and 6 get `per_user_limit`.
- (e) Identity: spoofed `user_id` in the body is ignored, no token gives 401, another user's cancel and an unknown id give 404, owner cancel 200, second cancel 409 `already_cancelled`, rebook of the freed seat 201.
- (f) Reconciliation: for every show the burst created, `available + held + confirmed == total`, `held == 0` and the confirmed count equals the client's tally; then the deltas of the Prometheus counters and of `seats{status="confirmed"}` between before and after the run must equal what the client counted. This assumes nothing else is using the service during the run.

Reading the output: each check prints `[PASS]`, `[FAIL]`, `[WARN]` or `[SKIPPED]`, followed by a result table and a final line such as `N checks: ... => PASS`. Exit code 0 means no check failed; 1 means at least one FAIL; 2 means bad arguments, the service not ready within 120 s, or an aborted run. Metric checks are SKIPPED (not failed) if `/actuator/prometheus` has no `reservations_*` series.

Client-error rule: connect failures (the request never reached the server) are retried up to 3 times and reported as a WARN with a retry count. Timeouts and resets have an unknown server outcome, are never retried and get their own bucket (`CLIENT-ERROR ...`), never counted as 5xx; reconciliation allows exactly that many units of slack. The run fails only if client errors exceed 1% of all calls. An HTTP 5xx from any source, including a platform proxy, counts toward the "zero 5xx" check.

Guidance for the free-tier deployment: it is small. Keep `--max-inflight` low (about 25). The hot-seat storm ignores `--max-inflight` and releases `hot-users x hot-seats` requests at once, so lower `--hot-users`/`--hot-seats` if you see 502s (see "Capacity" below). Suggested graduated runs against the live URL:

```
./burst.sh $BASE --hot-users 50  --hot-seats 2 --stampede 500  --users 200  --seats 200  --max-inflight 25
./burst.sh $BASE --hot-users 100 --hot-seats 2 --stampede 3000 --users 1000 --max-inflight 25
./burst.sh $BASE --hot-users 250 --hot-seats 3 --stampede 5000 --users 2000 --max-inflight 25
```

Defaults (`./burst.sh http://localhost:8080`) are the full 2,500-request storm plus the 20,000-request stampede and are meant for a local run.


## Observability

Health: `/actuator/health/liveness` is 200 while the process runs. `/actuator/health/readiness` includes the `db` check, implemented by `DbHealthIndicator`: it opens its own short-lived connection (not from the pool) with 2 s connect/login/socket timeouts and runs `SELECT 1`, so with the database unreachable readiness answers 503 in about 2 s, and a saturated pool cannot make it flap. Health details are hidden (`show-details=never`).

Metrics at `/actuator/prometheus` (only `health` and `prometheus` are exposed). Custom series, exactly as exported (all pre-registered, so they appear as 0 on the first scrape):

| Series | Labels | Counts |
|---|---|---|
| `reservations_confirmed_total` | none | reserve requests that created a reservation (one per reservation, not per seat) |
| `reservations_idempotent_replay_total` | none | reserve requests answered with the original (HTTP 200) |
| `reservations_declined_total` | `reason` = `seat_taken`, `per_user_limit`, `idempotency_conflict`, `validation` | declined reserves; `validation` covers 400s and 404 unknown show |
| `reservations_cancelled_total` | none | successful cancels |
| `cancels_declined_total` | `reason` = `already_cancelled`, `not_found` | declined cancels |
| `reservations_errors_total` | none | reserve requests that failed with an unexpected or DB error (503/500) |
| `cancels_errors_total` | none | same for cancel |
| `seats` | `status` = `available`, `held`, `confirmed` | gauge, summed over all shows |
| `seats_capacity` | none | gauge, total seats over all shows |

Every handled reserve request lands in exactly one of confirmed, replay, declined (any reason) or errors. Counters are incremented only after the outcome is final (after commit or rollback, never inside a retried transaction). The `seats` gauges come from one `GROUP BY` statement, so `available + held + confirmed == seats_capacity` on every scrape; the snapshot is cached for about 1 s and a scrape serves the last snapshot if the DB is slow or down. Because the gauges are global across shows, check a single show with `GET /shows/{id}`.

Reconciliation relationship: within one process lifetime that started on an empty database, `reservations_confirmed_total - reservations_cancelled_total` equals the number of live reservations, which equals `seats{status="confirmed"}` only when every reservation holds exactly one seat (multi-seat reservations hold several). Counters reset on restart and are per instance, so compare deltas around a run, which is what the burst does (it compares the seat delta against the client's confirmed seat count).

Standard Micrometer/Actuator series are also exported, for example `http_server_requests_seconds_*` (with histogram buckets), `hikaricp_connections_active|pending|max|timeout_total`, `jvm_memory_used_bytes`, `process_cpu_usage`.

Logging: the access log (logger `access`, one line per request except `/actuator/*`: `request method=... path=... status=... duration_ms=...`), plus outcome lines such as `reserve outcome=CONFIRMED reason=ok show_id=... user_id=... detail=...` and `cancel outcome=...`. `X-Request-Id` is accepted from the client if it is at most 128 characters of `[A-Za-z0-9._-]`, otherwise a UUID is generated; it is echoed in the response header and stored in the logging MDC as `request_id` (also `user_id`, `show_id`, `reservation_id` where known). The MDC fields only appear in the structured JSON log format.

IMPORTANT, current state of the code: `application.properties` sets `logging.structured.format.console=` (empty), which means console logs are plain text by default, in both the default profile and `dev`, and plain text lines do not include `request_id`. For JSON logs with `request_id`, set the environment variable `LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash` 

Reading logs: `docker compose logs -f app` locally; Render dashboard, service `seat-reservation`, Logs tab, on the deployment.

## Deployment (Render)

`render.yaml` is a Blueprint: a Docker web service (`plan: free`, region `oregon`, `healthCheckPath: /actuator/health/readiness`, `autoDeployTrigger: commit`) and a free Postgres `seat-reservation-db` in the same region (the internal DB URL only resolves in-region). Create it with Render > New > Blueprint pointing at the repo; Render prompts for `ADMIN_TOKEN`. Pushing a commit to the connected branch redeploys. The Dockerfile is a multi-stage build (Maven build, then `eclipse-temurin:21-jre` running as a non-root user).

Environment variables:

| Variable | Default | Notes |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/seats` | accepts `jdbc:postgresql://...` or Render-style `postgres://user:pass@host[:port]/db` (converted at startup; adds `sslmode=require` for dotted hostnames when none is given) |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `.../seats`, `seats`, `seats` | used when `DATABASE_URL` is not set (`DB_USER`/`DB_PASSWORD` are overridden by credentials inside a `postgres://` URL) |
| `JWT_SECRET` | dev placeholder | HS256, at least 32 bytes or startup fails; Render generates one |
| `JWT_EXPIRY_SECONDS` | 3600 | |
| `ADMIN_TOKEN` | `dev-admin-token` | secret on Render, not in git |
| `AUTH_TOKEN_ENDPOINT_ENABLED` | `true` | `false` makes `/auth/token` return 404 |
| `JAVA_OPTS` | Dockerfile: `-XX:MaxRAMPercentage=75 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC`; Render: `-XX:MaxRAMPercentage=65 ... -Xss512k` | |
| `PORT` | 8080 (Render injects 10000) | |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | empty (plain text) | see logging note above |

Pool and server settings (in `application.properties`): Hikari max 20 / min idle 5 / connection timeout 30 s; Tomcat 200 threads, accept-count 1000, max-connections 10000.

Free-tier caveats (from Render's docs as read earlier): the web service spins down after 15 minutes idle; the free Postgres expires 30 days after creation (then a 14-day grace period before deletion), is 1 GB, has no backups, and a workspace can have one free database. All data is lost if the database is not upgraded or recreated.

## Capacity and known limitations

Measured (see caveats):

- Local, `docker compose` : the default burst (500 users x 5 hot seats = 2,500 simultaneous requests, then a 20,000-request stampede) gave 0 5xx, exactly one winner per hot seat and all metric deltas reconciled. About 750 req/s on the hot storm (p50 1.4 s, p99 2.4 s) and about 1,250 req/s on the stampede (p99 about 1.1 s). It also passed at 2x (40,000 requests, 2,000 in flight). Running the client from the Windows host produced thousands of connect retries though
- Render free tier : hot storms of 100 users x 2 seats and 250 x 3 (up to about 750 simultaneous requests) with stampedes of 3,000 to 5,000 requests at `--max-inflight 25` passed all checks (44/44) with 0 5xx, stampede p50 about 0.4 s, p99 about 1.4 s, about 42 to 55 req/s. A 5,000-request stampede at `--max-inflight 100` had 597 requests (about 12%) fail to connect (they never reached the app: server counters matched the client's tally exactly), p99 7.3 s, 44 req/s; at 25 in flight there were 0 errors.
- Render free tier, 500 users x 5 hot seats (2,500 simultaneous requests) right after a cold start ("ready after 55.5 s"): 5 confirmed (exactly one winner per seat, no double-sell), 879 `seat_taken`, 1,615 HTTP 502, 1 client timeout, p50 29.7 s, p99 44.8 s, max 79 s, about 31 req/s. The 502s were generated by Render's proxy (the application does not emit 502). Render's Events showed "Instance failed: HTTP health check failed (timed out after 5 seconds)". The app's error counters stayed 0 and it logged no errors.

Interpretation: correctness held in every run, but the free instance (about 0.1 CPU per Render's free-tier description, an assumption not measured here) is capacity-bound somewhere between about 750 and 2,500 simultaneous requests, and a cold start makes it worse. A burst larger than that on the free tier can see 502s that are platform-level rather than application errors; the burst's "zero 5xx" check will still report them as 5xx, so compare with `reservations_errors_total` and the app logs. The free tier is not shown to handle a 20,000-request burst, and no paid plan has been tested.

Limitations verified in the code and tests:

- The token issuer (`POST /auth/token`) is open: anyone can obtain a token for any `user_id`. It exists for the exercise and can be switched off with `AUTH_TOKEN_ENDPOINT_ENABLED=false`.
- No holds and no expiry: reservations are confirmed immediately and released only by explicit cancel, so an abandoned reservation keeps its seats forever.
- One Postgres primary, no replica, no failover. When it is unreachable the service answers 503 (consistency over availability).
- The deadlock/serialization retry loop (up to 3 attempts on SQLSTATE 40P01/40001) and the 503 path for DB failures have no test that triggers them; the readiness test pauses the Postgres container, but no test asserts a 503 from `/reserve`. The concurrency tests show no errors under contention, which is evidence but not a proof that deadlocks cannot occur.
 across shows, there are no per-show or per-user metrics.
- `/actuator/prometheus` and health endpoints are unauthenticated. No rate limiting. Idempotency rows are never purged.
- `GET /shows/{id}` returns every seat, so its response grows with the hall size.

## How AI was used


- **Requirements.** The task started from `initial.md`, the brief provided, which lists the functional requirements, the correctness bar, and the deploy-and-observe requirements.
- **Design.** Claude Code and I brainstormed the architecture and system design together: the stack, where the atomic decision should live, how idempotency and the per-user limit are enforced, and how to make the system observable. The result was a written plan, which we finalized and divided into 10 parts.
- **Implementation.** Each part of the plan was executed by AI. I then reviewed the code for that part and tested the scenarios manually before moving to the next part.
- **Bugs.** Bugs and gaps showed up while testing. Some of them I corrected myself.
- **Understanding the code.** Whenever I had doubts about the code (for example the reserve controller and service, the locking, idempotency and the burst script), I asked the AI to explain it line by line until I understood it.
- **Deployment and load tests.** I created the Render account, deployed the service and ran the burst script against the live URL myself. The results, including where the free tier breaks, are in "Capacity and known limitations" above.

## Project layout

```
src/main/java/com/seatreservation/system/
  controller/     ShowController, ReservationController
  service/        ReservationService (reserve/cancel transactions), ShowService
  repo/           ReservationRepository, ShowRepository (plain SQL via JdbcTemplate)
  auth/           JwtService, JwtAuthFilter, AuthController, AuthContext
  config/         RequestIdFilter, DatabaseUrlEnvironmentPostProcessor
  exception/      ApiException, ReserveDeclinedException, GlobalExceptionHandler
  observability/  ReservationMetrics, SeatGauges, DbHealthIndicator
src/main/java/Burst.java            burst client
src/main/resources/db/migration/V1__init.sql   schema (shows, reservations, seats, user_show_holds)
src/test/java/...                   integration tests (Testcontainers Postgres 16)
Dockerfile, docker-compose.yml, render.yaml, Makefile, burst.sh, burst.cmd
```
