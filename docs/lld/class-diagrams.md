# Class diagrams (target design)

The class-level design of the three core modules and the shared `common` library. These diagrams are the **target**. Each class is built in the plan part noted next to it, and the code must match the diagram or the diagram is updated in the same commit. The pattern names refer to [DESIGN_PATTERNS.md](DESIGN_PATTERNS.md); the principles to [SOLID.md](SOLID.md).

**Conventions**
- `<<interface>>` = a port (application layer) or a published module API.
- `<<aggregate>>` = a consistency boundary: changed only through its own methods, one transaction at a time.
- Domain classes have no Spring or JPA imports (DIP). Persistence entities are separate classes in `infrastructure`.

---

## 1. Inventory module

### 1.1 Domain

```mermaid
classDiagram
    direction LR
    class Venue {
      +UUID id
      +String name
      +String city
      +addSection(code, rows, seatsPerRow, priceMinor) VenueSection
    }
    class VenueSection {
      +UUID id
      +String code
      +long defaultPriceMinor
    }
    class Seat {
      +UUID id
      +String seatCode
      +int row
      +int col
    }
    class Event {
      <<aggregate>>
      +UUID id
      +String name
      +EventStatus status
      +publish()
      +close()
    }
    class Show {
      <<aggregate>>
      +UUID id
      +UUID eventId
      +UUID venueId
      +Instant startsAt
      +ShowStatus status
      +openSale()
      +closeSale()
      +isOnSale() boolean
    }
    class ShowSeat {
      +UUID id
      +UUID showId
      +String seatCode
      +SeatStatus status
      +UUID holdId
      +Instant holdExpiresAt
      +long priceMinor
      +hold(holdId, expiresAt)
      +book(holdId)
      +release(holdId)
    }
    class Hold {
      <<aggregate>>
      +UUID id
      +UUID showId
      +UUID userId
      +HoldStatus status
      +Instant expiresAt
      +confirm(now)
      +release()
      +expire(now)
      +isExpired(now) boolean
    }
    class SeatStatus {
      <<enumeration>>
      AVAILABLE
      HELD
      BOOKED
      +canTransitionTo(SeatStatus) boolean
    }
    class HoldStatus {
      <<enumeration>>
      HELD
      CONFIRMED
      RELEASED
      EXPIRED
      +canTransitionTo(HoldStatus) boolean
    }
    Venue "1" *-- "1..*" VenueSection
    VenueSection "1" *-- "1..*" Seat
    Event "1" --> "1..*" Show
    Show "1" *-- "1..*" ShowSeat
    ShowSeat ..> Seat : copy of
    Show "1" --> "*" Hold
    Hold "1" --> "1..8" ShowSeat : holds
    ShowSeat --> SeatStatus
    Hold --> HoldStatus
```

- **State pattern:** `SeatStatus.canTransitionTo` and `HoldStatus.canTransitionTo` hold the legal transitions (plan §4.3). `ShowSeat` and `Hold` methods call them and throw `IllegalStateTransitionException` otherwise. (P1.3)
- **Why `ShowSeat` is not inside the `Hold` aggregate:** seats are claimed by a set-based conditional `UPDATE` for speed, not by loading aggregates. The aggregate methods are used in unit tests and the expiry/confirm paths; the hot claim path enforces the same rules in SQL.

### 1.2 Application layer and ports

```mermaid
classDiagram
    direction TB
    class InventoryApi {
      <<interface>>
      +hold(HoldCommand) HoldResult
      +confirm(holdId, userId) HoldResult
      +release(holdId, userId) HoldResult
      +get(holdId, userId) HoldResult
    }
    class CatalogAdminApi {
      <<interface>>
      +createVenue(CreateVenueCommand) VenueView
      +createEvent(CreateEventCommand) EventView
      +createShow(CreateShowCommand) ShowView
      +changeShowStatus(showId, ShowStatus) ShowView
    }
    class SeatMapQuery {
      <<interface>>
      +seatMap(showId, section) SeatMapView
    }
    class CreateHoldUseCase {
      -SeatLockingStrategy locking
      -HoldStore holds
      -OutboxWriter outbox
      -Clock clock
      +execute(HoldCommand) HoldResult
    }
    class ConfirmHoldUseCase {
      +execute(holdId, userId) HoldResult
    }
    class ReleaseHoldUseCase {
      +execute(holdId, userId) HoldResult
    }
    class ExpireHoldsJob {
      +runBatch(limit) int
    }
    class SeatLockingStrategy {
      <<interface>>
      +claim(showId, seatCodes, holdId, userId, expiresAt) ClaimResult
    }
    class ConditionalUpdateStrategy
    class PessimisticLockStrategy
    class OptimisticLockStrategy
    class RedisLockStrategy
    class RedisLuaStrategy
    class HoldStore {
      <<interface>>
      +createHeld(id, showId, userId, seatCodes, ttl) Hold
      +find(holdId) Optional~Hold~
      +lock(holdId) Optional~Hold~
      +saveStatus(Hold)
      +findExpired(limit) List~Hold~
    }
    class ShowStore {
      <<interface>>
      +find(showId) Optional~Show~
    }
    class ShowSeatStore {
      <<interface>>
      +bookSeatsOf(holdId) int
      +releaseSeatsOf(holdId) int
      +prices(showId, seatCodes) List~PricedSeat~
    }
    class DatabaseClock {
      <<interface>>
      +now() Instant
    }
    InventoryApi <|.. InventoryFacade
    InventoryFacade --> CreateHoldUseCase
    InventoryFacade --> ConfirmHoldUseCase
    InventoryFacade --> ReleaseHoldUseCase
    CreateHoldUseCase --> SeatLockingStrategy
    CreateHoldUseCase --> HoldStore
    SeatLockingStrategy <|.. ConditionalUpdateStrategy
    SeatLockingStrategy <|.. PessimisticLockStrategy
    SeatLockingStrategy <|.. OptimisticLockStrategy
    SeatLockingStrategy <|.. RedisLockStrategy
    SeatLockingStrategy <|.. RedisLuaStrategy
    HoldStore <|.. JdbcHoldStore
    ShowStore <|.. JdbcShowStore
    CreateHoldUseCase --> ShowStore
    ConfirmHoldUseCase --> HoldStore
    ConfirmHoldUseCase --> ShowSeatStore
    ConfirmHoldUseCase --> DatabaseClock
    ReleaseHoldUseCase --> ShowSeatStore
    ShowSeatStore <|.. JdbcShowSeatStore
    DatabaseClock <|.. JdbcDatabaseClock
    ExpireHoldsJob --> HoldStore
```

| Class | Pattern / principle | Plan |
|---|---|---|
| `InventoryApi`, `CatalogAdminApi`, `SeatMapQuery` | Facade + ISP: booking sees only hold operations; admin and read paths are separate interfaces | P0.14, P1.5, P6.9 |
| `InventoryFacade` | Facade: the module's single entry point, delegating to one use case per operation (SRP) | P0.14 |
| `SeatLockingStrategy` + 5 implementations | Strategy + OCP; one contract test suite for all (LSP) | P1.7 |
| `HoldStore` ← `JdbcHoldStore`, `ShowStore` ← `JdbcShowStore` | Repository behind a port (DIP); JDBC because every query needs explicit SQL and the DB clock (`createHeld` computes expiry in Postgres, SRS A3) | P1.3 ✅ |

---

## 2. Booking module

```mermaid
classDiagram
    direction TB
    class Booking {
      <<aggregate>>
      +UUID id
      +UUID userId
      +UUID showId
      +UUID holdId
      +BookingStatus status
      +long amountMinor
      +long version
      +markConfirmed()
      +markFailed()
      +markExpired()
      +cancel()
      +requireRefund()
      +markRefunded()
    }
    class BookingSeat {
      +UUID seatId
      +String seatCode
      +long priceMinor
    }
    class BookingStatus {
      <<enumeration>>
      PENDING_PAYMENT
      CONFIRMED
      FAILED
      EXPIRED
      CANCELLED
      REFUND_PENDING
      REFUNDED
      +canTransitionTo(BookingStatus) boolean
    }
    class BookingSaga {
      +UUID bookingId
      +SagaStep step
      +int attempts
      +Instant nextRetryAt
      +onHoldCreated()
      +onPaymentCaptured()
      +onPaymentFailed()
      +onHoldExpired()
      +compensate()
    }
    class SagaStep {
      <<enumeration>>
      HOLDING
      AWAITING_PAYMENT
      CONFIRMING_SEATS
      DONE
      COMPENSATING
      COMPENSATED
    }
    class CreateBookingUseCase {
      +execute(CreateBookingCommand) BookingView
    }
    class CancelBookingUseCase {
      +execute(bookingId, userId) BookingView
    }
    class BookingQueries {
      +history(userId, status, page) Page~BookingView~
      +get(bookingId, userId) BookingView
    }
    class SagaRecoveryJob {
      +resumeDue(limit) int
    }
    class IdempotencyStore {
      <<interface>>
      +begin(key, userId, requestHash) IdempotencyResult
      +complete(key, userId, status, body)
    }
    class BookingStore {
      <<interface>>
    }
    class InventoryApi {
      <<interface>>
    }
    class PaymentApi {
      <<interface>>
    }
    Booking "1" *-- "1..8" BookingSeat
    Booking --> BookingStatus
    BookingSaga --> SagaStep
    BookingSaga --> Booking : drives
    CreateBookingUseCase --> IdempotencyStore
    CreateBookingUseCase --> InventoryApi
    CreateBookingUseCase --> PaymentApi
    CreateBookingUseCase --> BookingStore
    BookingSaga --> InventoryApi
    BookingSaga --> PaymentApi
    SagaRecoveryJob --> BookingSaga
    CancelBookingUseCase --> BookingSaga
```

| Class | Pattern / principle | Plan |
|---|---|---|
| `Booking` + `BookingStatus` | State; optimistic `version` for concurrent webhook/expiry updates | P3.2 |
| `BookingSaga` | Saga orchestrator: it decides the next step, while `Booking` decides which transitions are legal (SRP) | P4.4 |
| `IdempotencyStore` | Idempotency key behind a port; Postgres adapter (DIP) | P3.4 |
| `InventoryApi`, `PaymentApi` | Booking depends on interfaces only; the in-process adapter now, HTTP after extraction (DIP, Adapter) | P3.3, P7.8 |
| `BookingQueries` | Read side kept apart from commands; history never calls inventory (`BookingSeat` copies) | P3.8 |

---

## 3. Payment module

```mermaid
classDiagram
    direction TB
    class Payment {
      <<aggregate>>
      +UUID id
      +UUID bookingId
      +PaymentStatus status
      +long amountMinor
      +String gatewayRef
      +startProcessing()
      +markCaptured(gatewayRef)
      +markFailed(reason)
      +markUnknown()
      +markRefunded()
    }
    class PaymentStatus {
      <<enumeration>>
      INITIATED
      PROCESSING
      CAPTURED
      FAILED
      UNKNOWN
      REFUNDED
      +canTransitionTo(PaymentStatus) boolean
    }
    class PaymentApi {
      <<interface>>
      +createIntent(bookingId, amountMinor) PaymentView
      +cancelIntent(bookingId)
      +refund(bookingId)
    }
    class PaymentGateway {
      <<interface>>
      +createOrder(idempotencyKey, amountMinor) GatewayOrder
      +capture(orderId) GatewayResult
      +refund(orderId) GatewayResult
      +fetchStatus(orderId) GatewayResult
    }
    class MockPaymentGateway {
      -FailureProfile profile
    }
    class SandboxPaymentGateway {
      -RazorpayClient client
    }
    class WebhookHandler {
      +handle(signedPayload)
    }
    class PaymentReconciliationJob {
      +reconcile(olderThan, limit) int
    }
    class FailureProfile {
      +double declineRate
      +double timeoutRate
      +double duplicateWebhookRate
      +Duration latency
    }
    Payment --> PaymentStatus
    PaymentApi <|.. PaymentFacade
    PaymentFacade --> PaymentGateway
    PaymentGateway <|.. MockPaymentGateway
    PaymentGateway <|.. SandboxPaymentGateway
    MockPaymentGateway --> FailureProfile
    WebhookHandler --> Payment
    PaymentReconciliationJob --> PaymentGateway
    PaymentReconciliationJob --> Payment
```

| Class | Pattern / principle | Plan |
|---|---|---|
| `PaymentGateway` + Mock/Sandbox | Adapter + OCP; shared contract tests keep the mock as strict as the sandbox (LSP) | P4.1 |
| `Payment` + `PaymentStatus` | State; `UNKNOWN` is an explicit state, not a failure | P4.2 |
| `WebhookHandler` | Idempotent receiver; verifies the signature, ignores stale transitions | P4.3 |
| `PaymentReconciliationJob` | Uses only `fetchStatus`; never charges or refunds by itself (SRP) | P4.8 |

---

## 4. `common` library

```mermaid
classDiagram
    direction LR
    class EventEnvelope~T~ {
      +UUID eventId
      +String eventType
      +int schemaVersion
      +String aggregateType
      +UUID aggregateId
      +Instant occurredAt
      +String traceId
      +T payload
    }
    class EventEnvelopeFactory {
      +create(aggregateType, aggregateId, eventType, payload) EventEnvelope
    }
    class OutboxWriter {
      +append(EventEnvelope)
    }
    class OutboxRelay {
      +publishBatch(limit) int
    }
    class IdempotentEventConsumer~T~ {
      <<abstract>>
      +onMessage(EventEnvelope)
      #handle(T payload)*
    }
    class NotificationConsumer
    class TicketConsumer
    EventEnvelopeFactory ..> EventEnvelope : creates
    OutboxWriter ..> EventEnvelope
    OutboxRelay ..> OutboxWriter : reads rows written by
    IdempotentEventConsumer <|-- NotificationConsumer
    IdempotentEventConsumer <|-- TicketConsumer
```

| Class | Pattern | Plan |
|---|---|---|
| `EventEnvelopeFactory` | Factory: identical metadata on every event | P5.1 |
| `OutboxWriter` / `OutboxRelay` | Transactional Outbox | P5.2–P5.3 |
| `IdempotentEventConsumer` | Template Method: `onMessage` does dedup, then `handle`, then metrics; subclasses implement only `handle` | P5.5 |

---

## 5. Module dependency rules (enforced by ArchUnit, P0.14)

```mermaid
flowchart LR
    APP[ticketing-app] --> INV[inventory]
    APP --> BOOK[booking]
    APP --> PAY[payment]
    BOOK -->|"only ..inventory.api"| INV
    BOOK -->|"only ..payment.api"| PAY
    INV --> COM[common]
    BOOK --> COM
    PAY --> COM
```

1. `booking` may depend on `inventory.api` and `payment.api` only, never on their `application`, `domain` or `infrastructure` packages.
2. `inventory` and `payment` never depend on `booking`. Booking reacts to their events instead, which keeps the dependency graph acyclic.
3. `..domain..` imports nothing from Spring, JPA or Jackson.
4. `common` depends on no module.
