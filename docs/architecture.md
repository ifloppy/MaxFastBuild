# Architecture

MaxFastBuild treats the server as the only authority. A client submits shape intent, anchors and presentation options; the server regenerates the shape, filters unchanged blocks, validates every mutation, calculates materials and fees, then persists a task before execution.

## Request pipeline

1. Authenticate the internal protocol envelope and reject replay or oversized input.
2. Check permission, request rate and concurrent task limits.
3. Generate the shape and transforms on the server.
4. Reject limits, invalid worlds, unsafe heights and configured unbreakable blocks.
5. Invoke platform protection checks for every mutation.
6. Reserve inventory, including shulker contents only when enabled.
7. Write a withdrawal intent, withdraw through Vault, then record the result.
8. Persist task and escrow state before queueing.
9. Revalidate expected state and protection immediately before each mutation.
10. Record successful changes to CoreProtect under the requesting player's name (placement uses target block data; break uses pre-break expected state so lookups attribute the real block, not air).
11. Persist the cursor after each execution step.
12. On completion or cancel, refund unfinished per-block/per-area fees and return unused place materials.

Vault and SQLite cannot share an ACID transaction. The ledger therefore uses durable transaction intents and unique transaction IDs. If persistence fails after withdrawal, MaxFastBuild performs a compensating refund and leaves a recoverable ledger row if that refund fails.

Fabric server billing is intentionally disabled in the first release. The shared `EconomyService` contract is retained for a later Fabric economy adapter.
