# Feature: UX Overhaul — Architecture

## Overview

User report (2026-09-23): UI, customization, features, layouts, Home and Settings all feel dull, slow and laggy. This doc is the consolidated output of five parallel read-only audits (performance, visual design, layout/navigation, settings/customization, feature gaps) over `services/mobile`. Nothing below has been measured on a device; line refs are as of commit `ce3f8e8`.

Paths are relative to `services/mobile/app/src/main/java/dev/easyide/app/` (`app/`) or `services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/` (`sandbox/`).

### Root causes (the five things behind "dull, slow, laggy")

1. **Editor is one giant `BasicTextField`** in a `verticalScroll`, driven through the ViewModel's single `WorkspaceUiState`. Every keystroke/scroll pixel re-lays out the whole document and recomposes tree, tabs, terminal and status bar. (Chainlog: 650 ms p95 frame on a 4,200-line file.)
2. **Release/canary ship unoptimised**: no R8, no `shrinkResources`, no baseline profile. Compose runs JIT'd on early launches.
3. **No visual identity**: native screens use M3 baseline purple `#6750A4` with only `primary` overridden; the workspace is a hex-for-hex copy of VS Code Dark+. Two design languages, stock Roboto, stock shapes, blank loading states.
4. **No input model**: no hardware-keyboard shortcuts, no command palette, no right-click/hover, fixed pane sizes. Everything is long-press + rail icons.
5. **Almost nothing is customizable**: theme mode is the only setting that reaches the editor/terminal; fonts, sizes, terminal colors, keybindings, git identity are constants.

Plus two **data-loss bugs** that must go first (see P0-S1, P0-S2).

## Decisions

Existing ADRs this work builds on:
- [0004](../decision/0004-material3-design-system.md) Material 3 foundation
- [0006](../decision/0006-native-ide-shell-before-theia.md) native Compose shell before Theia (the docs still describe a Theia WebView in places; see Docs hygiene)
- [0010 PTY](../decision/0010-pty-terminal-vendored-termux.md), [0010 TextMate](../decision/0010-textmate-highlighting-bundled.md)
- [0011](../decision/0011-jgit-for-object-model-sandbox-git-for-network.md), [0012](../decision/0012-git-token-in-process-env-not-credential-socket.md)

New ADRs required before landing (hard to reverse):
- **ADR-A Visual identity**: brand palette, UI + mono fonts (licenses verified from primary sources), unified token object for chrome + editor.
- **ADR-B Editor engine**: line-virtualised Compose editor vs. adopting a View-based editor library (license + maintenance verified). Blocks P2-E1.
- **ADR-C Settings schema + theme import**: opens the "closed set for v1" in `ThemeMode.kt:4`; VS Code theme JSON import.
- **ADR-D Workspace session lifetime**: sessions (ptys, buffers) owned by an app/service-scoped `WorkspaceRegistry`, not the nav-entry ViewModel.

## Architecture

### Pillar 1 — Performance

**Quick wins (hours each)**

| ID | Where | Fix |
|---|---|---|
| PF1 | `app/build.gradle.kts:70-78` | R8 + `proguard-android-optimize.txt` + `shrinkResources` for release/canary; keep rules for Termux JNI, JGit, TextMate reader |
| PF2 | build | `androidx.profileinstaller` + `:baselineprofile` macrobenchmark module (startup, open workspace, editor scroll, terminal toggle) |
| PF3 | `EditorPane.kt:100` | read scroll via `remember { derivedStateOf { LineWindow } }` instead of `scroll.value` in composition |
| PF4 | `EditorPane.kt:246-264` | only re-key the highlight window when `!isScrollInProgress`; widen overscan |
| PF5 | `sandbox/LinuxEnvironment.kt:167-172`, `sandbox/bootstrap/RootfsProvisioner.kt:65`, `WorkspaceViewModel.kt:112,599` | move `interactiveShellParams`/`ensureGuestDefaults`/`isReady` onto IO |
| PF6 | `WorkspaceViewModel.kt:103-107,478`, `sandbox/git/GitService.kt:67` | single-flight/conflated `refreshGit`, skip self-caused watcher events, cache one JGit `Repository` per project |
| PF7 | `WorkspaceScreen.kt:148-151` | stop animating editor height on terminal toggle; keep `TerminalView` composed, translate/fade instead of `AnimatedVisibility` dispose/recreate |
| PF8 | `res/values/themes.xml:9`, `MainActivity.kt:45-64` | DayNight window theme + `core-splashscreen` with `setKeepOnScreenCondition` (kills white flash + empty surface) |
| PF9 | `EasyIdeApplication.kt:14` | lazy `TextMateHighlighter.init` on first highlight (already on Default) |
| PF10 | `WorkspaceViewModel.kt:614-622` | batch install-log lines per ~60 ms into one main post |

**Structural (days)**

| ID | Where | Fix |
|---|---|---|
| PS1 | `WorkspaceViewModel.kt:57` | split `WorkspaceUiState` into independent flows: tree, tabs, activeContent, terminals, status. Panes take only their slice (`FileTreePane.kt:48,67`) |
| PS2 | `EditorPane.kt:120-121`, `WorkspaceViewModel.kt:288,315` | per-tab `TextFieldState` (new `BasicTextField(state=)` API); ViewModel syncs via `snapshotFlow` + debounce. Removes a frame of input latency |
| PS3 | `WorkspaceViewModel.kt:44`, `EditorPane.kt:87-88,269`, `WorkspaceChrome.kt:149` | replace O(n) `content != savedContent`, newline counts and text compares with a per-tab version counter + incremental line count |
| PS4 | `EditorPane.kt:139`, `MarkdownPreview.kt:59,67` | split lines / parse markdown off main (`produceState` on Default); keys + `contentType` in lazy lists |
| PS5 | `WorkspaceViewModel.kt:128-140,308` | `refreshTree` lists all expanded dirs then emits once; drop redundant refresh in `onSave` |
| PS6 | `MermaidView.kt:48,75` | one shared pre-warmed WebView renders to SVG, cached by source hash, drawn as image; `WebViewAssetLoader` instead of `allowFileAccess` |
| PS7 | `AppNavHost.kt:78-85,144` | pass `environmentId` in route (or repository lookup); drop the second `HomeViewModel` so Workspace composes on frame one |
| PS8 | `sandbox/shell/TerminalProcess.kt:81-100` | blocking read in `runInterruptible(io)` instead of `available()` + `sleep(25)` polling |
| PS9 | tooling | Compose compiler `metricsDestination`/`reportsDestination`; judge perf only on a minified non-debuggable build |

**Editor engine (weeks, ADR-B)** — PE1: line-virtualised editing surface (LazyColumn of lines or vetted View editor). This is the only real fix for large-file typing cost and unlocks undo/redo, find, multi-cursor, folding, diff gutter, LSP decorations.

### Pillar 2 — Safety and reliability (P0, before any polish)

| ID | Where | Problem -> Fix |
|---|---|---|
| S1 | `AppNavHost.kt:195`, `WorkspaceViewModel.kt:117-123` | Back pops Workspace, clears VM, kills every pty, drops dirty buffers silently -> `BackHandler` guard ("Save / Discard N files") + ADR-D `WorkspaceRegistry` so leaving parks, re-entering reattaches |
| S2 | `EditorTabBar.kt:288-294` | dirty dot is the 8dp close target; one tap discards edits -> Save/Discard/Cancel confirm; 48dp hit area |
| S3 | `AppNavHost.kt:150-151` | missing project = blank screen forever -> loading then "project not found" state with route Home |
| S4 | `WorkspaceScreen.kt:89`, manifest | no `imePadding`/`adjustResize`: soft keyboard covers terminal, key row, caret |
| S5 | manifest `configChanges` | fold/unfold/split-screen recreates activity -> add `smallestScreenSize|density|uiMode` or prove recreation safe |
| S6 | reliability | session restore (tabs, cursor, scroll, layout) + hot-exit backup of unsaved buffers; crash handler writing local log + "reopen last project" |
| S7 | sandbox | SHA-256 verify rootfs download; resumable `.part`; battery-optimisation exemption step |
| S8 | files | external-change reload/conflict prompt for open buffers; rename-follow for open tabs |

### Pillar 3 — Visual identity ("calm graphite + one electric accent", ADR-A)

**Token foundation** (fixes the "dull" root cause; everything else inherits it)
- One `EasyIdeColors` extended-token object feeding both M3 `ColorScheme` and `EditorColors`, so chrome and editor share one palette (`Color.kt:11`, `Theme.kt:27-54`, `EditorColors.kt:35-91`).
- Neutrals: cool graphite ramp, e.g. `#0E1014` editor / `#13161B` panel / `#181C22` raised / `#1F242C` overlay / `#262C35` hairline. Light: warm paper `#FBFAF8` / `#F3F2EE` / hairline `#E4E2DC`.
- One accent (candidate: iris `#7C8CFF` or mint `#3DDBB5`), used only for focus, active tab bar, cursor, selection (~22% alpha), primary action, progress.
- Semantic tokens: `gitAdded #4ADE80`, `gitModified #FBBF24`, `gitDeleted #F87171`, `gitConflict #F472B6`, `info #60A5FA`, plus 8-color categorical `lanePalette` (replaces syntax-color borrowing at `SourceControlPane.kt:311,344-355`, `CommitGraphView.kt:135-136`).
- Full surface families for AMOLED (`Theme.kt:41-46`) and real high-contrast sets for chrome and editor (`Theme.kt:50-54`, `EditorColors.kt:91`).
- Terminal palette from tokens: ANSI-16, fg/bg/cursor, light variant (`EditorColors.kt:48-53,71`, `TerminalPane.kt:110`).
- Custom low-saturation syntax theme tuned to the neutrals (not Dark+).
- `Spacing` (4dp grid), `Radius` (4/6/10/16), `Elevation`/tonal steps (editor < panel < overlay), `hairline`. Replace every per-file `*_DP` constant, inline `.dp`, `Color.White` (`WorkspaceChrome.kt:73,91`), `RoundedCornerShape(...)` literals.

**Type**
- UI font (Inter or Geist Sans) + mono (JetBrains Mono or Geist Mono, optional ligatures). OFL licenses verified before adoption.
- Dense IDE scale: 11 label/status, 12 tree/tabs, 13 body, 15 title, 20 screen title, 28 display (Home/onboarding only). Section headers 11sp caps +0.6 tracking. Tabular figures in gutter/status.

**Motion**
- 120 ms press/hover, 180 ms panes, 240 ms navigation; springs (`dampingRatio 0.9`) for pane expand/collapse.
- Motion confirms cause and effect only; never loops at idle (kill `EmptyState.kt:51-62` breathing loop). Keep the reduce-motion gate.
- Shared-element transition Home card -> Workspace title.

**Signature touches**
1. Command palette overlay (blurred backdrop, fuzzy files/commands/git).
2. Accent focus system: exactly one accent marks focus (active tab top bar, focused pane hairline, tree selection pill, cursor).
3. Living status bar: surface-coloured; branch + ahead/behind + dirty count, Ln/Col, language, encoding, environment pill (no isolation claims), Claude activity pip that pulses only while running.
4. Resizable panes with grab handles, snap points, haptic ticks; 8dp inner corners; tonal separation instead of 1dp lines.
5. Coloured file-type icons + project monograms (hash-tinted) across tree, tabs, palette, Home; live mini-editor preview cards in the theme picker.

**Per-surface polish**
- Editor: current-line highlight, bold active line number, themed cursor/selection via `LocalTextSelectionColors` (`EditorPane.kt:123`); welcome view with logo watermark, recent files, shortcut cheat sheet (`EditorPane.kt:290-312`).
- Tabs: 2dp accent top bar, file-type icon, italic preview tabs, 32-40dp hit boxes (`EditorTabBar.kt:66-124`).
- Tree: per-extension icons, accent open folder, visible selection pill, IconButton-sized header actions (`FileTreePane.kt:124,178,188-204`).
- Terminal key row: shaped keys, press feedback, accent fill for latched Ctrl/Alt, haptics (`TerminalKeyRow.kt:53-66`).
- Source control: dense input + ghost-button variants for editor chrome instead of stock M3 field/purple button (`SourceControlPane.kt:162-189`); progress line + skeleton rows (`:77,111`).
- Home: skeleton cards while loading (`HomeScreen.kt:88-89`).

### Pillar 4 — Layout, navigation, input

**Home (EXPANDED; COMPACT collapses to one column + detail screen)**
```
+----+---------------------------+------------------------+
|Logo| [Search projects...] [+New] [Clone]   Envs  Settings|
|Home|---------------------------+------------------------|
|Envs| Recent                    | my-app                 |
|Set.|  > my-app   main*  2m ago | ~/proj/my-app  ubuntu  |
|    |    api-svc  dev    1d     | branch main, 3 dirty   |
|    |    notes    -      5d     | [Open] [Terminal only] |
|    | All projects (grid/list)  | recent files, sessions |
+----+---------------------------+------------------------+
```
Cards: name, path, last opened, branch/dirty, language dot, monogram; long-press/right-click menu (rename, delete, duplicate, open folder); search + sort; Import folder / Clone entry points.

**Workspace (EXPANDED)**
```
+--+----------++--------------------------++---------+
|Ex| EXPLORER || tab1 | tab2* | ...     [>]|| OUTLINE |
|SC|          ||                          || PREVIEW |
|Se|  tree    ||   editor                 || CLAUDE  |
|Tm|          ||==========================|| (right) |
|..|          || term1 | term2 | +         ||         |
|PS|          || $ _         [key row*]    ||         |
+--+----------++--------------------------++---------+
| proj - main - Ln 12,Col 4 - UTF-8 - kotlin - env ok |
+-----------------------------------------------------+
```
- Rail: Explorer, Source control, Search, Terminal, Settings; `PS` project switcher at the bottom replaces the accidental-exit Back arrow (`WorkspaceChrome.kt:57`).
- `||` / `====` draggable splitters: 1dp line, 12-16dp invisible grab zone, min/max, persisted per project (`WorkspaceScreen.kt:372-405`, `StageState.kt:316-332`).
- Right stage becomes real (outline / markdown preview / Claude) or `rightVisible`/`toggleRight` are deleted (`StageState.kt:277,295-298`).
- Key row auto-hides with a hardware keyboard (`TerminalPane.kt:86`).
- One `SidePanelContent` replaces the duplicated `ExplorerColumn`/`ExplorerOverlay` (`WorkspaceScreen.kt:281-370`).

**Adaptive mapping**
- MEDIUM: one side panel at a time, resizable bottom panel, right stage off.
- COMPACT / narrow split-screen: modal drawer for explorer+SCM (scrim, back-to-close, auto-close on open); bottom segmented bar Files | Editor | Terminal | Git; tabs as dropdown.
- Foldables via `currentWindowAdaptiveInfo()`/`WindowInfoTracker` (`WindowSize.kt:53-65`): book posture = split on hinge; tabletop = editor above, terminal below.
- Insets: IME + nav bar everywhere (`HomeScreen.kt:141-142` fixed 96dp, `WorkspaceScreen.kt:223-228` snackbar).

**Navigation model**
- Onboarding (stepped, once) -> Home (root) -> Workspace / Settings / NewProject / CredentialVault (register the dangling route, `Destinations.kt:16`). Settings reachable from the Workspace.
- Ordered `BackHandler` chain in Workspace: palette/dialog/menu -> compact drawer/sheet -> maximised pane -> unsaved-work guard -> Home.
- Onboarding steps: Welcome -> Battery exemption -> Notifications -> first environment download with progress -> Done; mark complete only at the end (`OnboardingScreen.kt:244-287`, `AppNavHost.kt:118-123`).

**Input: one command registry**
- `Command(id, titleRes, icon, enabledWhen, run)` registry. Everything else is a view over it:
  - `Keymap` table (default chord per command + user overrides JSON), dispatched from a top-level `onPreviewKeyEvent`: Ctrl+S, Ctrl+P quick open, Ctrl+Shift+P / Ctrl+K palette, Ctrl+` terminal, Ctrl+B sidebar, Ctrl+Tab MRU tabs, Ctrl+F/H find/replace, Ctrl+G go to line, Ctrl+W close.
  - Terminal accessory row (derived from same table, user-orderable; `TerminalKeys.kt:31`).
  - Palette + quick open.
  - Context menus on long-press **and** secondary click; `hoverable` + `pointerHoverIcon` (`FileTreePane.kt:148,182`, `TerminalPane.kt:168-170`).
- Tabs: auto-scroll to active, overflow list, long-press menu (close others/all/to right), middle-click close, drag reorder, MRU selection on close (`EditorTabBar.kt:233-244`, `WorkspaceViewModel.kt:274-285`).
- Pinch-zoom font size shared by editor and terminal (`EasyTerminalViewClient.kt:29` returns 1f today); explicit stylus handwriting policy (`EditorPane.kt:107-128`).

### Pillar 5 — Settings and customization (ADR-C)

**Architecture: one declarative schema drives UI, storage, search, defaults, validation.**

```kotlin
sealed class Setting<T>(
  val key: String, val category: Category,
  @StringRes val title: Int, @StringRes val desc: Int,
  val default: T, val scope: Scope, // GLOBAL | PROJECT_OVERRIDABLE
  val keywords: List<String>,
)
// Bool / IntRange(min,max,step) / Enum<E> / Str / StrList / Secret
```
- `SettingsStore` over the existing DataStore file, keyed by `Setting.key`; invalid stored values fall back to default. Replaces hand-written flow/setter/key triples (`UiPreferences.kt:29-80`) and the 5-flow `combine` (`SettingsViewModel.kt:349-355`).
- Secrets in a Keystore-backed store beside `GitCredentials`; resolution order project > global > default.
- `ResolvedSettings` CompositionLocal from `MainActivity`; terminal applies font/colors/cursor in `AndroidView.update`, env/shell at session creation (`TerminalPane.kt:100,228`, `WorkspaceViewModel.kt:449`).
- UI: generic `SettingRow` per type; tablet = category rail + detail, phone = list; search over title/desc/keywords/key; modified dot + per-row reset; "Edit as JSON" (non-defaults only, schema-validated); export/import via SAF (secrets excluded); named profiles. No cloud sync (`services/backend` stays empty).

**Settings that should exist**
- **Appearance**: themeMode, accentColor, importedTheme, uiScale, density (compact/comfortable), reduceMotion (system/on/off), iconTheme, showStatusBar, showActivityBar.
- **Editor**: fontFamily, fontSize, lineHeight, fontLigatures; tabSize, insertSpaces, detectIndentation; wordWrap, lineNumbers (on/off/relative), highlightCurrentLine, renderWhitespace, rulers, bracketPairColorization, cursorStyle, cursorBlink; autoCloseBrackets, trimTrailingWhitespace, insertFinalNewline, largeFileThresholdKb, syntaxHighlighting; markdownPreviewDefault.
- **Files**: autoSave (off/afterDelay/onFocusLoss/onBackground), autoSaveDelayMs, encoding, eol, excludeGlobs, showHiddenFiles, respectGitignore, confirmDelete, defaultProjectsFolder.
- **Terminal**: fontFamily, fontSize, colorScheme, scrollback, cursorStyle, cursorBlink, bell (none/vibrate/sound), shell, env, lang, backIsEscape, accessoryBar (auto/always/never), accessoryKeys, pinchZoom, confirmCloseRunning.
- **Keyboard**: keybinding overrides, hardwareShortcutsEnabled.
- **Git**: userName, userEmail (project-overridable; prompt on first commit — today every commit is `easyIDE <dev@easyide.local>`, `WorkspaceViewModel.kt:635-636`), credentials list/add/forget (backend exists, `GitCredentials.kt:30-38`), autoFetch, confirmDiscard, defaultBranch, commitGraphEnabled.
- **Sandbox**: defaultEnvironment, chrootEnabled (gated on `RootDetector`, per 0002; `NewProjectViewModel.kt:38-39`), environment management, storage usage, keepAliveForegroundService.
- **Claude Code**: apiKey (secret, injected into process env per 0012 pattern), model, configDir, autoLaunchInTerminal, extraArgs.
- **Workspace**: restoreOpenTabs, openLastProjectOnLaunch, defaultSidePanel, stage sizes.
- **Advanced**: Edit JSON, export, import, reset all, profiles, diagnostics.

Theme picker becomes a grid of live mini-editor preview cards; labels from string resources, not enum names (`SettingsScreen.kt:297-299`, `:208`, `NewProjectScreen.kt:303`).

### Pillar 6 — Features (P0/P1/P2, S/M/L)

**Editor**: virtualised surface [P0 L] · undo/redo [P0 M] · find/replace + project search via ripgrep in sandbox [P0 M] · quick open + go to line [P1 S] · autosave [P1 S] · auto-indent, bracket match/close, comment toggle [P1 M] · git diff gutter [P1 M] · LSP client starting with Python (diagnostics, completion, hover, definition) [P1 L] · split editor side by side [P1 M] · image/PDF/hex viewers [P2 M] · folding, multi-cursor, minimap [P2 L].

**Terminal**: tmux per project `proj_<id>`, sessions owned by the foreground service [P0 M] · sticky Ctrl/Alt, configurable row [P1 S] · font/pinch/theme [P1 S] · clickable `file:line` links -> editor [P1 M] · per-project Run task button [P2 M] · search in scrollback [P2 S].

**Git**: clone/pull/push UI with PAT entry (backend done) [P0 M] · author identity [P0 S] · diff view [P0 M] · branch create/switch/delete [P1 S] · branch/dirty on Home cards [P1 S] · merge editor on ResolveMerger [P1 L] · stash, amend, stage/discard hunk [P2 M] · SSH keys [P2 M] · GitHub device-flow OAuth [P2 S, blocked on client_id].

**Claude Code** (no code exists today): one-tap install in sandbox (Node preset + npm) with verification [P0 M] · dedicated Claude pane/tab per project, tmux-backed [P0 M] · API key/OAuth through Keystore + process env [P0 M] · "send selection/file/diff to Claude" [P1 M] · review panel for files Claude changed (watcher exists) [P1 M] · IDE/MCP bridge exposing open file + selection [P2 L].

**Projects**: import folder / clone from Home [P0 S] · rename/duplicate/delete, recents, search [P1 S] · templates per preset [P2 S] · per-project settings [P2 M].

**Sandbox**: rootfs SHA-256 [P0 S] · disk usage + cleanup per env [P1 S] · env export/backup/restore [P1 M] · localhost preview WebView / port list for dev servers [P1 M] · verify chroot launcher on rooted device [P1 M] · apt package UI [P2 M] · resumable downloads [P2 S].

**Files**: multi-select, copy/move, drag in tree [P1 M] · share sheet in/out, open-with [P1 M] · gitignore/hidden filtering [P1 S] · trash/undo delete [P2 S].

**Tablet**: hardware shortcuts [P0 M] · mouse right-click/hover/scroll/I-beam [P1 M] · resizable splits [P1 M] · multi-window, second window per project [P2 M] · drag-and-drop from other apps [P2 M] · stylus scribble [P2 L].

**Accessibility**: fix 11 `null` contentDescriptions + key-row labels [P1 S] · font scaling, WCAG check on HC/AMOLED [P1 S] · 48dp targets [P2 S] · TalkBack semantics for editor/terminal [P1 L].

**Diagnostics (no telemetry)**: sandbox diagnostics screen (backend, rootfs version, disk, proot version, uname, last errors) [P1 S] · local log viewer + export [P1 S] · self-test smoke checks [P2 S] · dev perf overlay (tokenize time, memory, frame time) [P2 S].

**Collaboration/sync**: SAF mirror two-way conflict detection [P1 M] · settings/dotfiles sync through a git repo [P2 M].

### Pillar 7 — Docs and code hygiene

- `docs/README.md` mental model, `ui-shell/arch.md:7`, `design-system/arch.md:3` still describe a Theia WebView; 0006 made the shell native. Update them.
- `ui-shell/tracker.md`: regex `SyntaxHighlighter` (now TextMate), non-existent `StagePane.kt`, "pending git integration" on Home, credential-helper row (superseded by 0012).
- `sandbox-runtime/tracker.md`: stale `apt-get install git` exit-100 row, credential-helper row, Theia Claude-extension row, duplicate key-bar row.
- `extensions/tracker.md`: credential-socket row predates 0012. `design-system/tracker.md`: "Theia theme sync" framed as next.
- Two ADRs numbered 0010 — renumber one or document the collision in `decision/README.md`.
- Chainlog gap: nothing for W35 "track docs in git".
- `WorkspaceViewModel.kt` is 638 lines (> 600 cap) — split along PS1 (tree / tabs / terminals / git ViewModels or controllers).
- ~20 inline UI strings in workspace files -> `strings.xml` (`WorkspaceChrome.kt`, `FileTreePane.kt`, `SourceControlPane.kt`, `EditorPane.kt`, `WorkspaceScreen.kt`, `FileContextMenu.kt`).
- Dead tokens `terminalText`/`terminalPrompt` (`EditorColors.kt:48-49`) and stale KDoc (`StageState.kt:283-287`).
- Clarify CLAUDE.md "infra stays README-only" now that `.github/workflows` exist.

## Roadmap

Order is by dependency, not by appeal. Each phase is shippable on its own.

1. **Phase 0 — stop the bleeding (1 week)**: S1-S5, PF1-PF10. Visible result: no lost work, launch without white flash, smooth scroll, faster startup.
2. **Phase 1 — foundations (2-3 weeks)**: ADR-A..D; token system + fonts (Pillar 3 foundation); `SettingsStore` schema (Pillar 5); command registry + keymap + palette (Pillar 4 input); PS1-PS9; `WorkspaceRegistry`. Visual result: one coherent look everywhere.
3. **Phase 2 — layout + editor (3-4 weeks)**: resizable panes, new Home, adaptive/foldable, stepped onboarding, status bar, tabs; PE1 virtualised editor with undo/redo, find/replace, quick open.
4. **Phase 3 — features**: Git remote UI + diff, Claude Code integration, tmux sessions, session restore/hot-exit, LSP, diagnostics, accessibility pass.
5. **Continuous**: Pillar 7 hygiene with each touched area; baseline-profile + macrobenchmark regression gate in CI.

## Open questions

- Accent hue: iris `#7C8CFF` vs. mint `#3DDBB5` (or user-chosen with a curated default)?
- ADR-B: own a virtualised Compose editor, or adopt an existing View-based editor? Needs license + maintenance verification either way.
- Is the reported lag on debug builds? If so PF1/PF2 plus a release build may account for much of it; measure before and after.
- Should Theia remain on the roadmap at all, given the native shell is now the product?
