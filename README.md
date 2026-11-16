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

Tests: `mvn verify` runs unit and architecture tests, then integration tests against a throwaway PostgreSQL container (Testcontainers; Docker must be running).

The app starts at `http://localhost:8080`.

```bash
curl -X POST http://localhost:8080/api/v1/events/00000000-0000-0000-0000-000000000001/holds \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 11111111-1111-1111-1111-111111111111' \
  -d '{"seatCodes":["A1","A2"]}'
```

## Next milestones

1. Add idempotency persistence, Redis token-bucket rate limiting and waiting room.
2. Implement payment orchestration and confirmation.
3. Add transactional outbox, Kafka consumers, hold expiry worker and reconciliation.
4. Add OpenTelemetry, Grafana, k6 load tests, and Kubernetes deployment.
