# Configuration (administrators)

Paper defaults live in `maxfastbuild-paper/src/main/resources/config.yml` inside this repository. After first run, the live file is under the server’s MaxFastBuild plugin data folder.

## Keys

| Key | Meaning |
|-----|---------|
| `execution.ticks-per-block` | Scheduler period (ticks) |
| `execution.blocks-per-step` | Mutations attempted per tick step |
| `execution.max-region-blocks` | Hard cap on generated shape size |
| `execution.max-concurrent-tasks-per-player` | Active tasks per player |
| `rate-limit.*` | Token-bucket request limits |
| `inventory.search-shulker-boxes` | Count/take place materials from carried shulker boxes |
| `inventory.require-shulker-permission` | If true, also require `maxfastbuild.material.shulker` (default false) |
| `economy.enabled` | Master switch for Vault charges |
| `economy.per-operation` | Fixed fee per accepted task |
| `economy.per-area` | Fee from max bounding plane area |
| `economy.per-block` | Fee per planned mutation |
| `coreprotect.required` | Reject builds if CoreProtect is missing |
| `protocol.session-minutes` | Optional session lifetime |
| `protocol.max-payload-bytes` | Max decoded payload size |

## Billing notes

- Fees are additive across enabled dimensions.
- Money and place materials are taken when the task is **accepted**.
- When a task finishes with fewer **successful** mutations than planned, **per-block** and **per-area** portions are refunded in proportion to unfinished work; unused place items are returned.
- **Per-operation** fee is refunded only if **zero** mutations were applied (e.g. cancel before any change); once execution has applied blocks it is kept.
- Creative mode does not consume place materials. Bypass permissions are **not** granted to OP by default (`maxfastbuild.bypass.cost` / `materials` / `rate-limit` default false).
