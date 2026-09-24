# Feature: UI Redesign - Tracker

Status legend: not-started / in-progress / done / blocked

Design: [arch.md](arch.md). Design accepted by the owner on 2026-09-24 (ADR 0025, 0026 Accepted). Rows below track the build; nothing under R0 onward is built until marked.

| Component | Status | Notes |
|---|---|---|
| **Decisions** | | |
| ADR 0025 shell model | done | Accepted 2026-09-24 |
| ADR 0026 identity, kit, properties | done | Accepted 2026-09-24: orange default accent, Block motif |
| Extension-track hand-off (SDK additions) | not-started | extension-ui.md section 8; approved, still to be sent to the cloud session |
| In-flight worktree plan (salvage / merge / remove) | done | reliab, editor, git merged; layout pure logic salvaged (`ui/layout` screen edits dropped); `ui/home` stale, removal pending; `WorkspaceViewModel` split under 600 lines |
| **R0 Foundations** | | |
| Import-ratchet test + allowlist | done | `ImportRatchetTest`; allowlist `ui-ratchet-allowlist.txt` = 55 files, 202 imports; fails on growth, new offenders and un-lowered improvements |
| `tools/ui-lint.sh` (colour, type, copy, motion rules) | done | 7 rules, 0.3 s, baseline `tools/ui-lint-baseline.txt` (12 entries); not yet wired into CI (no test workflow exists) |
| Roborazzi compatibility spike | done | works on AGP 9.3.1 / Kotlin 2.4.10 / compileSdk 37 with three settings (kit.md section 7); Geist loads; Roborazzi Apache-2.0, Robolectric MIT; gallery golden in `app/src/test/screenshots`; Dropshots fallback not needed |
| `Appearance`, `UiMetrics`, `Motion`, `Feel` + settings schema | done | `ui/props`, `AppearanceSettingsSchema`; defaults equal today's constants (test); wired into `EasyIdeTheme` (accent, scale, shapes, typography, motion) |
| Token refactors 1-10 (kit.md section 6) | in-progress | done: 2 radius scale, 3 shapes fn, 5-6 typography by pairing, 7 theme takes `Appearance`, 8 `withAccent`, 10 (follows accent); open: 1 spacing call sites, 4 control scale call sites, 9 semantic accessors + `Tone` |
| Kit primitives (about 20) + gallery | in-progress | all 17 primitives landed (see kit.md 3). Gallery landed: `ui/kit/gallery` (debug and canary only), opened from Settings > Diagnostics > Developer tools, live controls for density, corners, font scale, accent, theme mode, motif and reduce motion; release has a no-op twin (`src/release`); checked on the built APKs: the route string and the gallery classes are in the canary dex and mapping, and absent from the release dex, mapping and resources (AGP creates no release unit-test task, so this is a build-output check, not a unit test). Semantics tests (role, name, state, 44dp) and gallery smoke tests run under Robolectric in `src/testDebug`. `tools/ui-device-check.sh` for the on-device 44dp and label check. Not seen on a device by the author; the screenshot goldens of the gallery matrix are still open |
| Motif drawables (cursor block, crop corners, prompt glyph, cell fill) | done | drawn, not typed (Geist Mono has no U+25AE); compiles and the logic is unit-tested, not seen on a device; the 6-cycle blink cap for Home and the 600ms reset after input are not implemented |
| **R1 App shell** | | |
| `ShellState`, registries (navigation, container, document) | done | pure engine in `ui/shell`, unit-tested; app scope is wired to it through `ShellViewModel` (`ui/shell/host`); `ShellAction.ResizePane` added for dragged panel widths; workspace scope is not wired (R3) |
| `NavSurface` (bottom bar / rail), `PanelHost`, `StageHost` (1 group) | in-progress | built and wired for app scope: bottom bar (max 5 + More via `KitDialog`) or rail per size class and `shell.navigation.*`, badges, docked primary panel with a persisted draggable width, one-group stage (tab strip wide, titled page with back on compact), `AdaptiveScaffold` insets and IME, `ShellHost`. JVM logic tests only; not seen on a device. Open: overlay side/bottom sheets for workspace panels (R3), secondary/bottom panels, more than one group, Ctrl+Alt+n nav shortcuts |
| Document URIs + open/preview/pin/history | in-progress | `DocumentUri`, `DocumentRegistry` (open-with, placeholder), `EditorGroup`/`EditorStage` (preview, pin, MRU, history, 1-4 groups) done and tested; no UI (`StageHost`) yet |
| Home "Now" page + project page | in-progress | built on the kit in `ui/screens/home`: `HomePanel` (resume hero, Running, Environment, Projects with search/sort/row menu), `HomeNowPage` (stage), `ProjectPage`, all Home dialogs on `KitDialog`/`KitField`; the old `HomeScreen` is now a thin host of them until the shell swaps adapters. Running reads the workspace registry (one row per live workspace, not per terminal), LSP statuses with RSS, and provisioning environments (no percentage source, so an indeterminate sweep). Compile, JVM logic tests and R8 release build only; not seen on a device. Open: per-terminal and agent rows, install percentage, environment size, last commits on the page, cursor blink cap |
| Extensions on the shell (list + extension page + install flow) | in-progress | built on the kit: `ExtensionsPanel`, `ExtensionPage`, `ExtensionsDialogs` (install approval, rollback, create, Browse detail, uninstall confirm) with unit-tested row/filter/grouping/capability-diff logic; `ExtensionsScreen` is a temporary frame until the shell hosts them. Compiles and passes JVM tests; not seen on a device; not yet wired into `StageHost`/`PanelHost` |
| Settings on the shell (categories + pages + Appearance + Layout) | not-started | pre-shell `SettingsScreen` hosted through a temporary adapter until the SETTINGS branch lands; `shell.*` keys exist in `ShellSettingsSchema` (listed under Appearance until the Layout page) |
| Back behaviour + app-scope restore | done | `BackHandler` in `ShellHost` runs `BackNavigation`; the snapshot is restored on first window and saved debounced (400 ms) in the app preferences file. Note: on a phone at Home the first Back moves focus to the bottom bar (section 11 step 5) and the second leaves the app. Not verified on a device |
| **R2 Flows and dialogs** | | |
| Onboarding, New project, Install Linux on the kit | not-started | baseline profile regen |
| 22 non-workspace dialogs to `KitDialog`; banners; toasts | in-progress | Home's six dialogs (rename, duplicate, delete with typed name, change environment, clone, import) plus the Install Linux and stop-session prompts are on `KitDialog`; Home confirmations are an inline `KitBanner` (no toast primitive yet); Settings and Extensions dialogs open |
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

## R1 swap list (temporary adapters)

The app launches into `ShellHost` with the pre-shell screens hosted as window-spanning panels (`ui/shell/host/LegacyAdapters.kt`, bound in `AppRenderers.panels`). When the sibling branches merge:

1. `AppRenderers.panels`: bind `home.projects` to `HomePanel`, `settings.categories` to `SettingsPanel(selected, onSelect)`, `extensions.list` to `ExtensionsPanel` (drop `spansWindow`).
2. `AppRenderers.documents`: bind `easyide.project` to `ProjectPage(projectId)`, `easyide.settings` to `SettingsPage(category)`, `easyide.extension` to `ExtensionPage(id)` (type ids in `AppDocuments`; pages read `LocalShellState` and call `LocalShellActions.open`).
3. Delete `LegacyAdapters.kt` and `PanelBinding.spansWindow` (and its branch in `ShellBody` and `PanelHost`). `ShellExits` stays: it is how the shell leaves for the workspace, new project, install and diagnostics routes.
4. Baseline profile: no selector changed (onboarding "Get started" and the first scrollable node are still Home's list on a phone).
