# Command/chat protocol v1

The protocol deliberately uses vanilla command and system-message packet types. It does not use plugin messaging or a Fabric custom payload.

## Client to server

Primary place intent (single command, always under 240 characters):

```text
/__mfb place <mode> <x1> <y1> <z1> <x2> <y2> <z2> <hollow 0|1> <material>
```

Example:

```text
/__mfb place wall 10 64 20 10 70 30 0 minecraft:stone
```

Primary break intent (tool in hand on client; server picks tools and durability):

```text
/__mfb break <mode> <x1> <y1> <z1> <x2> <y2> <z2> <hollow 0|1>
```

Example:

```text
/__mfb break wall 10 64 20 10 70 30 0
```

Break execution uses main-hand tool first, then other inventory tools. A tool is never worn below 4 remaining durability; drops and economy charges still apply.

Optional handshake (legacy):

```text
/__mfb hello
```

Legacy chunked authenticated payload (still accepted):

```text
/__mfb p <transfer-id> <index> <total> <chunk>
```

`__mfb` is intercepted before Brigadier dispatch and is never registered in the command tree. The server regenerates shapes from anchors and mode; the client never submits a trusted block list.

## Server to client

The server sends a system message whose literal content starts with the invisible separator marker `U+2063MFB1:` followed by JSON. The Fabric client consumes it before chat rendering and resolves `messageKey` using its own language files.

Common `messageKey` values:

- `maxfastbuild.task.accepted` — data: `blocks`, `charge`
- `maxfastbuild.task.completed` / `maxfastbuild.task.partial` — data: `applied`, `planned`, `refund`
- `maxfastbuild.error.insufficient_materials` — data: `need`, `have`, `material` (may be JSON numbers)
- `maxfastbuild.error.payment_failed` — data: `reason`
