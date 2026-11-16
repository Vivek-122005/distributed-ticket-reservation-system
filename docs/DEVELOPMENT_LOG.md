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
