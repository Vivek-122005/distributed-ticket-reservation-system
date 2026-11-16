# Development Log

A running record of **what was built, in which phase, in which part of the system, and why** — including the alternatives that were considered and rejected. Part IDs (`Px.y`) match [`PROJECT_PLAN.md`](PROJECT_PLAN.md). Larger decisions link to an ADR in [`adr/`](adr/).

## Entry template

```markdown
### Px.y — <short title>
- **Date:** YYYY-MM-DD
- **Module / files:** <service or path>
- **What was built:** <1–3 lines>
- **Why this approach:** <short reasoning>
- **Alternatives considered:**
  - <option> — <why not>
- **How it was verified:** <tests, load numbers, manual check>
- **ADR:** <link or "none">
```

---

## Phase 0 — Foundations & design baseline

### P0.1–P0.5 — Inventory service bootstrap
- **Date:** before 2026-11-14 (commit `37138c8`)
- **Module / files:** `inventory-service/`, `docker-compose.yml`, `.github/workflows/ci.yml`, `docs/adr/001-postgresql-is-inventory-authority.md`
- **What was built:** Spring Boot 3 / Java 21 inventory service with hexagonal packages (`api`, `application`, `domain`, `infrastructure`); `POST /api/v1/events/{eventId}/holds` creating an all-or-nothing multi-seat hold with a 5-minute expiry; Flyway V1 schema (`events`, `holds`, `seat_inventory`) with 100 demo seats; Compose stack with Postgres 16, Redis 7, Kafka 3.9 (KRaft); CI running `mvn verify`.
- **Why this approach:**
  - Seats are claimed with a **conditional update** (`UPDATE … WHERE status = 'AVAILABLE'`) inside one transaction; if any seat returns 0 affected rows the whole transaction rolls back → no partial holds, no oversell.
  - Seat codes are **sorted** before claiming so concurrent multi-seat requests lock rows in the same order → avoids deadlocks.
  - **PostgreSQL is the authority** (ADR 001) so correctness never depends on Redis.
  - Hexagonal layering keeps domain rules independent of Spring/JPA and lets the locking strategy be swapped later.
- **Alternatives considered:**
  - `SELECT … FOR UPDATE` then update — correct but holds row locks longer; to be benchmarked in P1.7.
  - Redis as source of truth — fastest, but holds could be lost on failover/eviction.
  - Kafka with ZooKeeper — KRaft is simpler (one container) and is Kafka's current default.
- **How it was verified:** builds in CI; manual `curl` hold request. Automated concurrency tests are pending (P1.6).
- **ADR:** [001](adr/001-postgresql-is-inventory-authority.md)

### P0.6 — Master plan and development log
- **Date:** 2026-11-14
- **Module / files:** `docs/PROJECT_PLAN.md`, `docs/DEVELOPMENT_LOG.md`
- **What was built:** Phased master plan (P0–P12) covering requirements, invariants, capacity estimation, HLD (architecture, state machines, saga sequence, ERD, Kafka topics), LLD principles and patterns, technology choices with alternatives, testing strategy, demo script and risks. This log, with an entry template.
- **Why this approach:** writing the design first gives every later part a defined place and a "why" to defend in reviews; small part IDs make progress trackable and each change reviewable.
- **Alternatives considered:**
  - Plan only in the README — too long for a README, mixes user docs with design.
  - Issue tracker only — loses the narrative reasoning that a system design project needs.
- **How it was verified:** reviewed against the existing code so Phase 0 status reflects what is actually implemented.
- **ADR:** none

### P0.7 — Maven multi-module build with `common` module
- **Date:** 2026-11-14
- **Module / files:** `pom.xml` (new root parent), `common/`, `inventory-service/pom.xml`, `.github/workflows/ci.yml`, `README.md`
- **What was built:** A root aggregator/parent POM (`ticket-reservation-parent`) that inherits from `spring-boot-starter-parent` and lists the modules; a `common` library module reserved for cross-cutting code (event envelope, idempotency, outbox); `inventory-service` re-parented onto the root POM and depending on `common`; CI now builds every module from the root; Testcontainers BOM pinned centrally for upcoming integration tests (P0.10).
- **Why this approach:**
  - One parent POM = one place for Java version, Spring Boot version and shared dependency versions → all services stay consistent.
  - A single `mvn verify` builds modules in dependency order, so a change in `common` immediately breaks any service it affects (caught in CI).
  - Doing it now, with one service, is cheap; restructuring after 6–8 services exist is not.
  - `common` is kept strictly cross-cutting (no service domain logic) so services remain independently deployable and don't turn into a distributed monolith.
- **Also fixed:** added `flyway-database-postgresql`. Spring Boot 3.4 ships Flyway 10, which moved PostgreSQL support into a separate artifact; without it the service fails at startup with "Unsupported Database".
- **Alternatives considered:**
  - Separate repository per service (polyrepo) — realistic at large companies, but heavy overhead for one developer: versioning/publishing `common`, many CI pipelines, cross-repo changes.
  - Independent builds per folder with no parent — duplicated versions drift over time.
  - Gradle multi-project — equally capable; Maven kept because the project already used it and its declarative POMs are easier to read in a review.
- **How it was verified:** `mvn verify` from the root (Maven 3.10, JDK 25 compiling with `--release 21`): reactor builds `common` then `inventory-service`, BUILD SUCCESS, both jars produced. No automated tests exist yet (P0.10), and the Flyway startup fix has not been exercised against a running Postgres yet; it will be covered by the Testcontainers setup in P0.10.
- **ADR:** none

### P0.8 — HLD pack
- **Date:** 2026-11-14
- **Module / files:** `docs/hld/` (`README.md`, `01-architecture.md`, `02-capacity-estimation.md`, `03-request-flows.md`, `04-data-model.md`, `05-consistency-and-failure-model.md`); `docs/PROJECT_PLAN.md` (links to HLD, diagram syntax fixes)
- **What was built:** The full high-level design for the target system:
  - **Architecture:** design drivers → responses, component and deployment diagrams, service boundaries, sync vs async communication, and scaling per layer.
  - **Capacity estimation:** QPS per flow, storage, Redis memory, DB connections (Little's law), Kafka partitions, and the p99 latency budget.
  - **Request flows:** ten sequence/flow diagrams covering waiting room, hold, booking+payment, decline, expiry, late payment, client retry, crash recovery, consumer retry/DLQ, and the outbox relay.
  - **Data model:** schema-per-service ERDs, the constraint behind each invariant, indexes, Redis keys, and the Kafka event envelope.
  - **Consistency & failure model:** strong vs eventual per data item, delivery semantics, a 13-row failure matrix, how each invariant is checked, and the timeout/retry policy.
- **Why this approach:**
  - Separate HLD documents (instead of one huge plan) can each be opened in a design review. The plan keeps a short summary and links to them.
  - Failure flows are designed *before* the code, so the implementation phases have clear acceptance criteria (each failure-matrix row maps to a chaos test in P10).
  - The capacity math explains *why* admission control and short compare-and-set transactions are needed, instead of adding them because "big systems do".
  - Mermaid diagrams live as text in the repo: they are versioned with the code, reviewable in diffs, and rendered natively by GitHub.
- **Alternatives considered:**
  - Draw.io/Excalidraw images — nicer visually, but binary files drift from the code and can't be diffed; may be added for slides in P12.
  - Database-per-service as separate Postgres instances — truer isolation, but too heavy for a laptop. Separate schemas with separate DB users give the same ownership boundary.
  - Choreography-only saga — rejected for the money flow (no single place to see its state); used for side effects only (saga ADR planned in P4.7, numbered 004 since P0.9).
- **How it was verified:** all 15 Mermaid diagrams (HLD + plan) parsed successfully with Mermaid 11.4.1. Fixed two problems found along the way: single-line ERD entities in the plan, and semicolons inside sequence diagrams (Mermaid treats `;` as a statement separator). Capacity numbers were cross-checked; they remain estimates until validated by load tests (P9).
- **ADR:** none (the locking and saga ADRs are scheduled in P1.8/P4.7)

### P0.9 — Design revision after the TicketFlow crosswork
- **Date:** 2026-11-16
- **Module / files:** `docs/PROJECT_PLAN.md` (changes tagged *(rev)*), `docs/hld/01–05`, `docs/adr/002-modular-monolith-first.md`
- **What was built:** We compared our plan with Yeeshu's TicketFlow blueprint and adopted its strongest parts:
  - **Domain model:** Event → Show → ShowSeat, plus Venue, VenueSection, Seat and BookingSeat.
  - **States:** booking `CANCELLED`; payment `INITIATED / PROCESSING / CAPTURED / FAILED / UNKNOWN / REFUNDED`.
  - **Payment:** a reconciliation job for `UNKNOWN` payments (new flow 3.11, invariant 5, a new failure-matrix row), and a `PaymentGateway` port with mock and sandbox adapters.
  - **Thin features:** cancel booking, booking history, admin APIs, WebSocket seat updates.
  - **Docs and errors:** an SRS and UML diagrams added to Phase 0; `timestamp` added to the error format.
  - **Architecture:** the core is built modular-monolith first (ADR 002), with extraction of inventory and payment planned in P7.7–P7.9.
  - **Renumbering:** Phase 0 parts were renumbered in execution order (contracts → P0.11, Testcontainers → P0.15). Planned ADRs were renumbered: locking 003, saga 004, rate limiter 005, partitioning 006.
- **Why this approach:**
  - **Show model:** without a `Show`, one chair can't be sold separately for two dates of the same event. `show_seats` keeps one lockable row per seat per show, so the compare-and-set design is unchanged.
  - **`UNKNOWN` payment state:** a gateway timeout doesn't tell us whether money moved. Guessing either way breaks invariant 3. Asking the gateway (reconciliation) is the only correct answer, and a late capture reuses the existing refund path.
  - **Monolith first:** the hard problems are correctness problems. Proving them in one process first removes network noise. Separate schemas, ports and ArchUnit keep the boundaries real, so extraction is an adapter swap backed by measurements.
  - **Kept from our plan:** the locking-strategy benchmark, waiting room, hot-event partitioning, idempotency table, chaos phase, tracing and K8s. The blueprint did not cover these.
- **Alternatives considered:**
  - **Microservices from day one:** rejected for the core; see ADR 002.
  - **Hold extension while a payment is `UNKNOWN`:** rejected. It adds an inventory API and new edge cases, and the late-payment refund path already keeps invariant 3.
  - **Always lock in Redis, then update the DB (blueprint):** not adopted as the default. It stays one of the five benchmarked strategies (P1.7), so the choice is measured.
  - **Spring Modulith for module boundaries:** see ADR 002.
- **How it was verified:** all 18 Mermaid diagrams (plan + HLD) parse with Mermaid 11.4.1. Cross-references were checked by grep (no remaining `seat_inventory` or `eventId:section` in the target design, and ADR numbers are consistent). This is a design change only; no code was changed.
- **ADR:** [002](adr/002-modular-monolith-first.md)

### P0.10 — LLD registries: design patterns and SOLID
- **Date:** 2026-11-16
- **Module / files:** `docs/lld/DESIGN_PATTERNS.md`, `docs/lld/SOLID.md`, plan §5 and §12 (rule 6)
- **What was built:**
  - **Design patterns registry:** 20 patterns, each with status (implemented, partly implemented or planned), the exact class and file, the problem it solves there, why it was chosen, and the rejected alternative.
  - **SOLID registry:** where each principle is applied and what it prevents, plus a list of the **known deviations** in the current bootstrap code, each linked to the part that fixes it.
  - **Working rule:** a change that introduces a pattern updates the registry in the same commit, and the class gets a Javadoc pointer to its entry.
- **Why this approach:**
  - **Answers in one place:** the project is judged on LLD, so "where exactly is the Strategy pattern and why?" should have a one-click answer.
  - **Planned vs implemented:** listing each pattern with its real status keeps the document honest, so nothing is claimed before it exists.
  - **Deviations first:** recording the gaps in the bootstrap code first (for example, the application layer returns an API DTO, which breaks DIP) turns them into tracked fixes instead of surprises in a review.
  - **LSP via contract tests:** substitutability is enforced by one shared test suite per interface, not just asserted.
- **Alternatives considered:**
  - **Pattern notes only in the final LLD document (P12.2):** they would be written from memory at the end and drift from the code.
  - **Javadoc only:** this can't be browsed as a whole or reviewed in one place. Javadoc is kept, as pointers.
  - **Generated diagrams only (e.g. PlantUML from code):** these show structure but not *why*. Class diagrams come in P0.13.
- **How it was verified:** each ✅ entry was checked against the actual source files in `inventory-service`. The deviations list comes from reading every class in that module.
- **ADR:** none

### P0.11 — API contracts (OpenAPI 3.1)
- **Date:** 2026-11-16
- **Module / files:** `docs/api/inventory-service.yaml`, `docs/api/booking-service.yaml`, `docs/api/README.md`
- **What was built:**
  - **Inventory contract (11 operations):** catalog reads (`/events`, `/events/{id}/shows`, `/shows/{id}/seats`); the hold lifecycle (`POST /shows/{id}/holds`, `GET`/`DELETE /holds/{id}`, `POST /holds/{id}/confirm`); thin admin operations (venue with seat layout, event, show, sale status).
  - **Booking contract (4 operations):** create booking (with `Idempotency-Key` and an optional `X-Admission-Token`, returning payment details), history (paginated), get, and cancel.
  - **Shared conventions:** one error shape `{code, message, retryable, timestamp, traceId?}` with a stable code enum; money in minor units; an `x-plan-part` tag on every operation.
- **Why this approach:**
  - **Contract-first:** the booking module and the future extracted inventory service are built against the same fixed interface, and the 409/422 idempotency behaviour from HLD flow 3.7 is written down before the code.
  - **Error shape:** `retryable` (ours) and `timestamp` (from the TicketFlow blueprint) together tell a client *whether* to retry and help support correlate errors with logs and traces.
  - **Explicit 409 cases:** `SEAT_UNAVAILABLE`, `HOLD_EXPIRED` and `IDEMPOTENCY_IN_PROGRESS` each map to a different client action (pick other seats, start the refund path, retry later).
  - **Integer minor units:** no floating-point rounding errors in money.
- **Alternatives considered:**
  - **Code-first (springdoc generating the spec):** the spec would only exist after the code and would follow it, not guide it. springdoc may still be added later to *check* the code against these files.
  - **RFC 7807 `application/problem+json`:** a good standard, but its `type`/`title`/`detail` fields add little over a stable `code`. Our shape is simpler for k6 assertions. It can be adopted later without breaking clients by adding fields.
  - **gRPC/protobuf for internal calls:** not needed at this scale (HLD §1.4).
- **How it was verified:** both files pass `openapi-spec-validator` (OpenAPI 3.1). A script confirmed that every `$ref` resolves and that no component is unused. Only `POST /events/{eventId}/holds` exists in code today; the spec documents that it moves to the show-based path in P1.0.
- **ADR:** none

