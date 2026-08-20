# Changelog

All notable changes to MaxFastBuild are documented here.

## [0.1.2] - 2026-08-20

### Added

- Pending-command cancellation without cancelling accepted build tasks.
- `/mfb replace` with single or multiple excluded blocks.
- Radial-menu action modes for place/break and selection-only workflows.

### Fixed

- Preserved selection-only mode across radial-menu openings and restored left-click selection cancellation.

## [0.1.1] - 2026-08-16

### Added

- Arc and array building modes, including array spacing controls.
- Litematica bulk paste with block-entity NBT, entities, instant mode, and server-side validation.
- Server-advertised paste limits, region metrics, payload limits, and multi-version client support.
- Nearby-container material search, nested shulker handling, seed-farm shortcuts, and fluid/fire material rules.
- Partial refunds and restart-safe task progress for queued builds.
- CoreProtect and Prism audit integrations.

### Changed

- Pasted blocks are placed through the same protection, tool, material, economy, and persistence paths as regular builds.
- Fabric clients target Minecraft 26.2 and Minecraft 1.21.7 with version-specific platform implementations.
- Bulk placement uses deferred physics and convergence passes for more Litematica-like results.

### Fixed

- Preserved block-entity data for negative-size schematic regions.
- Normalized sign text data for modern fixed-length sign codecs.
- Corrected billing for undyed and nested shulker-box contents.
- Corrected pane, fence, coral fan, stair, slab, and slope placement behavior.
