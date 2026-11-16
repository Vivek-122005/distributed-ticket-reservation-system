# Software Requirements Specification (SRS)

**System:** Distributed High-Concurrency Ticket Reservation System
**Version:** 1.0 (2026-11-16) · **Authors:** Vivek Vishnoi, Yeeshu
**Related:** [Plan](../PROJECT_PLAN.md) · [HLD](../hld/README.md) · [API contracts](../api/README.md) · [Use cases & activities](use-cases.md)

Structure loosely follows IEEE 830, trimmed to what this project needs.

---

## 1. Introduction

### 1.1 Purpose
Specifies what the system must do and how well, so that design (HLD/LLD), tests and the final demo can be checked against one agreed list. Each requirement has an ID that tests and plan parts refer to.

### 1.2 Scope
A reservation platform for **reserved-seating shows** (concerts, cinema, sports) whose defining problem is a **flash sale**: tens of thousands of users competing for a few thousand seats at the same moment. The system must sell every seat at most once, charge every customer at most once, and stay responsive under that load.

**In scope:** catalog (venues, events, shows), seat maps, seat holds, bookings, payments through a gateway port (mock + sandbox), virtual waiting room, rate limiting, asynchronous tickets/notifications/analytics, observability, load and chaos testing, thin admin APIs.
**Out of scope:** real-money payments, rich UI, recommendations, coupons, reviews, refunds on demand after confirmation.

### 1.3 Definitions
| Term | Meaning |
|---|---|
| Event | What is performed (e.g. a concert tour) |
| Show | One performance of an event at a venue and time; the unit that is sold |
| Show seat | A physical seat as sold for one show; the unit that is locked |
| Hold | Temporary reservation of show seats for one user, expires after 5 min |
| Booking | A user's purchase built from a hold; confirmed only after payment |
| Oversell | Two confirmed bookings (or active holds) for the same show seat |
| Idempotency key | Client-chosen id that makes a retried request return the original result |
| Admission token | Signed token from the waiting room that allows a user to book a show |

### 1.4 References
ADR 001 (PostgreSQL authority), ADR 002 (modular monolith first), HLD §3 flows, §5 failure model.

---

## 2. Overall description

### 2.1 Actors
| Actor | Type | Goals |
|---|---|---|
| **Customer** | Human | Find a show, pick seats, pay, receive a ticket, see/cancel own bookings |
| **Admin** | Human | Set up venues (layout), events and shows; open/close sales |
| **Payment gateway** | External system | Accepts payment, reports the outcome via webhook, answers status lookups |
| **Scheduler** | Internal (time) | Expires holds, reconciles unknown payments, recovers stuck sagas, admits queued users |
| **Notification channel** | External system | Delivers confirmation messages (local SMTP catcher in dev) |

### 2.2 Operating environment
Java 21 / Spring Boot 3 services, PostgreSQL 16, Redis 7, Kafka 3.9 (KRaft). Local: Docker Compose. Final demo: Kubernetes (kind) on a laptop.

### 2.3 Constraints
- C1: Must run end-to-end on a single developer laptop (scaled-down numbers are reported honestly).
- C2: No real money; payment gateway only in mock or test mode.
- C3: PostgreSQL is the only authority for seat ownership (ADR 001).
- C4: All diagrams and docs live as text in the repository.

### 2.4 Assumptions
- A1: Users have a stable user id from authentication (JWT, P6.2).
- A2: The payment gateway delivers webhooks at least once and supports lookup by order id.
- A3: Clocks may skew between replicas; the database clock is the reference for expiry.

---

## 3. Functional requirements

Priority: **M** = must, **S** = should, **C** = could. "UC" links to [use-cases.md](use-cases.md).

| ID | Requirement | Priority | UC | Plan |
|---|---|---|---|---|
| FR-01 | List published events and their shows | M | UC-01 | P1.5 |
| FR-02 | Show a show's seat map with status (available / held / booked) and price | M | UC-01 | P1.5, P2.3 |
| FR-03 | Hold 1–8 seats of a show atomically (all or none) for 5 minutes | M | UC-02 | P1.1 |
| FR-04 | Release a hold on cancel or payment failure; expire it automatically after 5 minutes | M | UC-04, UC-07 | P1.4, P2.1 |
| FR-05 | Create a booking from held seats and start payment | M | UC-02 | P3, P4.4 |
| FR-06 | Confirm a booking only after payment is captured and seats are still held | M | UC-03 | P4.4 |
| FR-07 | Refund automatically when payment is captured for an expired or cancelled booking | M | UC-03 | P4.5 |
| FR-08 | Return the original result for a retried request with the same idempotency key | M | UC-02 | P3.4 |
| FR-09 | Resolve payments with unknown outcome by asking the gateway | M | UC-08 | P4.8 |
| FR-10 | Queue users fairly (FIFO) during a hot sale and admit them in batches | S | UC-05 | P6.4–P6.5 |
| FR-11 | Limit request rate per user and per IP | S | UC-05 | P6.3 |
| FR-12 | Issue a ticket and send a confirmation after booking confirmation, asynchronously | S | UC-03 | P5.6–P5.7 |
| FR-13 | Let a customer list their bookings and cancel a pending one | S | UC-04, UC-06 | P3.7–P3.8 |
| FR-14 | Let an admin create venues with seat layout, events and shows, and open/close sales | S | UC-09 | P6.9 |
| FR-15 | Push live seat-status changes to viewers of a seat map | C | UC-01 | P5.11 |
| FR-16 | Record an audit trail and booking statistics | C | — | P5.8 |

## 4. Non-functional requirements

| ID | Category | Requirement | Measured by |
|---|---|---|---|
| NFR-01 | Correctness | 0 oversold seats, 0 double bookings | Invariant checker after every load test (P9.2) |
| NFR-02 | Correctness | 0 duplicate charges; every captured payment ends in a confirmed booking or a refund | Invariant checker, chaos tests P10.6/P10.8 |
| NFR-03 | Performance | Hold p95 < 250 ms, p99 < 500 ms at admitted load | k6 (P9) |
| NFR-04 | Throughput | ≥ 3,000 hold attempts/s on laptop-scale cluster | k6 (P9) |
| NFR-05 | Availability | Booking continues when one replica of any stateless component dies | Chaos P10.1 |
| NFR-06 | Durability | No confirmed booking or emitted event is lost | Outbox + chaos P10.4 |
| NFR-07 | Fairness | Waiting room admits in arrival order | Test P6.4 |
| NFR-08 | Observability | Every request traceable end-to-end; business metrics on dashboards | Jaeger/Grafana (P8) |
| NFR-09 | Maintainability | Hexagonal modules, documented patterns, module boundaries enforced in CI | ArchUnit (P0.14), [LLD registries](../lld/DESIGN_PATTERNS.md) |
| NFR-10 | Security | Signed admission tokens and webhooks; JWT auth; no secrets in the repo | Review, tests P4.3/P6.5 |
| NFR-11 | Recoverability | Unknown payment outcomes resolved within 15 min | `payments_unknown` metric + alert (P4.8) |

## 5. Business rules

| ID | Rule |
|---|---|
| BR-01 | A user may hold at most 8 seats per show at once. |
| BR-02 | A hold lasts 5 minutes; it cannot be extended. |
| BR-03 | Seats can only be booked for shows whose status is `ON_SALE`. |
| BR-04 | A booking can be cancelled by the customer only while `PENDING_PAYMENT`. |
| BR-05 | Prices are fixed per show seat when the show is created; a booking stores the price it was sold at. |
| BR-06 | Payment that arrives after expiry or cancellation is always refunded, never used to re-book. |

## 6. Traceability

Requirements → use cases ([use-cases.md](use-cases.md)) → plan parts ([PROJECT_PLAN.md §8](../PROJECT_PLAN.md#8-phased-roadmap)) → API operations (`x-plan-part` in [`docs/api`](../api/README.md)) → tests (named after the requirement ID, from P1.6 on).
