# Feature: Sandbox Runtime — Architecture

Covers the Android tablet IDE's core runtime: Theia-based editor, the pluggable Linux sandbox (proot / chroot+BusyBox), terminal, git credentials, and session persistence. This is the consolidated architecture doc for the feature — see [tracker.md](tracker.md) for implementation status.

## Decisions locked in

- **UI shell**: Kotlin + Jetpack Compose (native Android app)
- **Editor/IDE engine**: Eclipse Theia (VS Code-compatible, browser-rendered, dual-licensed EPL-2.0 / GPL-2.0-classpath-exception — see [decision 0001](../decision/0001-ide-foundation-theia.md) for the license correction and implications), run as a local Node.js server and displayed via WebView
- **Sandbox**: pluggable backend — proot (default, unrooted devices) or chroot+BusyBox (opt-in, rooted devices)
- **Terminal**: Termux's `terminal-emulator`/`terminal-view` libs (real PTY, VT100) — hosts shell, Neovim, Claude Code CLI
- **AI coding agent**: Claude Code CLI (+ VS Code extension under Theia, compatibility to be verified)
- **Git**: system `git` inside the sandbox + Keystore-backed credential helper
- **Persistence**: foreground Service + tmux for session survival across Android app lifecycle

Rationale for each is recorded as a formal decision doc under [/docs/decision/](../decision/) where the choice had real tradeoffs (see 0001, 0002).

## One-paragraph mental model

The Android app is a **thin native shell** (Compose) around a **WebView pointed at `localhost`**, where `localhost` is a Theia server running as a plain Node.js process inside a **Linux sandbox** (proot or chroot, selected at startup) that lives entirely within the app's private storage. Everything that "feels like Linux" (git, gcc, node, python, tmux, neovim, claude) is just real ELF/script binaries executing inside that sandbox. A **foreground Android Service** keeps the Node/sandbox process tree alive when the app is backgrounded, and **tmux** inside the sandbox provides session survival even if the app process itself is killed and restarted.

---

## 1. High-Level Architecture

### Layers

```
+-------------------------------------------------------------------+
|  Layer 5 -- Presentation (Kotlin / Jetpack Compose)                |
|  Project switcher, tab bar, panel docking, keyboard accessory      |
|  bar, settings, WebView host, notification UI                     |
+-------------------------------------------------------------------+
|  Layer 4 -- Bridge                                                  |
|  WebView <-> Compose JS bridge (native file picker, share sheet,   |
|  clipboard, biometric unlock for credentials)                      |
|  Local HTTP/WS bridge (Compose app <-> sandbox processes)          |
+-------------------------------------------------------------------+
|  Layer 3 -- Runtime / Process Management (Android Service)         |
|  Foreground Service owning the process tree:                       |
|   - Theia backend (Node.js)                                        |
|   - tmux server + per-project sessions                             |
|   - ssh-agent                                                      |
|   - credential-helper daemon (unix socket)                         |
+-------------------------------------------------------------------+
|  Layer 2 -- Sandbox (pluggable backend, chosen at startup)         |
|   Backend A: proot Linux userland (default, unrooted devices)      |
|   Backend B: chroot + BusyBox (opt-in, rooted devices only)        |
|  Termux bootstrap: bash, coreutils, pkg/apt-compatible manager     |
|  Installed toolchains: node, python, gcc/clang, git, tmux, nvim,   |
|  openssh, claude (npm global)                                      |
+-------------------------------------------------------------------+
|  Layer 1 -- Storage                                                 |
|  App-private filesystem (project files, sandbox rootfs)            |
|  Android Keystore / EncryptedSharedPreferences (secrets)           |
|  Room DB (project list, session map, UI state)                     |
+-------------------------------------------------------------------+
```

### Component map

```
                         +--------------------------+
                         |   Compose UI (Layer 5)    |
                         |  - Project list            |
                         |  - Tab/panel chrome        |
                         |  - Accessory keyboard      |
                         +-------------+--------------+
                                       | hosts
                         +-------------v--------------+
                         |   Android WebView            |
                         |  loads http://127.0.0.1      |
                         |  :PORT  (Theia frontend)      |
                         +-------------+--------------+
                                       | WebSocket JSON-RPC (Theia's own protocol)
+---------------------------------------v----------------------------------------+
|                    Foreground Service (Layer 3)                                 |
|                                                                                   |
|  +------------+   +---------------+   +--------------+   +------------+        |
|  |   Theia     |   |  tmux server   |   |  ssh-agent    |   | cred-helper|       |
|  |  backend    |   |  (persistent   |   |  (key cache)  |   |  daemon    |       |
|  |  (Node.js)  |   |   sessions)    |   |               |   | (unix sock)|       |
|  +------+------+   +-------+-------+   +-------+-------+   +-----+------+       |
+---------+------------------+-------------------+-----------------+--------------+
          | spawns PTYs      | attach/detach      | signs           | resolves
          v                  v                    v                 v
+----------------------------------------------------------------------------+
|                   Sandbox: proot or chroot+BusyBox (Layer 2)                |
|   bash . git . node . python . gcc . tmux . nvim . claude (CLI)            |
|   filesystem rooted at /data/data/<pkg>/files/sandbox                      |
+----------------------------------------------------------------------------+
                                       | real file I/O (no isolation from app)
                                       v
                       +----------------------------+
                       |  Layer 1 -- Storage          |
                       |  project files, secrets,      |
                       |  Room DB                       |
                       +--------------------------------+
```

### Data flow: "user asks Claude Code to edit a file, sees it live in Theia"

1. User types in the terminal panel (real PTY, rendered by terminal-view).
2. `claude` (Node CLI, running inside the sandbox) reads/edits the file via plain `open()`/`write()` syscalls -- no IDE-aware API involved.
3. Linux `inotify` fires a filesystem event.
4. Theia's own file-watcher service picks up the change and pushes it to the frontend over the existing JSON-RPC WebSocket.
5. Theia frontend (in the WebView) reloads the buffer if clean, or shows a conflict prompt if the tab has unsaved edits.

Theia ships its own file-watcher and external-change reconciliation UI, so no custom file-watching code is needed here.

### What the Compose shell is actually responsible for

- Lifecycle: start/stop the foreground Service, keep Theia's Node process alive, restart it if killed
- Project switching: which sandbox workspace directory Theia is currently pointed at
- WebView hosting + JS bridge for native file picker, biometric-gated secret unlock, Android share sheet
- Keyboard accessory bar (Ctrl/Esc/Tab/arrows) for Theia's terminal panel and Neovim/Claude Code sessions
- Session bookkeeping (Room DB) so "reopen this project" restores the right state

### Sandbox backend selection

```
                 +------------------------+
                 |  Root-detection check   |
                 +-----------+-------------+
                              |
              +----------------+----------------+
              |                                   |
        not rooted                            rooted
              |                                   |
              v                                   v
   Backend A: proot bootstrap        Prompt user: "Root detected --
   (Termux-style)                     use faster chroot sandbox?
                                       (reduces isolation)"
                                                    |
                                     +----------------+----------------+
                                    Yes                                No
                                     |                                  |
                                     v                                  v
                          Backend B: chroot + BusyBox           Backend A: proot
```

Rooted users are **not** forced into chroot mode -- explicit, informed opt-in only. Everything above Layer 2 doesn't need to know which backend is active.

---

## 2. Low-Level Architecture

### Process model

| Process | Owner | Restarted by | Notes |
|---|---|---|---|
| `TheiaService` (Android foreground Service) | Android OS process | System restart policy | Persistent notification required; without it Android reaps everything below within seconds of backgrounding |
| Theia backend (`node theia-app/backend/main.js`) | Spawned by the Service inside the sandbox | The Service, on crash | Listens on `127.0.0.1:<PORT>` |
| `tmux` server | Spawned once by the Service | Rarely restarts | One named session per open project (`tmux new -s proj_<id>`) |
| PTYs under tmux (shell, nvim, claude) | `tmux` | tmux re-spawns dead panes | Scrollback/cwd survive Service restarts as long as tmux server itself wasn't killed |
| `ssh-agent` | Spawned once | The Service | Exported into every pane's environment |
| credential-helper daemon | Spawned by the Service | The Service | Unix socket inside sandbox rootfs |

The Service is the root of the process tree -- if Android kills the Service, tmux server dies too, and relaunch means rebuilding from scratch (files survive, running processes don't). See [Session Management](#4-session-management) below.

### proot mechanics

`proot` intercepts syscalls via `ptrace(2)` and rewrites path arguments before they reach the kernel -- no new kernel namespace, the process is an ordinary Android app-UID process from the kernel's point of view. Cost: every syscall round-trips through the tracer (typically 10-30% overhead). Static Termux-repo binaries (Bionic ABI) don't strictly need proot at all; it's only required for an unmodified glibc distro (`proot-distro install ubuntu`).

### chroot + BusyBox mechanics (rooted-device backend)

Requires real root (`su`), since `chroot(2)` needs `CAP_SYS_CHROOT`.

**Rootfs staging**: directory-based (recommended default -- extract the bootstrap tarball onto app-private storage, already ext4/f2fs, chroot straight in) vs disk-image-based (`rootfs.img` + `losetup` + `mount`, more snapshot-friendly, adds loop-device management).

**Boot sequence**:
```
su -c '
  busybox mount -o bind /dev   <rootfs>/dev
  busybox mount -t proc  proc  <rootfs>/proc
  busybox mount -t sysfs sysfs <rootfs>/sys
  busybox chroot <rootfs> /bin/bash -l
'
```

**Performance**: no ptrace tracer -- syscalls hit the kernel directly, same cost as any native process. This is the entire point of offering this backend.

**Root detection**: best-effort only, not security-critical -- it only picks a bootstrap path. False negatives fall back safely to proot; false positives are effectively impossible since the chroot bootstrap simply fails without real root.

### Theia wire protocol (already solved, not something to build)

Theia frontend/backend communicate over **JSON-RPC 2.0 over WebSocket** -- the WebView is just a normal web page talking to `localhost`. The Compose/Kotlin layer only needs a bridge for things outside Theia's capabilities (native file picker, biometric prompts, share sheet, push notifications). Theia spawns a separate extension host process per plugin set, same as VS Code -- this is where the Claude Code extension runs, if compatible.

### Credential helper protocol

```
git push
  -> git invokes: credential.helper = "!/sandbox/bin/cred-helper get"
        -> cred-helper connects to unix socket /sandbox/run/cred.sock
              -> Android foreground Service (listening on that socket)
                    -> reads secret from EncryptedSharedPreferences,
                        decrypted via Android Keystore-backed key
                        (optionally gated behind BiometricPrompt)
              <---- returns {username, password/token} over the socket
        <-- cred-helper prints it in git's expected key=value format
```

The secret never touches the sandbox filesystem at rest -- resolved on-demand, in-memory, per request. Mitigation, not a hard boundary (see [Sandbox Isolation](#3-sandbox-isolation)).

### File watching path

Theia's built-in file-watcher (chokidar/native inotify bindings) already covers "Claude Code edits a file, Theia's tab reloads." No custom `FileObserver` code needed.

### Android-specific constraints

- **Scoped storage (Android 11+)**: keep the entire sandbox rootfs inside app-private storage, exempt from scoped-storage restrictions. Only "import/export a project" needs the Storage Access Framework.
- **OEM battery managers**: Xiaomi/Samsung/OnePlus etc. kill even foreground services under custom battery layers. A "disable battery optimization for this app" onboarding prompt is not optional.
- **WebView process**: separate Chromium process from the app process, can be reclaimed independently under memory pressure -- backend + tmux state survive, frontend just reconnects on WebView recreation.

---

## 3. Sandbox Isolation

### What proot provides

- Path remapping via `ptrace`
- A clean-looking distro layout separate from Android's real filesystem
- Organizational separation between projects (filesystem-path isolation, not a security mechanism)

### What proot does NOT provide

| Real container primitive | Provided by proot? | Consequence |
|---|---|---|
| PID namespace | No | Every process is a normal process under the app's own UID |
| Network namespace | No | No per-project network isolation -- everything shares the app's network permission |
| Mount namespace / enforcement | No -- path rewriting only | No kernel-enforced jail |
| User namespace / UID isolation | No | "root" inside the distro is cosmetic, not a real boundary |
| cgroup resource limits | No | No per-project CPU/RAM cap |

**Threat model**: anything running in the sandbox (a cloned repo, `npm install` postinstall scripts, an AI-generated shell command, a Neovim plugin) has the same effective access as the app itself -- full app-private storage, network, and anything reachable via the credential-helper socket during an active session. This is materially weaker than a real Docker/Codespaces container. Treat it as **single-tenant, trust-the-code-you-run**, not isolation between projects or against untrusted code.

### Rooted-device backend: chroot + BusyBox -- a performance win, security downgrade

Real `chroot(2)` is faster (no ptrace tracer) and supports things proot can't reliably emulate (nested debugging, some `/proc`/`ioctl`/mount behavior). But it requires `su`, meaning the sandboxed process tree now runs **outside Android's SELinux app domain and UID sandboxing entirely**:

| | proot (unrooted) | chroot + BusyBox (rooted) |
|---|---|---|
| Runs as | Normal Android app UID | root (via `su`) |
| Confined by Android's SELinux app domain? | Yes | No |
| Can a process escape the sandbox boundary? | Bounded by app-UID sandboxing underneath | Yes, trivially -- chroot has never been a security boundary against a privileged process |
| Blast radius of malicious code | This app's private storage + network | The entire device |
| Keystore-backed secrets still protected? | Yes (hardware-backed on most devices) | Weaker -- depends on TEE/StrongBox vs software-backed keys |

Rooting the device already broke Android's system-wide security model before this app is involved -- chroot mode inherits that risk rather than creating a new one, but it does concentrate more capability into this app's process tree than proot mode would. Make it opt-in with a clear warning, never the automatic default. Apply the same short-lived credential-cache mitigation, more strictly.

### Comparison table

| | proot (unrooted) | chroot + BusyBox (rooted) | Docker/OCI container | Cloud codespace (rejected option) |
|---|---|---|---|---|
| Filesystem isolation | Path remap only, not enforced | Real chroot, not enforced against a privileged process | Kernel mount namespace + overlayfs, enforced | Full container, enforced |
| Network isolation | None | None | Optional, per-container | Full, per-workspace |
| Resource limits | None | None | cgroups | cgroups + billing quotas |
| Runs with device-wide root? | No | Yes | N/A | N/A |
| Cross-project blast radius | High -- same app UID | Higher -- same root, entire device | Low -- contained to container | Low -- contained to workspace |
| Works offline | Yes | Yes | N/A | No |
| Setup cost | Low | Low, requires root | Not available on stock Android | High (backend infra) |

### Practical recommendations

1. Don't market this as "isolated per-project sandboxes" -- it's one sandbox, organizationally divided by directory.
2. Keep the credential-helper's cache short-lived; require periodic re-auth/biometric.
3. If a user needs to run genuinely untrusted code (grading submissions, running someone else's PR), this architecture is the wrong tool -- that needs real backend containers instead.
4. Network egress is all-or-nothing at the Android permission level; per-project network policy would need an app-layer proxy, not in scope unless required.
5. No cgroup cap exists -- consider a Service-level watchdog to kill runaway process trees if "one project freezes the tablet" needs guarding against.

---

## 4. Session Management

### The four session types

| Session type | Where state lives | Survives backgrounding? | Survives Service/process kill? | Survives reinstall? |
|---|---|---|---|---|
| **Theia editor session** (open tabs, buffers) | Theia workspace-state files + in-memory WebView state | Yes | No -- unsaved buffer content lost unless hot-exit is enabled | No |
| **Terminal/tmux session** (cwd, scrollback, running processes) | tmux server, in-memory | Yes, if tmux server survives | No | No |
| **Claude Code CLI session** (conversation history) | Disk-backed, keyed by project directory | Yes | Yes -- independent of app process lifecycle | Yes |
| **Git credential session** (cached token/passphrase) | ssh-agent + credential-helper daemon, in-memory; secret itself in Keystore | Yes | No -- fresh Keystore-gated fetch on restart | N/A |

Session lifecycle is **sandbox-backend-agnostic**: proot vs chroot+BusyBox affects performance/isolation, not how Theia/tmux/ssh-agent/credential-helper persist.

### App lifecycle state machine

```
[App foreground] --user switches app--> [App backgrounded]
                                              |
                          foreground Service keeps
                          Node/tmux/agent alive
                                              |
                     +-----------------------------------------------+
                     |                                                |
        user returns to app                          OS/OEM battery manager
        (common case)                                 kills the Service
                     |                                                |
                     v                                                v
         WebView reconnects to the still-           [Full teardown]
         running Theia backend over WS;             Room DB still has:
         tmux panes resume exactly as left            - project list
                                                        - last tmux session name
                                                        - last open file/tab
                                                                |
                                                    user relaunches app
                                                                |
                                                                v
                                                    [Rebuild flow, below]
```

### Rebuild flow (Service/process was killed)

1. Foreground Service restarts, respawns Theia backend + tmux server + ssh-agent + credential-helper against the existing sandbox rootfs (files untouched).
2. Attempt `tmux attach -t proj_<id>`. Usually the server is gone -- open a fresh shell in the same project directory and surface this calmly in the UI, not as an error.
3. Theia backend restarts against the same workspace path -- file tree/git status/extensions reload from disk correctly. Unsaved buffer content is lost unless Theia's backup/hot-exit is enabled (turn this on specifically for this failure mode).
4. Claude Code CLI needs no rebuild step -- disk-keyed, picks history back up automatically.
5. Git credential cache is cold -- first git op re-triggers the credential-helper -> Keystore path (possibly biometric), which is correct, not a bug.

### Multi-project session mapping

Room DB (conceptual):
```
Project(
  id,
  path,                  -- absolute path inside sandbox rootfs
  displayName,
  lastTmuxSession,        -- "proj_<id>", may or may not currently exist
  lastOpenTabs,            -- Theia workspace restore hint
  credentialProfile        -- which git identity/remote-scoped creds apply
)
```

Keep tmux sessions for **all** open projects alive simultaneously (cheap). Only run **one live Theia backend** at a time, for the currently-viewed project -- spin it down/up on project switch rather than running N full Theia backends concurrently (steady-state cost: N idle tmux sessions + 1 active Theia backend).

### Guardrails worth building early

- Onboarding prompt to disable OEM battery optimization.
- Enable Theia's backup/hot-exit to minimize unsaved-buffer loss on hard kill.
- Debounce project switches -- tearing down/restarting a Theia backend has real cost (Node startup + extension host spin-up).
