# Contributing

Thanks for helping improve MaxFastBuild.

## Development setup

- **JDK 25**
- Clone this repository and run `./gradlew build`
- Do not commit build outputs, IDE metadata, or local proxy settings in `gradle.properties`

## Code style

- Match existing package layout (`maxfastbuild-api` / `core` / `storage` / `fabric` / `paper`)
- Prefer server-authoritative validation for world and economy changes
- Keep the client protocol compact (`__mfb place` / `__mfb break`)

## Internationalization

- Client strings: `maxfastbuild-fabric/src/main/resources/assets/maxfastbuild/lang/`
  - Always update **both** `en_us.json` and `zh_cn.json`
- Paper `messages_*.yml`: reserved templates only (not loaded by the plugin yet); keep in sync if you add keys for a future loader
- Prefer translation keys in protocol payloads (`messageKey` + `data`) over hard-coded English

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/):

```text
feat: add shulker material search
fix: refund unfinished per-block charges
docs: refresh player manuals
chore: ignore release jars
```

Common types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `ci`, `perf`.

## Pull requests

1. Describe the player-visible or admin-visible change
2. Note protocol or config key changes
3. Link related issues if any
4. Keep PRs focused

## Security / economy

- Never grant cost/material bypass to OP by default
- Partial task completion must settle refunds and unused materials where applicable
