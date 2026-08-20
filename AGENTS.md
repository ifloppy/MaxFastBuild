# MaxFastBuild — agent notes

## Layout

- **Git repo (this project):** `MaxFastBuild/` (Gradle multi-module)
- **Local Leaf test server (dev only, not in git):** sibling directory `../test-server-leaf/`
- **Release jars:** `release/MaxFastBuild-Paper-*.jar`, `release/MaxFastBuild-Fabric-26.2-*.jar`, `release/MaxFastBuild-Fabric-1.21.7-*.jar`
- **Flatpak Prism Launcher client:** `/home/iruanp/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Fabulously Optimized/minecraft/`
- **Client mods directory:** `/home/iruanp/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Fabulously Optimized/minecraft/mods/`

## Build & deploy to Leaf test server

After completing code changes, agents **must**:

1. **Run the full build** to verify everything compiles and tests pass:
   ```bash
   ./gradlew build
   ```

2. **Ask the user** whether to deploy the Paper jar to the Leaf test server. Do NOT deploy automatically unless the user confirms.

When deploying to Leaf (user confirms):
```bash
./gradlew :maxfastbuild-paper:jar deployPaperToLeaf
```

Deploy target:
```text
../test-server-leaf/plugins/MaxFastBuild.jar
```

For every substantial feature update, update both sides before testing: copy the matching
Fabric client jar into the configured Prism Launcher instance and deploy the Paper jar to the
Leaf test server. If the Leaf server is already running, stop it gracefully and restart it so the
new MaxFastBuild jar is actually loaded by the JVM.

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
