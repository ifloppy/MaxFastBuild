# MaxFastBuild — agent notes

## Layout

- **Git repo (this project):** `MaxFastBuild/` (Gradle multi-module)
- **Local Leaf test server (dev only, not in git):** sibling directory `../test-server-leaf/`
- **Release jars:** `release/MaxFastBuild-Paper-*.jar`, `release/MaxFastBuild-Fabric-*.jar`

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
| `maxfastbuild-fabric` | Fabric client (+ stub server) |

## Conventions

- Server-authoritative builds; client only sends anchors via `/__mfb place|break`
- Java 25 / Minecraft 26.2
- Conventional Commits
- Place-over-solid: break rules (tool, durability, unbreakable) then place; charge replace breaks when per-block economy is on
- PlugMan-safe disable: cancel tasks, unregister listeners, pause tasks, close SQLite quietly

## Docs

Player manuals and architecture: `docs/`. Keep docs aligned with implemented features only.
