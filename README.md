# easyIDE

An IDE for Android tablets with a real Linux userland underneath it -- not a snippet editor,
not a remote-desktop client into someone else's server. You get a native editor, a file
tree, and a terminal running Ubuntu, all on the device, offline, with no root required.

**Status: early.** `v0.1.0`, no release build yet. Android 8.0+ (minSdk 26), arm64 and x86_64.
Source-available -- free for personal and noncommercial use, [paid for commercial use](#license).

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
- **Six Material 3 themes**, persisted, responsive across window size classes, and honouring
  the system reduce-motion setting.

## What is not there yet

Stated plainly, because a README that oversells is worse than one that is short:

- **No language intelligence.** No LSP, no completion, no diagnostics, no go-to-definition.
  Syntax highlighting is real (see above), but it is lexical only -- it does not know what a
  symbol *means*. Language servers are the planned next step.
- **The Claude Code CLI is not wired up.** Installing and verifying it inside the sandbox is
  planned, not done.
- **No git UI, no credential helper, no tmux session persistence, no ssh-agent.**
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
./gradlew :app:assembleDebug
```

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
| [docs/decision/](docs/decision/) | ADRs -- the major, hard-to-reverse choices and why |
| [docs/chainlog/](docs/chainlog/) | Append-only weekly log of what changed |

## License

easyIDE is **source-available, not open source**. Read it, modify it, redistribute it -- but
"free for personal use, paid for commercial use" discriminates by field of endeavor, which the
Open Source Definition does not allow. Calling it open source would be a false claim, so we
don't.

The code is licensed under the
**[PolyForm Noncommercial License 1.0.0](LICENSE)**, with a separate
**[commercial license](LICENSE-COMMERCIAL.md)** for everything it excludes.

**Free, with nobody to ask:**

- Personal use -- your own coding, side projects, hobby work, learning.
- Study, research, experiment and testing with no anticipated commercial application.
- Use by charities, schools and universities, public research bodies, public safety or health
  organizations, environmental organizations, and government institutions -- whatever their funding.
- Modifying the source and running your own build for any of the above.
- Redistributing it, modified or not, for any of the above -- as long as you pass along
  [LICENSE](LICENSE) (including its `Required Notice:` line) and [NOTICE.md](NOTICE.md).

**Requires buying a commercial license:**

- Any use by a for-profit company in the course of its business -- **including purely internal
  developer tooling**.
- Contractors, consultancies and freelancers using it on billable client work.
- Bundling or embedding easyIDE, or a derivative of it, in anything you sell or monetize.
- Offering it, or anything substantially derived from it, as a hosted or managed service.

Unsure which side you fall on? Ask: **ravibh5522@gmail.com**. The full boundary and how to buy
are in [LICENSE-COMMERCIAL.md](LICENSE-COMMERCIAL.md); why this model was chosen over AGPL-3.0
and BSL 1.1 is in [decision 0008](docs/decision/0008-noncommercial-source-available-licensing.md).

### Third-party components

easyIDE's own license does not cover what it bundles. Two of those are copyleft and stay
copyleft no matter which easyIDE license you hold: **PRoot** is GPL-2.0-or-later and
**talloc** is LGPL-3.0-or-later, both shipped inside the APK. PRoot is run as a separate
process rather than linked, so this does not place easyIDE's own source under the GPL -- but
anyone who receives the APK is owed the corresponding source for both. Every bundled
component, its license, and where that license was verified from is listed in
**[NOTICE.md](NOTICE.md)**.

The Claude Code CLI is Anthropic's commercial software and is **not** redistributed here.
Install it yourself under your own account.

## Contributing

Contributions are welcome -- but read this part before you write code, because the dual license
has a consequence for you.

- **Opening a pull request accepts the CLA** in [CONTRIBUTING.md](CONTRIBUTING.md). Nothing to
  sign or email; the PR is the acceptance.
- **It grants sublicensing rights.** The licensor can ship your contribution to paying
  commercial customers, and you will not be paid for it. Dual licensing does not work
  otherwise. You keep your own copyright and can reuse your work anywhere -- it is a license,
  not an assignment.
- **Not comfortable with that? Don't send code.** Issues, reproductions and design feedback are
  genuinely valuable, and none of them are covered by the CLA.
- **Don't paste in third-party code.** Propose it as a dependency instead, with its license and
  maintenance status verified from the upstream repo or official docs -- not from memory. It
  gets recorded in [NOTICE.md](NOTICE.md).

House rules that come up most often -- 600 lines max per file, no hardcoded colors/strings/
keybindings, error handling only at real boundaries, abstract at the third use, and never
describe the sandbox as security isolation. Non-trivial changes get a
[chainlog](docs/chainlog/) entry; hard-to-reverse choices get an
[ADR](docs/decision/). The full list is in [CONTRIBUTING.md](CONTRIBUTING.md).

**Verify before claiming done.** If you could not run it -- no tablet, no emulator -- say so in
the pull request instead of reporting success.

**Security issues:** do not open a public issue. Email **ravibh5522@gmail.com** and allow time
for a fix before disclosing.

---

Copyright 2026 Ravi. See [LICENSE](LICENSE).
