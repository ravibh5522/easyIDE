# Feature: Sandbox Runtime — Tracker

Status legend: `not-started` / `in-progress` / `done` / `blocked`

## Decisions (finalized in [/docs/decision/](../decision/))

| # | Decision | Status |
|---|---|---|
| [0001](../decision/0001-ide-foundation-theia.md) | Use Theia as the IDE engine (over native Compose editor, code-server, Spyder) | decided |
| [0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md) | proot as default sandbox backend, chroot+BusyBox as rooted opt-in | decided |
| [0005](../decision/0005-sandbox-environment-sharing-model.md) | Environments are shareable; projects bind-mount into them | decided |
| [0007](../decision/0007-sandbox-image-catalog-and-custom-rootfs.md) | Preset catalog sharing one base tarball; no self-hosted rootfs yet | decided |
| [0010](../decision/0010-pty-terminal-vendored-termux.md) | Real PTY-backed terminal via vendored Termux terminal-emulator/terminal-view | decided |

## Components

| Component | Status | Notes |
|---|---|---|
| Root-detection + backend selection flow | in-progress | `RootDetector` + `EnvironmentManager.availableBackends()` done; chroot offered only when root detected. UI surfaces it in the new-project form |
| Environment/project data model + persistence | done | `SandboxEnvironment`, `ProjectRecord`, `SandboxStore` (DataStore + JSON). Sharing + reassignment implemented per decision 0005 |
| Environment lifecycle (create / provision / delete) | in-progress | `EnvironmentManager` done incl. in-use deletion guard; provisioning needs a real bootstrap tarball to be wired |
| Sandbox path layout | done | `SandboxPaths` — projects deliberately outside every rootfs |
| External folder sync (save/load projects outside app storage) | done | `ExternalFolderSync` (SAF: persisted-permission lifecycle, import, per-path mirrored write/create/delete) + `ProjectManager` mirror methods. App-private storage stays the sandbox's working copy — a `content://` tree cannot be bind-mounted — a linked folder is a best-effort, additive-only mirror. Verified on device: import from a non-empty folder, and save/create/delete all propagating to the real filesystem. **Known gaps**: no relink-after-permission-loss UI yet, directory rename/delete not fully mirrored, terminal-driven writes (outside the editor) are not mirrored at all |
| Rootfs archive extraction | done | `TarGzExtractor` — gzip+tar with GNU long names, **pax extended headers**, tar-slip guard, **hard links copied** and symlinks created (Android denies `link(2)` in app data). Verified against the real ubuntu-base arm64 image: 2562 files / 194 symlinks / 2 hard links / 655 dirs, **zero `PaxHeaders` artifacts** |
| proot launch argv construction | done | `ProotLauncher`; executed and verified on a real tablet. Uses `--link2symlink -H -L` — dpkg's backup hard links fail without it |
| Sandbox image catalog / presets | done | `SandboxImage` + `SandboxImages.CATALOG` (base, build tools, Node.js, Python). Presets share one cached base tarball and differ by `setupCommands`, run at install time with output streamed to the terminal. Chosen preset persisted as `SandboxEnvironment.imageId`. Verified on device with two independent fresh installs (Python and Node.js presets, the latter pulling in dozens of transitive packages) - both completed cleanly, toolchain and `git` working, `dpkg --audit` clean. See [decision 0007](../decision/0007-sandbox-image-catalog-and-custom-rootfs.md) |
| chroot + BusyBox launch script | in-progress | `ChrootLauncher` builds the `su -c` mount+chroot script with shell quoting; never executed yet |
| Bootstrap tarball sourcing (download) | done | `RootfsProvisioner` downloads ubuntu-base through `download/VerifiedDownloader`: streaming SHA-256 against the digest pinned in `SandboxImages` (`RootfsArchive`), hard fail + partial deleted on mismatch, HTTP Range resume from `.part`, size cap, cancellable `Flow<DownloadEvent>`. Cached images are re-hashed before extraction. JVM unit tests against a loopback server; not yet exercised on device |
| Extension storage + guest visibility | done | `SandboxPaths` extension layout (lld/registry-and-install.md sec 11), traversal-safe `ExtensionId`/`ExtensionVersion`, `LaunchRequest.extraBinds` (`GuestBind`) in both launchers, `EnvironmentExtensionBinds` binds each env extension's resolved `current` at `/opt/easyide/extensions/<id>` for terminal/interactive launches. Bind under proot/chroot not yet verified on device |
| Process execution (spawn a shell into an environment) | done | `SandboxShell` + `TerminalProcess`: **streaming output** in ~60 ms batches, **stdin open** for interactive commands, cancellable. Executables run from `jniLibs`, which is what makes this work under enforcing SELinux. **Guest process environment is cleared before use** - `ProcessBuilder` otherwise leaks the Android app's own env vars (notably `TMPDIR`, pointed at the app's cache dir) into the guest, which broke any postinst script that called `mktemp()` |
| PTY-backed interactive shell (real terminal) | done | `PtyShellParams` + `SandboxShell.interactiveParams`/`ShellRunner.interactiveParams`/`LinuxEnvironment.interactiveShellParams` hand off shell path/args/env/cwd to the vendored Termux `terminal-emulator` JNI (`openpty`/`forkpty`), which is inherently immune to the `ProcessBuilder` env-leak class of bug since it builds `envp` explicitly after `clearenv()`. See [decision 0010](../decision/0010-pty-terminal-vendored-termux.md). Verified on a Xiaomi Pad 6: keyboard input round-trips to the shell and back, `Ctrl-C` delivers a real `SIGINT` (interrupted `sleep 10` instantly, exit 130), ANSI colors render |
| Theia backend integration (Node process managed by Service) | not-started | |
| WebView host + JS bridge (file picker, biometric, share sheet) | not-started | |
| Foreground Service (process lifecycle owner) | in-progress | `SandboxForegroundService` exists (notification, START_STICKY, dataSync type); does not yet own any child processes. Battery-optimization onboarding prompt still required |
| tmux session management (per-project) | not-started | Naming convention: `proj_<id>` |
| ssh-agent integration | not-started | |
| Credential-helper daemon (Keystore-backed, unix socket) | not-started | See arch.md §2 "Credential helper protocol" |
| Keyboard accessory bar | in-progress | `TerminalKeyboard` + `TerminalKeyRow` — `Esc`, `^C`, `^L`, arrow keys, `Tab`, clear and the shell symbols a soft keyboard buries, writing raw bytes straight to the `TerminalSession` pty now that one exists (decision 0010) |
| Claude Code CLI install + verification inside sandbox | not-started | |
| Claude Code VS Code extension compatibility test under Theia | not-started | Unverified — flagged risk in decision 0001 |
| Neovim install (complementary terminal editor) | not-started | Zero extra engineering once terminal + pkg manager exist |
| Room DB schema: Project session map | not-started | See arch.md §4 "Multi-project session mapping" |
| Rebuild/reattach flow after Service kill | not-started | See arch.md §4 "Rebuild flow" |
| Theia hot-exit/backup config | not-started | Mitigates unsaved-buffer loss on hard kill |

## Verification status

**The Linux sandbox works end to end inside the app**, verified on a Waydroid container (Android 13 / API 33, x86_64) by driving the app's own terminal:

| Step | Result |
|---|---|
| "Install Linux" → download + extract | 28 MB fetched, extracted, `Linux environment ready` |
| `cat /etc/os-release` | `ID=ubuntu`, `UBUNTU_CODENAME=noble` |
| `whoami` | `root` |
| `dpkg --version` | `Debian 'dpkg' ... 1.22.6 (amd64)` |
| `apt-get update` | `Fetched 33.7 MB in 16s` |
| `apt-get install git` | unpacked deps, `Setting up git (1:2.43.0-1ubuntu7.3)` — **then a dpkg trigger error, exit 100** (see open issues) |

**Also verified on real hardware** — Xiaomi Pad 6, Android 13, arm64-v8a, `getenforce` = **Enforcing**, app at targetSdk 37: rootfs extraction, `cat /etc/os-release` (Ubuntu noble), `whoami` -> `root`, `sudo whoami` -> `root`, `dpkg --version`, and `apt-get update` fetching **35.5 MB** of arm64 indexes. This required moving the executables into `jniLibs`; see Addendum 3 in [decision 0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md).

Still never executed: `ChrootLauncher`, `SandboxForegroundService`.

## Guest environment conventions

- **Images are cached device-wide**, at `<files>/images/<image-id>.tar.gz`, not per environment. A second environment built from the same image extracts straight from disk with no download (`LinuxEnvironment.isImageCached`).
- **`sudo` is a pass-through shim** written to `/usr/local/bin/sudo`, because proot already runs everything as uid 0 and a real setuid `sudo` cannot work under proot anyway. It consumes value-taking flags (`-u`, `-g`, …) so `sudo -u root foo` still runs `foo`, and it refuses to overwrite a real `/usr/bin/sudo` if the user installs one. `GuestEnvironment.DEFAULT_PATH` puts `/usr/local/bin` first so it resolves.
- **Interactive shells default to a non-root `dev` user** (uid/gid 1000, `RootfsProvisioner.createDefaultUser`), switched to via `su` (`SandboxShell.interactiveCommand`) when the rootfs has an `su` binary - every preset measured so far does. **This is cosmetic, not a privilege boundary**: verified on a real device that `id` inside a `su`'d shell still reports `uid=0(root)` (proot's `-0` fakes root for the whole session and does not track real `setuid()` calls the way a kernel would), even though `whoami`/`$HOME`/`$USER`/`$PWD` correctly resolve to `dev`/`/home/dev`. File writes to the bind-mounted project directory are unaffected either way. `sudo` stays the same pass-through shim - there is no real privilege for it to gate, for `dev` any more than for root. `/etc/skel` is copied into the new home directory when present, matching `useradd -m`.
  - **Regression caught right after shipping, fixed same session**: `su - dev` (`-`, login mode) simulates a fresh login, which includes `chdir()`-ing to `dev`'s home - discarding the project directory proot's own `-w` had already set as cwd. Terminals were opening in `/home/dev` instead of the project. Fixed with `su - dev -c "cd '<guest project path>'; exec /bin/sh"` - login setup still runs (so `$HOME`/`$USER` are correct), but the `-c` command re-`cd`s to the real target directory before `exec`ing the shell that actually attaches to the pty. Verified on device: `pwd`/`ls` now match the project again. **Known cosmetic side effect**: this prints `/bin/sh: 0: can't access tty; job control turned off` once at shell startup - harmless (nothing here uses job control) but not yet silenced.
- **Guest defaults are applied on every launch**, not only at provision time (`RootfsProvisioner.ensureGuestDefaults`), so environments created by older builds pick up fixes (DNS, the sudo shim) without being reinstalled.

## Open questions / risks

- ~~**`apt-get install` ends with `Sub-process /usr/bin/dpkg returned an error code`**~~ **RESOLVED on real hardware.** It was two independent bugs, neither of them a dpkg trigger:
  1. `dpkg: error: read error in configuration file '/etc/dpkg/dpkg.cfg.d/PaxHeaders': Is a directory` — `TarGzExtractor` did not understand **pax extended headers**, which every entry in the Ubuntu base tarball is preceded by, and materialised each one as a file. That left 492 stray `PaxHeaders` directories in the rootfs, one of them inside a directory dpkg reads on startup. Fixed by consuming `x`/`g` records (and honouring their `path`/`linkpath`/`size` overrides); `RootfsProvisioner.ensureGuestDefaults` deletes the leftovers from environments created by earlier builds, once, guarded by a marker.
  2. `unable to make backup link of './usr/bin/perl' before installing new version: Permission denied` — dpkg hard-links every file it is about to replace, and Android denies `link(2)` in app data. Fixed with proot's `--link2symlink` (plus `-H` and `-L`), which is what proot-distro uses for the same reason.
  - Verified on a Xiaomi Pad 6: `apt-get install -y git` exits **0**, and `git init` / `add` / `commit` / `log` all work; `apt-get install -y nano` from inside the app's own terminal completes with no error.
- ~~**Rootfs image downloads are not checksum-verified.**~~ **RESOLVED**: digests pinned per URL from the release `SHA256SUMS` (GPG-verified against the Ubuntu CD image signing key), checked while streaming; see `SandboxImages.kt`. Adding an image now requires its digest by construction (`RootfsArchive`).
- ~~**Highest-risk unknown: exec from app-private storage on an enforcing device.**~~ **RESOLVED on real hardware** (Xiaomi Pad 6, Android 13, arm64, enforcing): exec from app data *is* denied (`execute_no_trans`), but shipping the executables in `jniLibs` and running them from the native-library directory works, with no targetSdk downgrade and no root. Ubuntu, `sudo`, `dpkg` and `apt-get update` all verified on device. See Addendum 3 in [decision 0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md).

- Claude Code's official extension is built against VS Code's API surface — Theia implements a large subset but not 100%. Needs an early spike to confirm compatibility (see decision 0001, "de-risk early" note).
- No cgroup-based resource capping exists in either sandbox backend — decide whether a Service-level watchdog for runaway processes is needed before or after initial ship.
- Per-project network isolation is out of scope unless a concrete requirement emerges (would need an app-layer proxy).
- `SandboxForegroundService` declares `foregroundServiceType="dataSync"`, the closest standard type for a long-running process host. Verify against Play policy before shipping; `specialUse` may be the correct type and requires a declared subtype.
- `TarGzExtractor` writes symlink entries as placeholder files holding the target path, because archive ordering does not guarantee the target exists yet. A real Termux rootfs relies on symlinks, so this needs a second pass (resolve links after extraction) before provisioning can produce a working userland.
