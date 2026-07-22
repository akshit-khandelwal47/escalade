# Escalade

A self-hostable on-call / escalation engine — the thing that decides *who gets paged, on which
channel, after how long, and what happens when nobody acknowledges*. Built to demonstrate the
delivery-guarantee mechanics that make an alerting system trustworthy, not just a CRUD wrapper
over a `notifications` table.

> **Status:** Phases 0–4 complete (schema, policy + incident CRUD, idempotent dedup, API-key auth,
> the background escalation worker with `SKIP LOCKED` job claiming, and optimistic-locked
> acknowledgement). Dead-lettering and real channels land in later phases — see [Roadmap](#roadmap).

## Why this is interesting

A monitoring system that pages you is only as good as its guarantees. Escalade is designed around four:

| Guarantee | Mechanism |
|---|---|
| A retried webhook never pages twice for one outage | **Idempotent create** via a partial unique index `UNIQUE (org_id, dedup_key) WHERE status='OPEN'` — enforced at the database, not by a racey `SELECT`-then-`INSERT`. |
| Multiple workers, no double-sends, no message broker | `SELECT … FOR UPDATE SKIP LOCKED` job claiming on `notification_attempt` — each worker locks a disjoint batch and skips rows another holds. |
| An ack landing exactly as the next step fires can't lose | **Optimistic locking** (`incident.version`, JPA `@Version`) — the losing transaction rolls back and retries, so an acknowledged incident never pages again. |
| A failing channel surfaces, never silently swallows a page | Capped retries + exponential backoff → an explicit, queryable `dead_letter` state *(Phase 5)*. |

## Stack

Spring Boot 3 · Java 21 · Spring Data JPA · PostgreSQL · Flyway · springdoc-openapi · Testcontainers · Docker Compose.

## Run it

### Docker (one command)

```bash
docker compose up --build
```

App on `http://localhost:8080`, Swagger UI on `http://localhost:8080/swagger-ui`. A demo org is
seeded with API key `esc_demo_key_local`.

### Local (Postgres already running)

```bash
createdb escalade
DB_USER=$(whoami) DB_PASSWORD= ESCALADE_SEED_DEMO=true ./mvnw spring-boot:run
```

## API

All `/api/**` calls require `Authorization: Bearer <org api_key>`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/policies` | Create an escalation policy with ordered steps |
| `GET` | `/api/v1/policies/{id}` | Fetch a policy |
| `POST` | `/api/v1/incidents` | Trigger an incident (idempotent on `dedupKey`) |
| `GET` | `/api/v1/incidents/{id}` | Incident status + full escalation timeline |
| `GET` | `/api/v1/incidents?status=OPEN` | List incidents |
| `POST` | `/api/v1/incidents/{id}/ack` | Acknowledge — halts escalation |
| `POST` | `/api/v1/incidents/{id}/resolve` | Resolve |

### Example

```bash
KEY="esc_demo_key_local"

# 1. Create a policy: page Slack immediately, email after 30s.
POLICY=$(curl -s -X POST localhost:8080/api/v1/policies \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"name":"Backend on-call","steps":[
        {"channel":"SLACK","target":"#alerts","delaySeconds":0},
        {"channel":"EMAIL","target":"oncall@example.com","delaySeconds":30}]}' | jq -r .id)

# 2. Trigger an incident.
curl -s -X POST localhost:8080/api/v1/incidents \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d "{\"policyId\":\"$POLICY\",\"title\":\"High DB latency\",\"dedupKey\":\"db-latency-prod\"}"

# 3. Fire the SAME dedupKey again — returns the same incident (HTTP 200, not 201). No duplicate page.
```

## Roadmap

- [x] **0** Scaffold, Flyway, Docker Compose, Swagger
- [x] **1** Schema + migrations (all six tables + dedup/worker indexes)
- [x] **2** Policy + incident CRUD, idempotent dedup, API-key auth, state transitions
- [x] **3** Worker loop — `SKIP LOCKED` claiming + step escalation
- [x] **4** Ack-race handling — optimistic lock + concurrent test
- [ ] **5** Dead-letter path + retry/backoff
- [ ] **6** Real Slack + email channels
- [ ] **7** Inbound webhook receiver
- [ ] **8** Metrics, architecture diagram, demo GIF

## Design decisions worth asking about

- **Incremental attempt materialization.** Creating an incident inserts only the *first*
  `notification_attempt`; the worker schedules step N+1 when it sends step N. Avoids
  pre-materializing the whole ladder and makes "ack halts escalation" a single `UPDATE … WHERE status='PENDING'`.
- **At-least-once, honestly.** `SKIP LOCKED` + an external send (Slack/email) is at-least-once by
  nature — a crash after send-before-commit re-sends. Exactly-once across a system that doesn't
  dedupe is impossible; dedup is handled at the incident level instead.
- **Why no queue yet.** Postgres `SKIP LOCKED` is a perfectly good work queue at this scale. The
  swap to Kafka/SQS is a throughput/ordering decision, deferred until there's a reason.
