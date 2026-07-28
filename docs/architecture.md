# Architecture

MaxFastBuild treats the server as the only authority. A client submits shape intent (mode, anchors, hollow flag, material for place); the server regenerates the shape, filters unchanged blocks, validates every mutation, calculates materials and fees, then persists a task before execution.

There is **no durable inventory escrow** in the current release: place materials and economy fees are taken when the task is accepted. Unused materials and unfinished variable fees are returned on completion, cancel, or failure settlement. `EscrowStore` exists in the storage module for a future durable-reservation path and is not wired into Paper.

Transforms (`mirror` / `array`) exist in `maxfastbuild-core` but are **not** applied on the Paper submit path.

## Request pipeline (Paper)

1. Intercept `/__mfb` (not registered in Brigadier). Primary intents are single-line `place` / `break`. Optional legacy path: `hello` session + chunked `p` envelopes with HMAC (still accepted; not required for compact commands).
2. Check `maxfastbuild.use` (and `maxfastbuild.break` for break), request rate limit, and concurrent task limits.
3. Generate the shape on the server (bounding volume must not exceed `execution.max-region-blocks` before cell enumeration).
4. Reject invalid materials, forbidden place/break blocks, and coordinates outside world height.
5. Invoke platform protection checks for every mutation (`BlockPlaceEvent` / `BlockBreakEvent`).  
   Place over a solid block first requires the same break rules (unbreakable list, tool, durability, `BlockBreakEvent`); execution breaks then places. Soft/replaceable cells (air, fluids, short plants, …) skip the break step. Per-block economy fees charge place + each required replace-break.
6. Count inventory (optional shulker contents when configured); take materials immediately when accepted.
7. Write a withdrawal intent, withdraw through Vault when economy is enabled, then record the result.
8. Persist the task (including `cursor` and `applied_count`) before queueing. `escrow_id` is unused (null).
9. Revalidate expected state and protection immediately before each mutation.
10. Record successful changes to CoreProtect under the requesting player's name (placement uses target block data; break uses pre-break expected state).
11. Persist cursor and applied count after each execution step (restart-safe partial refunds).
12. On completion or cancel, refund unfinished per-block/per-area fees and return unused place materials (inventory or world drop if full/offline).

Vault and SQLite cannot share an ACID transaction. The ledger therefore uses durable transaction intents and unique transaction IDs. If persistence fails after withdrawal, MaxFastBuild performs a compensating refund and leaves a recoverable ledger row if that refund fails.

Fabric **server** entry is a stub: internal commands return `maxfastbuild.error.fabric_server_todo`. The Fabric **client** talks to a Paper/Leaf host. Shared `EconomyService` is retained for a later Fabric economy adapter.
