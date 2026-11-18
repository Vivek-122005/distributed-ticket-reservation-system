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

### P0.12 — SRS, use cases and activity diagrams
- **Date:** 2026-11-16
- **Module / files:** `docs/srs/SRS.md`, `docs/srs/use-cases.md`
- **What was built:**
  - **SRS:** purpose, scope, glossary, 5 actors, constraints and assumptions; 16 functional requirements (FR-01…16) with priority, use case and plan part; 11 non-functional requirements (NFR-01…11), each with how it is measured; 6 business rules.
  - **Use cases:** a use-case diagram and 9 use-case specifications with alternative flows.
  - **Activity diagrams:** checkout (hold → pay → confirm/refund/unknown), payment reconciliation, waiting-room admission, and show scheduling.
- **Why this approach:**
  - **Traceability:** reviewers expect a requirement → use case → design → test chain. IDs make it traceable, and future tests will be named after FR/NFR IDs.
  - **Alternative flows hold the hard cases:** taken seat, retries, unknown payment and late capture are where the interesting design lives, so they are written out explicitly.
  - **Measurable NFRs:** every non-functional requirement says *how* it is measured (invariant checker, k6, chaos test), so none of them is an unverifiable claim.
- **Alternatives considered:**
  - **Full IEEE 830 template:** too much boilerplate for a project of this size. Kept its structure, dropped empty sections.
  - **User stories only:** good for backlog grooming, but weak on alternative flows and non-functional requirements.
  - **PlantUML for real UML use-case notation:** it needs a renderer; GitHub doesn't render it natively. Mermaid flowcharts approximate the notation (noted in the file).
- **How it was verified:** all diagrams parse with Mermaid 11.4.1. Each FR maps to an existing plan part, and each use case links back to its FRs.
- **ADR:** none

### P0.13 — Class diagrams
- **Date:** 2026-11-16
- **Module / files:** `docs/lld/class-diagrams.md`, a pointer in `docs/lld/DESIGN_PATTERNS.md`
- **What was built:**
  - **Class diagrams:** the target design of the inventory domain and its application ports; booking (aggregate, saga, idempotency, ports); payment (aggregate, `PaymentGateway` with mock and sandbox adapters, webhook handler, reconciliation job); and `common` (event envelope, factory, outbox, consumer base).
  - **Mapping tables:** each class is listed with its pattern or principle and its plan part.
  - **Module rules:** a dependency diagram and four rules that ArchUnit will enforce in P0.14.
- **Why this approach:**
  - **Diagrams before code:** P0.14 and Phase 1 then implement a reviewed design instead of discovering it.
  - **Key boundaries made explicit:** booking sees only `InventoryApi`/`PaymentApi`, and inventory/payment never depend on booking, so the graph stays acyclic and extraction (ADR 002) needs no untangling.
  - **Hot claim path vs aggregate methods:** set-based SQL is used for speed on the hot path, while aggregate methods are used for confirm, expire and unit tests. This is documented so the duplication is a decision, not an accident.
- **Alternatives considered:**
  - **Generating diagrams from code (e.g. IntelliJ, PlantUML plugins):** accurate after the fact, but they show no intent and can't guide the build. Generated diagrams can be compared against these in P12.2.
  - **One huge diagram for the whole system:** unreadable. Split per module with a separate dependency view.
  - **Full GoF State classes per status:** the enum-with-transitions form is used instead (reasoning in DESIGN_PATTERNS §6).
- **How it was verified:** all 29 Mermaid diagrams in `docs/` parse with Mermaid 11.4.1. The class names match the API contracts (P0.11) and the pattern registry (P0.10).
- **ADR:** none

### P0.14 — Modular-monolith restructure with ArchUnit boundaries
- **Date:** 2026-11-16
- **Module / files:** `inventory/` (code moved from `inventory-service/` with `git mv`), `booking/`, `payment/` (skeletons), `ticketing-app/` (new Boot app + `ModuleBoundaryTest`), root `pom.xml`, `README.md`, LLD registries
- **What was built:**
  - **Modules:** the inventory code now lives in the `inventory` library module under `com.vivek.ticketreservation.inventory`, with five layers: `api`, `web`, `application`, `domain` and `infrastructure`. Empty `booking` and `payment` modules hold the place for Phases 3–4. `ticketing-app` is the single Spring Boot deployable that bundles all three. Flyway migrations move to a per-module folder.
  - **Published API:** `api/InventoryApi` (with `HoldCommand`, `HoldResult` and `SeatUnavailableException`) is implemented by a package-private `InventoryFacade`. The facade delegates to `CreateHoldUseCase` (formerly `InventoryApplicationService`). The controller now maps `HoldResult` to the HTTP DTO.
  - **Boundary tests:** 9 ArchUnit rules cover module access only through `..api`, no dependency on booking, a framework-free domain, `common` as a leaf, and hexagonal layer order. `ModuleBoundaryRulesBiteTest` proves the main rule really fails on a planted violation.
- **Why this approach:**
  - **Proving ADR 002 in code:** the modular monolith is only real if its boundaries are *checked*. ArchUnit turns "booking must only use `inventory.api`" from a convention into a failing build.
  - **Separate `api` and `web` packages:** `api` is the module's port for other modules and stays transport-free; `web` is just one adapter (HTTP). When inventory is extracted (P7.8), the HTTP adapter for `InventoryApi` simply implements the same interface.
  - **Package-private facade:** other modules *can't* even reference the implementation class, so the compiler joins ArchUnit in guarding the boundary.
  - **"Rules bite" test:** a rule that never fails proves nothing, for example if a package pattern were misspelled. The planted violation shows the rule detects real access.
  - **Fixed SOLID deviations #1 (DIP) and #4 (SRP), and added the missing boundary checks (#6).** Deviation #3 (JPA annotations in `domain.Hold`) is still tolerated by the domain rule, with an explicit comment pointing to P1.3.
- **Alternatives considered:**
  - **Spring Modulith:** see ADR 002. Packages-in-one-jar loses compile-time boundaries.
  - **Keeping `inventory-service` as the deployable and adding the others as libraries to it:** the name would lie about what the app is, and extraction would need a rename later anyway.
  - **Maven `dependency:analyze` / Enforcer bans:** these work at artifact level only. They can't express "booking may use `inventory.api` but not `inventory.domain`" inside one dependency.
  - **Creating `booking`/`payment` only in P3/P4:** possible, but the boundary rules (no dependency on booking) are best in place *before* the first booking class exists.
- **How it was verified:** `mvn verify` from the root builds all 6 reactor modules: BUILD SUCCESS, 10 tests, 0 failures, and `ticketing-app` repackaged as a runnable jar. ArchUnit ran with `resolveMissingDependenciesFromClassPath=false`, so it analyses only our classes, independent of the local JDK (27). **Not verified:** the app was not started against Postgres because Docker was not running. Spring wiring of the package-private facade and the Flyway location change are covered by the Testcontainers context test in P0.15.
- **Note:** the old `inventory-service/target/` build output may still exist locally. It is gitignored and safe to delete.
- **ADR:** [002](adr/002-modular-monolith-first.md) (implemented)

### P0.15 — Testcontainers integration test base, run in CI
- **Date:** 2026-11-16
- **Module / files:** `ticketing-app/pom.xml` (Testcontainers, failsafe), `ticketing-app/src/test/java/.../support/` (`PostgresContainerConfig`, `@IntegrationTest`, `InventoryTestData`), `ApplicationStartupIT`, `inventory/CreateHoldIT`, root `pom.xml`, `.github/workflows/ci.yml`, `docker-compose.yml`, `README.md`
- **What was built:**
  - **Test base:** a reusable `@IntegrationTest` annotation that starts the full app on a random port against a real `postgres:16-alpine` container. The container is wired with `@ServiceConnection`, so no URLs are hard-coded.
  - **Startup test (2 tests):** Flyway applies the module migration from its new folder, and `InventoryApi` is wired to the package-private facade. This closes the gap left open in P0.14.
  - **Hold tests (4 tests, over HTTP):** a successful multi-seat hold; a conflicting request holds **nothing** (no partial seat held, and the failed hold row is rolled back); duplicate seat codes → 400; an unknown seat → 409.
  - **Unit vs integration split:** `*Test` runs under surefire (fast, no Docker) and `*IT` under failsafe in `verify`. CI's existing `mvn verify` now runs both.
- **Why this approach:**
  - **Real Postgres, not H2:** the core guarantee depends on Postgres row locking and the conditional `UPDATE`. H2's locking differs, so a green H2 test would prove nothing (plan §6).
  - **`@ServiceConnection` + Spring context caching:** one container per test run, shared by every IT class, keeps runs fast. Each test resets the seat data instead of restarting the database.
  - **Separate surefire/failsafe phases:** architecture and unit feedback in seconds; Docker tests only in `verify`.
  - **Testcontainers 1.21.4 instead of 1.20.6:** Docker Engine 29 (installed here) rejects client API versions below 1.44, which older Testcontainers used by default.
- **Also fixed:** `docker-compose.yml` publishes Postgres on `${POSTGRES_PORT:-5432}`. On this laptop a Homebrew PostgreSQL already listens on `localhost:5432` and shadowed the container (`role "ticket" does not exist`). The README documents the override.
- **Alternatives considered:**
  - **Embedded Postgres (zonky):** no Docker needed, but it's a different distribution from production, and Testcontainers is reused for Redis and Kafka later.
  - **One container per test class (`@Container` static field):** simpler to read, but slower and it multiplies Spring contexts.
  - **`@DataJpaTest` slices only:** faster, but they miss the HTTP mapping and the full wiring the startup test checks.
  - **Testcontainers reuse mode (`withReuse`):** faster locally, but leaves containers running and needs per-developer config. Can be added later.
- **How it was verified:** `mvn verify` with BUILD SUCCESS. Surefire ran 10 tests (ArchUnit) and failsafe 6 tests against a real Postgres 16 container, 0 failures. Manual smoke test of the packaged jar against the Compose Postgres (port 55432): Flyway applied V1, `POST /events/{id}/holds` for A99+A100 → 201, holding A100 again → 409 `SEAT_UNAVAILABLE`, `/actuator/health` → UP. GitHub CI will be confirmed by the run on this push.
- **ADR:** none


---

## Phase 1 — Inventory & reservation engine

### P1.0 — Show-based schema (Flyway V2)
- **Date:** 2026-11-16
- **Module / files:** `inventory/src/main/resources/db/migration/inventory/V2__show_based_schema.sql`; `inventory` code (`ShowSeatRepository`/`ShowSeatRow` renamed from `SeatInventory*`, new `ShowRow`/`ShowRepository`, `Hold`, `HoldStatus`, `HoldCommand`/`HoldResult`, `ShowNotFoundException`, `ShowNotOnSaleException`, controller, exception handler); tests (`ApplicationStartupIT`, `CreateHoldIT`, `InventoryTestData`); plan, HLD §4, API spec, README, registries
- **What was built:**
  - **Schema:** a new `inventory` Postgres schema with the catalog (`venues → venue_sections → seats`, `events → shows`) and the sellable inventory (`show_seats`, `holds` with `show_id`). It enforces `UNIQUE (show_id, seat_id)` (invariant 1) and `UNIQUE (show_id, seat_code)` (claim lookup), plus status `CHECK`s for every table and non-negative prices.
  - **Demo data:** the "Demo Concert" event, plus one show at "Demo Arena" with 100 seats A1–A100 in section A at ₹1,500. Fixed ids are used for tests and the README. The show seats are generated from the venue layout the same way admin show creation will do it (P6.9).
  - **Retiring V1:** `public.seat_inventory`, `public.holds` and `public.events` are dropped. They held only demo data.
  - **Code:** hold creation moved to `POST /api/v1/shows/{showId}/holds`. It now checks that the show exists (`404 SHOW_NOT_FOUND`) and is `ON_SALE` (`409 SHOW_NOT_ON_SALE`, BR-03) before claiming. The response includes `showId`.
  - **Status rename:** the stored hold status is renamed `ACTIVE` → `HELD`. This fixes SOLID deviation #5, which was planned for P1.3.
- **Why this approach:**
  - **Show as the unit of sale:** without `shows`, the same chair can't be sold separately for two dates. `show_seats` keeps per-seat identity per show, so the lock target is one row (HLD §4.1).
  - **Copied columns on `show_seats` (`seat_code`, `section_code`, `price_minor`):** the hot claim query stays a single-table `UPDATE` with no join. The price is frozen when the show is created (BR-05).
  - **Schema per module now, explicitly qualified (`inventory.show_seats`, `@Table(schema = "inventory")`):** ownership is visible in every query. There is no global `search_path` or Hibernate `default_schema`, which would break once booking adds its own schema.
  - **Show-status check before claiming:** it is not done under a lock, and that's deliberate. Closing a sale is not a correctness invariant (unlike oversell); a hold granted at the instant of closing just expires or is paid normally. Locking the show row would serialise every hold on a hot show.
  - **Status rename pulled forward:** creating a brand-new table with a value we already planned to rename would only have meant another migration.
  - **No separate `idx_show_seats_lookup`:** the unique `(show_id, seat_code)` index already serves the claim and seat-map reads by show. A redundant index on the hottest table only adds write cost.
- **Alternatives considered:**
  - **Editing V1 in place:** rejected. An applied migration must never change, because existing databases (Compose, CI caches) would fail Flyway validation.
  - **Copying the V1 rows into the new tables:** pointless for demo data. A real system would need an expand/migrate/contract sequence instead.
  - **Per-module Flyway with its own history table in `inventory`:** cleaner for extraction, but V1 is already recorded in `public.flyway_schema_history`. Deferred to P3.1, when booking needs its own migration stream anyway.
  - **Keeping the legacy `/events/{eventId}/holds` path as an alias:** there are no clients yet, and two paths for one operation would only confuse the contract.
- **How it was verified:**
  - **`mvn verify`:** BUILD SUCCESS. Surefire ran 10 tests (ArchUnit) and failsafe 11 ITs on Postgres 16. New tests: V1+V2 applied, 100 priced show seats matching the 100 venue seats, legacy tables gone, unknown show → 404, show not on sale → 409 with nothing held, stored hold status is `HELD`.
  - **Upgrade path:** the packaged jar ran against the existing Compose database, which already had V1 and a leftover held seat from earlier. Flyway applied only V2.
  - **Smoke test:** A99+A100 → 201; A100 again → 409 `SEAT_UNAVAILABLE`; random show → 404 `SHOW_NOT_FOUND`.
- **ADR:** none

### P1.1 — Batched atomic multi-seat claim
- **Date:** 2026-11-16
- **Module / files:** `inventory.infrastructure.JdbcSeatClaimRepository` (new; replaces `ShowSeatRepository`/`ShowSeatRow`), `CreateHoldUseCase`, `api.HeldSeat`, `HoldResult`, `web.HoldResponse`, `ShowRow` (currency), `CreateHoldIT`; plan, API spec, pattern and SOLID registries
- **What was built:**
  - **One statement per hold:** all requested seats are claimed in one SQL statement. A CTE selects the still-`AVAILABLE` rows with `ORDER BY seat_code FOR UPDATE`, and an `UPDATE … FROM target … RETURNING seat_code, price_minor` marks them `HELD`.
  - **All-or-nothing:** the use case compares the number of returned rows with the number requested. On a mismatch it throws `SeatUnavailableException`, and the transaction rollback undoes the partial claim and the hold row.
  - **Priced response:** the hold response now matches the contract's `Hold` schema: `seats[{seatCode, priceMinor}]`, `totalMinor` and `currency` (from the show).
- **Why this approach:**
  - **One round trip instead of up to 8:** the hold transaction is the hottest path in the system. Fewer round trips means shorter transactions, and so shorter lock hold times on contended rows.
  - **Lock order in SQL:** `ORDER BY … FOR UPDATE` makes Postgres lock rows in a single global order. Two overlapping requests (A1+A2 vs A2+A1) queue instead of deadlocking. Before, the guarantee depended on Java sorting before N separate statements. A bare `UPDATE … WHERE seat_code = ANY(?)` would lock in whatever order the plan scans, which is not guaranteed.
  - **Correct under READ COMMITTED:** when a transaction waits on a locked row, Postgres re-evaluates the `WHERE` against the committed version (EvalPlanQual). A seat that was taken meanwhile drops out of the result instead of being double-claimed.
  - **JDBC instead of Spring Data:** `@Modifying` queries return only an update count. `RETURNING` gives the claimed seats and their prices in the same round trip. This was foreseen as an option in the Repository pattern entry.
- **Alternatives considered:**
  - **Keeping the per-seat loop:** correct, but N statements and N round trips.
  - **`FOR UPDATE SKIP LOCKED` or `NOWAIT`:** these reject faster under contention, but would wrongly refuse a seat whose current locker later rolls back. Recorded as a variant to measure in the P1.8 benchmark.
  - **`SERIALIZABLE` isolation:** also correct, but adds serialization-failure retries for no gain over a single compare-and-set statement.
- **How it was verified:** `mvn verify`: BUILD SUCCESS, 10 surefire + 13 failsafe tests. New ITs: a priced response (2 × 150000 = 300000 INR); a per-seat frozen price is used (99900 + 150000); an 8-seat hold claims exactly 8 rows with one hold id. The existing conflict test still proves nothing is left held after a partial overlap. The deadlock-freedom claim is tested under real contention in P1.6.
- **ADR:** none (the strategy comparison is ADR 003 in P1.8)

### P1.2 — Lazy expiry in the claim query, database clock for expiry
- **Date:** 2026-11-16
- **Module / files:** `JdbcSeatClaimRepository` (claim condition), new `JdbcHoldRepository`, `CreateHoldUseCase`, new `LazyExpiryIT`, `ApplicationStartupIT`; plan, pattern registry
- **What was built:**
  - **Lazy expiry:** the claim now treats a seat as claimable if it is `AVAILABLE` **or** `HELD` with `hold_expires_at < now()`. An expired hold never blocks a seat, even if the expiry worker (P2.1) has not run.
  - **Database-clock expiry:** hold rows are inserted with `expires_at = now() + 5 min`, computed by Postgres and returned with `RETURNING`. That value is written to the seats in the same transaction.
- **Why this approach:**
  - **Correctness without the worker:** invariant 4 says every hold expires. If availability depended on a scheduled job, a slow or crashed worker would make seats unsellable during the hottest minutes. With lazy expiry the worker only *cleans up* (marks old holds `EXPIRED`, emits events), and is never on the critical path.
  - **One clock:** SRS assumption A3 says app replicas may skew. If the app computed `expiresAt` and the database compared it with `now()`, a replica 30 s fast would give 4.5-minute holds. `now()` is the transaction start time, so the hold, its seats and the comparison all use one instant.
  - **The stale hold row is safe:**
    - It stays `HELD` until the worker runs, but its seats can be re-held.
    - Confirm (P1.4) must also require `expires_at > now()`, so the old hold can't be confirmed later.
    - The worker must release seats only `WHERE hold_id = <expired hold>`, so it can't free a seat that someone has re-held.
    
    Both rules are written into the claim class Javadoc so P1.4 and P2.1 implement them.
  - **`BOOKED` is never reclaimed:** the expiry branch requires `status = 'HELD'`, so a stale `hold_expires_at` on a booked seat is harmless (tested).
- **Alternatives considered:**
  - **Expiry worker only:** simpler SQL, but availability then depends on job timing. This is the exact failure lazy expiry removes.
  - **Clearing expiry in the same statement:** marking old holds `EXPIRED` inside the claim would make the hot statement touch a second table and lock hold rows of other users. Left to the worker.
  - **Postgres `pg_cron` or a TTL mechanism:** Postgres has no row TTL; `pg_cron` is an extra extension, and still a timer.
  - **App clock with NTP:** usually fine, but NTP drift and pauses during a VM migration happen, and one authoritative clock is free here.
- **How it was verified:**
  - **`mvn verify`:** BUILD SUCCESS, 10 surefire + 17 failsafe tests.
  - **`LazyExpiryIT`:**
    - a seat of an expired hold is re-held with no worker running, and an unrequested seat keeps its stale hold;
    - a live hold still blocks;
    - a `BOOKED` seat with an old expiry is never reclaimed;
    - `expiresAt` = DB `now()` + 5 min, and the seat's `hold_expires_at` equals the hold's.
  - **Test isolation fix:** `ApplicationStartupIT` counted only `AVAILABLE` seats, which depended on test order. It now counts all show seats.
- **ADR:** none

### P1.3 — Domain state machines, outbound ports, database state checks
- **Date:** 2026-11-16
- **Module / files:**
  - **`inventory.domain`:** `SeatStatus`, `HoldStatus`, `ShowStatus`, `Hold`, `ShowSeat`, `Show`, `IllegalStateTransitionException`, `HoldExpiredException`.
  - **`inventory.application.port`:** `SeatLockingStrategy`, `HoldStore`, `ShowStore`.
  - **`inventory.infrastructure`:** `ConditionalUpdateStrategy`, `JdbcHoldStore`, `JdbcShowStore`, replacing the JPA repositories and entities.
  - **Other code:** `CreateHoldUseCase`, `web.ApiError`, `V3__show_seat_state_checks.sql`, `inventory/pom.xml` (JPA → JDBC), `application.yml`.
  - **Tests:** domain unit tests; `ModuleBoundaryTest` (2 rules changed or added); `SeatStateConstraintsIT`.
  - **Docs:** pattern and SOLID registries, class diagram, HLD §4.1, plan.
- **What was built:**
  - **State machines:**
    - **Transition tables:** each status enum declares its allowed next states (`canTransitionTo`).
    - **Aggregates:** `Hold.confirm(now) / release() / expire(now)`, `ShowSeat.hold / book / release` and `Show.openSale / closeSale` enforce the tables plus the business conditions (expiry, ownership by hold id, idempotent repeats). They throw on illegal moves.
    - **Tests:** 45 unit tests, with no Spring or database. `StatusTransitionsTest` lists every transition pair explicitly.
  - **Ports and adapters:**
    - **Use case:** `CreateHoldUseCase` now depends only on `ShowStore`, `HoldStore` and `SeatLockingStrategy`.
    - **Adapters:** package-private JDBC classes implement those ports. The old claim class is now `ConditionalUpdateStrategy`, the first `SeatLockingStrategy` (the other four arrive in P1.7).
  - **Database backstop:** V3 adds three `CHECK` constraints:
    - an `AVAILABLE` seat carries no hold;
    - a `HELD` seat has a hold, a user and an expiry;
    - a `BOOKED` seat keeps its hold id.
  - **Error format:** `ApiError` gains `timestamp`, giving the agreed `{code, message, retryable, timestamp}`.
  - **ArchUnit:**
    - `domainIsFrameworkFree` now also bans `jakarta.persistence`, `java.sql` and `javax.sql`.
    - New rule `applicationDependsOnPortsNotAdapters`.
- **Why this approach:**
  - **Closes SOLID deviations #2 and #3:** the use case can be unit-tested with fake ports, and a locking strategy can be swapped without touching business code. That's exactly what the P1.7 benchmark needs.
  - **JDBC instead of a JPA entity next to each domain class:**
    - **Explicit SQL everywhere:** every inventory query needs it anyway: `ORDER BY … FOR UPDATE`, `RETURNING`, `now() + interval`.
    - **Little lost:** JPA only added a mirror class per table and schema validation, and Flyway plus the ITs already cover the schema.
    - **Same layer budget:** removing JPA keeps the number of mapping layers the same, instead of adding entity ↔ domain mappers.
  - **Same expiry rule in Java and SQL (`expiresAt < now`):** at the exact expiry instant a hold is still live. "Claimable by someone else" and "confirmable" therefore never overlap and never leave a gap.
  - **Ownership by hold id in `ShowSeat.book/release`:** this is the rule that keeps lazy expiry (P1.2) safe. A late actor holding an old hold id can't move a seat that has been re-held. It's stated in code now so P1.4 and P2.1 follow it.
  - **`CHECK` constraints in addition to code:** the hot path writes seats with set-based SQL, not through the aggregate. The database is the last line of defence for any future strategy, manual fix or bug.
  - **`SOLD_OUT → ON_SALE` allowed, `CLOSED` terminal:** seats return to stock on release or expiry. UC-09 9a forbids reopening a closed show.
- **Alternatives considered:**
  - **Keep JPA and add a separate `HoldEntity` plus mapper:** this was the plan's first idea. It doubles the classes per table, and still needs native SQL for every interesting query.
  - **GoF State with one class per state:** see DESIGN_PATTERNS §6. Too heavy for state machines of 3–4 states.
  - **Rules only in the database (triggers):** they would hold, but the rules couldn't be read or unit-tested in Java, and error messages would be opaque.
  - **Rules only in Java:** leaves set-based SQL paths unguarded.
- **How it was verified:**
  - **`mvn verify`:** BUILD SUCCESS. Surefire: 45 domain unit tests in `inventory` and 11 architecture tests. Failsafe: 20 ITs.
  - **`SeatStateConstraintsIT`:** each of the three constraints rejects a hand-written impossible row.
  - **`ApplicationStartupIT`:** V1–V3 applied.
  - **Error format:** the 409 body now has `retryable` and `timestamp`.
  - **ArchUnit rules bite:** the planted booking violation still fails.
  - **Test isolation:** the test-data reset now also restores prices, so one failing test can't leak a modified price into others (seen once while fixing a SQL typo).
- **ADR:** none

### P1.4 — Confirm, release and get hold
- **Date:** 2026-11-16
- **Module / files:**
  - **`inventory.api`:** `InventoryApi` (+ `confirm`, `release`, `get`), `HoldState`, `HoldResult` (+ status), `HoldNotFoundException`, `HoldConflictException`.
  - **`inventory.application`:** `ConfirmHoldUseCase`, `ReleaseHoldUseCase`, `GetHoldUseCase`, `HoldViews`, `InventoryFacade`.
  - **Ports:** `ShowSeatStore`, `DatabaseClock`, `HoldStore` (+ `lock`, `saveStatus`, seat codes).
  - **JDBC adapters:** `JdbcShowSeatStore`, `JdbcDatabaseClock`, `JdbcHoldStore`.
  - **Domain:** `Hold` (+ `seatCodes`, `effectiveStatus`; expired holds can't be confirmed).
  - **Other code:** controller, exception handler, `V4__hold_seat_codes.sql`.
  - **Tests and docs:** `HoldLifecycleIT`, `HoldTest`; API spec, plan, registries, class diagram.
- **What was built:**
  - **Endpoints:** `GET /holds/{id}`, `POST /holds/{id}/confirm`, `DELETE /holds/{id}` over HTTP, and the same three on the `InventoryApi` port that the booking saga will call in-process (P3.3).
  - **Common steps:** every operation locks the hold row (`SELECT … FOR UPDATE`), checks ownership (another user's hold → 404, so ids can't be probed) and lets the `Hold` aggregate decide the transition using the database clock.
  - **Confirm:** books seats `WHERE hold_id = :hold AND status = 'HELD'`. If fewer seats are booked than the hold has, it fails with `HOLD_EXPIRED` and rolls back.
  - **Release:** frees only the seats the hold still owns. A hold already past expiry is recorded as `EXPIRED`.
  - **Repeats:** a repeat confirm or release returns the current state with 200.
  - **V4:** stores the hold's `seat_codes`, with a 1–8 `CHECK` (BR-01). A hold can then report its seats and prices even after they were released or re-held.
- **Why this approach:**
  - **Hold row lock first:** confirm (from a payment webhook), release (from a cancel) and the expiry worker can race on one hold. Locking the hold row serialises them, and the aggregate then sees a stable state. The claim path of *other* holds never locks this row, so it adds no contention on the hot path and can't form a lock cycle.
  - **Count check on confirm, the key race:**
    1. Our hold is checked as live at T.
    2. It expires at T+ε.
    3. Another user lazily re-holds one of its seats (P1.2).
    
    Booking conditionally then returns fewer rows than the hold has seats, and we roll back with `HOLD_EXPIRED`. Invariant: a `CONFIRMED` hold owns **every** one of its seats; there is never a half-booked hold. The saga takes the refund path (FR-07).
  - **Release doesn't check the count:** seats re-held by someone else after expiry must stay theirs. Releasing "what is still mine" is the correct, idempotent meaning.
  - **Expired-but-`HELD` → `EXPIRED` on release, not `RELEASED`:** the history then tells the truth (it ran out, it wasn't cancelled). `GET` reports the same `effectiveStatus` before the worker runs.
  - **Idempotency without a key table:** the state machine itself makes repeats safe (`CONFIRMED` + confirm = no-op). Saga retries and duplicate webhooks need nothing more here. The `Idempotency-Key` table (P3.4) is for *creating* bookings.
  - **404 for another user's hold:** the same answer as "doesn't exist" avoids leaking which hold ids are valid.
  - **Seat codes on the hold row instead of a `hold_seats` table:** the codes are written in the same `INSERT` (no extra round trip on the hot path). Prices are read from `show_seats` by code, because the price is frozen per show seat, not per holder.
- **Alternatives considered:**
  - **Optimistic `version` on holds instead of `FOR UPDATE`:** this works too, but conflicting confirm/release would then surface as retries, and lock waits here are rare and short.
  - **`hold_seats` join table:** more normalised, but one extra insert per seat in the hottest transaction.
  - **Confirm extends or refreshes the hold:** rejected by BR-02 (holds are never extended) and by the agreed "no hold extension while payment is `UNKNOWN`" rule (plan §4.4b).
  - **Marking the hold `EXPIRED` when confirm fails:** not possible in the same transaction, because the failure rolls back. The expiry worker (P2.1) does it.
- **How it was verified:**
  - **`mvn verify`:** BUILD SUCCESS. Surefire: 46 domain + 11 architecture tests. Failsafe: 29 ITs.
  - **`HoldLifecycleIT` (9 tests over HTTP):**
    - confirm books seats and is idempotent;
    - release frees seats, is idempotent and still reports its seats;
    - confirmed → release → 409 `HOLD_ALREADY_CONFIRMED`;
    - released → confirm → 409 `ILLEGAL_STATE_TRANSITION`;
    - expired → confirm → 409 `HOLD_EXPIRED`, with nothing booked;
    - **the re-held-seat race** → 409 `HOLD_EXPIRED` and no partial booking;
    - releasing an expired hold records `EXPIRED` and leaves the other user's seat alone;
    - `GET` shows lazy expiry;
    - another user gets 404 on all three operations, and the seat is unchanged.
  - **OpenAPI spec:** still validates.
- **ADR:** none

### P1.5 — Catalog and seat-map read APIs
- **Date:** 2026-11-16
- **Module / files:**
  - **`inventory.api`:** `CatalogQuery`, `SeatMapQuery`, `EventNotFoundException`.
  - **`inventory.application`:** `CatalogQueries`, `port.CatalogReadStore`.
  - **`inventory.infrastructure`:** `JdbcCatalogReadStore`.
  - **`inventory.web`:** `CatalogController`, `ApiExceptionHandler` (+ `EVENT_NOT_FOUND` and uniform 400s).
  - **Tests and docs:** `CatalogReadIT`; API spec, plan, registries (new pattern #21), class diagram.
- **What was built:**
  - **`GET /api/v1/events?page&size`:** published events only, paged (size 1–100), with `totalItems`.
  - **`GET /api/v1/events/{id}/shows`:** shows in start order, with venue name and a live `availableSeats` count. A draft or unknown event → 404 `EVENT_NOT_FOUND`.
  - **`GET /api/v1/shows/{id}/seats?section=`:** every seat with section, row, column, price and status, ordered by section/row/column, plus `generatedAt` (database time). An unknown show → 404.
  - **Uniform 400s:** malformed UUIDs, missing `X-User-Id` and body-validation failures now return the standard `ApiError` (`INVALID_REQUEST`) instead of Spring's default error page.
- **Why this approach:**
  - **Read side separate from day one (ISP, pattern #21):** reads are 100× more frequent than holds during a sale and may be slightly stale; holds must not be. Separate interfaces and a separate port mean P2.3 can serve the seat map from Redis without touching the hold path.
  - **Lazy expiry applied to reads:** a seat whose hold ran out shows as `AVAILABLE`, using the same SQL condition as the claim. Users are never told "taken" for a seat they could get, and the count in `availableSeats` agrees.
  - **Views returned as-is:** the query records are transport-free and already match the contract schemas. Copying them into identical web DTOs would add code without decoupling anything. Write-side responses keep their own DTOs, where the shapes differ.
  - **Unpublished events hidden as 404:** draft catalog entries stay invisible to customers (UC-01).
  - **`generatedAt` from the database clock:** it tells clients and the future cache layer *when* the snapshot was valid.
- **Alternatives considered:**
  - **Keyset pagination for events:** better for deep pages, but the event list is small. Offset is simpler and matches the contract.
  - **Counting availability with a counter column on `shows`:** cheaper reads, but every hold, release and expiry would update the same hot row, which is exactly the contention this system avoids. Redis (P2.3) is the planned answer.
  - **Spring's default 400 handling (`ProblemDetail`):** standard, but a second error shape next to ours, against the agreed format.
- **How it was verified:**
  - **`mvn verify`:** BUILD SUCCESS, 57 unit/architecture tests and 37 ITs.
  - **`CatalogReadIT` (8 tests):**
    - only published events, with paging metadata;
    - size 101 → 400;
    - show list with venue and `availableSeats` = 98 after a 2-seat hold;
    - draft and unknown events → 404;
    - the seat map covers 100 seats with layout and price; a live hold shows `HELD` and an expired one `AVAILABLE`;
    - the section filter is case-insensitive and an unknown section is empty;
    - unknown show → 404;
    - malformed id → 400 in the standard shape.
  - **OpenAPI spec:** still validates.
- **ADR:** none

### P1.6 — Concurrency test suite
- **Date:** 2026-11-16
- **Module / files:** `ticketing-app/src/test/.../inventory/SeatContentionIT.java`, `support/Contention.java` (race harness), `support/InventoryInvariants.java` (SQL invariant checker); plan, registries
- **What was built:**
  - **Six contention tests against real Postgres 16, through the `InventoryApi` port**, with a 50-connection pool:
    - **One seat:** 500 attempts → exactly 1 winner, and the 499 losers' hold rows are rolled back.
    - **100 seats:** 2,000 attempts → exactly 100 winners, with no seat won twice.
    - **Overlapping multi-seat requests:** 600 random 2–4-seat requests over 12 seats, each in shuffled order (fixed seed) → every held seat belongs to a complete winning hold, and there are no deadlocks.
    - **Reversed pairs:** 400 requests of `A1+A2` vs `A2+A1` (the textbook deadlock) → exactly 1 winner, no deadlock.
    - **Expired-seat stampede:** 300 attempts on a seat whose hold just expired → exactly 1 winner.
    - **Confirm vs release:** confirm racing release on 40 holds → exactly one of the two wins for every hold, and the booked seats equal the confirmed holds.
  - **`Contention.race`:** every attempt runs on a virtual thread and waits on one start gate, so the attempts hit the database together. It records each outcome or exception.
  - **`InventoryInvariants.assertHold`:** three SQL checks after every test:
    - every live hold owns *all* its seats;
    - every confirmed hold owns all its seats as `BOOKED`;
    - no seat is owned by a hold that doesn't list it or is in the wrong state.
    
    P9.2's load-test checker will reuse these queries.
  - **Requirement-named tests:** test names carry their requirement IDs (`fr03_…`, `nfr01_…`), as promised in the SRS traceability section.
- **Why this approach:**
  - **Unexpected exceptions fail the test:** the only allowed failure is the business outcome (`SeatUnavailableException`, or `HoldConflictException` in the confirm/release race). A deadlock, timeout or constraint violation fails the test, so "no deadlocks" is asserted, not assumed.
  - **Pool of 50, not the default 10:** more transactions are truly concurrent at the database, which makes lock-ordering bugs much more likely to show. The separate pool size gives this class its own Spring context and container, which costs about 3 s.
  - **Calling the port, not HTTP:** locking happens in the database; going through Tomcat only adds noise and time. HTTP behaviour is covered by the other ITs.
  - **Fixed random seed:** a failure is reproducible.
  - **Mutation check:** a test suite that never fails proves nothing, so the claim was broken on purpose twice:
    1. **Status condition removed (seats can be stolen):** 5 of 6 tests fail.
    2. **Lock order randomised:** the reversed-pairs test fails, and Postgres logs `deadlock detected`.
    
    Both mutations were reverted.
- **Alternatives considered:**
  - **JMH or k6 for this part:** those measure throughput (P1.8, P9). This part is about *correctness* under contention, and belongs in `mvn verify` so every commit is checked.
  - **Java `synchronized` or mocks to simulate contention:** they would test Java, not Postgres row locking, which is the thing that must be right.
  - **jcstress:** made for JVM memory-model tests, not database transactions.
  - **Repeating each test many times:** slower CI. The mutation runs showed the current sizes already expose both bug classes.
- **How it was verified:** `mvn verify`: BUILD SUCCESS, 57 unit/architecture tests and 43 ITs. `SeatContentionIT` (6 tests) runs in about 3 s. The two mutation runs are described above.
- **ADR:** none

### P1.7 — Five seat-locking strategies behind one port
- **Date:** 2026-11-16
- **Module / files:**
  - **`inventory.infrastructure.locking`:** `SeatLockingConfig`, `LockingProperties`, `SeatRows`, `ConditionalUpdateStrategy` (moved here), and new `PessimisticLockStrategy`, `OptimisticLockStrategy`, `RedisLockStrategy`, `RedisLuaStrategy`.
  - **Migration:** `V5__show_seat_lock_fence.sql`.
  - **Config:** `inventory/pom.xml` (+ Spring Data Redis), `application.yml` (strategy, Redis host, Redis health off by default).
  - **Web:** `ApiExceptionHandler` (503 `SERVICE_UNAVAILABLE` when Postgres or Redis is unreachable).
  - **Tests:** `SeatLockingContract` (was `SeatContentionIT`) plus five `*LockingIT`, and `RedisContainerConfig`; `@IntegrationTest` is now `@Inherited`.
  - **Docs:** registries (Strategy, Fencing token ✅, new Decorator #22), class diagram, plan, README.
- **What was built:**
  - **(a) Pessimistic:** lock all requested rows in seat order (`FOR UPDATE`), let `ShowSeat` decide, then one `UPDATE`.
  - **(b) Optimistic:** read without locks; write each seat `WHERE version = :read` in seat order; on a version conflict re-read and retry (max 3). Seats already won by this hold are recognised by hold id.
  - **(c) Conditional update:** unchanged (P1.1–P1.2), still the default.
  - **(d) Redis lock:**
    1. `INCR` a per-show fencing token.
    2. Take `SET lock:{show}:{seat} holdId NX PX 10s` in seat order.
    3. Release the locks with compare-and-delete **after the transaction completes**.
    4. Write with `lock_fence < :token`.
    
    A missing counter is re-seeded from `max(lock_fence)`.
  - **(e) Redis Lua:** one script checks every seat's mark (`holdId|expiryMillis` in `seats:{show}`) and sets all marks only if none is live. On a pass it delegates to (c), and removes its marks if the transaction doesn't commit.
  - **Selection:** `inventory.locking.strategy` (env `LOCKING_STRATEGY`), bound to an enum. An unknown value fails startup.
- **Why this approach:**
  - **One contract suite for all five (LSP):** each `*LockingIT` only sets the property, so a strategy that passes is substitutable by definition. Together they run 33 contract tests plus strategy-specific ones.
  - **Factory method instead of five conditional beans:** exactly one strategy exists at runtime, and the choice is visible in one `switch` and one startup log line.
  - **Redis locks held until the transaction ends:** releasing them when `claim()` returns would let another client read the row before our write commits. That bug is easy to write and invisible in single-threaded tests.
  - **Fencing token in Postgres:** this is what makes a Redis lock survivable when it expires mid-flight, as far as it can be. The class Javadoc and the registry name the remaining hole (a stale holder beats a newer one that hasn't written yet), which is why Postgres stays the authority.
  - **Lua as a decorator:** the filter can only reject more, never accept more. A stale mark causes a false rejection, never an oversell. `RedisLuaLockingIT.liveMarkRejectsWithoutTouchingPostgres` shows exactly this trade-off.
- **Problems found by the tests (and fixed):**
  1. **The fencing counter reset bug:** after Redis was cleared, the counter restarted at 1 while Postgres held tokens in the thousands. Every redis-lock claim was then rejected: safe, but a total outage for that show. Fixed by seeding a missing counter from `max(lock_fence)`, with a regression test (`lostFenceCounterIsReseededFromPostgres`).
  2. **The Lua filter doesn't see database edits:** the expired-seat test edits expiry in SQL. Under (e) it needs a hook that clears the Redis mark, which documents that the cache must be kept in sync. This is the job of P2.3–P2.5.
- **Alternatives considered:**
  - **`SERIALIZABLE` isolation as a sixth strategy:** correct, but it moves conflict handling into retries on serialization failures, which overlaps with (b). It can be added to the benchmark if time allows.
  - **`SKIP LOCKED`/`NOWAIT` variants of (a):** faster rejection, but they refuse seats whose locker later rolls back. Kept as a note for P1.8.
  - **Redisson for the lock:** a richer client, but it hides the very mechanics (TTL, fencing, release-after-commit) this part is meant to show.
- **How it was verified:** `mvn clean verify`: BUILD SUCCESS, 129 tests, 0 failures:
  - **Contract suite:** all 5 strategies pass the 6-test contract.
  - **redis-lock extras:** locks are gone after the transaction, a stale fencing token is rejected, and a lost counter is re-seeded.
  - **redis-lua extras:** a failed database claim removes the marks, and a live mark rejects before Postgres.
  
  Redis 7 runs in Testcontainers.
- **ADR:** the choice between them is ADR 003, written in P1.8 from measurements.

### P1.8 — Locking benchmark and ADR 003
- **Date:** 2026-11-16
- **Module / files:** `ticketing-app/src/test/java/.../inventory/infrastructure/locking/LockingBenchmarkIT.java` (opt-in harness), `docs/results/locking-comparison.md`, `docs/adr/003-seat-locking-strategy.md`, `ApiExceptionHandler` (transient data-access errors → 503), plan (P1.8, P2.4)
- **What was built:**
  - **Benchmark harness:** it builds each of the five strategies and runs it under the real `CreateHoldUseCase` inside a `TransactionTemplate`, in one Spring context with Postgres and Redis, on a 5,000-seat show.
  - **Workloads:** hot seat (1,000 → 1), flash sale (5,000 → 500), overlapping multi-seat (2,000 requests of 2–4 seats over 50) and uncontended (2,000 → 2,000). Each runs 1 warm-up + 3 measured runs, and reports throughput, p50/p95/p99, winners, rejections, deadlocks and errors.
  - **Failure run:** flash sale with the Redis container paused.
  - **Correctness gate:** the invariant checker runs after every run, so a fast but wrong strategy can't produce a number.
  - **ADR 003:** conditional update is the authority. Redis Lua becomes a filter outside the transaction with fail-open (P2.4/P2.6). Redis locks are rejected.
- **Key results** (full table in the report):
  - **Correctness:** all strategies kept every invariant with 0 deadlocks.
  - **Uncontended:** conditional update is fastest (5,404/s vs 3,250–4,613).
  - **Contention:** Redis Lua is best under contention (flash sale 7,948/s, +36%; multi-seat +49%).
  - **Hot seat:** optimistic and Redis reject losers faster than conditional update, which makes them wait for the row lock.
- **Most important finding:** with Redis paused, **both Redis strategies sold nothing, with p99 ≈ 30 s**. The Redis call sits *inside* the database transaction, so a hanging Redis held all 50 pooled connections, and every other request waited out Hikari's 30 s connection timeout. This changed the design of P2.4: the filter must run before a connection is taken and fail open to Postgres. The plan row now says so.
- **Also fixed:** Redis command timeouts and other transient data-access errors (`TransientDataAccessException`, which includes lock timeouts) now map to `503 SERVICE_UNAVAILABLE`, `retryable: true`, instead of a 500.
- **Why this approach:**
  - **Same JVM, same database, strategies swapped in place:** each strategy sees identical data and warm caches. Five separate application runs would add noise from restarts.
  - **Invariants checked inside the benchmark:** a benchmark that only measures speed can reward a broken strategy.
  - **Honest caveats in the report:** the latencies are dominated by queueing for 50 connections, the numbers are from a laptop VM, and there is about 10% noise, so small differences are called ties. NFR-03 is judged later at *admitted* load (P9), not here.
  - **Java harness instead of k6:** the plan allowed k6 or JMH. A Java harness drives the strategy directly, without HTTP noise, and can pause the Redis container mid-run. k6 is used for end-to-end load in P9.
- **Alternatives considered:**
  - **JMH:** better for microbenchmarks of CPU-bound code, but this is I/O-bound contention between thousands of concurrent transactions, which JMH's model doesn't fit well.
  - **Running the benchmark in CI:** it takes about 4 minutes and the numbers would vary with CI hardware. It is opt-in instead, via `-Dbenchmark=true`. The correctness side already runs in CI as the contract suite.
  - **Choosing Redis Lua as the production strategy:** it is the fastest under contention, but in its current placement it is a single point of failure. ADR 003 keeps its benefit while removing the failure mode (P2.4/P2.6).
- **How it was verified:** the benchmark run completed with every invariant check passing (output in the report). `mvn verify` without the flag passes (129 tests, with the benchmark skipped).
- **ADR:** [003](adr/003-seat-locking-strategy.md)

### P1.9 — Inventory business metrics
- **Date:** 2026-11-16
- **Module / files:** `inventory.application.port.HoldMetrics`, `inventory.infrastructure.MicrometerHoldMetrics`, `InventoryFacade` (records metrics), `inventory/pom.xml` (+ `micrometer-core`), `HoldMetricsIT`, README, plan, registries; a blank-line fix in the P1.8 report table
- **What was built:**
  - **Metrics exported at `/actuator/prometheus`:**
    - `seat_hold_attempts_total{outcome, strategy}`, where `outcome` is `HELD`, `SEAT_UNAVAILABLE`, `SHOW_NOT_ON_SALE`, `SHOW_NOT_FOUND`, `INVALID_REQUEST` or `ERROR`;
    - `seat_hold_conflicts_total{strategy}`;
    - `hold_latency_seconds{outcome, strategy}`, a histogram with SLO buckets at 50, 100, 250 and 500 ms;
    - `hold_transitions_total{operation, outcome}` for confirm and release results (for example `CONFIRMED` or `HOLD_EXPIRED`).
  - **Where it's recorded:** the facade records each request after the use case returns or throws.
- **Why this approach:**
  - **Measured at the facade, outside the transaction:** latency then includes the commit, which is what a caller waits for. Inside the use case, the commit (and any lock wait at commit) would be invisible.
  - **Port + adapter:** the application layer stays free of Micrometer (DIP). Tests or another backend can swap the implementation.
  - **`strategy` tag:** dashboards can compare locking strategies under the same load (P9), continuing P1.8.
  - **SLO buckets at 250 and 500 ms:** these map directly to NFR-03 (p95 < 250 ms, p99 < 500 ms), so P8.4 can build burn-rate alerts from the histogram.
  - **Meters created once and cached:** the hot path does no registry lookups.
  - **Conflicts as a separate counter:** "conflict rate" is the main business signal during a sale, and a dedicated series keeps the query simple.
- **Problem found:** `/actuator/prometheus` returned 404 in tests. Spring Boot disables metrics export under `@SpringBootTest` unless `@AutoConfigureObservability` is present. The test now opts in. The packaged app was checked separately: the endpoint serves all four metrics.
- **Alternatives considered:**
  - **`@Timed` / `@Counted` annotations:** less code, but they can't tag by business outcome, and they put Micrometer annotations on application classes.
  - **The Micrometer Observation API:** it would also create tracing spans (P8.1). It's worth revisiting then, but too much for counters today.
  - **Logging and counting later:** loses real-time dashboards and alerting.
- **How it was verified:**
  - **`mvn verify`:** BUILD SUCCESS, 130 tests and 1 skipped (the benchmark).
  - **`HoldMetricsIT`:** a hold, a conflict and a confirm over HTTP move exactly the expected series, and the 250 ms histogram bucket exists.
  - **Packaged-jar smoke test against the Compose database:** V3–V5 applied on top of the existing data (V4's `NOT VALID` check let the old holds through), with the conditional-update strategy logged at startup:
    - hold → 201, confirm → 200, conflict → 409;
    - the seat map shows the booked seat;
    - the four metric series are present, and health is `UP`.
- **ADR:** none

**Phase 1 complete.** Seat holds are correct under contention, checked against five interchangeable strategies, mutation-tested, benchmarked (ADR 003) and metered.

---

## Phase 2 — Hold expiry, Redis fast path & reconciliation

### P2.1 — Hold expiry worker
- **Date:** 2026-11-16
- **Module / files:**
  - **Expiry job:** `inventory.application.ExpireHoldsJob`, `infrastructure.ScheduledHoldExpiry`, `infrastructure.SchedulingConfig`.
  - **Events:** `domain.event.HoldExpired`, `application.port.InventoryEventPublisher` and its adapter `infrastructure.SpringInventoryEventPublisher`.
  - **Ports and adapters:** `HoldStore` (+ `lockExpired`, `findExpiredIds`, `lockIfExpired`, `saveStatuses`), `ShowSeatStore` (+ `releaseSeatsOfHolds`), `JdbcHoldStore`, `JdbcShowSeatStore`.
  - **Metrics:** `MicrometerHoldMetrics` (expiry metrics).
  - **Migration:** `V6__backfill_hold_seat_codes.sql`.
  - **Config:** `application.yml` (`inventory.expiry.*`), test `application.properties`.
  - **Tests:** `ExpireHoldsJobIT`, `infrastructure/ScheduledHoldExpiryIT`.
  - **Docs:** plan, registries, class diagram.
- **What was built:**
  - **Scheduling:** every 5 s (`EXPIRY_INTERVAL`), the worker drains up to 20 batches of 500 holds, each batch its own short transaction.
  - **Selecting a batch:** each batch locks the oldest due `HELD` holds with `FOR UPDATE SKIP LOCKED`, and the `Hold` aggregate re-checks each one.
  - **Releasing seats:** the seats each hold *still owns* are released in one locked select and one update.
  - **Recording the outcome:** the holds are marked `EXPIRED` in one batch statement, and one `HoldExpired` event is published per hold, listing the seats actually released.
  - **Metrics from events:** `holds_expired_total` and `seats_released_by_expiry_total` are counted by an after-commit observer, so a rolled-back batch counts nothing.
  - **Poison-row isolation:** if a batch fails, its holds are retried one by one in separate transactions. Failures are skipped, logged and counted (`hold_expiry_failures_total`).
- **Why this approach:**
  - **Cleanup, not availability:** since P1.2 an expired seat is already sellable, so this job only makes the stored state truthful (seat map, hold history) and tells others the hold is over. A slow worker therefore costs freshness, never sales.
  - **`SKIP LOCKED` and the same lock order as confirm/release (hold, then seats):** several replicas can run the job without a leader and without waiting on each other. A hold being confirmed at that moment is skipped, and once confirmed it is no longer due. (Measured with many workers in P2.2.)
  - **Seats released only `WHERE hold_id = this hold`:** a seat someone re-held after expiry is never freed (tested).
  - **Events through a port, after commit:**
    - **Interim delivery:** in-process delivery is enough for metrics and the cache (P2.3), and it is honest about the gap: it isn't durable.
    - **Durable later:** the outbox (P5.2) replaces the adapter only, and reconciliation (P2.5) repairs anything a lost event leaves behind.
  - **Many small transactions instead of one big one:** a backlog after an outage is worked off without holding thousands of row locks at once.
- **Problem found by the smoke test (and fixed):**
  - **Symptom:** run against the existing Compose database, the worker failed on *every* tick.
  - **Cause:** a hold created before V4 had an empty `seat_codes`. V4's `NOT VALID` check still applies when such a row is *updated*, so marking it `EXPIRED` violated the constraint. Because the worker takes the oldest holds first, the same poison row sat at the front of every batch, and no hold could expire at all.
  - **Fix 1:** V6 back-fills seat codes from `show_seats`, deletes legacy holds that own nothing, and then `VALIDATE`s the constraint.
  - **Fix 2:** the worker's one-by-one fallback means a future bad row costs one hold, not the whole job. This is tested with a trigger that makes one hold impossible to update.
  - **Lesson recorded:** `NOT VALID` constraints still bite on `UPDATE`, so plan a back-fill with them.
- **Alternatives considered:**
  - **Postgres `pg_cron` doing the update in SQL:** no Java, but no events, no metrics and no domain re-check, and it adds an extension.
  - **A delay queue (Redis sorted set or Kafka timers):** precise timing, but another system to keep consistent. Lazy expiry already makes timing non-critical.
  - **One transaction per hold always:** simplest isolation, but about 500× more transactions under normal load. Batching is the normal path, and per-hold is only the fallback.
  - **ShedLock / leader election:** not needed with `SKIP LOCKED`. Compared in P2.2.
- **How it was verified:**
  - **`mvn verify`:** BUILD SUCCESS, 135 tests and 1 skipped (benchmark).
  - **`ExpireHoldsJobIT`:**
    - due holds expire, their seats are released, and events arrive after commit with the right seats;
    - a lazily re-held seat is untouched and not listed;
    - confirmed and released holds are ignored;
    - batches drain 4 + 4 + 2.
  - **`ScheduledHoldExpiryIT`:** a poison hold is skipped and counted while the others expire.
  - **Smoke test of the packaged jar (1 s interval) against the Compose database:**
    - V6 repaired 1 legacy row, and the constraint is now validated;
    - the worker expired the 2 previously stuck holds plus a new test hold within 3 s;
    - `holds_expired_total` = 3, `seats_released_by_expiry_total` = 4, `hold_expiry_failures_total` = 0.
- **Environment note:** Docker Desktop restarted during this part and stopped the Compose containers. They were brought back with `POSTGRES_PORT=55432 docker compose up -d postgres redis`.
- **ADR:** none

### P2.2 — No leader election: competing expiry workers
- **Date:** 2026-11-16
- **Module / files:** `ReleaseHoldUseCase` (publishes `HoldExpired`), new `ExpiryWorkersIT`; plan, DESIGN_PATTERNS (new #23)
- **Decision:** every replica runs the expiry worker. There is **no ShedLock and no leader election.** `FOR UPDATE SKIP LOCKED` turns the `holds` table into a work queue: concurrent workers lock disjoint batches and never wait on each other.
- **What was built:**
  - **`eightConcurrentWorkersExpireEveryHoldExactlyOnce`:** 2,000 expired holds (100 owning a seat, 1,900 whose seats were re-held long ago) are drained by 8 workers started together through the contention harness, each looping `runBatch(50)`. The test checks:
    - every hold expired exactly once, and the per-worker sums add up to 2,000;
    - more than one worker did real work;
    - all 100 seats are back to `AVAILABLE`;
    - exactly 2,000 `HoldExpired` events with 2,000 distinct ids;
    - the invariants still hold.
  - **`releaseRacingTheWorkerOnAnExpiredHoldAnnouncesTheExpiryOnce`:** a release and a worker hit the same expired hold at once. The hold ends `EXPIRED`, with exactly one event listing both seats.
  - **Gap fixed in passing:** when *release* found an already-expired hold, it recorded `EXPIRED` but published nothing, so a downstream listener (the booking saga, P4) would never have heard of that expiry. It now publishes the same `HoldExpired` as the worker. The hold-row lock guarantees only one of the two ever records it.
- **Why this approach:**
  - **The job can be partitioned:** each hold is independent, so parallel workers are strictly better than one runner. ShedLock exists for jobs that *must* run once at a time (for example "send the daily report"), which this is not.
  - **Fewer moving parts:** no lock table, no lock TTL to tune, and no situation where a crashed leader delays cleanup until its lease runs out.
  - **The event is part of the transition, not of the worker:** "whoever records `EXPIRED` publishes `HoldExpired`" makes the rule simple to check, and it holds for any future code path.
- **Alternatives considered:**
  - **ShedLock with JDBC:** correct but serial, and needs extra schema plus `lockAtMostFor` tuning.
  - **Kubernetes Lease-based leader election:** ties the app to the platform, and gives no benefit over `SKIP LOCKED` here.
  - **Sharding holds by hash across replicas (`WHERE hashtext(id) % n = k`):** needs to know the replica count, and rebalancing is manual. `SKIP LOCKED` adapts automatically.
- **How it was verified:**
  - **`mvn verify`:** BUILD SUCCESS, 137 tests and 1 skipped.
  - **Run log:** "8 workers expired 2000 holds in 0.12 s (550, 200, 400, 500, 50, 300, 0, 0 per worker)". The two idle workers are expected: they started after the queue was drained.
- **Environment note:** `git push` hung twice after Docker Desktop restarted. The macOS keychain credential helper was waiting on an invisible prompt. Pushes now use `gh`'s stored credentials (`credential.helper=!gh auth git-credential`) with a 60 s timeout.
- **ADR:** none (a reversible, local choice, recorded in the pattern registry)

### P2.3 — Redis seat cache with versioned, order-independent updates
- **Date:** 2026-11-16
- **Module / files:**
  - **Events:** new `domain.event.SeatsChanged` and `application.SeatEvents`; all four write paths publish it (`CreateHoldUseCase`, `ConfirmHoldUseCase`, `ReleaseHoldUseCase`, `ExpireHoldsJob`).
  - **Ports:** `SeatLockingStrategy.ClaimedSeat` and `ShowSeatStore` now return row versions (`SeatVersion`), and the strategies' SQL returns `version`.
  - **New `infrastructure.cache` package:** `SeatStateCache`, `SeatSnapshots`, `SeatCacheUpdater`, `CachedCatalogReadStore`, `SeatCacheConfig`.
  - **Config:** `application.yml` (`inventory.seat-cache.*`, Redis timeouts of 250 ms), test properties.
  - **Tests:** `SeatCacheIT`; the benchmark harness constructor is updated.
  - **Docs:** plan, HLD §4.5 Redis keys, API spec, registries (new pattern #24).
- **What was built:**
  - **`SeatsChanged` event:** every seat write now publishes it, listing each changed seat with its state, hold expiry and new row `version`.
  - **Cache layout:** `seatstate:{showId}` maps seat → `version|A/H/B|expiryMs`, plus `seatstate:{showId}:ready`.
  - **Updater:** an after-commit observer queues each event to a bounded executor (2 threads, queue 10,000). A Lua script applies the event only where the version is newer. A full queue drops and counts (`seat_cache_updates_total{result}`).
  - **Seat-map reads:**
    - **Layout:** seat id, row, column and price are immutable, so they're kept in memory per show.
    - **States:** read from Redis.
    - **Cold show:** warmed from Postgres on first read, then marked ready.
    - **Any Redis error:** falls back to Postgres (`seat_map_reads_total{source=redis|warmup|database}`).
    - **Lazy expiry still applies:** a cached `HELD` past expiry shows `AVAILABLE`.
  - **Redis timeouts:** 250 ms for commands and connect, instead of Lettuce's 60 s default.
- **Why this approach:**
  - **Versions make delivery order irrelevant (pattern #24):** in-process async events can be applied out of order, and later Kafka events are at-least-once. Keeping the highest row version per seat makes the cache converge to the newest state anyway. It also protects warm-up: a snapshot older than an already-applied event can't overwrite it (tested).
  - **Ready marker:** updates may create entries before a show is warmed, but nobody reads the show until the full snapshot has been applied. A half-filled hash would show missing seats as available.
  - **Async updates, not inline:** after-commit listeners still run on the request thread, *before* the database connection returns to the pool. A Redis call there would recreate the P1.8 / ADR 003 failure. The bounded queue keeps a slow Redis from ever blocking a request; dropped updates are repaired by P2.5.
  - **Decorator plus fallback:** the read side gets faster without any write-path change. With Redis down, seat maps still work, just at database speed.
  - **One hash per show for now:** a show with 5,000 seats is one ~100 KB hash, so `HGETALL` is cheap. Per-section keys come with hot-event partitioning (P7.1), where the section becomes the shard key.
- **Problem found by the tests (and fixed):** the read script first returned Lua `false` for "not ready". Spring Data Redis delivered that as a one-element list `[null]`, which the code took for "cached, but empty", so every seat showed `AVAILABLE` and no warm-up ever ran. The script now returns an empty array, and the decoder also treats `[null]` as "not cached".
- **Alternatives considered:**
  - **Spring Cache (`@Cacheable`) on the seat map:** simple, but it caches whole responses with a TTL. It goes stale for the whole TTL after every change, and can't apply per-seat updates.
  - **Redis bitmaps (one bit per seat):** smaller, but they lose the hold expiry and version, which lazy expiry and ordering need.
  - **CDC from Postgres (Debezium) to feed the cache:** this solves durability properly, and is a stretch goal (P5.10). The outbox in P5.2 is the planned step.
  - **Caching the layout in Redis too:** it's immutable, so in-process memory is faster and needs no invalidation.
- **How it was verified:**
  - **`mvn verify`:** BUILD SUCCESS, 143 tests and 1 skipped.
  - **`SeatCacheIT`:**
    - the first read warms 100 seats and sets the ready marker, and later reads follow Redis;
    - hold, confirm, release and the expiry worker all reach the cache, with versions equal to the database rows;
    - an older version never overwrites a newer one;
    - a half-filled hash is not served, and warm-up doesn't overwrite a newer event;
    - an expired cached hold shows `AVAILABLE`;
    - with Redis paused, the read falls back to Postgres, bounded by the timeout.
  - **Smoke test of the packaged jar with the Compose Redis:**
    - the first read (warm-up) took 316 ms;
    - after a hold, the seat showed `HELD` from Redis in 21 ms, and the hash entry carried its version;
    - with Redis stopped, the same seat was still `HELD`, served from Postgres in 267 ms;
    - the counters read 1 read each from redis, warmup and database, and 1 applied update.
- **ADR:** none (follows ADR 001: Postgres authority, Redis as cache)

### P2.4 — Fast-reject filter outside the transaction
- **Date:** 2026-11-16
- **Module / files:**
  - **Application:** `application.FilteredCreateHold`, `application.SeatCodes`, `application.port.SeatFilter`, and `InventoryFacade` (now uses `FilteredCreateHold`).
  - **Infrastructure:** `infrastructure.NoSeatFilter` and `infrastructure.cache.RedisSeatFilter`; `SeatStateCache` (pending state `P`) and `SeatCacheConfig`.
  - **Config:** `application.yml` (`inventory.fast-path.enabled`).
  - **Tests:** `SeatFilterIT`.
  - **Docs:** plan, ADR 003 follow-up, registries (new #25 Null Object and #26 Fast-reject filter).
- **What was built:**
  - **The check:** before any database work, one Lua script checks every requested seat in `seatstate:{showId}`.
    - **Rejected:** if any seat is booked, or held/pending and not expired, the request is rejected and Postgres is never touched.
    - **Reserved:** otherwise all requested seats are marked `version|P|now+2s|token` in the same atomic step, and the normal hold transaction runs.
  - **After the transaction:**
    - **Postgres refuses:** the request's own pending marks are turned back into "available" by token.
    - **Hold commits:** the committed `SeatsChanged` (higher version) replaces the marks with the real `HELD`.
  - **Skipped:** a cold cache, an unknown seat code or a Redis error means `SKIPPED`, and Postgres decides alone.
  - **Fast path off:** `FAST_PATH_ENABLED=false`, or no seat cache, uses a pass-through Null Object filter.
  - **Metrics:** `seat_filter_verdicts_total{verdict}` and `seat_filter_errors_total`.
- **Why this approach:**
  - **Outside the transaction (ADR 003):** in P1.8 the in-transaction Redis call exhausted the connection pool when Redis hung. Here the filter runs before a connection is taken, and has a 250 ms timeout plus fail-open.
  - **Pending marks:** without them, every request that arrives before the first winner's `SeatsChanged` lands would still pass the check and fight in Postgres. With the mark, the first of 1,000 identical requests reserves and the rest are rejected in Redis (test: more than 900 rejected, 1 winner, invariants hold).
  - **The filter can only reject more:**
    - **Stale cache:** if the cache says free but Postgres says booked, the database refuses and the marks are undone (tested).
    - **Lost success event:** the marks time out after 2 s, so the worst outcome of a cache problem is a false 409, never an oversell.
  - **A wrapper class, not more facade code:** the facade stays a thin delegator plus metering (SRP), and `CreateHoldUseCase` is unchanged (OCP).
  - **`@Primary` + always-present Null Object instead of `@ConditionalOnMissingBean`:** the latter is only reliable in auto-configuration. Between ordinary configuration classes it depends on registration order.
- **Problem found (and fixed):** the configuration class `NoSeatFilter` and its bean method `noSeatFilter()` produced the same bean name, and Spring refused to start (bean definition overriding is disabled). The bean method is now `passThroughSeatFilter()`.
- **Alternatives considered:**
  - **Rejecting only on `B`/`H` without pending marks:** simpler, but does little against a stampede, which is the main case.
  - **Longer pending TTL:** fewer races, but a crashed request blocks its seats for longer. 2 s comfortably covers a hold transaction (p99 well under 1 s, P1.8).
  - **Reserving in Redis and confirming asynchronously in Postgres (Redis as the authority):** much faster, but rejected by ADR 001.
- **How it was verified:**
  - **`mvn verify`:** BUILD SUCCESS, 148 tests and 1 skipped.
  - **`SeatFilterIT`:**
    - a taken seat is rejected with no hold row and no transaction, and the free seat of that request is not marked;
    - stampede: 1,000 → 1 winner, more than 900 rejected in Redis;
    - when Postgres says no (stale cache), the pending marks are undone and the seat is holdable;
    - a successful hold's pending mark is replaced by the committed `HELD` (version 1);
    - cold cache → Postgres decides;
    - Redis paused → holds still work, a taken seat is still refused by Postgres, and the filter error is counted.
- **ADR:** follow-up of [003](adr/003-seat-locking-strategy.md)

### P2.5 — Seat-cache reconciliation
- **Date:** 2026-11-16
- **Module / files:** `infrastructure.cache.SeatCacheReconciler`, `ScheduledSeatCacheReconciliation`, `SeatCacheConfig`; `application.yml` (`inventory.reconcile.*`), test properties; `SeatCacheReconcilerIT`; plan, registries (new #27)
- **What was built:**
  - **The job:** every 30 s (`RECONCILE_INTERVAL`), for every show that is on sale and currently cached, it reads the Postgres seat states and the Redis hash and repairs differences. Postgres wins.
  - **Drift rules:**
    - **Missing** (no entry) or **stale** (older version than Postgres): written through the version-guarded `APPLY`.
    - **Diverged** (same version, different state: a corrupted value, or a pending mark whose request died): replaced by compare-and-set, only if the entry is still exactly what was read.
    - **Not drift:** an entry *ahead* of the snapshot (an event landed after our read) and a *fresh* pending mark are left alone.
  - **Metric:** `inventory_drift_total{kind=missing|stale|diverged}`. A warning is logged per show with repairs.
- **Why this approach:**
  - **Closing the durability gap:** this is the safety net for every gap left by P2.1–P2.4: in-process events lost in a crash, updates dropped by the bounded queue, a Redis restart, or a manual SQL fix. With it, drift is bounded by one interval, and the outbox (P5.2) later makes it rarer, not unnecessary.
  - **Never overwrite newer data:** a naive "copy Postgres into Redis" would race with live updates and sometimes write an *older* snapshot over a newer event. Version guards and compare-and-set make the repair safe to run during a sale.
  - **Dead pending marks:** a request that crashed between the filter and the database leaves a `P` mark. It already times out for reads and for the filter after 2 s, and reconciliation removes it from storage.
  - **Every replica runs it:** it's read-only on Postgres and idempotent in Redis, so there's no leader (same reasoning as P2.2).
- **Alternatives considered:**
  - **Entry TTLs:** these force reloads at the worst time and don't fix wrong data any faster.
  - **Rebuilding the whole hash:** races with live updates.
  - **Comparing only counts (available seats):** cheaper, but it can't tell *which* seat drifted or repair it.
  - **Running only on demand (an admin endpoint):** drift would persist until someone notices.
- **How it was verified:**
  - **`mvn verify`:** BUILD SUCCESS, 152 tests and 1 skipped.
  - **`SeatCacheReconcilerIT`:**
    - a consistent cache needs no repair, and the cached show is discovered;
    - one missing, one stale (database changed without an event) and one diverged entry are all repaired, the counters move, and a second run finds nothing (idempotent);
    - a fresh pending mark and a cache-ahead entry are left alone;
    - an abandoned pending mark is cleaned up.
  - **Smoke test of the packaged jar (2 s interval) with the Compose Redis:** after A1 was deleted and A2 corrupted to `BOOKED` by hand, both were back to `0|A|0` (matching Postgres) within 4 s. The log showed `{MISSING=1, DIVERGED=1}`, and `inventory_drift_total` matched.
- **ADR:** none

### P2.6 — Redis-down behaviour: flags and a circuit breaker
- **Date:** 2026-11-16
- **Module / files:**
  - **New:** `infrastructure.cache.RedisGuard`.
  - **Wired through it:** `RedisSeatFilter`, `CachedCatalogReadStore`, `SeatCacheUpdater`, `ScheduledSeatCacheReconciliation`, `SeatCacheConfig`.
  - **Build:** root `pom.xml` (Resilience4j 2.2.0 BOM) and `inventory/pom.xml` (`resilience4j-circuitbreaker`, `resilience4j-micrometer`).
  - **Config:** `application.yml` (`inventory.redis-breaker.wait-in-open`).
  - **Tests:** `RedisOutageIT`.
  - **Docs:** plan, ADR 003 follow-up, DESIGN_PATTERNS #18, README.
- **What was built:**
  - **Flags:** `SEAT_CACHE_ENABLED=false` removes the cache, filter and reconciliation beans, so everything is served by Postgres. `FAST_PATH_ENABLED=false` removes only the filter (Null Object, P2.4).
  - **One circuit breaker, `redis`:** it guards every Redis call in the module and has a fallback for each:
    - **filter:** skip the filter;
    - **seat map:** read from Postgres;
    - **cache update:** count it as dropped, and don't queue at all while the breaker is open;
    - **reconciliation:** skip the round.
  - **Breaker settings:** count window 10, at least 5 calls, opens at 50% failures, stays open 5 s, then 3 trial calls. Exported as `resilience4j_circuitbreaker_state{name="redis"}`, plus `redis_calls_short_circuited_total`.
- **Why this approach:**
  - **The last piece of ADR 003:** P2.4 already fails open, but without a breaker *every* request still waits for the 250 ms Redis timeout before falling back. Under flash-sale load that is a 250 ms latency floor and thousands of hung Redis calls. The breaker turns "Redis is dead" into a decision made once, not per request.
  - **One shared breaker:** the four users all depend on the same Redis. Whichever notices the outage first protects the others, and recovery is detected once.
  - **Resilience4j instead of a hand-written breaker:** it's the standard JVM library (and is planned for the HTTP adapters in P7.8), with tested half-open semantics and Micrometer metrics. Only the core module is used, not the Spring Boot starter, so the configuration is explicit in one bean.
  - **Updates dropped, not queued, while open:** queuing would fill the buffer with work certain to fail. Reconciliation repairs the cache after recovery.
- **Problem found (and fixed):** the first version of the outage test passed for the wrong reason. A previous test in the same class had left the breaker open, so "Redis down" requests were fast from the start. `RedisGuard.reset()` now runs before each test, and the assertion requires the first calls to *pay* the timeout, proving the breaker is what makes the later calls fast.
- **Alternatives considered:**
  - **A shorter Redis timeout only (for example 20 ms):** this shrinks the cost but still pays it on every request, and it risks false failures during GC pauses.
  - **A health-check thread that flips a flag:** detection lags by the check interval, and it duplicates what the breaker learns from real traffic.
  - **Spring Retry with a recovery method:** retry is the opposite of what's wanted here; it adds load to a struggling Redis.
- **How it was verified:**
  - **`mvn verify`:** BUILD SUCCESS, 154 tests and 1 skipped.
  - **`RedisOutageIT`, with Redis paused:**
    - the first holds took 256, 266 and 260 ms, then 3 ms once the breaker opened (p95 of the remaining 30: 2 ms);
    - all 40 holds succeeded, and Postgres alone still refused a taken seat;
    - the seat map served 100 seats from Postgres in 1 ms;
    - after unpausing and waiting 1 s (test setting), the trial calls closed the breaker, the fast path rejected a taken seat again, and the invariants held.
  - **Smoke test of the packaged jar with the Compose Redis stopped:** 10 holds all succeeded (55 ms, then 3–6 ms), and the breaker reported `open`. After `docker compose start redis`, 4 holds took 11–34 ms, the breaker was `closed`, and a taken seat got a 409 in 8 ms through the fast path.
- **ADR:** follow-up of [003](adr/003-seat-locking-strategy.md)

**Phase 2 complete:**
- **Expiry:** expired holds are cleaned up by leaderless workers.
- **Seat-map reads:** served from a versioned Redis cache, which reconciliation keeps honest.
- **Fast path:** most flash-sale losers are rejected in Redis before Postgres.
- **Redis outage:** losing Redis costs only speed, never correctness or availability.

### P2.5 fix — order-dependent cache tests (CI failure on 716ed8a)
- **Date:** 2026-11-16
- **Files:** `SeatCacheReconcilerIT`, `SeatFilterIT`
- **What happened:**
  - **The failure:** GitHub CI failed on the P2.5 commit with `STALE=2 (expected: 1)`. It passed locally and on the next commit.
  - **Why the version mattered:** `SeatCacheReconcilerIT` shares its Spring context, and therefore its database, with `SeatCacheIT`. Row versions only ever grow; the per-test reset puts seats back to `AVAILABLE` but deliberately doesn't rewind `version`, since that would break the very ordering guarantee the cache relies on.
  - **Why CI saw it:** CI ran `SeatCacheIT` first, which had held and expired seat A3 (version 2). The "diverged" case then wrote A3 with a hard-coded version `0`. That is *older* than Postgres, so the reconciler correctly counted it as STALE, not DIVERGED.
- **Fix:**
  - **Real versions:** the tests now build their cache values from the real row version (`version(seat)`) instead of assuming 0.
  - **`SeatFilterIT`:** it had the same latent assumption, and now matches `\d+|A|0`.
  - **Reproduced first:** CI's order was reproduced locally with `-Dit.test=SeatCacheIT,SeatCacheReconcilerIT -Dfailsafe.runOrder=alphabetical` before and after the fix.
- **Lesson:** tests that share a context share data, so assert on *relationships* (same or older version) rather than literal values a previous test may have moved. The production code was right; the test encoded an assumption.
- **How it was verified:** `mvn verify` passes, 154 tests and 1 skipped. The forced CI order passes.

---

## Phase 3 — Booking service & idempotency

### P3.1–P3.2 — Booking module schema and the Booking aggregate
- **Date:** 2026-11-18
- **Module / files:**
  - **Schema:** `booking/src/main/resources/db/migration/booking/V1__bookings.sql` (`booking.bookings`, `booking.booking_seats`).
  - **Migration runner:** `booking.infrastructure.BookingSchemaMigration`.
  - **Domain:** `booking.domain.{Booking, BookingSeat, BookingStatus, IllegalStateTransitionException}`.
  - **Tests:** `booking/src/test/.../BookingTest` (unit), `ApplicationStartupIT.bookingHasItsOwnMigrationHistory`.
  - **Build:** `booking/pom.xml` (JDBC, Flyway, test).
- **What was built:**
  - **Own schema, own history:** the booking module migrates `booking.*` itself, at startup, with the history table `booking.flyway_schema_history`. Inventory's Spring Boot Flyway run is unchanged.
  - **No cross-module foreign keys:** `hold_id` and `show_id` are stored by value. A booking references exactly one hold (`UNIQUE (hold_id)`).
  - **The state machine (plan §4.4)** lives on `BookingStatus`; `Booking` exposes one method per business event. Repeating a transition that already happened is a no-op that returns `false`, so a retried webhook or saga step is harmless. Anything else illegal throws.
  - **Price copied at booking time:** each `BookingSeat` keeps the seat code and the price paid, and the aggregate rejects an amount that is not the sum of its seats.
  - **`version` column** for optimistic locking; the store that uses it arrives in P3.3.
- **Why this approach:**
  - **Per-module history** lets each module number its migrations from V1 and keeps the door open for database-per-service (P7.8) without rewriting history.
  - **Enum-table State pattern**, as in inventory: one place for the rules; seven states don't justify a class per state.
  - **Idempotent transitions** make every caller (user, webhook, expiry, saga) safe to retry without extra bookkeeping.
- **Alternatives considered:**
  - **One Flyway run over every folder** (`db/migration/inventory`, `db/migration/booking`): versions must then be unique across modules, and the modules share one history. Rejected.
  - **A second `Flyway` bean:** Spring Boot's Flyway auto-configuration backs off when one exists, which would silently stop inventory migrating. Hence a plain component that calls Flyway's API.
  - **Foreign keys to `inventory.holds`:** stronger in a single database, but they break the module boundary and extraction. Consistency comes from the saga and reconciliation instead.
- **How it was verified:** `BookingTest` (9 cases: happy path, idempotent repeats, refund paths, terminal states, amount and seat-count rules); `ApplicationStartupIT` checks the booking history table and both tables exist; `ModuleBoundaryTest` still passes.
- **ADR:** none (follows ADR 002)
