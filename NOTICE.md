# Third-Party Notices

easyIDE's own source is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE)
with a [commercial option](LICENSE-COMMERCIAL.md). The components below are **not**
covered by that license -- each is governed by its own terms, which apply to you
independently of which easyIDE license you hold.

Licenses recorded here were verified from primary sources (upstream repository,
official project page, or the source headers themselves) on the date shown, per
the standing rule in [.claude/CLAUDE.md](.claude/CLAUDE.md).

## Bundled in the APK

These ship inside the application package. Redistributing easyIDE means
redistributing these.

| Component | Where | License | Verified |
|---|---|---|---|
| [PRoot](https://github.com/termux/proot) (Termux fork; upstream (c) STMicroelectronics) | `jniLibs/*/libproot.so`, `libprootloader.so`, `libprootloader32.so` | **GPL-2.0-or-later** | 2026-08-10, `COPYING` + source headers |
| [talloc](https://talloc.samba.org/) (Samba Team) | `assets/sandbox/*/libtalloc.so.2` | **LGPL-3.0-or-later** | 2026-08-10, `lib/talloc/talloc.h` in samba.git |
| [libandroid-shmem](https://github.com/termux/libandroid-shmem) | `assets/sandbox/*/libandroid-shmem.so` | BSD-3-Clause | 2026-08-10, repo metadata |
| [Mermaid](https://github.com/mermaid-js/mermaid) 11.16.1 | `assets/web/mermaid.min.js` | MIT | 2026-08-10, npm registry |
| Kotlin, AndroidX, Jetpack Compose, Material 3, Material Symbols | Gradle dependencies | Apache-2.0 | 2026-08-10 |

### Obligations this creates

- **PRoot (GPL-2.0-or-later)** and **talloc (LGPL-3.0-or-later)** are copyleft.
  easyIDE invokes PRoot as a **separate process** and does not link against it,
  so this is aggregation -- it does **not** place easyIDE's own Kotlin source
  under the GPL. What it does require is that anyone we distribute the APK to can
  obtain the complete corresponding source of PRoot and talloc, and that the
  LGPL library remains replaceable. Satisfied by shipping this file with the
  upstream URLs above, plus a written offer of source on request to the contact
  in [LICENSE-COMMERCIAL.md](LICENSE-COMMERCIAL.md).
- These obligations survive a commercial license. A paying customer is still
  bound by the GPL and LGPL terms for these two components.
- If PRoot is ever **linked into** the app process rather than exec'd, this
  analysis no longer holds and the licensing model must be revisited.

## Downloaded at runtime, not bundled

Obtained by the user's device after installation. easyIDE does not redistribute
these, so the distribution obligations sit with their upstream hosts.

| Component | License | Note |
|---|---|---|
| Ubuntu base rootfs tarball and any packages installed into it via `apt` (`git`, build tools, Node.js, Python, ...) | Mixed; substantially GPL-2.0 / GPL-3.0 / LGPL | Fetched per environment, cached on device |
| [Claude Code CLI](https://claude.com/claude-code) (Anthropic) | Anthropic commercial terms -- **not redistributable** | Must be installed by the user with their own account; never vendored into this repo or the APK |

## Not currently included

| Component | License | Status |
|---|---|---|
| [Eclipse Theia](https://github.com/eclipse-theia/theia) | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | Deferred -- see [decision 0006](docs/decision/0006-native-ide-shell-before-theia.md). When it lands, EPL-2.0's file-level copyleft applies only to modified Theia files; our extension code calling its public APIs is a combined work and is unaffected ([decision 0001](docs/decision/0001-ide-foundation-theia.md)). |
| [PDF.js](https://github.com/mozilla/pdf.js), [SheetJS CE](https://github.com/SheetJS/sheetjs) | Apache-2.0 | Planned renderers, see [decision 0003](docs/decision/0003-multi-stage-panels-theia-widgets.md) |

Every future addition to this list must have its license and maintenance status
verified from primary sources before adoption.
