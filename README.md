# Distributed Ticket Reservation System

A final-year, production-inspired backend project for high-demand ticket sales. The first milestone implements the core invariant: **a seat can be held or booked by only one customer at a time**.

## Current milestone

- Java 21 / Spring Boot inventory service
- PostgreSQL as the authoritative inventory store
- Atomic, all-or-nothing multi-seat holds using transactional conditional updates
- Five-minute hold expiry model
- Docker Compose services for PostgreSQL, Redis, and Kafka
- Flyway schema migrations and sample concert inventory

## Run locally

Prerequisites: Java 21+, Maven 3.9+ and Docker Desktop.

The repository is a Maven multi-module build (`common`, `inventory-service`, more services to come). See [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md) for the full roadmap and [docs/DEVELOPMENT_LOG.md](docs/DEVELOPMENT_LOG.md) for what was built in each phase and why.

```bash
docker compose up -d
mvn install -DskipTests          # builds all modules from the root parent POM
mvn -pl inventory-service spring-boot:run
```

The service starts at `http://localhost:8080`.

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
