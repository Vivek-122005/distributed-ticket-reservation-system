# High-Level Design (HLD)

This folder holds the system's high-level design. [`../PROJECT_PLAN.md`](../PROJECT_PLAN.md) gives the summary and roadmap; these documents go deeper and are the reference for design reviews.

| # | Document | Answers |
|---|---|---|
| 1 | [Architecture](01-architecture.md) | What are the components, who talks to whom, sync vs async, how is it deployed? |
| 2 | [Capacity estimation](02-capacity-estimation.md) | How much traffic, storage, connections, partitions? Where is the bottleneck? |
| 3 | [Request flows](03-request-flows.md) | What happens step by step for each important scenario, including failures? |
| 4 | [Data model](04-data-model.md) | Which service owns which tables, what constraints enforce the invariants, which indexes? |
| 5 | [Consistency & failure model](05-consistency-and-failure-model.md) | What is guaranteed, where is it strong vs eventual, what happens when each component fails? |

Diagrams use [Mermaid](https://mermaid.js.org/), which GitHub renders natively.

**Status:** the design describes the *target* system. Parts not yet implemented are tagged with the plan part that builds them (e.g. `P4.4`).
