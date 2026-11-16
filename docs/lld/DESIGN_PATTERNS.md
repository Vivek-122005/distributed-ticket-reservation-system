# Design patterns registry

The record of **every design pattern used in this codebase**: where it lives (class and file), what problem it solves there, why it was chosen, and what the alternative was.

**Rules** (plan §12, rule 6):
- A change that introduces or removes a pattern updates this file **in the same commit**.
- The class that applies the pattern carries a one-line Javadoc pointer, for example `/** Pattern: Strategy, see docs/lld/DESIGN_PATTERNS.md#strategy */`.
- An entry stays ⬜ *planned* until the code exists. It then becomes ✅ with real file paths. Nothing is listed as implemented before it is.

Class-level view of where these patterns sit: [class-diagrams.md](class-diagrams.md).

Legend: ✅ implemented · 🔨 partly implemented · ⬜ planned (plan part in brackets)

## Summary

| # | Pattern | Category | Status | Where (main class) |
|---|---|---|---|---|
| 1 | [Ports & Adapters (Hexagonal)](#1-ports--adapters-hexagonal) | Architectural | 🔨 | all modules |
| 2 | [Repository](#2-repository) | Persistence | ✅ | `HoldRepository`, `ShowRepository`, `JdbcSeatClaimRepository` |
| 3 | [Compare-and-Set (conditional update)](#3-compare-and-set-conditional-update) | Concurrency | ✅ | `JdbcSeatClaimRepository.claimAvailable` |
| 4 | [Data Transfer Object](#4-data-transfer-object) | API | ✅ | `CreateHoldRequest`, `HoldResponse`, `ApiError` |
| 5 | [Centralised exception translation](#5-centralised-exception-translation) | API | ✅ | `ApiExceptionHandler` |
| 6 | [State](#6-state) | Behavioural (GoF) | ⬜ P1.3, P3.2, P4.2 | `ShowSeat`, `Hold`, `Booking`, `Payment` |
| 7 | [Strategy](#7-strategy) | Behavioural (GoF) | ⬜ P1.7 | `SeatLockingStrategy` |
| 8 | [Adapter](#8-adapter) | Structural (GoF) | ⬜ P4.1, P3.3 | `PaymentGateway`, `InventoryApi` adapters |
| 9 | [Facade](#9-facade) | Structural (GoF) | 🔨 P0.14 (inventory) | `InventoryApi` → `InventoryFacade`; `PaymentApi` in P4.1 |
| 10 | [Saga (Orchestrator)](#10-saga-orchestrator) | Distributed | ⬜ P4.4 | `BookingSaga` |
| 11 | [Transactional Outbox](#11-transactional-outbox) | Distributed | ⬜ P5.2–P5.3 | `OutboxWriter`, `OutboxRelay` |
| 12 | [Idempotent Receiver / Idempotency Key](#12-idempotent-receiver--idempotency-key) | Distributed | ⬜ P3.4, P5.5 | `IdempotencyInterceptor`, `IdempotentEventConsumer` |
| 13 | [Template Method](#13-template-method) | Behavioural (GoF) | ⬜ P5.5 | `IdempotentEventConsumer` |
| 14 | [Observer / Publish-Subscribe](#14-observer--publish-subscribe) | Behavioural (GoF) | ⬜ P5, P5.11 | domain events → Kafka → consumers |
| 15 | [Factory](#15-factory) | Creational (GoF) | ⬜ P5.1 | `EventEnvelopeFactory` |
| 16 | [Builder](#16-builder) | Creational (GoF) | ⬜ P1.6 | test-data builders |
| 17 | [Chain of Responsibility](#17-chain-of-responsibility) | Behavioural (GoF) | ⬜ P6.1–P6.5 | gateway filters |
| 18 | [Circuit Breaker / Retry / Bulkhead](#18-circuit-breaker--retry--bulkhead) | Resilience | ⬜ P7.8 | HTTP adapters (Resilience4j) |
| 19 | [Token Bucket](#19-token-bucket) | Rate limiting | ⬜ P6.3 | `token_bucket.lua` |
| 20 | [Fencing token](#20-fencing-token) | Concurrency | ⬜ P1.7 | `RedisLockStrategy` |

---

## 1. Ports & Adapters (Hexagonal)
- **Status:** 🔨 the inbound side is done: `web` (HTTP adapter) → `api.InventoryApi` (port) → `application`. Outbound ports (repositories behind interfaces) are still missing; see [SOLID.md, known deviations](SOLID.md#known-deviations-to-fix) #2.
- **Where:** `inventory/src/main/java/com/vivek/ticketreservation/inventory/{api,web,application,domain,infrastructure}`. `api` = published module port + DTOs; `web` = inbound HTTP adapter. Layer rules are enforced by `ticketing-app/src/test/java/com/vivek/ticketreservation/architecture/ModuleBoundaryTest.java`.
- **Problem:** business rules (who may hold a seat) must not depend on HTTP, JPA or Redis, because the locking implementation has to be swappable for the benchmark (P1.7) and modules must be extractable later (ADR 002).
- **Why:** the domain and use cases sit in the centre. `api` is the inbound adapter. `infrastructure` holds outbound adapters behind port interfaces.
- **Alternative:** classic layered architecture (controller → service → DAO). It is simpler, but the service layer then depends on persistence details, and swapping a locking strategy touches business code.

## 2. Repository
- **Status:** ✅
- **Where:** `inventory/src/main/java/com/vivek/ticketreservation/inventory/infrastructure/HoldRepository.java`, `ShowSeatRepository.java`, `ShowRepository.java` (Spring Data JPA).
- **Problem:** use cases need to load and save aggregates without writing SQL plumbing.
- **Why:** a collection-like interface per aggregate. Spring Data generates the common methods; hot paths use explicit native queries.
- **Alternative:** a hand-written DAO with `JdbcTemplate`. That gives more control but more boilerplate. It is still available for the hot claim query if JPA overhead shows up in P9.5.

## 3. Compare-and-Set (conditional update)
- **Status:** ✅ batched in P1.1 (lazy expiry in P1.2)
- **Where:** `inventory.infrastructure.JdbcSeatClaimRepository.claimAvailable`, called by `inventory.application.CreateHoldUseCase.execute`:
  `WITH target AS (SELECT id … WHERE show_id=? AND seat_code = ANY(?) AND status='AVAILABLE' ORDER BY seat_code FOR UPDATE) UPDATE … FROM target … RETURNING seat_code, price_minor`.
- **Problem:** thousands of concurrent requests for the same seat; exactly one may win, and a multi-seat request must get all of its seats or none.
- **Why:**
  - **Atomic check-and-set:** under READ COMMITTED, Postgres re-checks the `WHERE` on the newest row version after waiting for a lock, so a seat claimed meanwhile is simply not returned.
  - **All-or-nothing:** the use case compares the number of claimed rows with the number requested and throws on a mismatch. The transaction rollback undoes the partial claim and the hold row.
  - **No deadlocks:** `ORDER BY … FOR UPDATE` locks rows in one global order, so two overlapping requests can't each wait for the other.
  - **One round trip:** the claim and the prices come back in one statement instead of N.
- **Alternative:**
  - **One `UPDATE` per seat (the P0 version):** correct, but N round trips, with lock order depending on Java sorting.
  - **A bare `UPDATE … WHERE seat_code = ANY(?)` without the ordered CTE:** lock order then follows the plan's scan order, which isn't guaranteed.
  - **`NOWAIT` / `SKIP LOCKED`:** faster rejection, but a seat locked by a transaction that later rolls back would be refused wrongly. Kept for the benchmark.
  - **Pessimistic and optimistic strategies:** benchmarked in P1.7–P1.8.

## 4. Data Transfer Object
- **Status:** ✅
- **Where:** `inventory/src/main/java/com/vivek/ticketreservation/inventory/web/CreateHoldRequest.java`, `HoldResponse.java`, `ApiError.java` (HTTP DTOs); `inventory/src/main/java/com/vivek/ticketreservation/inventory/api/HoldCommand.java`, `HoldResult.java` (module-API DTOs, transport-free). All Java records.
- **Problem:** the HTTP contract must not leak JPA entities, and must stay stable when the schema changes.
- **Why:** immutable records with Bean Validation annotations. The contract is defined in `docs/api/*.yaml`.
- **Alternative:** returning entities directly. Less code, but it couples the API to the schema and risks lazy-loading errors.

## 5. Centralised exception translation
- **Status:** ✅
- **Where:** `inventory/src/main/java/com/vivek/ticketreservation/inventory/web/ApiExceptionHandler.java` (`@RestControllerAdvice`).
- **Problem:** every endpoint must map domain failures to the same error shape (`code`, `message`, `retryable`, `timestamp` from P1.3).
- **Why:** one place maps exception → HTTP status → stable error code, and controllers stay free of try/catch.
- **Alternative:** try/catch in each controller. Duplicated, and error codes drift.

## 6. State
- **Status:** ⬜ P1.3 (`ShowSeat`, `Hold`), P3.2 (`Booking`), P4.2 (`Payment`)
- **Problem:** each aggregate has a lifecycle (see the state machines in plan §4.3–§4.4b). Illegal transitions, such as `CONFIRMED → HELD`, must be impossible.
- **Plan:** each status enum declares its allowed next states (`canTransitionTo`). The aggregate's methods (`confirm()`, `release()`, `expire()`) validate the transition and throw `IllegalStateTransition`. Unit-tested without a database.
- **Alternative:** full GoF State (one class per state). This is heavier; the enum-with-transitions form gives the same guarantee for state machines of 4–7 states. Scattered `if (status == …)` checks were rejected.

## 7. Strategy
- **Status:** ⬜ P1.7
- **Plan:** a `SeatLockingStrategy` interface with `PessimisticLockStrategy`, `OptimisticLockStrategy`, `ConditionalUpdateStrategy`, `RedisLockStrategy` and `RedisLuaStrategy`, selected by `inventory.locking.strategy`.
- **Why:** the benchmark must run the same use case against each approach, so only the strategy may vary.
- **Alternative:** separate branches or feature flags inside the use case. Untestable in isolation and violates OCP.

## 8. Adapter
- **Status:** ⬜ P4.1 (payment), P3.3 / P7.8 (inventory)
- **Plan:** port `PaymentGateway` (`createOrder`, `capture`, `refund`, `fetchStatus`), with adapters `MockPaymentGateway` (configurable failure injection) and `SandboxPaymentGateway` (Razorpay test mode). Port `InventoryApi`, with `InProcessInventoryAdapter` (stage 1) and `HttpInventoryAdapter` (stage 2).
- **Why:** core code is identical whether it talks to a fake PSP, a real sandbox or a remote service. Reconciliation (P4.8) needs `fetchStatus` from every gateway.
- **Alternative:** calling the Razorpay SDK directly from use cases. Failure injection becomes impossible and the vendor locks in.

## 9. Facade
- **Status:** 🔨 inventory done in P0.14: `api/InventoryApi.java` implemented by package-private `application/InventoryFacade.java`, which delegates to one use case per operation. `PaymentApi` follows in P4.1.
- **Design:** each module exposes one public package, `…inventory.api`, with `InventoryApi` and DTOs. Everything else is package-private or internal, and ArchUnit fails the build if another module imports internals.
- **Why:** a narrow, stable entry point per module is what makes extraction (ADR 002) a mechanical swap.
- **Alternative:** modules calling each other's services and repositories directly. Fast at first, but they become entangled.

## 10. Saga (Orchestrator)
- **Status:** ⬜ P4.4
- **Plan:** `BookingSaga` keeps the persisted step, attempts and `next_retry_at`. Steps: hold → create intent → await capture → confirm hold → confirm booking. Compensations: release hold, cancel intent, refund.
- **Why:** there is no ACID transaction across inventory, booking and the PSP. The orchestrator gives one place to see and resume the money flow.
- **Alternative:** choreography (services react to each other's events). Rejected for the money flow, used for side effects. 2PC/XA was rejected because it blocks and PSPs and Kafka don't support it. Full reasoning: ADR 004 (P4.7).

## 11. Transactional Outbox
- **Status:** ⬜ P5.2–P5.3
- **Plan:** the outbox row is inserted in the same transaction as the state change. `OutboxRelay` polls with `FOR UPDATE SKIP LOCKED` and publishes to Kafka.
- **Why:** it solves the dual-write problem: a DB commit plus a Kafka send can't be atomic, but two inserts in one transaction can.
- **Alternative:** publish after commit (an event is lost on crash), Kafka transactions (they don't cover the Postgres write), Debezium CDC (stretch, P5.10).

## 12. Idempotent Receiver / Idempotency Key
- **Status:** ⬜ P3.4–P3.5 (HTTP), P4.3 (webhooks), P5.5 (Kafka)
- **Plan:** an `idempotency_keys` table with a request hash and the stored response; a `processed_events` insert in the same transaction as the consumer's side effect.
- **Why:** every channel is at-least-once, so duplicates must be harmless (HLD §5.2).
- **Alternative:** a Redis-only key store. Fast, but keys are lost on eviction or failover.

## 13. Template Method
- **Status:** ⬜ P5.5
- **Plan:** the abstract `IdempotentEventConsumer` does dedup → `handle(event)` (abstract) → metrics and error routing to retry topics.
- **Why:** every consumer gets identical dedup and retry behaviour; subclasses only write business logic.
- **Alternative:** copying the dedup code into every consumer, which drifts. Composition with a decorator would also work; Template Method is clearer when the skeleton is fixed.

## 14. Observer / Publish-Subscribe
- **Status:** ⬜ P5.6–P5.8, P5.11
- **Plan:** modules publish domain events (via the outbox). Notification, ticket, analytics and the WebSocket push subscribe through Kafka consumer groups.
- **Why:** producers don't know or wait for consumers, and new consumers need no producer change (OCP at system level).
- **Alternative:** direct synchronous calls to each side effect. These couple booking latency and availability to email or ticket services.

## 15. Factory
- **Status:** ⬜ P5.1
- **Plan:** `EventEnvelopeFactory.create(aggregate, type, payload)` fills `eventId`, `schemaVersion`, `occurredAt` and `traceId`.
- **Why:** envelope metadata must be identical everywhere; consumers rely on it for dedup and tracing.
- **Alternative:** constructing envelopes by hand at each call site. Fields get forgotten.

## 16. Builder
- **Status:** ⬜ P1.6
- **Plan:** test builders such as `ShowFixture.withSeats(100)` and `holdRequest().seats("A1","A2")`.
- **Why:** concurrency tests set up many rows; builders keep tests short and intention-revealing.
- **Alternative:** SQL fixture files. Fine for static data, poor for per-test variations.

## 17. Chain of Responsibility
- **Status:** ⬜ P6.1–P6.5
- **Plan:** gateway filters in order: correlation ID → JWT auth → rate limit → admission-token check → route.
- **Why:** each cross-cutting check is independent and ordered by config, and any link can short-circuit (401, 429).
- **Alternative:** one big filter. Hard to test and reorder.

## 18. Circuit Breaker / Retry / Bulkhead
- **Status:** ⬜ P7.8 (inventory HTTP adapter), P7.9 (payment), plus the PSP adapter in P4.1
- **Plan:** Resilience4j around remote adapters, with timeouts and retry policy from HLD §5.5.
- **Why:** a slow dependency must fail fast instead of exhausting threads (cascading failure).
- **Alternative:** plain timeouts only. They don't stop retry storms against a dependency that is down.

## 19. Token Bucket
- **Status:** ⬜ P6.3
- **Plan:** a Redis Lua script refills tokens and takes one atomically per request, keyed per user and per IP.
- **Why:** it allows short bursts but caps the sustained rate. Atomic in Lua, so it is correct across gateway replicas.
- **Alternative:** fixed window (bursts at window edges), sliding log (memory per request). Compared in ADR 005.

## 20. Fencing token
- **Status:** ⬜ P1.7
- **Plan:** `RedisLockStrategy` gets a monotonically increasing token with the lock. The DB write includes `WHERE lock_token < :token`.
- **Why:** a Redis lock can expire during a GC pause, so two holders are possible. The fencing token makes the stale holder's write fail.
- **Alternative:** Redlock without fencing. Not safe under pauses or clock drift (Kleppmann's critique).
