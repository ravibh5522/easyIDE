# Feature: UX Overhaul — Tracker

Status legend: not-started / in-progress / done / blocked

IDs match [arch.md](arch.md).

| Component | Status | Notes |
|---|---|---|
| **Phase 0 — safety** | | |
| S1 Back guard + no silent pty/buffer loss | done | superseded by ADR-D: leaving parks the workspace (shells and buffers stay alive), the unsaved-changes guard now protects the explicit Close project button. built + unit tests; not run on device |
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
| ADR-D Workspace session lifetime | done | [0023](../decision/0023-workspace-session-lifetime.md): app-scoped `WorkspaceRegistry` keyed by project id (park / re-attach / explicit close), park limit setting `workspace.maxParkedProjects`, LRU eviction with a backup under `onTrimMemory`. JVM tests of the policy and registry; not run on a device |
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
| New Home (list-detail, rich cards, search) | done | List-detail at EXPANDED, one column + detail screen below; cards with monogram, branch/dirty (JGit `GitService.summary`, cached, 2 at a time), language dot, environment badge, relative time; search + sort; long-press/right-click menu (rename, duplicate, delete with confirm, change environment, open folder location); import folder (SAF) and clone (https, through `GitRemote`, environment must be installed; no PAT entry UI yet, so private repos fail with an explanatory message); skeleton, empty state, Install Linux prompt. `ProjectManager.rename/duplicate/delete` real, symlink-safe (`SafeTree`), never touch environments. "Open with terminal" reveals the terminal panel (no terminal-only workspace mode exists); sessions info not shown (no tmux sessions yet). Settings added to the workspace rail. Built + unit tests; not run on device |
| Compact bottom switcher + modal drawer | not-started | |
| Foldable postures | not-started | |
| Stepped onboarding | done | Welcome, battery exemption (real system intent, state re-read on resume), notifications (API 33+), first environment (preset, real download/unpack/setup progress, cancel, retry/resume, classified actionable errors), Done; complete only at the end; skipping the environment leaves Home's Install Linux prompt (same content on `install_linux`). See [0021](../decision/0021-onboarding-permissions-and-install-keepalive.md). Built + unit tests; not run on device |
| Living status bar | not-started | |
| Tab strip (overflow, reorder, MRU, menus) | not-started | |
| Right-click / hover / pinch zoom | not-started | |
| PE1 Virtualised editor | not-started | direction set by 0018: own line-virtualised Compose editor keeping `EditorGeometry` / decoration seams |
| Undo/redo, find/replace, go to line | not-started | |
| **Phase 3 — features** | | |
| Git remote UI, identity, diff view | not-started | backend exists |
| Claude Code install + pane + key handling | not-started | |
| Foreground service keep-alive for live workspaces | done | `SandboxKeepAlive` starts `SandboxForegroundService` with the first live workspace and stops it with the last; notification shows the count and offers Stop all; not sticky; Android 15 dataSync timeout handled. Not run on a device |
| tmux sessions owned by foreground service | not-started | deliberately not built: tmux dies with the app process (children of it), needs tmux in every rootfs, and adds nothing over park + snapshot; see 0023 sec 9 |
| Session restore + hot-exit | done | S6, S8. Versioned snapshot + atomic dirty-buffer backups per project (2 s timer, park/evict, `onStop`), restore with conflict handling and a "Restored N unsaved files" notice, `workspace.restoreOpenTabs` / `workspace.openLastProjectOnLaunch`; external-change reload / conflict dialog for open buffers, rename-follow for files and folders. Read-only tabs (binary, truncated) are not re-checked. JVM tests; process-death restore not run on a device |
| LSP (Python first) | not-started | |
| Diagnostics screen + local logs | done | Settings > Diagnostics: sandbox per environment (backend, state, rootfs version, on-demand image checksum, disk use, clear package caches), proot version, uname/ABI, app/device, storage table with cleanup (symlink-safe), recent errors, export via SAF and share sheet. File-backed size-capped log (2 x 256 KiB), uncaught-exception handler writing crash reports, recovery dialog on next launch (reopen last project / share log). Extension log and LSP server state are bridged through their public ports; full LSP session lines need a one-line `LspRuntime.logSink` change (not made: extension-owned path). Not run on a device |
| Accessibility pass | not-started | |
| **Hygiene** | | |
| Stale Theia wording in README / ui-shell / design-system docs | not-started | |
| Stale tracker rows (ui-shell, sandbox-runtime, extensions, design-system) | not-started | |
| Duplicate ADR 0010 numbering | not-started | |
| Inline strings -> strings.xml | not-started | |
