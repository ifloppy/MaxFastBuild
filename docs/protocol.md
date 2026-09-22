# Command/chat protocol v5

The protocol deliberately uses vanilla command and system-message packet types. It does not use plugin messaging or a Fabric custom payload.

Protocol v5 is a coordinated client/server release. Older clients and servers are rejected by the
hello version check; update the Paper/Leaf plugin and the matching Fabric client together.

Anyone who can send chat commands can attempt `/__mfb`; the **server** enforces `maxfastbuild.use`, rate limits, shape caps, materials, economy, and world protection. The compact path does **not** require an HMAC session.

## Client to server

Primary place intent (single command, always under 240 characters):

```text
/__mfb place <mode> <x1> <y1> <z1> <x2> <y2> <z2> <hollow/submode> <material>
```

`arc` inserts `<x3> <y3> <z3>` before `<material>`; `array` may insert `<stepX> <stepY> <stepZ>` before `<material>`.

Example:

```text
/__mfb place wall 10 64 20 10 70 30 0 minecraft:stone
```

Primary break intent (tool in hand on client; server picks tools and durability):

```text
/__mfb break <mode> <x1> <y1> <z1> <x2> <y2> <z2> <hollow/submode>
```

`arc` appends `<x3> <y3> <z3>` and `array` may append `<stepX> <stepY> <stepZ>`.

Example:

```text
/__mfb break wall 10 64 20 10 70 30 0
```

Break execution uses main-hand tool first, then other inventory tools. A tool is never worn below 4 remaining durability; drops and economy charges still apply.

Optional handshake (legacy HMAC path — not used by the Fabric client compact commands):

```text
/__mfb hello 5
```

The hello reply includes a per-player session secret over the same marked system-message channel. Treat it as legacy; do not rely on chat secrecy for new clients. Permission checks still apply after envelope verify.

Legacy chunked authenticated payload (still accepted; same permission checks after reassembly):

```text
/__mfb p <transfer-id> <index> <total> <envelope-sha256> <chunk>
```

`__mfb` is intercepted before Brigadier dispatch and is never registered in the command tree. The server regenerates shapes from anchors and mode; the client never submits a trusted block list — **except** the Litematica bulk-paste channel below, where the client-supplied block list is re-validated mutation by mutation.

## Litematica bulk paste (client → server)

The Fabric client can stream a Litematica placement as a single bulk build request:

1. Client runs `/__mfb hello` to obtain a per-player HMAC session (same legacy handshake).
2. The paste is encoded as a palette (unique block-state strings) plus entries `dx,dy,dz:paletteIndex` (schematic-relative), gzipped, wrapped in the authenticated envelope, and split into `p` transfers. Every payload part carries a SHA-256 checksum over its canonical JSON, and every command carries the SHA-256 checksum of the complete envelope.
3. The client sends one command chunk every 80ms. The server persists chunks and paste parts in SQLite, applies per-player/global byte and transfer quotas, and removes idle uploads after the configured retention window. This prevents a large paste from becoming a command flood or a heap-resident upload.
4. Server detects gzip magic bytes on the verified payload and reassembles parts per `(player, pasteSessionId)`. Part count and entries per part are server-configured and advertised in the v5 hello capabilities.
5. After each part the server replies a protocol-only `paste_ack` (`type: paste_ack`, data `pasteSessionId`, `part`, `parts`, `received`, `complete`). The client displays upload progress and retries the same durable chunk/part on a lost ACK. Fully received uploads remain in a durable `READY` state until task admission, then become short-lived idempotency records, so a reload cannot lose the final upload or create a duplicate task.
6. Each payload also carries transformed Litematica region boxes. Their dimensions and summed volume include air; the server applies `execution.max-region-blocks` and the three axis limits before planning. The server does not trust the client block list as authority and validates every target mutation against the world.
7. When the final part arrives the whole paste is planned as **one build task**: every mutation is validated against world height, protection, tool rules, materials (per unique block type), economy, then enqueued like any other task. Duplicate coordinates are counted once; conflicting duplicate targets are malformed. Block-entity NBT is preserved: palette entries may be `state{...}` (SNBT appended to the state), the server splits at the first `{` and applies the NBT to the placed block. Container contents are billed item-for-item (exact match) in addition to the container block item.
8. The server counts unique planned coordinates whose target differs from the current state and applies `execution.max-affected-blocks` to both queued and instant paste. Instant pastes are charged at `instant-paste.multiplier` × the normal quote and execute synchronously.

Entity NBT is bounded and allow-listed by entity type. Namespaced non-Minecraft entities, command-block minecarts, nested `Passengers`, command-output and spawner/loot control keys are rejected; there is no Java object deserialization, script engine, or server command execution path in the paste protocol.

The ack is sent over the marked system-message channel only (no chat line) and is consumed by the client before rendering.

## Server to client

The server sends a system message whose literal content starts with the invisible separator marker `U+2063MFB1:` followed by JSON. The Fabric client consumes it before chat rendering and resolves `messageKey` using its own language files.

Common `messageKey` values:

- `maxfastbuild.task.accepted` — data: `blocks`, `charge` (and `taskId`)
- `maxfastbuild.task.completed` / `maxfastbuild.task.partial` — data: `applied`, `planned`, `refund`
- `maxfastbuild.error.insufficient_materials` — data: `need`, `have`, `material` (may be JSON numbers)
- `maxfastbuild.error.payment_failed` — data: `reason`
- `maxfastbuild.error.no_permission` — data: `permission`
- `maxfastbuild.error.shape_too_large` — data: `limit`
- `maxfastbuild.error.nbt_unavailable` — data: `{}` (block-entity NBT present but the NMS API could not parse/apply it)
