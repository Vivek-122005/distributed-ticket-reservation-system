# ADR 004: Orchestrated saga for the booking money flow

- **Status:** Accepted (2026-11-18)
- **Deciders:** Vivek Vishnoi, Yeeshu
- **Plan parts:** P4.4–P4.6 (saga, timeouts, recovery), P4.8 (payment reconciliation)

## Context

A booking touches three owners of state: **inventory** (the seats), **booking** (the booking) and **payment**, which in turn depends on a third-party provider. There is no transaction that spans them. After extraction (ADR 002, P7.8–P7.9) they are separate services with separate databases, and the provider is outside our control altogether.

The flow must survive everything in between: a declined card, a provider that takes the money and never answers, a user who pays one second after the hold expired, a user who cancels while paying, and a process that dies between "money captured" and "seats confirmed". Two invariants must hold throughout: **no seat is sold twice** (N1) and **no one pays without either seats or a refund** (N2, invariant 3).

## Decision

1. **An orchestrated saga** (`booking.application.BookingSaga`) owns the flow: hold → payment intent → await capture → confirm seats → booking confirmed. Compensations: release the seats (declined), cancel the intent (expired or cancelled before paying), refund (captured but the seats were lost).
2. **The saga's state is a row** (`booking.booking_sagas`: step, attempts, `next_retry_at`, last error), created in the same transaction as the booking.
3. **Three rules make it crash-safe:**
   - **Save the step, then do it.** After a crash, the saved step is exactly what to repeat.
   - **Every step is idempotent:** confirm, release, cancel and refund in inventory and payment, and every booking transition. Repeating any step is always safe.
   - **A failed step is a retry, not an error.** It is recorded with backoff (1–60 s), and `SagaRecoveryJob` resumes due sagas on any replica (`FOR UPDATE SKIP LOCKED` + lease).
4. **Payment outcomes arrive as events** (`PaymentCaptured`, `PaymentFailed`), and payment never calls booking. Because an event can be lost (a crash, or later a broker problem), a waiting saga also **re-checks the payment every 30 s**.
5. **`UNKNOWN` is waited for, never guessed.** While a payment is `PROCESSING` or `UNKNOWN`, the booking is not expired. Payment reconciliation (P4.8) asks the provider by order id and turns `UNKNOWN` into `CAPTURED` or `FAILED`. The saga then continues as usual, and the late-payment path refunds a capture that came too late.

## Options considered

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Orchestration** (one coordinator, persisted state) | One place shows where every booking is and why; compensations are explicit code; easy to test every path with fakes | The coordinator is a central dependency; it must be made crash-safe (rules above) | ✅ Chosen for the money flow |
| **Choreography** (each module reacts to the others' events) | No coordinator; loose coupling | The flow exists only implicitly across listeners; "why is booking X stuck?" needs every module's logs; compensation logic is scattered | Used later for side effects (notifications, tickets, analytics, P5) |
| **2PC / XA** across the three schemas | Atomic, simple to reason about | Blocks while any participant is slow; a coordinator crash leaves locks held; payment providers and Kafka don't take part in XA; impossible after extraction | ❌ |
| **One local transaction** (works while it is a monolith) | Trivial today | Stops working the day inventory or payment is extracted, and never covers the provider | ❌ Designing for the split now |

## Consequences

- **Good:** every failure mode has a named path and a test: `BookingSagaTest` (all paths, in-memory), `BookingSagaIT` (end to end), `SagaRecoveryIT` (crash states, four competing replicas, reconciliation).
- **Good:** recovery is just "run the saved step again", the same code as the normal path, so it isn't an untested special case.
- **Cost:** the booking may stay `PENDING_PAYMENT` past its hold expiry while a payment is `UNKNOWN`, at most until reconciliation runs (about 30–40 s by default). The seats stay held during that time, which is what we want if the money did move.
- **Cost:** in-process events are synchronous for now: checkout returns with the booking already confirmed (or refunded). The outbox (P5.2) makes them durable and asynchronous without changing the saga.
- **Follow-ups:** `BookingConfirmed` and `BookingExpired` events through the outbox (P5); HTTP adapters with circuit breakers for `SeatReservations` and `Payments` at extraction (P7.8–P7.9); a `payments_unknown` gauge and an alert (P8).
