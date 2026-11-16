# ADR 002: Modular monolith first, extract services later

- **Status:** Accepted (2026-11-16)
- **Deciders:** Vivek Vishnoi, Yeeshu
- **Plan parts:** P0.9 (decision), P0.14 (restructure), P7.8–P7.9 (extraction)

## Context

Two plans were compared before Phase 1 started:

- **Plan A (ours):** separate services from day one (inventory, booking, payment, plus edge and consumer services).
- **Plan B (TicketFlow blueprint):** one modular monolith first, split later.

The hard problems of this project (no oversell, idempotency, saga, outbox) are about **correctness**, not about how many processes run. Building three core services on day one adds network calls, retries, timeouts, contract versioning and distributed tracing to every step, *before* the core logic is proven. On the other hand, the project must still show service boundaries and a credible path to microservices, because that is part of the HLD story.

## Decision

1. **Stage 1: modular monolith for the core.** `inventory`, `booking` and `payment` are separate Maven modules, packaged into one Spring Boot deployable, `ticketing-app`.
   - Each module owns its own **PostgreSQL schema** (`inventory`, `booking`, `payment`), its own Flyway migrations and its own hexagonal layers.
   - Modules talk only through **published ports**: Java interfaces in the module's `api` package (for example `InventoryApi.hold(...)`), and through events written to the outbox. No module reads another module's tables or uses its entities or repositories.
   - Boundaries are enforced by **ArchUnit tests** in CI (P0.14), so a violation fails the build.
2. **Separate from day one:** the edge (`api-gateway`, `waiting-room-service`) and the Kafka consumers (`notification`, `ticket`, `analytics`). They have very different scaling needs (about 20k req/s of polling), and they already communicate only over HTTP or Kafka.
3. **Stage 2: extraction, driven by evidence (P7.8–P7.9).**
   - **Inventory** is extracted when the hot-event tests (P7) show it needs to scale on its own.
   - **Payment** is extracted to isolate the third-party gateway, its secrets and its failures.
   - Extraction means: the module becomes its own Boot app, and the in-process `InventoryApi` adapter is replaced by an HTTP adapter with timeouts, retries and a circuit breaker. The contract is already written ([`docs/api/inventory-service.yaml`](../api/inventory-service.yaml)).

## Options considered

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Microservices from day one | Shows distribution early; independent deploys | Network failure modes before the logic is proven; slower to build and debug; risk of a "distributed monolith" with chatty calls | Rejected for the core |
| Plain monolith (one module, shared tables) | Fastest to start | Boundaries erode; extraction later becomes a rewrite; weak design story | Rejected |
| **Modular monolith + planned extraction** | Fast and debuggable; real boundaries (schemas, ports, ArchUnit); extraction is a mechanical adapter swap; lets us *measure* before splitting | Extraction work is deferred, not avoided; one deployable means one failure domain in stage 1 | **Chosen** |
| Spring Modulith instead of Maven modules + ArchUnit | Built-in module verification and event publication registry | Modules are packages in one artifact, so there are no compile-time boundaries; its registry overlaps with our own outbox (P5.2) | Not chosen; may be revisited |

## Consequences

- Phase 1–5 work runs in one process. Saga steps that cross modules (booking → inventory → payment) are in-process calls behind ports, so the saga logic, idempotency and outbox are proven first, without network noise.
- Database-per-service ownership is preserved by schema and DB-user separation. Extraction does not require a data migration.
- Booking → inventory network failures (timeouts, partial failures) only exist after P7.8. The chaos tests that depend on them (P10.2, P10.7) are scheduled after extraction.
- A review can follow the evolution step by step: boundaries first, then a **measured** reason to split, then the split itself.
