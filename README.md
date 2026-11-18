# Distributed Ticket Reservation System

A final-year, production-inspired backend project for high-demand ticket sales. The first milestone implements the core invariant: **a seat can be held or booked by only one customer at a time**.

## Current milestone

- Java 21 / Spring Boot modular monolith (`ticketing-app`) with an inventory module; booking and payment modules scaffolded
- Module boundaries enforced by ArchUnit tests in CI
- PostgreSQL as the authoritative inventory store
- Atomic, all-or-nothing multi-seat holds using transactional conditional updates
- Five-minute hold expiry model
- Docker Compose services for PostgreSQL, Redis, and Kafka
- Flyway schema migrations and sample concert inventory

## Run locally

Prerequisites: Java 21+, Maven 3.9+ and Docker Desktop.

The repository is a Maven multi-module build: `common`, the core modules `inventory`, `booking` and `payment`, and `ticketing-app`, the one deployable that bundles them ([ADR 002](docs/adr/002-modular-monolith-first.md)). Design docs: [SRS](docs/srs/SRS.md), [HLD](docs/hld/README.md), [LLD](docs/lld/class-diagrams.md), [API contracts](docs/api/README.md). See [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md) for the full roadmap and [docs/DEVELOPMENT_LOG.md](docs/DEVELOPMENT_LOG.md) for what was built in each phase and why.

```bash
docker compose up -d
mvn install -DskipTests          # builds all modules from the root parent POM
mvn -pl ticketing-app spring-boot:run
```

If a local PostgreSQL already uses port 5432, move the Docker one:

```bash
POSTGRES_PORT=55432 docker compose up -d
DB_URL=jdbc:postgresql://localhost:55432/ticket_reservation mvn -pl ticketing-app spring-boot:run
```

Seat locking strategy: `LOCKING_STRATEGY=conditional-update` (default), `pessimistic`, `optimistic`, `redis-lock` or `redis-lua` (the Redis ones need `docker compose up -d redis`). See ADR 003.

Tests: `mvn verify` runs unit and architecture tests, then integration tests against a throwaway PostgreSQL container (Testcontainers; Docker must be running).

The app starts at `http://localhost:8080`.

```bash
# Demo show 00…0101 (Demo Concert at Demo Arena, 100 seats A1–A100), created by migration V2
curl -X POST http://localhost:8080/api/v1/shows/00000000-0000-0000-0000-000000000101/holds \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 11111111-1111-1111-1111-111111111111' \
  -d '{"seatCodes":["A1","A2"]}'
```

### Live demo UI

Open **`http://localhost:8080`** once the app is running. One page, served by the app itself (plain HTML/JS in `ticketing-app/src/main/resources/static`, no build step):

- **Seat map** of the demo show, refreshed every 1.5 s (open two tabs, or switch user between Alice, Bob and Charlie, to watch seats change live).
- **Book → pay:** pick up to 8 seats, *Hold seats & book*, then choose what the mock gateway does: **Pay ✓** (booking `CONFIRMED`, seats `BOOKED`), **Card declined** (booking `FAILED`, seats freed at once), **Gateway timeout** (money taken, answer lost: payment `UNKNOWN`, seats stay held, reconciliation confirms it within about 40 s), or **Cancel booking**. The saga timeline shows each state change.
- **Concurrency lab:** *Stampede* sends N concurrent bookings for one seat from N different users (exactly one wins); *Duplicate retries* sends 20 copies of one request with the same `Idempotency-Key` (exactly one booking).
- **My bookings** (history) and an **HTTP activity** log of every call.

Between demo runs, reset the local data (all bookings, 100 free seats): `docker compose exec -T postgres psql -U ticket -d ticket_reservation < docs/demo/reset-demo.sql`.

Redis is optional: `SEAT_CACHE_ENABLED=false` / `FAST_PATH_ENABLED=false` switch the Redis paths off. If Redis dies at runtime, a circuit breaker sends everything to Postgres within a few requests.

Metrics: `curl localhost:8080/actuator/prometheus | grep -E 'seat_hold|hold_latency|hold_transitions'`.

## Next milestones

1. Transactional outbox and Kafka consumers: notifications, tickets, analytics (Phase 5).
2. Edge: JWT auth, Redis token-bucket rate limiting and the virtual waiting room (Phase 6).
3. Hot-event scaling and service extraction (Phase 7).
4. OpenTelemetry, Grafana, k6 load tests, chaos tests and Kubernetes deployment (Phases 8–11).
