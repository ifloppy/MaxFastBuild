# Architecture

MaxFastBuild treats the server as the only authority. A client submits shape intent (mode, anchors, hollow flag, material for place); the server regenerates the shape, filters unchanged blocks, validates every mutation, calculates materials and fees, then persists a task before execution.

There is **no durable inventory escrow** in the current release: place materials and economy fees are taken when the task is accepted. Unused materials and unfinished variable fees are returned on completion, cancel, or failure settlement. `EscrowStore` exists in the storage module for a future durable-reservation path and is not wired into Paper.

Transforms (`mirror` / `array`) exist in `maxfastbuild-core` but are **not** applied on the Paper submit path.

## Request pipeline (Paper)

1. Intercept `/__mfb` (not registered in Brigadier). Primary intents are single-line `place` / `break`. Optional legacy path: `hello` session + chunked `p` envelopes with HMAC (still accepted; not required for compact commands).
2. Check `maxfastbuild.use`, request rate limit, and concurrent task limits.
3. Generate the shape on the server (bounding volume must not exceed `execution.max-region-blocks` before cell enumeration).
4. Reject invalid materials, forbidden place/break blocks, and coordinates outside world height.
5. Local protection checks only during planning (no synthetic `BlockBreakEvent`/`BlockPlaceEvent` — those multi-logged in CoreProtect).  
   **Execute with vanilla APIs:** break = `breakNaturally` (CP records one break); place = `setBlockData`. Soft cells skip break. Per-block fees charge place + each replace-break.
6. Count inventory (optional shulker contents when configured); take materials immediately when accepted.
7. Write a withdrawal intent, withdraw through Vault when economy is enabled, then record the result.
8. Persist the task (including `cursor` and `applied_count`) before queueing. `escrow_id` is unused (null).
9. Revalidate expected state and protection immediately before each mutation.
10. CoreProtect: trust `breakNaturally` for removals (do **not** also `logRemoval`). After place `setBlockData`, call `logPlacement` once.
11. Persist cursor and applied count after each execution step (restart-safe partial refunds).
12. On completion or cancel, refund unfinished per-block/per-area fees and return unused place materials (inventory or world drop if full/offline).

Vault and SQLite cannot share an ACID transaction. The ledger therefore uses durable transaction intents and unique transaction IDs. If persistence fails after withdrawal, MaxFastBuild performs a compensating refund and leaves a recoverable ledger row if that refund fails.

Fabric **server** entry is a stub: internal commands return `maxfastbuild.error.fabric_server_todo`. The Fabric **client** talks to a Paper/Leaf host. Shared `EconomyService` is retained for a later Fabric economy adapter.

## Fabric client (dual-version)

Two client modules compile the same shared sources against different Minecraft versions:

- `maxfastbuild-fabric` — Minecraft 26.2: `KeyMapping.Category`, HUD elements (`HudElementRegistry`), gizmos preview (`LevelRenderEvents.BEFORE_GIZMOS` + per-frame gizmo collection). Java 25.
- `maxfastbuild-fabric-1_21_7` — Minecraft 1.21.7 (obfuscated era): String key category + `KeyBindingHelper`, `HudRenderCallback`, classic `WorldRenderEvents.AFTER_TRANSLUCENT` preview. Built with loom-remap + official Mojang mappings (shared mojmap sources compile unchanged) and remapped to intermediary in the published jar. Embeds `api`/`core`/`storage` jar-in-jar; everything in the jar is Java 21 bytecode.

Shared sources live under `maxfastbuild-fabric/src/{main,client}/java` and may only use APIs that exist in both versions. Everything version-volatile goes through the per-version `ClientPlatformImpl` (key binding, screen access, HUD, preview renderer, message delivery, physical key state, hotbar-scroll hook — no-op on 1.21.7, whose fabric-api has no such event).
