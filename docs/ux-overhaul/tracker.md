# Feature: UX Overhaul — Tracker

Status legend: not-started / in-progress / done / blocked

IDs match [arch.md](arch.md).

| Component | Status | Notes |
|---|---|---|
| **Phase 0 — safety** | | |
| S1 Back guard + no silent pty/buffer loss | not-started | full fix depends on ADR-D `WorkspaceRegistry` |
| S2 Dirty-tab close confirm, 48dp hit area | not-started | |
| S3 Project-not-found state | not-started | |
| S4 IME insets + `adjustResize` | not-started | |
| S5 `configChanges` for fold/split | not-started | |
| **Phase 0 — perf quick wins** | | |
| PF1 R8 + shrinkResources | not-started | needs keep rules for Termux JNI, JGit, TextMate |
| PF2 Baseline profile module | not-started | |
| PF3 Editor scroll via derivedStateOf | not-started | |
| PF4 Highlight window only when scroll idle | not-started | |
| PF5 Shell params / isReady off main | not-started | |
| PF6 Single-flight git refresh + cached Repository | not-started | |
| PF7 Terminal toggle without editor relayout | not-started | |
| PF8 Splash + DayNight window theme | not-started | |
| PF9 Lazy TextMate init | not-started | |
| PF10 Batched install log | not-started | |
| PF11 Syntax highlighting first paint (memoised scope roles, no debounce on open/tab/scroll, 8-tab tokenizer LRU, grammar prewarm on tree load, cancellable passes) | done | compiles + assembles; not measured on device. SQLite/disk token cache rejected: state stacks are not serialisable and regex compile, not tokenizing, is the cold cost |
| **Phase 1 — foundations** | | |
| ADR-A Visual identity | not-started | fonts need primary-source license check |
| ADR-B Editor engine | not-started | |
| ADR-C Settings schema + theme import | not-started | |
| ADR-D Workspace session lifetime | not-started | |
| Unified `EasyIdeColors` + spacing/radius/elevation tokens | not-started | |
| UI + mono fonts, dense type scale | not-started | |
| Terminal palette from tokens (incl. light) | not-started | |
| Git semantic + lane palette tokens | not-started | |
| `SettingsStore` schema + generic settings UI + search | not-started | |
| Command registry + Keymap + hardware shortcuts | not-started | |
| Command palette + quick open | not-started | |
| PS1 Split `WorkspaceUiState` (also fixes 600-line cap) | not-started | |
| PS2 Per-tab `TextFieldState` | not-started | |
| PS3 Version counter / incremental line count | not-started | |
| PS4-PS9 remaining structural perf | not-started | |
| **Phase 2 — layout + editor** | | |
| Resizable, persisted panes | not-started | |
| New Home (list-detail, rich cards, search) | not-started | |
| Compact bottom switcher + modal drawer | not-started | |
| Foldable postures | not-started | |
| Stepped onboarding | not-started | |
| Living status bar | not-started | |
| Tab strip (overflow, reorder, MRU, menus) | not-started | |
| Right-click / hover / pinch zoom | not-started | |
| PE1 Virtualised editor | not-started | blocked on ADR-B |
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
