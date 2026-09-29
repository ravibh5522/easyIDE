# Terminal / sandbox audit (2026-09-29)

Scope: why AI agents (Claude Code) and several terminals behave badly, against the goal **one sandbox = one OS, many projects sharing tools and memory**. Static read of the code and docs at `66ecd4a`; nothing below was reproduced on a device, so each finding says how to confirm it.

## Verdict

The *storage* model already matches the goal (decision 0005: one rootfs, projects bind-mounted). The *runtime* model does not: every terminal, agent command and language server is its own independent proot process tree, all projects appear at the same guest path, nothing owns process lifetime except the Android app process, and there is no tmux/supervisor layer (tracker: `not-started`). Most of the symptoms follow from those four facts.

## Findings (highest impact first)

### F1. Every project mounts at `/workspace` - agent state collides across projects
`SandboxPaths.guestProjectPath()` returns the constant `/workspace` ([SandboxPaths.kt:62](../../services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/SandboxPaths.kt#L62)); `HOME` is `/root` for everyone. Claude Code keys history, trust, allowed tools, MCP servers and auto-memory by absolute project path (`~/.claude/projects/-workspace/`, `~/.claude.json`). Projects A and B in one environment therefore share one Claude "project": mixed `--continue`/resume lists, a trust/permission decision in one applies to the other, memory files leak between them. Same for any path-keyed tool cache (tsserver, eslint, jest, git safe.directory).
Confirm: open Claude in two projects of one environment and run `ls ~/.claude/projects`; only `-workspace` exists.
Fix: bind each project at a unique stable guest path, e.g. `/workspace/<projectSlug>` (or `/projects/<slug>`), keep `/workspace` as the cwd-symlink only if needed.

### F2. Terminals die silently; nothing survives an app-process kill or a workspace eviction
- Each tab is a pty child of the app ([WorkspaceTerminals.kt:113-140](../../services/mobile/app/src/main/java/dev/easyide/app/ui/screens/workspace/WorkspaceTerminals.kt#L113-L140)). `WorkspaceRegistry.evict` (park limit `maxParkedProjects`=2, or `onTrimMemory`) ends the session and `release()` SIGKILLs every shell ([WorkspaceRegistry.kt:144](../../services/mobile/app/src/main/java/dev/easyide/app/session/WorkspaceRegistry.kt#L144)). A Claude session in a parked project is killed with no notice to the user. This is the most likely cause of "works, then after some time it doesn't".
- tmux/supervisor is `not-started` ([tracker.md](tracker.md)); decision 0023 rejected it, but its argument (tmux dies with the process) only holds if the supervisor is a child of the app. It is not an argument against a persistent per-environment guest daemon that is re-attached after the app restarts *when Android leaves the process alive*.
- `SandboxForegroundService` is `dataSync`: Android 15 caps it at 6 h/day then `onTimeout` stops it ([SandboxForegroundService.kt:34](../../services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/service/SandboxForegroundService.kt#L34)); after that every shell is exposed to the cached-process killer. The tracker still says `START_STICKY`, the code is `NOT_STICKY` (stale doc).

### F3. Android's phantom-process killer is not handled or even mentioned
Android 12+ kills app child processes beyond 32 (`max_phantom_processes`), and when the app is in the background without protection. One terminal running Claude (node) under proot is already 4-8 processes (proot tracer, tracee, loader, bash, node, node workers, claude subprocesses); three tabs plus a language server exceed 32. Symptom: `[Process completed (signal 9)]`, random agent tool failures, "multiple terminals can't run smoothly". No code anywhere handles or surfaces this (grep for `phantom|signal 9|SIGKILL`: only the tab-close path). Confirm: `adb shell dumpsys activity processes | grep -i phantom` / `logcat | grep -i phantom`.
Fix: detect exit by signal 9 and show a one-time banner with the exact remedy (Developer options > *Disable child process restrictions*, Android 14+, or `adb shell device_config put activity_manager max_phantom_processes 2147483647`); reduce process count (F4, F5).

### F4. No shared execution layer: every agent command cold-starts its own proot
`LinuxEnvironment.startPiped/start/commandPtyParams` each: `ensureInstalled(proot)`, `ensureGuestDefaults` (DNS, sudo shim, pax marker, `GuestAccounts.ensure` reading and rewriting `/etc/passwd`, `/etc/shadow`, `/etc/group`), resolve extension binds, then fork a fresh proot ([LinuxEnvironment.kt:163-262](../../services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/LinuxEnvironment.kt#L163-L262)). `ServerProcessFactory.isInstalled` spawns a whole proot just to run `command -v`. Costs: ptrace startup latency per command, cwd/env/shell state lost between agent commands, N concurrent proot tracers, and the `/etc/*` rewrite races when several launches start at once (non-atomic `writeText`, [GuestAccounts.kt:80](../../services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/bootstrap/GuestAccounts.kt#L80); a reader in another proot can see a truncated file - only when content changes, so rare, but real).
Fix (structural): one long-lived proot per environment ("the OS") that hosts a small in-guest supervisor (tmux server or a tiny exec daemon on a unix socket in `/run`). Terminals, agent commands, tasks and language servers become sessions/execs *inside* that one proot (`tmux new-window`, `exec` RPC) instead of new proot trees. This cuts process count and startup cost, makes cwd/env persistent for agents, and is the "one OS" model. Short of that: skip `ensureGuestDefaults` after the first success per environment per process (a boolean in memory), and make its writes atomic.

### F5. Two identities in one OS: shells run as `dev`, tasks/extensions/agent terminals as root
Interactive shells are `setpriv --reuid=1000` ([SandboxShell.kt:120](../../services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/shell/SandboxShell.kt#L120)); `newShell(initialCommand)` and `namedTerminal` open **root** shells ([WorkspaceTerminals.kt:45-72](../../services/mobile/app/src/main/java/dev/easyide/app/ui/screens/workspace/WorkspaceTerminals.kt#L45-L72)); `startPiped`/tasks/LSP run root. Consequences for Claude Code: it refuses `--dangerously-skip-permissions` as root, so the same command works in one tab and fails in another; an install recipe run as root vs. `dev` can create root-owned-looking config in the shared `/root` that the other identity cannot write (fake ids only; verify with `ls -ln ~/.claude`). `SHELL`/`USER`/`LOGNAME` are set for the dev shell only ([SandboxShell.kt:204](../../services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/shell/SandboxShell.kt#L204)), so a root shell has none of them.
Fix: one identity for everything user- or agent-facing (dev), `sudo` for install steps; pass the same env map to all launch paths.

### F6. Claude Code is not part of the environment
Tracker: "Claude Code CLI install + verification inside sandbox: not-started". No preset installs it; users install by hand in one terminal. It lands in the environment, so it is shared *if* both projects use the same environment - but `HomeViewModel` only *suggests* the first ready environment, and a project created against another environment (or a fresh preset) has no `claude`. No warning shows which environment a workspace uses inside the workspace itself.
Fix: an "AI tools" layer per environment (installed once, version-pinned, `~/.claude` shared) with a status row in the workspace; show the environment name in the terminal panel header.

### F7. Smaller items
- `Cleanup` deletes the environment `/tmp` while shells run ([Cleanup.kt:73](../../services/mobile/app/src/main/java/dev/easyide/app/diagnostics/Cleanup.kt#L73)); Claude Code's Bash tool keeps per-session files under `/tmp`, so a running agent breaks. Refuse while the environment has live sessions.
- `sudo` stamp in `/tmp/.easyide-sudo-stamp` is shared by all sessions of the environment (fine) but is removed by the same Cleanup.
- No memory/process budget: no cgroups (known), no per-environment process count, nothing warns before Android kills the process. Add a live process/RSS readout per environment (proc walk of `/proc` by `PROOT_TMP_DIR` or session pids) - also the data needed to tell users which tab to close.
- Multiple concurrent Claude instances share `~/.claude.json` and the OAuth credential file; concurrent token refresh/writes across instances is a known Claude Code weak spot. Unique per-project paths (F1) reduces but does not remove it.
- `TerminalProcess`/`ShellRunner` line-mode paths and `ProcessCapture` are sound (bounded, killed on cancel). No defect found there.

### F8. Threading and parallel execution model (how shells run in parallel)
- **Tabs** are independent pty sessions from the vendored Termux JNI, one proot tree each. Tabs run truly in parallel across cores and share only the filesystem. Each session also has a few Java threads (reader, writer, exit waiter - from the Termux library, not re-read here).
- **Line-mode and agent execs** park one `ioDispatcher` thread per blocking read (`TerminalProcess.stream`) and three per capture (`ProcessCapture`: two pipe drains plus `waitFor`). The dispatcher's thread cap was not inspected; many concurrent agent commands could exhaust it.
- **proot is a single-threaded tracer per tree**: parallel work inside one shell (`make -j8`) is serialised through one tracer; separate tabs get separate tracers, so they do not slow each other but each adds processes toward the phantom-process limit (F3).
- **Nothing bounded or serialised launches**: `ensureGuestDefaults` ran concurrently on every launch and rewrote `/etc/passwd`, `shadow` and `group` in place (F4). `WorkspaceTerminals.named` was a plain `HashMap`.
- Conclusion: "multiple terminals can't run smoothly" is CPU/process pressure and Android killing children (F3), not a lock in our code.

### F9. npm cache corrupts itself under proot (language-server install errors)
Found on the device (`~/.npm/_logs`, `_cacache`): proot's `--link2symlink` makes cacache's `link(tmp, content-v2/...)` + `unlink(tmp)` leave every cached blob as a symlink to a hidden `tmp/.l2s.*` file. `npm cache verify` (or any cleaning of `tmp`) deletes the bytes; from then on `npm install` and `npm cache verify` fail with `ENOENT ... content-v2/sha512/...`. Reproduced on a clean Ubuntu + Node.js environment (verify once: fine; verify again: "Missing content: 40"). Fix: a Node preload (`NODE_OPTIONS=--require`, set by `ProotLauncher` only when the file exists) makes `fs.link` into a `_cacache` directory a copy, and a one-time purge removes caches earlier builds already broke. Verified on the device: 0 symlinks after install, repeated `npm cache verify` passes, install/uninstall/upgrade/reinstall of the yaml and typescript language servers pass. Not fixed: a package directory installed *before* the fix that contains `.l2s` files can still fail a later `npm install` with `ENOTEMPTY` on rename (proot hides `.l2s` files, so the directory looks empty but is not); remove that package directory once.

## Status of fixes (2026-09-29, canary build)

| Finding | Status |
|---|---|
| F1 | **Fixed** without breaking the extension contract: `/workspace` stays bound (extension paths, LSP, snippets use it); terminals additionally bind the project at `/projects/<project id>` and start there (`LaunchRequest.guestAlias`), so Claude's history, trust and memory key per project. Existing Claude history under `-workspace` is not migrated. |
| F2 | **Mitigated**: over-limit and memory-pressure eviction now ends idle workspaces before busy ones (`ParkPolicy`, `HeldInfo.busy`), and a toast says so when a workspace with running shells is ended. The 6 h `dataSync` cap and process death still kill shells; a persistent supervisor is F4. |
| F3 | **Detected, not preventable**: a terminal that dies of a SIGKILL the app did not send shows the remedy (`TerminalExit`). |
| F4 | **Partly fixed**: `ensureGuestDefaults` runs once per rootfs per process, under a lock; account files are replaced by atomic rename. The per-environment supervisor is **not built**: it needs an ADR superseding 0023 s.9 and a device spike. |
| F5 | **Fixed**: named terminals (extension `runInTerminal`, agents) are the default user; the root shell now has `USER`/`LOGNAME`/`SHELL` too. Install recipes, tasks and language servers stay root on purpose (apt needs it). |
| F6 | **Not built** (AI-tools layer). |
| F9 | **Fixed and verified on device** (see F9). |
| F7 | **Fixed**: Cleanup leaves `/tmp` alone while any workspace is live; `named` is a `ConcurrentHashMap`. Live process/RSS readout not built. |

## Recommended order

1. **F3 banner + F2 eviction notice** (small, immediately explains the failures to users). Stop killing parked shells silently; warn or raise `maxParkedProjects` while the foreground service is up.
2. **F1 per-project guest path** (small, high value; needs a migration note because `/workspace` is baked into saved sessions and Claude history).
3. **F5 single identity** (small).
4. **F4 per-environment supervisor** (large; needs an ADR superseding 0023 §9 and a device spike measuring proot process count and latency before/after).
5. **F6 AI-tools layer**.

## Not verified

No device or emulator was available, so F1-F5 are from code and docs; F3 and the F5 ownership claim need the confirming commands above on the tablet. Proot version and its `PROOT_NO_SECCOMP` behaviour were not inspected.
