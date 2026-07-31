# MaxFastBuild — agent notes

## Layout

- **Git repo (this project):** `MaxFastBuild/` (Gradle multi-module)
- **Local Leaf test server (dev only, not in git):** sibling directory `../test-server-leaf/`
- **Release jars:** `release/MaxFastBuild-Paper-*.jar`, `release/MaxFastBuild-Fabric-26.2-*.jar`, `release/MaxFastBuild-Fabric-1.21.7-*.jar`

## Build & deploy to Leaf test server

After any Paper plugin change, agents **must** produce a jar and install it into the local Leaf plugins folder (overwrite old):

```text
../test-server-leaf/plugins/MaxFastBuild.jar
```

Preferred commands (from this repo root):

```bash
./gradlew :maxfastbuild-paper:jar deployPaperToLeaf
# or full build (also copies release/ + deploy when Leaf exists):
./gradlew build
```

- `copyReleaseJars` → `release/`
- `deployPaperToLeaf` → `../test-server-leaf/plugins/MaxFastBuild.jar` (no-op with a log if the server dir is missing)
- Root `build` / Paper `jar` are wired so a successful Paper jar build refreshes the test plugin when the Leaf tree is present

Do **not** leave a stale jar in `test-server-leaf/plugins/` after code changes. Do not commit jars under `release/` (gitignored except `.gitkeep`) or the test server.

## Modules

| Module | Role |
|--------|------|
| `maxfastbuild-api` | Contracts |
| `maxfastbuild-core` | Shapes, billing, protocol, tasks |
| `maxfastbuild-storage` | SQLite |
| `maxfastbuild-paper` | Paper/Leaf plugin (authority) |
| `maxfastbuild-fabric` | Fabric client (Minecraft 26.2, modern APIs) |
| `maxfastbuild-fabric-1_21_7` | Fabric client for Minecraft 1.21.7 (loom-remap + official Mojang mappings, jar-in-jar of api/core/storage) |

## Conventions

- Server-authoritative builds; client only sends anchors via `/__mfb place|break`
- Paper plugin: `api-version: 1.21` (loads on 1.21.11+); compile against `paper-api:1.21.11-R0.1-SNAPSHOT`
- Do **not** shade `sqlite-jdbc` into the Paper jar (compileOnly + server-provided driver)
- Fabric client: shared sources in `maxfastbuild-fabric/src/{main,client}/java` compile against every supported version; version-volatile calls go through the per-version `ClientPlatformImpl` (dev.maxfastbuild.fabric.client.platform). Shared code must not use `net.minecraft.resources.Identifier` (1.21.7: `ResourceLocation`), `sendSystemMessage` (1.21.7: `displayClientMessage`), or 26.2-only fabric-api events (`HudElementRegistry`, `ClientHotbarScrollEvents` — 1.21.7 uses `HudRenderCallback`, hotbar hook is a no-op)
- `maxfastbuild-fabric-1_21_7`: loom-remap needs explicit `sponge-mixin` dependency and `modImplementation` for fabric-api (meta jar's nested jars are not remapped); published jar is intermediary, Java 21 bytecode (`java21Modules` in root build)
- Fabric client may still target Minecraft 26.2
- Conventional Commits
- Place-over-solid: break rules (tool, durability, unbreakable) then place; charge replace breaks when per-block economy is on
- PlugMan-safe disable: cancel tasks, unregister listeners, pause tasks, close SQLite quietly

## Docs

Player manuals and architecture: `docs/`. Keep docs aligned with implemented features only.
