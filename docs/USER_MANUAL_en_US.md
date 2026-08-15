# MaxFastBuild User Manual

![MaxFastBuild icon](assets/maxfastbuild-icon.svg)

Player-facing guide. Server configuration, permissions, and operations are documented separately for administrators.

## Requirements

- Minecraft Java Edition **26.2**
- **Fabric Loader** and **Fabric API** (versions matching 26.2)
- A server running **MaxFastBuild** (players only need the client mod)

## Client install

Put the client jar and Fabric API in the instance `mods` folder, then join a server that supports the mod.

## Radial menu

1. **Hold** the bind (default **Left Alt**, rebind under Options → Controls → MaxFastBuild); **release to close**.
2. Move over a sector to highlight; **release** or **left-click** to select.
3. The menu is transparent so the world stays visible.

## Place and break

| Main hand | Mode |
|-----------|------|
| Placeable block | **Place** |
| Pickaxe / axe / shovel / hoe / sword / shears | **Break** (must be effective for the block; e.g. obsidian needs a proper pick) |
| Empty hand, bow, other items | **No mode** |

### Selection

1. Pick a shape from the radial menu.
2. Normal shapes use two **right-clicks** for the start and end; a **three-point arc** uses start, through, and end points.
3. **Left-click** cancels the selection.
4. Corners may be in air; against a solid block, place uses vanilla replaceable/adjacent rules.
5. **Shift+scroll** sets look depth **1–64**; volume thickness/solid/hollow uses **Ctrl+scroll**; plain scroll still changes the hotbar.
6. Preview shows only the shape **shell** plus a bounds outline.

### Costs and materials (server-defined)

- **Place** (survival): consumes matching blocks from the inventory; may charge economy if enabled. Insufficient items or balance rejects the request.
- **Nearby containers** (survival paste): with `inventory.search-containers` (default on) materials are also gathered from chests (incl. double), trapped chests, barrels, placed shulker boxes, and shulker boxes nested inside those containers within **5 blocks** (`container-search-radius`) of the player's feet; consumption and refunds go to the **exact source slot**.
- **Fire/soul fire** (survival): a **flint and steel** anywhere in the searched sources is required instead of a fire item; **each fire block costs 1 durability**. A flint enchanted with Mending keeps its last durability point and the next flint and steel is used instead.
- **Water/lava** (survival): never consumed. With the configured number of buckets (default **2** water buckets / **2** lava buckets) in the inventory, unlimited water/lava can be placed; the buckets themselves are not spent.
- **Break**: normal drops and tool wear; a tool is never worn below **4** remaining durability—other inventory tools are used next.
- **Partial completion**: unfinished mutations refund variable per-block/per-area fees and unused place materials. A fixed per-operation fee (if enabled) is generally kept once execution has started.

## Shapes

Single, line, wall, floor, cube, three-point arc, array, slope floor, circle, cylinder, sphere, pyramid, cone.

The three-point arc uses CAD-style start, through, and end points. Array fills the selected two-point range on an X/Y/Z lattice; use Ctrl+scroll for X, Ctrl+Shift+scroll for Y, and Ctrl+Alt+scroll for Z on the client.

## Without the client mod

Public server commands (permission `maxfastbuild.use`). Use `/mfb` or `/mfb help` for help (server default language is Chinese unless `default-language` is changed).

```text
/mfb help
/mfb mode line
/mfb pos1
/mfb pos2
/mfb pos3
/mfb array-spacing 2 1 2
/mfb hollow false
/mfb apply
/mfb status
/mfb cancel
```

`apply` matches the client: main-hand block = place/replace, mining tool = break, empty hand = reject (material comes from the held block).

## Litematica bulk paste

With the client mod and **Litematica** both installed, the current active placement can be pasted as a **single** build task (the server still validates every block and charges materials/fees):

1. Open Litematica and place a **SchematicPlacement** in the world.
2. Bind MaxFastBuild's "**Paste active Litematica placement**" key (default unbound; Options → Controls → MaxFastBuild).
3. Press the key and wait for the server to validate.

Notes:

- Block entities (chests, signs, lecterns, …) keep their **contents/text**: a container paste deducts **one plain container block item** plus **every item inside its stored contents** (exact match), from the inventory or nearby containers.
- Optional **instant paste**: bind the "**Toggle instant paste**" key (default unbound), press it to arm the mode (HUD indicator), then paste. Instant pastes are charged at the server's `instant-paste.multiplier` (default 2×) and execute immediately instead of waiting in the queue; they share `execution.max-affected-blocks` with queued pastes. Materials and tool durability are still required.
- A single paste is bounded by `execution.max-region-blocks` (selected volume including air), `execution.max-affected-blocks` (unique coordinates planned to change), and the X/Y/Z size limits. The client shows server limits and current schematic metrics; split larger schematics.
- As with normal placement, survival deducts **per block type** from the inventory and applies world-protection, tool-durability and economy checks.
- Without Litematica the key does nothing; creative mode still goes through the same validation.

## Troubleshooting

- **Menu does not open**: check key conflicts; confirm the client mod and server version.
- **Cannot pick**: select a mode first; hold a block or tool.
- **Insufficient materials**: you need at least as many blocks as the shape will place (in your inventory, or nearby containers within 5 blocks when container search is on); fire needs a flint and steel, water/lava need buckets.
- **Payment failed**: not enough balance or economy unavailable.
- **Partial refund message**: normal when some blocks could not be applied.
