<div align="center">

# easyIDE

**A real IDE with a real Linux userland, for Android tablets.**<br>
Native editor, file tree and an Ubuntu terminal -- all on the device, offline, no root.

[![Latest release](https://img.shields.io/github/v/release/ravibh5522/easyIDE?include_prereleases&label=release&color=2ea043)](https://github.com/ravibh5522/easyIDE/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue)](#license)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white)](#download)
[![Status: beta](https://img.shields.io/badge/status-beta-orange)](#what-is-not-there-yet)
[![Join on Telegram](https://img.shields.io/badge/Telegram-join%20the%20chat-26A5E4?logo=telegram&logoColor=white)](https://t.me/+yMH4w5rX1gZhOTM1)

[Download](#download) &middot; [What works](#what-works-today) &middot; [Not yet](#what-is-not-there-yet) &middot; [Privacy](#privacy-and-telemetry) &middot; [Build](#build) &middot; [Community](#community) &middot; [Contributing](#contributing)

</div>

---

Not a snippet editor, and not a remote-desktop client into someone else's server. You get a
native editor, a file tree, and a terminal running Ubuntu, all on the device.

> **Beta: `v0.2.0-beta.4`.** arm64 and x86_64, Android 8.0+ (minSdk 26). Expect rough edges and
> [report them as issues](https://github.com/ravibh5522/easyIDE/issues), or tell us in the
> [Telegram group](https://t.me/+yMH4w5rX1gZhOTM1).

## Download

| Channel | What it is | Get it |
|---|---|---|
| **Stable beta** | Tagged builds, installs as `dev.easyide.app` | [Latest release](https://github.com/ravibh5522/easyIDE/releases/latest) |
| **Canary** | Newest build, installs alongside stable as *easyIDE Canary* | [`canary` release](https://github.com/ravibh5522/easyIDE/releases/tag/canary) |

Download the `.apk` from the release page, allow installs from your browser or file manager, and
open it. Each release lists a SHA-256 you can check. A new build installs over the previous one
and keeps your projects.

## Community

<a href="https://t.me/+yMH4w5rX1gZhOTM1"><img src="https://cdn.simpleicons.org/telegram/26A5E4" alt="Telegram" width="28" align="left" hspace="8"></a>
**Join the discussion on [Telegram](https://t.me/+yMH4w5rX1gZhOTM1).**
<br clear="left">

That is the place for questions, ideas, tablet-specific quirks, "does this work on my device?",
early looks at what is coming, and general chat with the people building and using easyIDE.
A bug you can reproduce, or a feature you want tracked, belongs in a
[GitHub issue](https://github.com/ravibh5522/easyIDE/issues) so it does not get lost in the scroll.

[![Join the Telegram group](https://img.shields.io/badge/Telegram-join%20the%20discussion-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/+yMH4w5rX1gZhOTM1)

## What works today

Everything in this list has been executed on real hardware (Xiaomi Pad 6, Android 13, arm64,
SELinux **enforcing**) or in a Waydroid container (Android 13, x86_64) -- not just compiled.
Per-component detail and evidence lives in the `tracker.md` files under [docs/](docs/).

- **A real Linux sandbox.** Ubuntu `noble` provisioned into app-private storage via
  [PRoot](https://github.com/termux/proot), no root needed. `whoami` -> `root`,
  `apt-get update` fetches, `apt-get install git` completes, `dpkg --audit` comes back clean.
- **Environment presets** -- bare Ubuntu, build tools, Node.js, or Python -- sharing one cached
  base tarball ([decision 0007](docs/decision/0007-sandbox-image-catalog-and-custom-rootfs.md)).
- **A real PTY terminal.** Full-screen ncurses programs run: `cmatrix` was installed with
  `apt-get` and rendered its animation live, then `^C` restored the shell with scrollback
  intact. An Esc/Ctrl accessory row sits above the soft keyboard for the keys a touch
  keyboard hides.
- **A streaming, interactive terminal.** Output arrives as it is produced (~60 ms batches, so a
  long build never looks frozen), stdin stays open so commands that prompt actually work, and
  `\r` is handled as overwrite so progress bars collapse instead of flooding the scrollback.
  Multiple tabs, `^C`, command history, and an accessory key row for the shell symbols a soft
  keyboard buries two menus deep.
- **A native IDE shell** ([decision 0006](docs/decision/0006-native-ide-shell-before-theia.md)):
  activity rail, lazy-expanding file explorer, tabbed editor with a line-number gutter, and a
  status bar -- operating on real files. Saves survive a full app restart.
- **Syntax highlighting for 229 languages** using the same TextMate grammars VS Code uses,
  bundled in the APK (1.35 MB) so it works offline with no download. All 229 grammars are
  verified to compile, and the tokenizer is a real scope-aware parser -- `#include` is a
  preprocessor directive in C, `//` is a division operator in Python.
- **Markdown preview** with headings, lists, blockquotes, fenced code, tappable links, and
  **mermaid diagrams rendered offline** from a bundled copy of mermaid.
- **Projects and environments** as separate things: environments are shareable and projects
  bind into them ([decision 0005](docs/decision/0005-sandbox-environment-sharing-model.md)).
- **Link a real folder** on internal storage or an SD card through SAF, so your code is not
  trapped in app-private storage.
- **A VS Code-shaped workspace.** Dense, VS Code-style chrome that adapts from a phone (bottom
  navigation) to a tablet (activity rail, side bar, bottom panel, secondary panel): editor
  groups with split view, breadcrumbs, real file-type icons (Material Icon Theme, swappable), an
  optional full-screen mode with no system bars, and three density levels.
- **Language servers.** A built-in LSP client runs servers inside the sandbox: completion,
  signature help, hover, diagnostics, code actions, rename, formatting, go-to-definition,
  references, symbols, inlay hints, code lens, semantic tokens and folding. Language packs
  (Python with Ruff, TypeScript, Web, Rust, Go, C/C++, YAML, Markdown, Shell) install their own
  server on demand. Any stdio server can be added through an extension manifest.
- **Extensions.** Declarative `.easyext` packs contribute languages, grammars, themes, icon
  themes, snippets, keybindings, commands, toolbar keys, settings, panels and language servers,
  with capability disclosure at install ([docs/extension-sdk/](docs/extension-sdk/)). A VS Code
  icon-theme `.vsix` from Open VSX installs as is.
- **Source control.** JGit-backed: stage, unstage and discard per file, a changes tree, a commit
  split button with amend, and a VS Code-style history graph with branch and tag chips and a
  commit context menu (cherry-pick, tag, checkout, compare). Changes made by the terminal show
  up as well. Repositories it creates are read correctly by real `git`.
- **Themes and settings.** Material 3 themes, a settings screen generated from a schema, and
  honouring of the system reduce-motion setting.

## What is not there yet

Stated plainly, because a README that oversells is worse than one that is short:

- **VS Code extensions that run code** (GitLens, the Claude Code extension and so on) do not
  run. Only declarative packs and icon themes install. A Node extension host in the sandbox is
  designed ([decision 0030](docs/decision/0030-vscode-extension-host-in-sandbox.md)), not built.
- **The Claude Code CLI is not wired up.** Installing and verifying it inside the sandbox is
  planned, not done.
- **Git over the network is lightly tested.** Push, pull and sync exist but have had little
  device testing; there is no merge editor. Commits use a placeholder identity until you set
  one.
- **No debugger and no notebooks.**
- **No credential helper, no tmux session persistence, no ssh-agent.**
- **The chroot+BusyBox backend for rooted devices builds its argv but has never been executed.**
- No checksum verification on the downloaded rootfs tarball.

### The sandbox is not a security boundary

PRoot and chroot+BusyBox are single-tenant, trust-the-code-you-run models. They exist to give
you a working Linux userland on Android, **not** to isolate one project from another or to
contain hostile code. Do not treat them as a sandbox in the security sense. The reasoning is in
[decision 0002](docs/decision/0002-sandbox-backend-proot-default-chroot-optin.md).

## How it works

PRoot ships as `libproot.so` in `jniLibs` -- which is what makes it executable under enforcing
SELinux on a modern Android. On first run the app downloads an `ubuntu-base` tarball and
extracts it (gzip + tar, including pax extended headers and GNU long names, with a tar-slip
guard) into app-private storage. Commands run through `SandboxShell`, which spawns PRoot with
`--link2symlink -H -L` (dpkg backs files up with hard links, and Android denies `link(2)` in app
data) and a **cleared** process environment, so the Android app's own vars cannot leak into the
guest. Projects live deliberately *outside* every rootfs, so an environment can be deleted
without taking your code with it.

Full architecture: [docs/sandbox-runtime/arch.md](docs/sandbox-runtime/arch.md).

## Repo layout

```
docs/            architecture + trackers per feature, ADRs, weekly change log
services/
  mobile/        the Android app
    app/           Compose UI, ViewModels, navigation, theming
    sandbox-runtime/  environment model, persistence, PRoot/chroot launchers, terminal
  shared/        cross-service contracts
  backend/       intentionally empty -- there is no server component yet
db/              backend and on-device (Room) migrations
tools/           dev/build scripts, never shipped
infra/           intentionally empty -- CI/deploy config, when there is any
```

## Build

```
cd services/mobile
./gradlew :app:assembleDebug     # local development
./gradlew :app:assembleCanary    # tip of main, installs alongside a stable build
./gradlew :app:assembleRelease   # stable
```

Canary builds use applicationId `dev.easyide.app.canary` and are labelled **easyIDE
Canary**, so you can run the tip of `main` on the same tablet as a build you rely on.
Every push to `main` publishes one to the rolling
[`canary` release](https://github.com/ravibh5522/easyIDE/releases/tag/canary); tagging
`v*` cuts a stable one. Channels, versioning and signing secrets:
[infra/README.md](infra/README.md).

Needs a **full JDK 17+**, not a JRE -- `javac` must be on the toolchain. If the system Java is a
JRE, pass one for the invocation: `JAVA_HOME=/path/to/jdk ./gradlew :app:assembleDebug`.

`local.properties` (holding `sdk.dir`) is gitignored; recreate it on a fresh clone.

Building against Gradle 9.7.0, AGP 9.3.1, Kotlin 2.4.10, compileSdk/targetSdk 37.
More detail, including AGP 9.x gotchas: [services/mobile/README.md](services/mobile/README.md).

## Docs

| | |
|---|---|
| [docs/sandbox-runtime/](docs/sandbox-runtime/) | Linux sandbox, terminal, git, session persistence |
| [docs/ui-shell/](docs/ui-shell/) | Screens, navigation, stage system, editor/terminal UX |
| [docs/design-system/](docs/design-system/) | Themes, color tokens, icons, motion, responsive layout |
| [docs/telemetry/](docs/telemetry/) | Analytics, crash reporting, push, and the consent gate |
| [docs/decision/](docs/decision/) | ADRs -- the major, hard-to-reverse choices and why |
| [docs/chainlog/](docs/chainlog/) | Append-only weekly log of what changed |

## Privacy and telemetry

The first time you open easyIDE (a new install, or the first launch after upgrading to
`v0.2.0-beta.4`) it asks you to agree to technical diagnostics. This is required to use the app.

- **Collected:** device details (model, Android version, CPU), app version, which features are
  used and whether actions succeeded, app errors and crash logs, app start and screen timings,
  and a registration for release-announcement notifications.
- **Never collected:** your name, phone number, contacts or accounts, anything about your other
  apps, and your files, project names, code, commands or terminal output.
- **Provider:** Firebase, kept behind a small provider-neutral module so it can be replaced
  ([decision 0031](docs/decision/0031-telemetry-firebase-behind-neutral-module.md)). Firebase
  Analytics is proprietary Google software; the rest of easyIDE is GPL-3.0.
- **Turn it off** any time in Settings > Privacy. That stops collection and deletes the
  announcement registration. A build made without a Firebase config file collects nothing.

## License

easyIDE is free software, licensed under the **[GNU General Public License v3.0](LICENSE)**.
You may use, study, modify and redistribute it, for any purpose, provided that distributed
copies and derivative works are also released under the GPL with their source.

### Third-party components

easyIDE's own license does not cover what it bundles. Two of those carry their own copyleft
terms: **PRoot** is GPL-2.0-or-later and
**talloc** is LGPL-3.0-or-later, both shipped inside the APK. PRoot is run as a separate
process rather than linked. Anyone who receives the APK is owed the corresponding source for both. Every bundled
component, its license, and where that license was verified from is listed in
**[NOTICE.md](NOTICE.md)**.

The Claude Code CLI is Anthropic's commercial software and is **not** redistributed here.
Install it yourself under your own account.

## Contributing

easyIDE is built in the open, and help is genuinely welcome, from a typo fix to a whole
feature. You do not need to be an Android expert to be useful.

**Ways to help**

- **Try it and tell us what broke.** A beta lives on real devices, and yours is one we have not
  seen. Say which tablet, which Android version and what you did; a screenshot or the crash
  report from Settings > Diagnostics makes it fixable.
- **Test on hardware we lack.** Most testing so far is one Xiaomi Pad 6 and a Waydroid container,
  so reports from other tablets, other Android versions and a rooted device are worth a lot.
- **Fix a bug or pick up a gap** from [What is not there yet](#what-is-not-there-yet), or from the
  [open issues](https://github.com/ravibh5522/easyIDE/issues).
- **Write an extension or a language pack.** Declarative `.easyext` packs are the easiest way to
  add a language server, theme or snippet set; start at [docs/extension-sdk/](docs/extension-sdk/).
- **Improve the docs.** If something confused you, it will confuse the next person too.
- **Talk it through first.** For anything large, say hello in the
  [Telegram group](https://t.me/+yMH4w5rX1gZhOTM1) or open an issue before you start, so nobody
  builds the same thing twice.

**How to send a change**

1. Fork the repo and branch from `main`.
2. Keep the change focused on one thing, and build it (`./gradlew :app:assembleDebug`).
3. Open a pull request that says what changed and why.

**A few ground rules**

- **Opening a pull request accepts the CLA** in [CONTRIBUTING.md](CONTRIBUTING.md). Nothing to
  sign or email; the PR is the acceptance, and you keep your own copyright.
- **Please do not paste in third-party code.** Propose it as a dependency instead, with its
  license and maintenance status checked against the upstream repo or official docs (not from
  memory). It then gets recorded in [NOTICE.md](NOTICE.md).
- **Follow the house style:** files stay under 600 lines, no hardcoded colors, strings or
  keybindings, error handling only at real boundaries, an abstraction only at its third use, and
  the sandbox is never described as security isolation. Non-trivial changes get a
  [chainlog](docs/chainlog/) entry, and hard-to-reverse choices get an [ADR](docs/decision/).
  The full list is in [CONTRIBUTING.md](CONTRIBUTING.md).
- **Say what you verified.** If you could not run it (no tablet, no emulator), write that in the
  pull request instead of reporting success. An honest "untested" is more useful than a
  confident guess.

**Security issues:** please do not open a public issue. Email **ravibh5522@gmail.com** and allow
time for a fix before disclosing.

---

<div align="center">

[Telegram](https://t.me/+yMH4w5rX1gZhOTM1) &middot; [Issues](https://github.com/ravibh5522/easyIDE/issues) &middot; [Releases](https://github.com/ravibh5522/easyIDE/releases)

Copyright 2026 Ravi. Licensed under the GPL-3.0; see [LICENSE](LICENSE).

</div>
