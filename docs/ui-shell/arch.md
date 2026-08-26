# Feature: UI Shell — Architecture

Covers what actually gets built on top of the runtime described in [/docs/sandbox-runtime/arch.md](../sandbox-runtime/arch.md): the screen inventory, navigation flow, and the editor/terminal interaction flows a user actually experiences. See [tracker.md](tracker.md) for implementation status.

## Overview

The native Compose layer is deliberately thin (per [decision 0001](../decision/0001-ide-foundation-theia.md)) — most of "the IDE" (file explorer, editor, git UI, terminal panel docking) is Theia running inside a WebView, not custom Compose screens. Compose owns only: app-level navigation between projects, settings/credentials, onboarding, and the chrome around the WebView (keyboard accessory bar, panel-resize touch handling passthrough).

## Screen inventory (UI schema)

| Screen | Owner | Purpose |
|---|---|---|
| **Splash / Bootstrap** | Compose | First launch only (or on cold rebuild per [session-management rebuild flow](../sandbox-runtime/arch.md#4-session-management)): root detection, sandbox backend selection, bootstrap tarball extraction progress |
| **Onboarding** | Compose | Battery-optimization exemption prompt (required — see arch.md §2 Android constraints), storage permission, optional biometric setup for the credential vault |
| **Project List (Home)** | Compose | Root screen. List of projects: name, path, last-opened, git branch/dirty indicator. Actions: open, new project (empty / clone from URL / import via SAF), delete, settings |
| **Project Workspace** | Compose shell + Theia WebView | The core IDE screen — see layout below. One live Theia backend at a time, per [multi-project session mapping](../sandbox-runtime/arch.md#multi-project-session-mapping) |
| ↳ File explorer, editor tabs, git panel, extensions | **Theia (inside WebView)** | Not custom Compose — Theia ships all of this. Compose does not reimplement any of it |
| **Settings** | Compose | Sandbox backend toggle (proot / chroot+BusyBox, rooted devices only, per [decision 0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md)), keyboard accessory customization, storage/sandbox size management, diagnostics |
| **Credential vault** | Compose | View/add/remove git identities (SSH keys, PATs), biometric-unlock toggle — see [credential helper protocol](../sandbox-runtime/arch.md#5-credential-helper-protocol) |
| **New/Import project flow** | Compose | Modal: empty project, `git clone <url>`, or import via Android's Storage Access Framework |

## Navigation flow

```
[Splash/Bootstrap] --first run only--> [Onboarding] --+
        |                                              |
        +----------------------------------------------+
        v
[Project List (Home)]  <---------------------------------+
   |         |            |                              |
   | tap     | "+ New"    | menu icon                     |
   v         v            v                                |
[Workspace] [New/Import   [Settings] --> [Credential vault] |
   |         flow]           |                              |
   | back                     +------------------------------+
   +--------------------------------------------------------->
```

- **Home is the nav root** — a simple stack, not a deep hierarchy. Workspace, New/Import, and Settings are all one level deep from Home.
- **Back from Workspace returns to Home**, not to app-exit — the underlying tmux session and (per the multi-project model) other projects' terminals keep running; only the active Theia backend for that project spins down.
- **Settings and Credential vault** are reachable from both Home and Workspace via the same menu affordance, since sandbox backend choice and credentials aren't project-scoped.

## Stage system (left / main / right / bottom)

Built directly on Theia's `ApplicationShell` dock areas per [decision 0003](../decision/0003-multi-stage-panels-theia-widgets.md) — "stages" in product language are these dock areas, not a custom panel system:

```
+---------------------------------------------------------------+
| Top bar: project name . branch/git-status . menu               |
+------------+----------------------------------------------------+
| LEFT STAGE  |  MAIN STAGE                                        |
| (file       |  Tabbed widgets: code editor, PDF viewer, docx/    |
|  explorer   |  xlsx viewer, video player, ... any WidgetFactory- |
|  by default,|  registered content type (see decision 0003)      |
|  can host   |                                              +----+
|  any widget)|                                              |RIGHT|
|             |                                              |STAGE|
|             |                                              |(git |
|             |                                              |diff,|
|             |                                              |ext- |
|             |                                              |ras) |
+------------+----------------------------------------------------+
| BOTTOM STAGE: terminal panel (tabs: bash / claude / nvim ...) --  |
| resizable, collapsible, drag-handle. Rendered by terminal-view,  |
| PTYs owned by tmux per project (see sandbox-runtime arch.md)     |
+---------------------------------------------------------------+
[Keyboard accessory bar -- Ctrl/Esc/Tab/Arrows -- appears above the |
 soft keyboard whenever a terminal or Theia text input has focus]  |
```

**What "each stage can render anything" means concretely**: any stage (left/main/right/bottom) is a Theia dock area that can host any widget registered via `WidgetFactory` — the file explorer, an editor tab, a PDF viewer, a video player, and (later) a custom-extension-provided widget are all the same kind of citizen to the shell. There's no special-casing of "the code stage" vs "the PDF stage" — a widget declares which area it defaults to opening in, and the user can still drag it to a different stage like any Theia panel.

**Default stage assignments** (user-movable, not hardcoded into the shell):
| Stage | Default content |
|---|---|
| Left | File explorer |
| Main | Editor tabs, PDF/docx/xlsx/video viewers |
| Right | Git diff view, extension-provided side panels |
| Bottom | Terminal |

Panel docking/resizing (stage width/height, tab drag-and-drop between stages) is handled by **Theia's own layout system** inside the WebView — Compose's job is only to size the WebView correctly and let touch-drag gestures pass through to Theia's resize handles, not to reimplement docking (open question below on the exact gesture-passthrough mechanism).

**Extensibility (for the later custom-extensions system)**: registering a new content type for any stage means implementing a Theia widget + `WidgetFactory` + `AbstractViewContribution`, per decision 0003 — this is the one, already-proven extension point; no separate native "stage plugin API" needs to be designed.

## Editor flow

1. Tap a file in the explorer (Theia) → opens as a tab in the editor area.
2. Typing triggers Theia's LSP-backed autocomplete/diagnostics if a language server extension is installed for that language.
3. External edits (e.g. Claude Code CLI editing the same file from the terminal panel) trigger Theia's built-in file-watcher: clean buffer → silent reload; dirty buffer → conflict prompt. No custom code needed (see [sandbox-runtime data flow](../sandbox-runtime/arch.md#data-flow-user-asks-claude-code-to-edit-a-file-sees-it-live-in-theia)).
4. Save: default to **autosave-on-focus-change** rather than relying on `Ctrl+S` muscle memory, since touch users won't reliably reach for it — the keyboard accessory bar still exposes Ctrl+S for users who want explicit control.
5. Git diff decorations/gutters render inline in the editor — Theia-native, no custom Compose UI.
6. Tab overflow: horizontal scroll + overflow menu for open tabs, since tablet width (especially in split-screen multitasking) is tighter than desktop.

## Terminal flow

1. Terminal panel is docked at the bottom of Workspace by default, resizable/collapsible via drag handle (matches the VS Code convention users already know).
2. Panel shows one tab per PTY in the current project's tmux session (`bash`, `claude`, `nvim`, ...); `+` spawns `tmux new-window`.
3. Focusing a terminal pane brings up the soft keyboard **and** the keyboard accessory bar (Ctrl/Esc/Tab/Arrows/Alt) — shared UI, built once, benefits Claude Code's TUI, Neovim, and plain shell use equally (see prior discussion on this synergy).
4. Text selection uses Android's native selection handles adapted to terminal-view's selection mode; copy/paste bridges to the system clipboard.
5. Terminal state persists across backgrounding (foreground Service) and, best-effort, across process kill via tmux reattach — see the full [rebuild flow](../sandbox-runtime/arch.md#rebuild-flow-servceprocess-was-killed) for what happens when reattach fails.
6. Switching projects in Home does **not** kill other projects' terminals — their tmux sessions stay alive dormant; only the active Theia backend changes.

## Input: shortcuts, gestures, external devices

Tablet-class devices realistically see three input modes, often switching mid-session (a tablet with a detachable keyboard case):

| Input mode | Handling |
|---|---|
| **Touch only** | Keyboard accessory bar (Ctrl/Esc/Tab/Arrows) for terminal/Theia text focus; tap/long-press/drag for selection and stage-resize; standard Android back-gesture navigates the app-level stack (Home ↔ Workspace ↔ Settings), not consumed by Theia |
| **Physical/Bluetooth keyboard** | Standard desktop-IDE shortcuts (Ctrl+S save, Ctrl+P quick-open, Ctrl+\` toggle terminal, Ctrl+B toggle left stage, etc.) — Theia already ships a keybinding system for its own widgets; native Compose screens (Home, Settings) need their own keybinding registration for the same shortcuts to feel consistent outside the WebView. Keyboard accessory bar auto-hides when a physical keyboard is detected (`Configuration.keyboard`/hardware-keyboard-present checks), since it's redundant |
| **Mouse/trackpad (via USB-C hub or Bluetooth)** | Right-click context menus (Theia supports these natively for its widgets), hover states, precise stage-border drag-to-resize — native Compose screens need hover/right-click handling added explicitly, since touch-first Compose defaults don't include it |

**Gestures** (touch): tap (open/select), long-press (context menu / text selection), drag (stage resize, tab reorder), swipe-from-edge (Android back gesture — must not conflict with Theia's own left-stage reveal, needs explicit edge-gesture exclusion zones where Theia's UI has its own drag targets), pinch (font-size zoom in editor/terminal — maps to Theia's built-in zoom command).

**Shortcut/keybinding source of truth**: keep a single declarative keybinding table (not hardcoded per-screen) that generates both the keyboard accessory bar's button set and the physical-keyboard keybinding registrations, so the two stay in sync by construction rather than by manual duplication — this maps to the "no hardcoding, reusable" coding standard in `.claude/CLAUDE.md`.

## Memory and storage management (UI layer)

Distinct from the sandbox/storage layer covered in [sandbox-runtime/arch.md](../sandbox-runtime/arch.md#7-android-specific-constraints) — this is about the UI layer's own resource use:

- **Widget lifecycle**: Theia already disposes widgets that are closed (not just hidden) — the app must actually close, not just visually hide, stages/tabs the user isn't using, to let Theia's own disposal free memory (a widget "minimized" but never closed still holds its full state, e.g. a video player's decoder).
- **Inactive project memory**: per the [multi-project session mapping](../sandbox-runtime/arch.md#multi-project-session-mapping) model, only one Theia backend runs live at a time — switching away from a project should trigger the WebView to drop its JS heap for that project's Theia frontend, not just navigate away in Compose while the WebView keeps rendering it hidden.
- **Large-file stages** (video, large PDFs): these widgets should release decoder/renderer resources when scrolled out of view or the tab loses focus for an extended period, not just on explicit close — video playback in particular must pause and release the underlying `MediaCodec`/decoder when backgrounded, not just when the tab closes, to avoid Android's OS-level "app using excessive background resources" kill.
- **Tab/stage open-count limits**: no hardcoded maximum, but an LRU eviction policy (auto-close least-recently-used tabs beyond a memory-pressure threshold, signaled via Android's `onTrimMemory`) is needed rather than letting open tabs grow unbounded — exact threshold is a tuning parameter, not a hardcoded constant (see coding standards).

## Open questions

- **Touch-to-Theia-resize bridging mechanism**: decision 0003 confirms Theia owns the actual dock/resize logic, but the exact bridge for translating a native Compose drag gesture on the WebView's edge into Theia's own resize-handle drag events (synthetic pointer events injected into the WebView? a JS-side gesture recognizer instead, driven by touch events forwarded wholesale?) still needs a spike once Theia integration starts.
- Whether the terminal-tab bar and Theia's own tab bar (editor tabs) should visually unify or stay visually distinct (Theia terminal tabs vs. Theia editor tabs are technically the same underlying Theia widget system — worth checking if theming them differently confuses users, or clarifies the split).
- Autosave-on-focus-change vs. explicit save: worth user-testing before locking in as default, since it diverges from desktop VS Code's default behavior.
- Exact `onTrimMemory` threshold/LRU tab-eviction policy needs real device profiling (tablet RAM varies widely) rather than a guessed constant — track under design-system or a future "performance" feature once there's a running app to profile.
- Edge-gesture exclusion zones (Android back-gesture vs. Theia's own left-stage reveal drag) need real on-device testing to tune — can't be fully resolved from docs alone.
