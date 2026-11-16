# API contracts

OpenAPI 3.1 contracts, written **before** the code (P0.11) so each module is built against a fixed interface.

| Contract | Audience | Covers |
|---|---|---|
| [`booking-service.yaml`](booking-service.yaml) | Public (through the gateway) | Create booking (idempotent, admission-gated), booking history, get, cancel |
| [`inventory-service.yaml`](inventory-service.yaml) | Catalog reads: public. Holds: internal (booking saga). Admin: role `ADMIN` | Events, shows, seat map, hold / confirm / release, venue/event/show admin |
| Payment (webhooks, gateway callbacks) | PSP → payment module | Written with the payment module (P4.1) |

**Conventions**
- `x-plan-part` on every operation names the plan part that builds it. Most operations are not implemented yet.
- One error shape everywhere: `{code, message, retryable, timestamp, traceId?}`. Clients branch on `code`; `retryable` says whether the same request may succeed later.
- Money is an integer in minor units (paise), never a float.
- Mutating public calls require `Idempotency-Key`.
- In stage 1 ([ADR 002](../adr/002-modular-monolith-first.md)) booking calls inventory in-process through `InventoryApi`. The HTTP hold operations become live when inventory is extracted (P7.8).

**Validate locally**

```bash
pip install openapi-spec-validator && openapi-spec-validator docs/api/*.yaml
```
