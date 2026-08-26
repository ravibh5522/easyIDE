# 0007 - Sandbox image catalog, and why we do not host a rootfs yet

Status: Accepted

## Context

Until now the rootfs was a single hardcoded URL in `AppContainer`: one Ubuntu
base tarball, same for everybody. A bare `ubuntu-base` is a rough starting
point — no git, no compiler, no Python — so every new environment began with the
same ten minutes of `apt-get install`.

The obvious fix is to host our own rootfs with the packages already baked in and
let the user pick from a list. That raises two separable questions, and they
have different answers:

1. **Should the user be able to pick a pre-configured environment?** Yes,
   clearly.
2. **Does that require us to host rootfs images?** No — and the cost of hosting
   is higher than it first looks.

Constraints that shaped this:

- A tablet on mobile data pays for every megabyte. A "Node.js image" and a
  "Python image" as separate 300 MB tarballs is 600 MB for two environments
  that are 95% identical.
- The image cache is already device-level and keyed on the tarball's file name
  ([`SandboxPaths.cachedImage`]), so anything sharing a URL is downloaded once
  for the whole device.
- Hosting a rootfs makes us a distributor: it needs storage, bandwidth, a
  rebuild cadence for security updates, integrity verification, and a check on
  the redistribution terms of everything inside the image. None of that is
  hard; all of it is ongoing.

## Decision

**A declarative catalog of presets (`SandboxImage`), where a preset is a rootfs
URL plus the commands to run inside it after unpacking.** The presets we ship
all point at the *same* Ubuntu base tarball and differ only in their
`setupCommands`.

So "Ubuntu + Python" is: unpack the cached Ubuntu base, then
`apt-get install python3 python3-pip python3-venv`. Picking it after
"Ubuntu + Node.js" costs an apt install, not a second download.

**We do not host a rootfs image today.** `SandboxImage.urlByAbi` means we can:
adding a privately hosted, pre-baked rootfs is a new entry in
`SandboxImages.CATALOG` and no code change anywhere in the runtime. The door is
open; we are not walking through it until a preset exists that setup commands
genuinely cannot express.

## Alternatives considered

- **Host one rootfs per stack, pre-baked** — rejected for now. Better first-run
  time (no apt step) at the cost of a per-image download with almost no shared
  bytes, plus the standing distribution burden above. Worth revisiting if
  first-run setup time turns out to be the thing users complain about.
- **Let the user paste an arbitrary rootfs URL** — rejected. It is a one-line
  feature and a genuinely dangerous one: an unverified tarball is unpacked and
  then *executed*. If this is ever added it needs a checksum the user can
  verify, not a free-text box.
- **Ship a rootfs inside the APK** — rejected. `ubuntu-base` alone is ~30 MB
  compressed and the APK is already ~21 MB with mermaid bundled. It also pins
  the distro to the app's release cadence.

## What hosting would require, if we do it later

Recorded here so the decision is re-litigated with the real cost visible:

- **Build**: `docker buildx build --platform linux/arm64` then `docker export`
  a container to get a rootfs tarball. Must be built for `arm64` (and `x86_64`
  for emulators) — the catalog is keyed by Android ABI for exactly this.
- **Integrity**: publish a SHA-256 alongside each image and verify it after
  download. `RootfsProvisioner` does **not** verify checksums today (see the
  sandbox-runtime tracker) — that is acceptable for a tarball fetched over TLS
  from `cdimage.ubuntu.com`, and would not be acceptable for one of ours.
- **Redistribution terms**: must be checked from primary sources before
  publishing anything, not assumed. Ubuntu's base image and the packages inside
  it carry their own licences and trademark policy, and "it's open source" is
  not a substitute for reading them — the standing rule in `.claude/CLAUDE.md`
  exists because that assumption was already wrong once (decision 0001).
- **Maintenance**: a pre-baked image is a snapshot. It needs rebuilding for
  security updates, or users get a stale toolchain that `apt-get upgrade` then
  has to repair anyway.

## Consequences

- Adding a preset is a data change in one file, and costs no extra download as
  long as it reuses a base tarball already in the catalog.
- A preset's setup runs at install time with its output streamed to the
  terminal, so a long apt step is visible rather than looking like a hang. A
  failed setup step fails the install with the step named, rather than leaving
  a half-configured environment that looks ready.
- The chosen preset is persisted on the environment record (`imageId`), because
  provisioning is deferred — the rootfs is only unpacked on first use, which may
  be days after the choice was made. Environments created before the catalog
  existed have no id and fall back to the bare base image.
- First run of a non-bare preset is slower than the bare one, and needs the
  network. That is the trade we are making instead of a bigger download.
