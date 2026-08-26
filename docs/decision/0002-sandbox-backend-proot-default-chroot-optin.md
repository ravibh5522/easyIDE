# 0002 - Sandbox backend: proot default, chroot+BusyBox opt-in for rooted devices

Status: Accepted

## Context

The IDE needs a Linux userland on-device to run git, compilers, Node, Python, and Claude Code CLI. Android does not support real containers (Docker) for a regular app — no kernel namespace/cgroup access without root. Some users' devices are rooted, which unlocks real `chroot(2)` and removes the performance overhead of syscall-interception-based sandboxing.

## Decision

Ship **proot** (Termux-style, ptrace-based path remapping) as the default sandbox backend for all devices. Additionally support **chroot + BusyBox** as an **opt-in** backend, offered only when root is detected, with an explicit warning shown before enabling it.

## Alternatives considered

- **chroot as the default whenever root is available** — rejected. Real `chroot` requires `su`, which runs the sandboxed process tree outside Android's SELitrix app-sandboxing (SELinux domain, per-app UID isolation) entirely. This is a security *downgrade* relative to proot, not an upgrade, despite feeling more "real" — chroot has never been a boundary against a privileged process. Defaulting to it silently would misrepresent the security posture to users.
- **proot-only, no chroot support at all** — simplest, but leaves real performance and compatibility on the table for rooted users (chroot avoids proot's ~10-30% ptrace syscall overhead and supports operations proot can't reliably emulate, e.g. nested debugging).
- **Real Docker via a rooted kernel with namespace/cgroup support** — theoretically possible on some custom kernels, but not reliably available across the rooted-device population; too inconsistent to build a supported path around.

## Addendum (2026-08-10): the W^X / exec-from-app-data question

The whole proot plan rests on one assumption: that the app can `execve()` binaries living in its own private storage — both the shipped `proot` and every package a user later installs with `apt`/`pkg`.

This is not guaranteed on modern Android. Apps targeting API 29+ are, on real devices, denied `execute` on `app_data_file` by SELinux; this is precisely why Termux pins `targetSdkVersion` to 28.

**Measured (2026-08-10, Waydroid, Android 13 / API 33, this app at targetSdk 37):** copying `/system/bin/toybox` into the project directory, `chmod 755`, and running it printed its output — exec from app-private storage **worked**.

**But that result does not generalize.** `getenforce` on that container returns `Disabled`, so SELinux was not in the picture. The measurement rules out mount-level `noexec` (`/data` is `rw,relatime`) but says nothing about an enforcing device.

**Open, and blocking for the sandbox promise** — must be re-measured on real hardware with SELinux enforcing:
- If exec from app data works there: the plan stands as written.
- If it is denied: shipped binaries can be relocated into the APK's native-library directory (`jniLibs`, extracted to `/data/app/.../lib/`, which stays executable), which covers `proot` and `busybox` — but **not** packages installed at runtime, since those land in app data. A full `apt`-installable userland would then require either `targetSdkVersion 28` (giving up Play Store distribution) or the rooted chroot backend.

Until this is settled on a real device, treat "install anything with apt" as unproven on unrooted Android.

## Addendum 3 (2026-08-10): RESOLVED on real hardware — ship executables in `jniLibs`

Measured on a **Xiaomi Pad 6 (Android 13, API 33, arm64-v8a, `getenforce` = Enforcing)** with the app at targetSdk 37.

**The predicted denial is real.** With proot copied into app-private storage, every command returned exit 127, and the kernel audit log gave the exact reason:

```
avc: granted { execute }          for name="proot"
avc: denied  { execute_no_trans } for path="/data/data/dev.easyide.app/files/run/bin/proot"
     scontext=u:r:untrusted_app:s0 tcontext=u:object_r:app_data_file:s0 permissive=0
```

`execute` (mapping a file as a library) is granted; `execute_no_trans` (exec'ing it as a program) is denied. That distinction is the whole problem — and the whole solution.

**The fix works, and it is not the drastic one.** Moving the executables into the app's native-library directory — which is `apk_data_file`, not `app_data_file` — makes them executable with no change to targetSdk and no root:

| Check on the tablet | Result |
|---|---|
| `cat /etc/os-release` | `Ubuntu 24.04 (noble)` |
| `whoami` | `root` |
| `sudo whoami` | `root` |
| `dpkg --version` | works |
| `apt-get update` | `Fetched 35.5 MB in 1min 25s` from `ports.ubuntu.com` (arm64) |

Requirements, all load-bearing:
- executables must be named `lib*.so` (`libproot.so`, `libprootloader.so`, `libprootloader32.so`) or the packager drops them;
- `android:extractNativeLibs="true"` **and** `packaging { jniLibs { useLegacyPackaging = true } }`, or the libraries are never written to disk as real files and there is nothing to exec;
- shared libraries whose SONAME cannot be renamed (`libtalloc.so.2`) stay as assets copied into app storage and are reached via `LD_LIBRARY_PATH` — dlopen only needs `execute`, which is granted.

**Consequences for the earlier addenda:** Addendum 1's fear that runtime-installed (`apt`) packages could not be relocated to `jniLibs` turns out not to bite. Packages installed by apt live *inside the rootfs* and are executed by proot's own loader rather than by `execve` from the Android process, so they inherit proot's execution context. `apt-get update` and package binaries work. **targetSdk 28 and root are both unnecessary.**

**Second real-device finding: the platform `tar` cannot unpack a rootfs here.** It aborts on hard links:

```
avc: denied { link } for name="perl" tcontext=u:object_r:app_data_file:s0
tar: can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl': Permission denied
```

Android denies `link(2)` in app data. `RootfsProvisioner` therefore uses the in-repo `TarGzExtractor`, which **materialises hard links as copies** and creates symlinks properly. On the tablet: *"Extracted 5975 files, 194 links, 2 hard links copied"*, then a working userland.

## Addendum 2 (2026-08-10): Ubuntu under proot — measured end to end

Rather than reason about whether a glibc distro runs on Android, it was run. On the same Waydroid container (x86_64), using Termux's `proot` build (5.1.107.89) plus its `loader`/`loader32`, against an unmodified `ubuntu-base-24.04.3-base-amd64` rootfs:

| Check | Result |
|---|---|
| `cat /etc/os-release` | `Ubuntu 24.04.3 LTS (Noble Numbat)` |
| `whoami` | `root` (proot `-0`; cosmetic, not real privilege) |
| `uname -a` | the real host kernel — proot does not virtualize it |
| `apt-get update` | reached `archive.ubuntu.com` and fetched indexes |
| `apt-get install jq gpgv` | unpacked and configured, including `libjq1`, `libonig5` |
| `jq --version`, `echo {...} \| jq .ok` | `jq-1.7`, returned `true` |

So a glibc userland, its dynamic loader, `apt`, and freshly-installed binaries all execute. Disk: ~376 MB extracted from a 29 MB tarball.

Two caveats that keep this short of proving the product works:

1. **It ran from `/data/local/tmp` as the `shell` user, not from app-private storage as the app's UID.** That deliberately isolates the proot/glibc question from the exec-permission question in Addendum 1, which is still the open risk.
2. **SELinux was `Disabled`** on this container, so it says nothing about an enforcing device.

Also observed, and worth carrying into the bootstrap design:
- `ubuntu-base` ships an empty `/etc/resolv.conf`; DNS must be written before `apt` will resolve anything.
- `apt` reports `gpgv` missing even once `/usr/bin/gpgv` exists, so the first `apt-get update` needs `-o Acquire::AllowInsecureRepositories=true`. Using a prepared image (as `proot-distro` does) avoids this bootstrap ordering problem.
- No `systemd` or service manager, so nothing that expects PID 1 will start.

## Consequences

- Sandbox backend selection happens once at startup via a best-effort root-detection check; the choice is architecturally invisible to everything above Layer 2 (Theia, tmux, credential helper, Claude Code CLI all work identically regardless of backend).
- Full isolation-tradeoff analysis lives in [/docs/sandbox-runtime/arch.md](../sandbox-runtime/arch.md#3-sandbox-isolation) and must be kept in sync if either backend's mechanics change.
- Product/UI copy must never describe either backend as providing per-project isolation or protection against untrusted code — both are single-tenant, trust-the-code-you-run models; chroot mode is strictly more permissive, not less.
- If a genuine multi-tenant/untrusted-code use case emerges later, neither backend is the right answer — that requires the previously-rejected cloud-codespace (real Docker backend) architecture instead.
