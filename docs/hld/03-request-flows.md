# 3. Request flows

Each flow lists the steps, the guarantee it provides, and what happens on failure. Plan part tags show where it is built. In stage 1 (ADR 002) arrows between Booking, Inventory and Payment are in-process port calls; in stage 2 the same arrows become HTTP calls.

---

## 3.1 Joining the waiting room and being admitted (P6.4–P6.5)

```mermaid
sequenceDiagram
    participant U as User
    participant G as Gateway
    participant W as Waiting Room
    participant R as Redis
    U->>G: POST /shows/{id}/queue
    G->>W: enqueue(userId, showId)
    W->>R: ZADD NX queue:{showId} <arrivalTs> userId
    W-->>U: position, ETA
    loop every 5 s
      U->>G: GET /shows/{id}/queue/status
      G->>W: status
      W->>R: ZRANK / admitted:{showId}:{userId}?
      W-->>U: position (or admission token)
    end
    Note over W,R: Admission worker, every 1 s
    W->>R: free = cap − SCARD active:{showId}
    W->>R: ZPOPMIN queue:{showId} free
    W->>R: SADD active:{showId} users, store token (TTL 10 min)
```

- **Guarantee:** FIFO by arrival time; one entry per user (`ZADD NX`); never more than `cap` active sessions.
- **Admission token:** HMAC-signed `{userId, showId, exp}`; the gateway verifies it **statelessly** on every booking call — no Redis lookup on the hot path.
- **Failure:** Redis down → waiting room returns `503 Retry-After`; booking path stays closed for that event rather than letting the full crowd through (fail closed protects correctness and the DB).

---

## 3.2 Holding seats (P1.1–P1.2, P2.4)

```mermaid
sequenceDiagram
    participant B as Booking
    participant I as Inventory
    participant R as Redis
    participant P as Postgres
    B->>I: hold(showId s, seats [A1,A2]) via InventoryApi
    I->>R: Lua: all seats free in cache? mark PENDING
    alt any seat taken in cache
      I-->>B: 409 SEAT_UNAVAILABLE (fast reject, ~1 ms)
    else
      I->>P: BEGIN
      I->>P: INSERT hold (HELD, expires_at = now()+5m)
      I->>P: UPDATE show_seats SET status='HELD', hold_id=…<br/>WHERE show_id=s AND seat_code = ANY(sorted seats)<br/>AND (status='AVAILABLE' OR (status='HELD' AND hold_expires_at < now()))
      alt rows updated == seats requested
        I->>P: INSERT outbox(SeatsHeld) then COMMIT
        I->>R: mark HELD
        I-->>B: 201 holdId, expiresAt
      else fewer rows
        I->>P: ROLLBACK
        I->>R: undo PENDING marks
        I-->>B: 409 SEAT_UNAVAILABLE
      end
    end
```

- **Guarantee:** all-or-nothing; a seat can be claimed by exactly one transaction because the `UPDATE`'s `WHERE` is re-evaluated under the row lock (compare-and-set). Losers see 0 rows instantly.
- **Why sorted seats:** two requests for `{A1,A2}` and `{A2,A1}` lock rows in the same order → no deadlock cycle.
- **Lazy expiry in the `WHERE`:** an expired hold is reclaimable immediately even if the expiry worker hasn't run → correctness never depends on worker timing.
- **Redis is only a filter.** If Redis wrongly says "free", Postgres still rejects. If Redis wrongly says "taken" (drift), the reconciliation job (P2.5) repairs it; worst case is a false rejection, never an oversell.

---

## 3.3 Booking and payment — success path (P3, P4)

```mermaid
sequenceDiagram
    participant U as User
    participant B as Booking
    participant I as Inventory
    participant PS as Payment
    participant K as Kafka
    U->>B: POST /bookings  Idempotency-Key: k1
    B->>B: INSERT idempotency_keys(k1, IN_PROGRESS) — new
    B->>I: hold seats
    I-->>B: holdId, expiresAt
    B->>B: TX: booking PENDING_PAYMENT + saga(step=AWAIT_PAYMENT) + outbox(BookingCreated)
    B->>PS: create intent (idempotency = bookingId)
    PS-->>B: paymentToken
    B->>B: store response for k1 (COMPLETED)
    B-->>U: 201 bookingId, paymentToken, expiresAt
    U->>PS: pay (mock checkout)
    PS->>B: webhook PaymentCaptured(bookingId) [signed]
    B->>I: POST /holds/{holdId}/confirm
    I->>I: TX: seats HELD→BOOKED where hold_id=… + outbox(SeatsBooked)
    I-->>B: 200
    B->>B: TX: booking CONFIRMED + saga DONE + outbox(BookingConfirmed)
    B--)K: relay → booking.events
    K--)K: notification · ticket · analytics consume
```

---

## 3.4 Payment declined (P4.4)

1. Webhook `PaymentFailed(bookingId)` arrives.
2. Saga step → `COMPENSATING`; Booking calls `DELETE /holds/{holdId}` (idempotent).
3. Inventory: seats `HELD → AVAILABLE` (conditional on `hold_id`), outbox `HoldReleased`.
4. Booking: `PENDING_PAYMENT → FAILED`, outbox `BookingFailed`.
5. If the release call fails, the saga retries with exponential backoff; even if it never succeeds, the hold **expires on its own** after 5 minutes — compensation has a natural safety net.

---

## 3.5 Hold expires before payment (P2.1, P4.5)

```mermaid
sequenceDiagram
    participant EW as Expiry worker (inventory)
    participant P as Postgres
    participant K as Kafka
    participant B as Booking
    EW->>P: SELECT holds WHERE status='HELD' AND expires_at < now()<br/>FOR UPDATE SKIP LOCKED LIMIT 500
    EW->>P: TX: seats → AVAILABLE, hold → EXPIRED, outbox(HoldExpired)
    P--)K: relay → inventory.events
    K--)B: HoldExpired(holdId)
    B->>B: booking PENDING_PAYMENT → EXPIRED then cancel payment intent
```

- `SKIP LOCKED` lets several worker replicas process different batches without blocking each other.

---

## 3.6 Late payment — captured after expiry (P4.5)

1. Webhook `PaymentCaptured` arrives for a booking already `EXPIRED`.
2. Booking does **not** try to re-hold the seats (they may already belong to someone else).
3. Booking → `REFUND_PENDING`; calls Payment `refund(bookingId)` (idempotent).
4. On `RefundCompleted` → `REFUNDED`; outbox `BookingRefunded` → user notified.
- **Guarantee:** a customer is never charged without either a ticket or a refund (invariant 3).

---

## 3.7 Client retry after timeout (P3.4)

| Situation when retry with same key arrives | Response |
|---|---|
| Key not seen | Process normally |
| Key `COMPLETED`, same request hash | Return stored response (same `bookingId`) — no new booking |
| Key `IN_PROGRESS` (first request still running) | `409 Conflict` + `Retry-After: 1` |
| Key exists, **different** request hash | `422 Unprocessable` — key reuse with different body is a client bug |
| Key expired (> 24 h) | Treated as new |

---

## 3.8 Crash between payment capture and seat confirmation (P4.6)

1. Booking receives `PaymentCaptured`, persists saga step `CONFIRMING_SEATS`, then crashes before calling Inventory.
2. Payment service retries the webhook (at-least-once) **and** the saga recovery job scans sagas stuck in a step past `next_retry_at`.
3. Whichever arrives first resumes the saga: `confirm hold` is idempotent, so a duplicate call is harmless.
4. If the hold expired in the meantime → falls into the late-payment path (3.6).

---

## 3.9 Event consumption with retry and DLQ (P5.5, P5.9)

```mermaid
flowchart LR
    T[booking.events] --> C[Consumer]
    C -->|already in processed_events| ACK[ack, skip]
    C -->|success: side effect + insert processed_events in one tx| ACK2[ack]
    C -->|transient error| R1[booking.events.retry.1m]
    R1 --> C2[Retry consumer] -->|fails again| R5[retry.5m] --> C3[Retry consumer] -->|fails again| DLQ[booking.events.dlq]
    DLQ --> OPS[Alert + replay endpoint]
```

- Retries go to separate topics so one poison message doesn't block the partition (non-blocking retry).
- DLQ non-empty raises an alert (P8.7); messages can be replayed after a fix.

---

## 3.10 Outbox relay (P5.3)

```mermaid
sequenceDiagram
    participant R as Relay (any replica)
    participant P as Postgres
    participant K as Kafka
    loop every 100 ms
      R->>P: SELECT * FROM outbox WHERE published_at IS NULL<br/>ORDER BY created_at LIMIT 200 FOR UPDATE SKIP LOCKED
      R->>K: send batch (idempotent producer, acks=all), await acks
      R->>P: UPDATE outbox SET published_at = now() WHERE id IN (…) then COMMIT
    end
```

- Crash after send but before `UPDATE` → events re-sent on next poll → **duplicates are possible, loss is not.** Consumers dedupe by `eventId`.

---

## 3.11 Payment outcome unknown: gateway timeout and reconciliation (P4.8) *(rev)*

```mermaid
sequenceDiagram
    participant B as Booking
    participant PS as Payment
    participant GW as PSP (mock / sandbox)
    participant RJ as Reconciliation job
    B->>PS: capture(bookingId)
    PS->>GW: charge (idempotency key = bookingId)
    GW--xPS: timeout, no answer
    PS->>PS: payment PROCESSING → UNKNOWN
    PS-->>B: outcome UNKNOWN (booking stays PENDING_PAYMENT, seats stay HELD)
    loop every 1 min
      RJ->>PS: payments UNKNOWN, or PROCESSING older than 2 min
      RJ->>GW: fetch status by idempotency key / order id
      alt charge found
        RJ->>PS: UNKNOWN → CAPTURED + outbox(PaymentCaptured)
      else no charge
        RJ->>PS: UNKNOWN → FAILED + outbox(PaymentFailed)
      end
    end
    PS--)B: PaymentCaptured or PaymentFailed → saga continues (3.3 / 3.4 / 3.6)
```

- **Why not treat a timeout as failure?** The charge may have succeeded. Releasing the seats would then leave a customer who paid with no ticket. Retrying the charge is only safe because it carries the same idempotency key. Asking the gateway is the authoritative answer.
- **If the hold expired while the payment was `UNKNOWN`:** a later `CAPTURED` simply follows the late-payment refund path (3.6). Invariant 3 holds without extending holds.
- **Guarantee:** no payment stays `UNKNOWN` past the reconciliation window (default 15 min). After that, an alert fires for manual review (invariant 5).

---

## 3.12 User cancels a pending booking (P3.7) *(rev)*

1. `POST /bookings/{id}/cancel`. Allowed only in `PENDING_PAYMENT`; repeating it returns the same result.
2. Booking → `CANCELLED`; the saga runs the compensations: release the hold (`DELETE /holds/{id}`) and cancel the payment intent.
3. If a capture still arrives afterwards (the user paid in another tab), the booking goes `CANCELLED → REFUND_PENDING → REFUNDED`. This is the same path as a late payment.
