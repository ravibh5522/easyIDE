# 0010 - Real PTY-backed terminal via vendored Termux terminal-emulator/terminal-view

Status: Accepted

## Context

The terminal pane shipped so far ([ui-shell tracker](../ui-shell/tracker.md)) streamed process stdout/stderr into a scrolling line list, with a separate input row for typing commands. It could run commands and show output, but it was not a real terminal: no shell prompt to type against inline, no PTY, no VT100/ANSI escape interpretation, no arrow-key shell history, no `Ctrl-C` delivering an actual `SIGINT`, and no way to run a full-screen program (`vim`, `top`, `less`). A genuine terminal requires allocating a pseudo-terminal (`openpty`/`forkpty`) and parsing the VT100/ANSI stream the shell writes back — both are impossible from pure JVM code via `ProcessBuilder`; they require native code.

## Decision

Vendor Termux's `terminal-emulator` and `terminal-view` libraries (native `openpty`/`forkpty` JNI + VT100 parser + the `TerminalView` Android `View`) as two new Gradle modules under `services/mobile/`, and rebuild the terminal pane on top of them: `SandboxShell`/`ShellRunner`/`LinuxEnvironment` gained an `interactiveParams(...)` path that hands back a `PtyShellParams` (shell path, args, env, cwd) instead of running a one-shot command; the app layer wraps this in `TerminalSession`/`EasyTerminalSessionClient`/`EasyTerminalViewClient` and hosts `TerminalView` directly via Compose `AndroidView`. The old line-list terminal (`TerminalSession.kt` data class, `WorkspaceTerminals.kt`) was deleted rather than kept alongside.

Built directly rather than pausing for a separate ADR checkpoint first, per explicit user direction — this document was written alongside the implementation, not before it.

## Alternatives considered

- **Keep the line-based terminal, add cosmetic PTY-like behavior** (e.g. `\r`-as-overwrite, fake cursor) — rejected. Cannot support full-screen programs, real signal delivery, or arrow-key history no matter how much polish is added; the gap is architectural (no PTY), not visual.
- **Write a from-scratch PTY + VT100 parser** — rejected. A correct VT100/xterm-256/ANSI parser plus native pty JNI glue is a large, easy-to-get-subtly-wrong surface (cursor addressing, scroll regions, UTF-8 combining chars, mouse reporting). Termux's implementation is mature, MIT/Apache-licensed, and battle-tested across the Android version/OEM matrix this app already has to survive.
- **Depend on the Termux app/plugin via AIDL/intents instead of vendoring code** — rejected. Requires the Termux app to be installed separately, breaks the single-APK distribution model, and hands terminal session lifecycle to a process this app does not control.

## Licensing

`terminal-emulator` and `terminal-view` are carved out of the otherwise-GPLv3 `termux-app` repository as **Apache License 2.0** (inherited from their origin, `jackpal/Android-Terminal-Emulator`), verified from primary sources — GitHub API `license` field and the modules' own `LICENSE`/`NOTICE.md` — not assumed, per the standing project rule (see decision 0001's Theia license correction). Apache-2.0 is compatible with this project's dual PolyForm-Noncommercial + commercial license (decision 0008): permissive, no copyleft obligation flows onto the rest of the app. Both modules' vendored `LICENSE`/`NOTICE.md` files are kept in place under `services/mobile/terminal-emulator/` and `services/mobile/terminal-view/`.

## Consequences

- **Makes easier**: real shell prompts, ANSI colors, `Ctrl-C` as a genuine `SIGINT`, full-screen program support (`vim`, `top`) — all verified on a real Xiaomi Pad 6 device (see sandbox-runtime and ui-shell trackers).
- **Makes harder**: the app now has a native code dependency (NDK, `ndk-build`, `libtermux.so` per-ABI) where previously it had none — adds build complexity and per-ABI binary size. `TerminalView` does not redraw itself on session output; the host must explicitly forward `TerminalSessionClient.onTextChanged` to `TerminalView.onScreenUpdated()`, an easy-to-miss wiring step (it was missed once during this implementation and cost a debugging pass).
- **Forecloses**: swapping the terminal rendering engine for something Compose-native later without redoing this integration layer; the pane is now built around a plain Android `View`, not a Composable-first design.
