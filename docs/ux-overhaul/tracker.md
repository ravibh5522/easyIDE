# Feature: UX Overhaul — Tracker

Status legend: not-started / in-progress / done / blocked

IDs match [arch.md](arch.md).

| Component | Status | Notes |
|---|---|---|
| **Phase 0 — safety** | | |
| S1 Back guard + no silent pty/buffer loss | done | unsaved-changes guard on back/rail; session survival still needs ADR-D. built + unit tests; not run on device |
| S2 Dirty-tab close confirm, 48dp hit area | done | built + unit tests; not run on device |
| S3 Project-not-found state | done | plus PS7 (second HomeViewModel removed). built + unit tests; not run on device |
| S4 IME insets + `adjustResize` | done | built + unit tests; not run on device |
| S5 `configChanges` for fold/split | done | smallestScreenSize only; density/uiMode left out deliberately (terminal px size, bar styling). built + unit tests; not run on device |
| **Phase 0 — perf quick wins** | | |
| PF1 R8 + shrinkResources | done | R8 + shrinkResources on release/canary; JGit message-bundle keep rule; release/canary assemble OK (~6.2 MB). Runtime under R8 not verified on device |
| PF2 Baseline profile module | in-progress | profileinstaller + :baselineprofile generator (startup, open project); profile not generated - needs a device |
| PF3 Editor scroll via derivedStateOf | done | built + unit tests; not run on device |
| PF4 Highlight window only when scroll idle | done | overscan 250. built + unit tests; not run on device |
| PF5 Shell params / isReady off main | done | built + unit tests; not run on device |
| PF6 Single-flight git refresh + cached Repository | done | WorkspaceGitController single-flight + cached Repository; self-save event skipping not done (watcher reports dirs). built + unit tests; not run on device |
| PF7 Terminal toggle without editor relayout | done | TerminalDock keeps terminal composed. built + unit tests; not run on device |
| PF8 Splash + DayNight window theme | done | core-splashscreen held until settings+onboarding load; light/night window themes. Not verified on device |
| PF9 Lazy TextMate init | done | index read on first highlight/prewarm/LanguageConfigs lookup |
| PF10 Batched install log | done | built + unit tests; not run on device |
| PF11 Syntax highlighting first paint (memoised scope roles, no debounce on open/tab/scroll, 8-tab tokenizer LRU, grammar prewarm on tree load, cancellable passes) | done | compiles + assembles; not measured on device. SQLite/disk token cache rejected: state stacks are not serialisable and regex compile, not tokenizing, is the cold cost |
| **Phase 1 — foundations** | | |
| ADR-A Visual identity | done | [0019](../decision/0019-visual-identity.md) (Proposed: accent pending owner confirmation); iris accent, Geist/Geist Mono OFL-1.1 verified from vercel/geist-font v1.7.2 |
| ADR-B Editor engine | done | [0018](../decision/0018-editor-engine-and-decorations.md); decoration layers landed on the current editor |
| ADR-C Settings schema + theme import | not-started | |
| ADR-D Workspace session lifetime | not-started | |
| Unified `EasyIdeColors` + spacing/radius/elevation tokens | done | `ThemeTokens`/`ColorToken` -> ColorScheme + EditorColors; Light/Dark/AMOLED/HC dark+light; `Spacing`/`Radius`/`Elevation`/`Stroke`/`IconSize`/`ControlSize`; WCAG test; VS Code key map + pure mapper (file loading next wave). Chrome, tabs, tree, key row, SCM, Home, Settings tokenised; accent tab bar, tree pill, current line + active number, themed cursor/selection, Home/SCM skeletons, theme preview cards. Built + unit tests; not run on device |
| UI + mono fonts, dense type scale | done | Geist 400/500/600 + Geist Mono 400/700 (editor, terminal), 11/12/13/15/20/28 scale. Not seen on device |
| Terminal palette from tokens (incl. light) | done | `TerminalTheme` writes ANSI-16 + fg/bg/cursor into Termux's default scheme, resets live emulators once per palette change. Not run on device |
| Git semantic + lane palette tokens | done | |
| `SettingsStore` schema + generic settings UI + search | in-progress | schema + store + generated rows + search; editor/terminal font size, line height live; layering beyond global not yet |
| Command registry + Keymap + hardware shortcuts | in-progress | registry, default keymap, hardware dispatch incl. terminal focus done; no when-clauses/user keymap yet |
| Command palette + quick open | in-progress | palette done (Ctrl+Shift+P + rail button); quick open not yet |
| PS1 Split `WorkspaceUiState` (also fixes 600-line cap) | not-started | |
| PS2 Per-tab `TextFieldState` | not-started | |
| PS3 Version counter / incremental line count | done | LineCount cache + lazy isDirty |
| PS4-PS9 remaining structural perf | in-progress | PS5, PS7, PS8 done; PS4 editor part done; PS6, PS9 not started |
| **Phase 2 — layout + editor** | | |
| Resizable, persisted panes | not-started | |
| New Home (list-detail, rich cards, search) | not-started | |
| Compact bottom switcher + modal drawer | not-started | |
| Foldable postures | not-started | |
| Stepped onboarding | not-started | |
| Living status bar | not-started | |
| Tab strip (overflow, reorder, MRU, menus) | not-started | |
| Right-click / hover / pinch zoom | not-started | |
| PE1 Virtualised editor | not-started | direction set by 0018: own line-virtualised Compose editor keeping `EditorGeometry` / decoration seams |
| Undo/redo, find/replace, go to line | not-started | |
| **Phase 3 — features** | | |
| Git remote UI, identity, diff view | not-started | backend exists |
| Claude Code install + pane + key handling | not-started | |
| tmux sessions owned by foreground service | not-started | |
| Session restore + hot-exit | not-started | |
| LSP (Python first) | not-started | |
| Diagnostics screen + local logs | not-started | |
| Accessibility pass | not-started | |
| **Hygiene** | | |
| Stale Theia wording in README / ui-shell / design-system docs | not-started | |
| Stale tracker rows (ui-shell, sandbox-runtime, extensions, design-system) | not-started | |
| Duplicate ADR 0010 numbering | not-started | |
| Inline strings -> strings.xml | not-started | |
