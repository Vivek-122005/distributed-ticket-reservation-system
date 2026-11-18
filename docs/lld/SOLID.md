# SOLID registry

How each SOLID principle is applied in this codebase: **where**, **how**, and **what would happen without it**. It also lists where the current code does **not** follow a principle yet, and which plan part fixes that. An honest list of known gaps is more useful in a review than a claim of perfection.

**Rule** (plan §12, rule 6): a change that makes a notable SOLID decision, or fixes a deviation listed below, updates this file in the same commit.

Legend: ✅ applied in code · ⬜ planned (plan part) · ⚠️ known deviation

---

## S: Single Responsibility Principle
*A class has one reason to change.*

| Where | How it is applied | Status |
|---|---|---|
| `inventory.web.InventoryController` | Only HTTP concerns: binding, validation trigger, status code, DTO mapping | ✅ |
| `inventory.application.InventoryFacade` | Only delegation from the module API to use cases | ✅ P0.14 |
| `inventory.application.CreateHoldUseCase` | Only the hold use case (renamed from `InventoryApplicationService`) | ✅ P0.14 |
| `ApiExceptionHandler` | Only exception → error-response mapping | ✅ |
| `ConditionalUpdateStrategy` | Only the hot seat-claim statement | ✅ P1.1 |
| `JdbcHoldStore`, `JdbcShowStore` | Only row ↔ aggregate mapping for one table each | ✅ P1.3 |
| `Hold`, `ShowSeat`, `Show` | Only their own state rules; no persistence or HTTP | ✅ P1.3 |
| Use cases `CreateHoldUseCase`, `ConfirmHoldUseCase`, `ReleaseHoldUseCase`, `GetHoldUseCase`; `ExpireHoldsJob` | One class per use case, replacing the single `InventoryApplicationService`; the shared result mapping lives once in `HoldViews`. Expiry: *what* to do is in `ExpireHoldsJob`, *when* (scheduling, batching, poison isolation) in `infrastructure.ScheduledHoldExpiry` | ✅ P1.4, P2.1 |
| `BookingSaga` vs `Booking` | The saga decides *what to do next*; the aggregate decides *which transitions are legal* | 🔨 `Booking` ✅ P3.2; saga ⬜ P4.4 |
| `BookingSchemaMigration` | Only migrates the booking schema; the module owns its history table, inventory's Flyway is untouched | ✅ P3.1 |
| `SeatCacheReconciler` vs `ScheduledSeatCacheReconciliation` | The first decides what is drift and repairs it; the second only decides when to run | ✅ P2.5 |
| `PaymentReconciliationJob` | Only resolves `UNKNOWN` payments; never creates or refunds them itself | ⬜ P4.8 |

**Without it:** one "BookingService" class doing HTTP, rules, retries and SQL. Every change risks breaking an unrelated path, and the concurrency logic can't be tested on its own.

## O: Open/Closed Principle
*Open for extension, closed for modification.*

| Where | How it is applied | Status |
|---|---|---|
| `SeatLockingStrategy` | A new locking approach is a new class plus one `case` in `SeatLockingConfig` and a config value; `CreateHoldUseCase` does not change (five strategies exist) | ✅ P1.7 |
| `PaymentGateway` | Adding Stripe next to Razorpay is a new adapter, not an edit to payment logic | ⬜ P4.1 |
| `IdempotentEventConsumer` | A new consumer extends the base; dedup and retry code stays untouched | ⬜ P5.5 |
| Kafka pub/sub | A new side effect (for example SMS) is a new consumer group; producers are unchanged | ⬜ P5 |

**Without it:** `switch (strategy)` blocks inside use cases, so every new option edits tested code.

## L: Liskov Substitution Principle
*Any implementation can replace its interface without surprising the caller.*

| Where | How it is applied | Status |
|---|---|---|
| All `SeatLockingStrategy` implementations | One shared contract suite (`SeatLockingContract`: exactly-N winners, no partial holds, no deadlocks, expired-seat stampede, confirm vs release) runs against **all five** strategies via `*LockingIT` subclasses that only select the strategy | ✅ P1.6–P1.7 |
| `MockPaymentGateway` / `SandboxPaymentGateway` | Same contract tests: idempotent `capture`; `fetchStatus` returns the same outcome as the original call; timeouts surface as `GatewayTimeout`, never as a fake failure | ⬜ P4.1 |
| `InProcessInventoryAdapter` / `HttpInventoryAdapter` | Same results and error codes (`SEAT_UNAVAILABLE`, `HOLD_EXPIRED`), so extraction does not change booking behaviour | ⬜ P7.8 |

**Without it:** the mock behaves "nicer" than the real gateway, tests pass, and production breaks. Contract tests are how LSP is enforced, not just claimed.

## I: Interface Segregation Principle
*Clients depend only on the methods they use.*

| Where | How it is applied | Status |
|---|---|---|
| `InventoryApi` (booking's view) vs `CatalogAdminApi` (admin) | Booking sees `hold / confirm / release / get` only; venue and show creation is a separate interface | ✅ `InventoryApi` P1.4; `CatalogAdminApi` ⬜ P6.9 |
| `PaymentApi` vs `PaymentGateway` | Booking uses `PaymentApi` (create intent, refund); only the payment module sees the PSP-level `PaymentGateway` | ⬜ P4.1 |
| Read vs write ports (`CatalogQuery` / `SeatMapQuery` vs `InventoryApi`) | Reads are served by `application.CatalogQueries` over the read-only port `CatalogReadStore`; the hold write path shares no interface with them, so the seat map can move to Redis (P2.3) or a replica (P7.5) alone | ✅ P1.5; Redis ✅ P2.3 (the decorator `CachedCatalogReadStore` replaces the read store; no write-path class changed) |

**Without it:** one fat `InventoryService` interface. Every consumer recompiles for unrelated changes, and extraction has to expose everything.

## D: Dependency Inversion Principle
*High-level policy depends on abstractions; details depend on them too.*

| Where | How it is applied | Status |
|---|---|---|
| Use cases → outbound ports | `CreateHoldUseCase` depends on `application.port.{ShowStore, HoldStore, SeatLockingStrategy}`; package-private JDBC adapters in `infrastructure` implement them. ArchUnit `applicationDependsOnPortsNotAdapters` | ✅ P1.3 (more strategies P1.7) |
| `ExpireHoldsJob` → `InventoryEventPublisher` | The job publishes domain events through a port; today Spring events, later the outbox (P5.2), with no change to the job | ✅ P2.1 |
| `FilteredCreateHold` → `SeatFilter` | The fast path is a port with two implementations (Redis, pass-through Null Object); `CreateHoldUseCase` didn't change at all (OCP) | ✅ P2.4 |
| Facade → `HoldMetrics` | Business metrics go through an application port; Micrometer lives only in `infrastructure.MicrometerHoldMetrics` | ✅ P1.9 |
| `InventoryController` → `InventoryApi` | The HTTP adapter depends on the module interface, not on the use-case class | ✅ P0.14 |
| Booking → `InventoryApi` | Booking depends on the interface; Spring wires the in-process or HTTP adapter | ⬜ P3.3 |
| Domain → nothing | Domain classes import no Spring, Jackson, JPA or JDBC, enforced by ArchUnit (`ModuleBoundaryTest.domainIsFrameworkFree`) | ✅ P1.3 |

**Without it:** business code imports JPA and HTTP clients directly, so it can't be unit-tested, can't swap strategies and can't be extracted.

---

## Known deviations (to fix)

Found by reviewing the bootstrap code (`inventory-service`, commit `37138c8`) against the rules above:

| # | Deviation | Principle | Fixed in |
|---|---|---|---|
| 1 | ~~`InventoryApplicationService` returns `api.HoldResponse`, so the application layer depends on the inbound adapter~~ | DIP | ✅ **Fixed in P0.14:** the use case returns `api.HoldResult`; `web.InventoryController` maps it to `HoldResponse`. ArchUnit rule `applicationDoesNotDependOnWeb` prevents regression |
| 2 | ~~`CreateHoldUseCase` uses Spring Data repositories from `infrastructure` directly, with no port interface~~ | DIP | ✅ **Fixed in P1.3:** ports `HoldStore`, `ShowStore`, `SeatLockingStrategy` in `application.port`; JDBC adapters in `infrastructure`; ArchUnit rule `applicationDependsOnPortsNotAdapters` |
| 3 | ~~`domain.Hold` carries JPA annotations, so the domain depends on persistence~~ | DIP | ✅ **Fixed in P1.3:** `Hold` is a plain class; `JdbcHoldStore` maps rows to it. JPA was removed from the module, and `domainIsFrameworkFree` now also bans `jakarta.persistence` and `java.sql` |
| 4 | ~~One service class will grow to hold, confirm and release~~ | SRP | ✅ **Fixed in P0.14:** `CreateHoldUseCase` behind `InventoryFacade`; confirm/release get their own use cases in P1.4 |
| 5 | ~~Stored `HoldStatus.ACTIVE` vs API `HELD`: two names for one state~~ | (consistency) | ✅ **Fixed in P1.0:** the new `inventory.holds` table stores `HELD` (with a `CHECK`), and `HoldStatus` uses the API names. Pulled forward because creating a new table with a known-wrong value would only need another migration in P1.3 |
| 6 | ~~No ArchUnit rules yet, so the boundaries are convention only~~ | — | ✅ **Fixed in P0.14:** 9 rules in `ModuleBoundaryTest`, plus `ModuleBoundaryRulesBiteTest`, which proves a planted violation fails |
