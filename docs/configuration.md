# Configuration (administrators)

Paper defaults live in `maxfastbuild-paper/src/main/resources/config.yml` inside this repository. After first run, the live file is under the server’s MaxFastBuild plugin data folder.

## Keys

| Key | Meaning | Read by code |
|-----|---------|--------------|
| `execution.ticks-per-block` | Scheduler period (ticks) | yes |
| `execution.blocks-per-step` | Mutations attempted per tick step | yes |
| `execution.max-region-blocks` | Hard cap on bounding volume / generated shape size | yes |
| `execution.max-concurrent-tasks-per-player` | Active tasks per player | yes |
| `execution.pause-when-player-offline` | Present in default config; offline pause is always applied in code | no (behavior hard-coded) |
| `default-language` | Paper CLI message pack (`messages_<lang>.yml`), default `zh_cn` | yes |
| `rate-limit.requests` / `interval-seconds` / `burst` | Token-bucket request limits | yes |
| `inventory.search-shulker-boxes` | Count/take place materials from carried shulker boxes | yes |
| `inventory.require-shulker-permission` | If true, also require `maxfastbuild.material.shulker` | yes |
| `inventory.fluid-bucket-requirement` | Buckets needed to place unlimited water/lava (default 2, never consumed) | yes |
| `economy.enabled` | Master switch for Vault charges | yes |
| `economy.per-operation.enabled` / `.price` | Fixed fee per accepted task | yes |
| `economy.per-area.enabled` / `.price` | Fee from max bounding plane area | yes |
| `economy.per-block.enabled` / `.price` | Fee per planned mutation | yes |
| `instant-paste.multiplier` | Instant paste charge multiplier (default 2; 0 = free aside from materials) | yes |
| `instant-paste.max-blocks` | Instant paste block cap (default 5000; 0 = protocol cap) | yes |
| `coreprotect.required` | Reject builds if CoreProtect is missing | yes |
| `protocol.session-minutes` | Lifetime for optional legacy HMAC sessions | yes |
| `protocol.max-payload-bytes` | Max decoded legacy payload size | yes |

## Permissions

| Permission | Effect |
|------------|--------|
| `maxfastbuild.use` | `/mfb` and `/__mfb` place/break (default true) |
| `maxfastbuild.material.shulker` | Shulker search when `require-shulker-permission` is true |
| `maxfastbuild.bypass.cost` / `.materials` / `.rate-limit` | Explicit bypass (default false; not OP) |
| `maxfastbuild.admin` | `/mfbadmin reload\|recovery` |

## Hot reload (PlugMan / PlugManX)

Unload/reload is supported for normal operation:

1. Scheduler tasks are cancelled first.
2. Listeners and command executors are cleared.
3. Active build tasks are persisted as `PAUSED_SHUTDOWN` (with `applied_count`) then SQLite is closed quietly.
4. On load, online players’ recoverable tasks are re-queued.

Prefer a full server restart after upgrading the jar when possible. Soft-depend plugins (Vault, CoreProtect) should remain loaded across the reload.

## Billing notes

- Fees are additive across enabled dimensions.
- Money and place materials are taken when the task is **accepted** (immediate take; no durable escrow yet).
- When a task finishes with fewer **successful** mutations than planned, **per-block** and **per-area** portions are refunded in proportion to unfinished work; unused place items are returned (or dropped if inventory is full / player offline).
- **Per-operation** fee is refunded only if **zero** mutations were applied; once any block applied it is kept.
- `applied_count` is persisted so restarts do not over-refund.
- Creative mode does not consume place materials.
- Container pastes deduct **one plain block item per container** plus **every item inside its NBT contents** (exact match: same type + meta). Container contents are validated for forbidden/undecodable items before any charge; a paste whose NBT cannot be parsed/read is rejected outright rather than placed empty.
- Instant paste runs the mutations synchronously (no queue wait) at `instant-paste.multiplier` × the normal quote; it still requires materials, tool durability, CoreProtect, and economy unless bypassed. Partial failure refunds proportional fees and returns unused materials like a normal task.
