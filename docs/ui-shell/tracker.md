# Feature: UI Shell — Tracker

Status legend: `not-started` / `in-progress` / `done` / `blocked`

## Components

| Component | Status | Notes |
|---|---|---|
| Splash / Bootstrap screen | not-started | Root detection + sandbox backend init progress UI |
| Onboarding (battery exemption, permissions, biometric setup) | in-progress | Animated welcome screen, completion persisted. Real permission/battery steps pending sandbox runtime (arch.md §7 — not optional) |
| Project List (Home) screen | in-progress | Live project data, adaptive grid (1 col compact / adaptive otherwise), animated empty state, environment badge with shared-count. Git branch/dirty indicator pending git integration |
| New/Import project flow | in-progress | Full environment-choice form (reuse vs create new, backend picker, chroot warning), a **preset picker** (Ubuntu base / build tools / Node.js / Python), and a **storage-folder picker** (SAF, mirrors to/from internal storage or SD card - see sandbox-runtime tracker) all wired to the runtime. Clone-from-URL still pending |
| Settings: default projects folder | done | SAF picker + persisted default, pre-fills the New Project form's own picker. Verified on device |
| Project Workspace screen (native IDE shell) | in-progress | Working IDE per [decision 0006](../decision/0006-native-ide-shell-before-theia.md): activity rail, file explorer, tabbed editor with gutter + syntax colouring, real PTY terminal, status bar — all on real files. Missing: LSP, git UI |
| File explorer (real, lazy-expanding tree) | done | `FileTreePane` + `ProjectFiles`; verified on device. **Auto-syncs with terminal-driven changes**: `ProjectFileWatcher` (sandbox-runtime, `FileObserver`-based, debounced 400ms) watches the project root plus every expanded directory and triggers `refreshTree()` on create/delete/move/write - a shell `touch`/`rm`/`git clone` writes straight to the bind-mounted directory with no other way for the explorer to know. Verified on device: file created and deleted via the terminal both appeared/disappeared in the explorer with no manual refresh |
| Editor: tabs, dirty state, edit, save | done | `EditorPane`/`EditorTabBar`; save verified to survive a full app restart |
| Syntax highlighting | done | `SyntaxHighlighter` — regex-based (comments/strings/numbers/keywords), not a parser |
| Markdown preview | in-progress | Headings, paragraphs, bullets, numbered lists, blockquotes, rules, fenced code with language label, inline emphasis/code, **tappable links**, and **rendered mermaid diagrams** (`MermaidView`, bundled mermaid 11.16.1 MIT, offline). **Missing: tables, images, nested lists, task lists**; plantuml/dot render as labelled source |
| Terminal pane (real PTY terminal) | done | Vendored Termux `terminal-emulator`/`terminal-view` ([decision 0010](../decision/0010-pty-terminal-vendored-termux.md)): `TerminalView` hosted directly via Compose `AndroidView`, multiple tabs (renameable via long-press), real shell prompt (no separate input row), horizontal padding so text and cursor don't touch the screen edge. Verified on a Xiaomi Pad 6: keyboard input round-trips through the pty to the shell and back, `Ctrl-C` delivers a real `SIGINT`, ANSI colors render correctly, new shells default to a non-root `dev` user (see sandbox-runtime tracker). Shell is `dash` (no readline), so arrow-key history is a shell limitation, not a terminal one — matches real-terminal behavior. **Install progress** (`onInstallLinux`) prints as real scrolling output straight into the terminal tab active when install started (`WorkspaceViewModel.appendInstallLog`, via `TerminalEmulator.append`), not a Snackbar per line and not a single truncated status line - both were tried and rejected as regressions |
| Keyboard accessory bar | in-progress | `TerminalKeyRow` pinned under `TerminalView`: `Esc`, `^C`, `^L`, arrow keys, `Tab`, clear, and the shell symbols a soft keyboard buries, writing raw bytes straight to the pty. Not yet shared with a Theia terminal / Neovim, which do not exist yet |
| Settings screen | in-progress | Theme picker (persisted) + environment list with dependent-project counts and delete, error surfaced via snackbar. Sandbox diagnostics pending |
| Credential vault screen | not-started | Depends on credential-helper daemon (sandbox-runtime) |
| Navigation graph (Home root, one-level-deep stack) | done | `ui/navigation/` — routes in `Destinations.kt`, graph + animated transitions in `AppNavHost.kt` |
| Theme/preference persistence (DataStore) | done | `data/UiPreferences.kt` — theme mode and onboarding completion |
| Window size classes (compact/medium/expanded + height) | done | `ui/foundation/WindowSize.kt`, provided via `LocalWindowSize` |
| Motion system + reduce-motion compliance | done | `ui/foundation/Motion.kt` — `motionSpec()` collapses to `snap()` when system animations are off |
| Navigation transitions | done | `ui/foundation/NavTransitions.kt` — direction-aware slide+fade, zero-duration when motion disabled |
| Reusable components (EmptyState, ChoiceCard, EnvironmentBadge, StagePane) | done | `ui/components/`, `ui/screens/workspace/StagePane.kt` |
| WebView<->Theia touch-resize gesture passthrough | not-started | Open question in arch.md — needs a spike |
| Autosave-on-focus-change default | not-started | Open question — needs user-testing before locking in |
| Stage system (left/main/right/bottom via Theia ApplicationShell) | not-started | See [decision 0003](../decision/0003-multi-stage-panels-theia-widgets.md) |
| PDF viewer widget (PDF.js) | not-started | |
| Video player widget (native `<video>`) | not-started | Must release decoder on background per arch.md memory section |
| Docx viewer widget (mammoth.js) | not-started | |
| Xlsx viewer widget (SheetJS CE) | not-started | |
| Declarative keybinding table (drives both accessory bar + physical-keyboard bindings) | not-started | Single source of truth — see arch.md "Input" section |
| Widget/tab LRU eviction under memory pressure | not-started | Threshold needs on-device profiling, not a guessed constant |

## Verification status

Verified on a Waydroid container (Android 13 / API 33, x86_64, 1920x1168) by installing the debug APK and driving it over adb:

- Onboarding → Home → New Project → Workspace navigation works.
- Creating a project seeds starter files and they appear in the explorer.
- Opening files creates tabs; editing marks the tab dirty; Save writes to disk.
- **Persistence confirmed across a full `am force-stop` + relaunch**: both the project list and an edited file's contents (`a = 5;`) survived.
- Terminal ran `ls` and `ls -1 src`, returning output matching the explorer, plus non-zero exit codes for bad commands.

Not yet verified: compact/medium width behaviour (only tested at expanded width), reduce-motion, and the light/AMOLED/high-contrast themes.

**Real PTY terminal verified on a Xiaomi Pad 6** (Android 13, arm64-v8a, enforcing SELinux): `dumpsys input_method` confirmed `TerminalView` as the served input view; typed `ls` + Enter produced real shell output and a fresh prompt; `Ctrl-C` interrupted a running `sleep 10` immediately (exit 130); `printf` with `\033[31m`-style escapes rendered actual red/green/blue text. See [decision 0010](../decision/0010-pty-terminal-vendored-termux.md).

## Dependencies on sandbox-runtime

This feature cannot progress past static screens without:
- Theia backend integration (renders the actual Workspace content)
- Foreground Service + tmux (terminal panel persistence)
- Credential-helper daemon (Credential vault screen has nothing to manage without it)

See [/docs/sandbox-runtime/tracker.md](../sandbox-runtime/tracker.md) for those statuses.
