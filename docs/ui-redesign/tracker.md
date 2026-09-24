# Feature: UI Redesign - Tracker

Status legend: not-started / in-progress / done / blocked

Design: [arch.md](arch.md). All rows are PROPOSED as of 2026-09-24; nothing below is built.

| Component | Status | Notes |
|---|---|---|
| **Decisions** | | |
| ADR 0025 shell model | not-started | Proposed; owner sign-off, arch.md section 11 |
| ADR 0026 identity, kit, properties | not-started | Proposed; default accent and motif pending owner |
| Extension-track hand-off (SDK additions) | not-started | extension-ui.md section 8; owned by the extension track |
| In-flight worktree plan (salvage / merge / remove) | not-started | arch.md section 8; needs owner confirmation |
| **R0 Foundations** | | |
| Import-ratchet test + allowlist | not-started | fails on raw Material widgets outside `ui/kit` |
| `tools/ui-lint.sh` (colour, type, copy, motion rules) | not-started | ux-rules.md section 12 |
| Roborazzi compatibility spike | not-started | AGP 9.3.1 / Kotlin 2.4.10 unverified; Dropshots fallback |
| `Appearance`, `UiMetrics`, `Motion`, `Feel` + store | not-started | defaults identical to today's constants (test) |
| Token refactors 1-10 (kit.md section 6) | not-started | |
| Kit primitives (about 20) + gallery | not-started | kit.md section 3 |
| Motif drawables (cursor block, crop corners, prompt glyph, cell fill) | not-started | block is drawn: Geist Mono has no U+25AE |
| **R1 App shell** | | |
| `ShellState`, registries (navigation, container, document) | not-started | |
| `NavSurface` (bottom bar / rail), `PanelHost`, `StageHost` (1 group) | not-started | |
| Document URIs + open/preview/pin/history | not-started | shell-model.md sections 4-5 |
| Home "Now" page + project page | not-started | needs session registry (ADR-D) for Running |
| Extensions on the shell (list + extension page + install flow) | not-started | |
| Settings on the shell (categories + pages + Appearance + Layout) | not-started | |
| Back behaviour + app-scope restore | not-started | shell-model.md sections 11-12 |
| **R2 Flows and dialogs** | | |
| Onboarding, New project, Install Linux on the kit | not-started | baseline profile regen |
| 22 non-workspace dialogs to `KitDialog`; banners; toasts | not-started | |
| **R3 Workspace shell** | | |
| Editor groups (1-4), document switcher / tab strip | not-started | |
| Panels: Files, Search, Source control, Problems, Outline, Terminal | not-started | |
| Bottom navigation in workspace + input dock | not-started | dock replaces bar while typing |
| Status strip, layout presets, foldable postures, persisted layout | not-started | salvage pure logic from `ui-layout` |
| Workspace stock dialogs/menus to the kit | not-started | 7 `AlertDialog`, `ExtensionSlots` menu |
| **R4 Extension UI** | | |
| Registries fed by the runtime (navigation, containers, documents, openers, presets) | not-started | |
| View schema renderer + limits | not-started | extension-ui.md section 4 |
| Sample packs: Docker, Agents, Chat | not-started | |
| **R5 Identity assets** | | |
| Custom icon set (24 glyphs) + resolver | not-started | needs a draughtsperson pass |
| App icon, splash, adaptive + monochrome layers | not-started | |
| Motion + haptics helpers, empty-state art, copy pass | not-started | |
| **R6 Split view, diff, gestures** | | |
| Open beside, groups, `git-diff` / `git-commit` documents, diff providers | not-started | |
| Gesture layer, caret scrub bar | blocked | waits for the editor engine (ADR 0018 PE1) |
| **Hygiene** | | |
| ux-overhaul Pillar 4 superseded note | not-started | shell-model replaces its layout plan |
| ADR 0019 amendment note | not-started | accent and motif |
