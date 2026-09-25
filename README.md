# Notification Engine

A multi-channel notification service built on Spring Boot and Kafka. It accepts a
notification over HTTP, renders it from a template, and delivers it over email, SMS, or
push — without losing messages when something downstream breaks.

The interesting part is not the fan-out; it is what happens when things go wrong. A
crash between the database commit and the Kafka publish, a caller that retries a timed-out
request, a vendor returning 503 for ten minutes, a user who opted out last week — each has
a specific answer here, and each is covered by a test.

```
                                        ┌──────────────────────────────┐
  POST /api/v1/notifications            │          PostgreSQL          │
            │                           │  notification + outbox_event │
            ▼                           │  written in ONE transaction  │
  ┌───────────────────┐                 └───────────────┬──────────────┘
  │  rate limit       │  Ignite                         │ poller claims with
  │  idempotency      │  Ignite                         │ FOR UPDATE SKIP LOCKED
  │  preferences      │  Postgres                       │
  │  render template  │  Postgres                       ▼
  └───────────────────┘                 ┌──────────────────────────────┐
                                        │   notifications.inbound      │
                                        └───────────────┬──────────────┘
                                                        │ router fans out by channel
                        ┌───────────────────────────────┼───────────────────────────────┐
                        ▼                               ▼                               ▼
              notifications.email              notifications.sms               notifications.push
                        │                               │                               │
                        ▼                               ▼                               ▼
                 EmailProvider                    SmsProvider                     PushProvider
                        │                               │                               │
                        └───────── retries (exponential backoff), then ─────────────────┘
                                                        ▼
                                          notifications.<channel>.DLT
                                                        │
                                                        ▼
                                      DeadLetterAuditor marks DEAD_LETTERED
```

## What it guarantees

**A notification is never accepted without being sent, and never sent twice.**

That sentence is doing a lot of work, so here is how each half is paid for.

*Never lost.* The notification row and its outbox row are written in one transaction. If
the process dies immediately after the commit, the outbox row is still there and the next
poll publishes it. If the transaction rolls back, neither exists and nothing was ever
promised to the caller. There is no window in which the API has returned `202` but no
Kafka message is owed.

*Never duplicated.* Kafka is at-least-once, so redelivery is normal, not exceptional.
Before calling a provider, the consumer checks whether the notification has already
reached a terminal state and skips it if so. On the inbound side, an `Idempotency-Key`
header is claimed in Ignite with a single atomic `getAndPutIfAbsent`; the unique
constraint on `notification.idempotency_key` is the durable backstop, so losing the cache
costs a database round trip rather than correctness. That is not a theoretical claim —
see [Losing the cache](#losing-the-cache).

The honest caveat: the outbox gives *at-least-once* publication. A crash between a
successful Kafka send and the status update will republish. That is exactly why the
consumer-side terminal-status check exists.

## Failure handling

| What breaks | What happens |
|---|---|
| Vendor returns 503 | `TransientDeliveryException` → retried 4 times with exponential backoff (1s, 2s, 4s), then dead-lettered |
| Malformed recipient | `PermanentDeliveryException` → **no retries**, straight to the dead-letter topic |
| Unparseable Kafka payload | Non-retryable → dead-lettered, so a poison message cannot block the partition |
| Kafka unreachable | Outbox rows stay `PENDING` and retry on the next poll; parked as `FAILED` after 10 attempts |
| Ignite down | Engine keeps running: de-duplication falls back to the database constraint, rate limiting fails open |
| Postgres down | Requests fail fast with 500; nothing is half-written |

Retries are blocking, per-partition. That is a deliberate trade for ordering: a stuck
message delays later messages *for that partition*, and the non-retryable classification
above is what keeps that delay bounded. If you need failing messages to step aside instead,
Spring Kafka's non-blocking `@RetryableTopic` is the alternative.

A dead-lettered message keeps its full payload, so it can be replayed onto the source
topic once the underlying problem is fixed. The auditor records the *cause*, not Spring's
listener wrapper:

```
status: DEAD_LETTERED
reason: TransientDeliveryException: email-provider is temporarily unavailable
```

## Running it

Needs Docker and JDK 17.

```bash
docker compose up -d      # Postgres, Ignite, Kafka (KRaft, no ZooKeeper)
./mvnw spring-boot:run    # Flyway creates the schema and seeds demo templates
```

> If a service fails to reach the app, check for a port clash — a local Postgres on 5432
> shadows the container silently. Copy `.env.example` to `.env` and change the host port:
> ```bash
> POSTGRES_PORT=55432 docker compose up -d
> POSTGRES_URL=jdbc:postgresql://localhost:55432/notifications ./mvnw spring-boot:run
> ```

Optional extras:

```bash
docker compose --profile app up -d --build    # run the app in Docker too
docker compose --profile tools up -d kafka-ui # Kafka console on :8081
```

### Send something

```bash
# 1. Tell the engine where to reach the user
curl -X PUT http://localhost:8080/api/v1/users/user-1/preferences \
  -H 'Content-Type: application/json' \
  -d '{"channel":"EMAIL","enabled":true,"destination":"ada@example.com"}'

# 2. Send
curl -X POST http://localhost:8080/api/v1/notifications \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-42' \
  -d '{"userId":"user-1","channel":"EMAIL","templateCode":"welcome",
       "data":{"firstName":"Ada","product":"Acme"}}'

# 3. Watch it land
curl http://localhost:8080/api/v1/notifications/<id>
curl http://localhost:8080/api/v1/notifications/<id>/attempts
```

Delivery is logged rather than actually sent — see [Providers](#providers).

### See the failure handling work

```bash
# Every send fails with a retryable error: watch 4 attempts, then DEAD_LETTERED
./mvnw spring-boot:run -Dspring-boot.run.arguments=--notification.providers.simulated-failure-rate=1.0

curl "http://localhost:8080/api/v1/notifications?status=DEAD_LETTERED"
```

Replaying the same `Idempotency-Key` returns `200` with `"duplicate": true` and the
original notification, rather than `202` and a second send.

## API

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/notifications` | `202` accepted, `200` if the idempotency key repeats |
| `GET` | `/api/v1/notifications/{id}` | |
| `GET` | `/api/v1/notifications` | Filter by `userId`, `channel`, `status`; paged |
| `GET` | `/api/v1/notifications/{id}/attempts` | Per-attempt history, including retry errors |
| `GET`/`PUT` | `/api/v1/users/{userId}/preferences` | |
| `GET`/`PUT`/`DELETE` | `/api/v1/templates` | |
| `GET` | `/actuator/health`, `/actuator/prometheus` | |

Errors carry a stable machine-readable `code`:

| Status | Code | Meaning |
|---|---|---|
| 400 | `VALIDATION_FAILED` / `MALFORMED_REQUEST` | Bad field, or a body that will not parse |
| 404 | `NOT_FOUND` | No such notification or template |
| 409 | `DUPLICATE_IN_FLIGHT` | Same idempotency key, first request not committed yet — retry |
| 422 | `UNPROCESSABLE` / `TEMPLATE_RENDER_FAILED` | Nowhere to deliver, or a missing template variable |
| 429 | `RATE_LIMITED` | Over quota; carries `Retry-After` |

`429` responses always set `Retry-After` to at least 1 second, so a client backing off on
that header cannot end up in a hot loop.

## Statuses

```
QUEUED ──(outbox poller)──▶ PUBLISHED ──(channel consumer)──▶ SENT
   │                            │                              │
   │                            └──▶ FAILED ──(retries used)──▶ DEAD_LETTERED
   └──▶ SUPPRESSED (opted out, or quiet hours)
```

`SUPPRESSED` is recorded, not silently dropped — "why didn't my user get this?" is a
question the database can answer.

## Templates and preferences

Templates are stored per `(code, channel, locale)` and use `{{variable}}` placeholders. A
missing variable is a `422`, not a half-rendered message with `{{firstName}}` in it.

Rendering happens **at ingest**, so the Kafka message carries finished text. A template
edit cannot retroactively change an in-flight notification, and consumers never need the
template table.

The renderer substitutes named keys and nothing else — deliberately not a full expression
language, since operator-supplied templates rendered with caller-supplied data is exactly
the shape of a server-side template injection bug.

Preferences are per user and channel: on/off, destination, locale, time zone, and an
optional quiet window. Quiet hours are evaluated in the user's own zone and handle windows
that wrap past midnight (22:00–07:00). A user with no preference row falls back to
`notification.preferences.default-enabled` — on by default, so transactional mail keeps
flowing for users who never opened their settings. A locale with no translation falls back
to the default locale rather than failing.

## Rate limiting

Fixed-window counters in Apache Ignite, per user and channel, configurable per channel
(SMS is cheaper to abuse and costs real money, so it defaults lower than push).

Ignite's thin client has no server-side atomic increment, so the counter advances through
a compare-and-set loop over `putIfAbsent` and `replace(key, old, new)`. Both are atomic on
the primary node, so a lost race is detected and retried rather than silently overwriting
a concurrent increment — a plain read-modify-write would undercount under load and let a
user past their quota. The loop is bounded; sustained contention allows the request rather
than spinning.

The window's expiry is attached to the write that *creates* the counter and is never
extended by later increments (`CreatedExpiryPolicy` returns null for updates). That is
what makes it a fixed window rather than a sliding one that a steady stream of requests
could hold open indefinitely.

## Losing the cache

Both Ignite-backed features are designed to degrade, and the engine is built so an outage
of the cache is survivable rather than fatal:

- **The connection is lazy.** The app boots with Ignite unreachable and reconnects on its
  own once it returns, with a cooldown so a down cluster does not add a connection timeout
  to every request. A cache the engine can survive losing at runtime must not be able to
  stop it starting, or a brief outage during a deploy takes out every instance at once.
- **De-duplication falls back to Postgres.** The unique constraint on
  `notification.idempotency_key` still rejects a replayed request, and ingestion turns that
  rejection back into the original notification — the caller still gets `200` with
  `"duplicate": true`.
- **Rate limiting fails open.** Quotas stop being enforced; notifications keep flowing.
  The trade-off is deliberate, and it is why the health check matters.
- **Health reports `OUT_OF_SERVICE`, not `DOWN`.** The instance is still doing its job, so
  a load balancer should not pull it from rotation — but the degradation is visible:

  ```json
  { "status": "OUT_OF_SERVICE",
    "details": { "impact": "de-duplication falls back to the database; rate limiting is off" } }
  ```

This was verified by stopping the Ignite container with the engine running: it kept
accepting and delivering notifications, a replayed `Idempotency-Key` still returned the
original, and the engine recovered on its own when the cluster came back — no restart.

## Providers

The three shipped providers log instead of sending, and validate recipients the way a real
vendor would — an address failing `EmailProvider`'s check is a *permanent* failure, because
no number of retries makes a malformed address valid.

To integrate a real vendor, implement `NotificationProvider` and register it as a bean:

```java
@Component
class SesEmailProvider implements NotificationProvider {
    public Channel channel() { return Channel.EMAIL; }
    public String name() { return "ses"; }
    public DeliveryResult send(NotificationMessage message) { ... }
}
```

`ProviderRegistry` picks it up by channel. Throw `TransientDeliveryException` for anything
worth retrying and `PermanentDeliveryException` for anything that is not — that
distinction is what drives the retry-versus-dead-letter decision. Registering two providers
for one channel fails at startup rather than letting delivery depend on bean ordering.

## Configuration

Everything lives under `notification.*` in `application.yml`. The knobs worth knowing:

| Property | Default | |
|---|---|---|
| `notification.retry.max-attempts` | `4` | Deliveries before dead-lettering, including the first |
| `notification.rate-limit.limits.SMS` | `5` | Per user, per window |
| `notification.rate-limit.window` | `1m` | |
| `notification.idempotency.ttl` | `24h` | How long a key is remembered |
| `notification.outbox.poll-delay` | `500ms` | Latency floor from accept to publish |
| `notification.outbox.batch-size` | `100` | Rows claimed per poll |
| `notification.kafka.concurrency.email` | `3` | Listener threads; never exceed the partition count |
| `notification.providers.simulated-failure-rate` | `0.0` | Failure injection for demos |
| `notification.ignite.backups` | `0` | Cache copies per entry; raise above a single-node cluster |
| `notification.ignite.reconnect-cooldown` | `10s` | Wait before retrying a failed Ignite connection |

Connection settings come from `POSTGRES_URL`, `IGNITE_ADDRESSES`, and
`KAFKA_BOOTSTRAP_SERVERS`.

### A note on the Ignite JVM flags

Ignite 2.x reflects into JDK internals that have been closed by default since Java 16, so
the application JVM needs three `--add-opens` flags. They are already wired into
`spring-boot-maven-plugin` and the `Dockerfile`, so `./mvnw spring-boot:run` and the
container both work unchanged. Running the packaged jar directly needs them spelled out:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/java.util=ALL-UNNAMED \
     --add-opens=java.base/java.lang=ALL-UNNAMED \
     -jar target/notification-engine-0.0.1-SNAPSHOT.jar
```

Without them the thin client dies at startup with an `InaccessibleObjectException`. This
applies to the thin client too, not just an embedded server node.

## Tests

```bash
./mvnw test              # 160 unit tests, no Docker required
./scripts/verify.sh      # 19 end-to-end checks against a running stack
```

`scripts/verify.sh` drives the real HTTP API and asserts on the behaviour that matters —
a send reaching `SENT`, a replayed `Idempotency-Key` returning the original, SMS
throttling on the sixth request, suppression by opt-out and quiet hours, and the error
codes. It exits non-zero on the first failure and prints the server's response, so it
works as a CI gate as well as a local smoke test.

160 unit tests, no Docker required — every dependency is mocked or supplied by a fixed `Clock`,
so the suite runs in CI without infrastructure.

They target the decisions rather than the getters: the rate limiter fails open when the
cache throws, the CAS loop retries when a concurrent request wins the race, the window's
expiry is never extended by an increment, the ingest path releases its idempotency claim
when rendering fails, `markPublished` refuses to walk a `SENT` notification back to
`PUBLISHED`, quiet hours handle windows that wrap midnight, and a redelivered message for
an already-sent notification calls no provider. Several real defects surfaced this way — a
malformed request body returning `500` instead of `400`, and a `smallint`/`integer`
mismatch between the migration and the entity.

End-to-end behaviour was verified against real Postgres, Ignite, and Kafka: a full send
reaching `SENT`, an idempotent replay returning the original, SMS throttling at the sixth
request in a window, suppression by opt-out and by quiet hours, a transient failure
dead-lettering after exactly 4 attempts, a permanent failure dead-lettering after 1, and
the whole degraded-cache path described above.

## Scaling notes

Every node runs the outbox poller; `FOR UPDATE SKIP LOCKED` means concurrent pollers take
disjoint batches rather than fighting over rows or double-publishing. Channel consumers
scale independently — a throttling SMS vendor cannot back up email or push. Messages are
keyed by user id, so one user's notifications stay ordered within a channel; concurrency
above the partition count just leaves consumers idle.

The published `PUBLISHED` outbox rows are swept hourly
(`notification.outbox.retention`, default 7 days) so the table does not grow without bound.

## Layout

```
src/main/java/com/paridhi/notificationengine/
├── api/           REST controllers, DTOs, error handling
├── config/        Kafka topics and error handler, Ignite caches, properties
├── domain/        JPA entities and enums
├── messaging/     Outbox publisher, router, channel consumers, DLT auditor
├── provider/      Provider SPI and the simulated implementations
├── repository/    Spring Data repositories
└── service/       Ingest, delivery, templates, preferences, idempotency, rate limiting
src/main/resources/db/migration/   Flyway schema and seed data
```

## Built with

Java 17 · Spring Boot 4.1 · Spring Kafka · PostgreSQL 16 · Apache Ignite 2.18 · Flyway · Kafka 3.9 (KRaft)
