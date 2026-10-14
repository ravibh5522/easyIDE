# Third-Party Notices

easyIDE's own source is licensed under the [GNU Affero General Public License v3.0](LICENSE). The components
below are **not** covered by that license -- each is governed by its own terms, which apply to you
independently of which easyIDE license you hold.

Licenses recorded here were verified from primary sources (upstream repository,
official project page, or the source headers themselves) on the date shown, per
the standing rule in [.claude/CLAUDE.md](.claude/CLAUDE.md).

## Bundled in the APK

These ship inside the application package. Redistributing easyIDE means
redistributing these.

| Component | Where | License | Verified |
|---|---|---|---|
| [PRoot](https://github.com/termux/proot) (Termux fork; upstream (c) STMicroelectronics) | `jniLibs/*/libproot.so`, `libprootloader.so`, `libprootloader32.so` | **GPL-2.0-or-later** | 2026-09-30, `COPYING` is the GPL v2 text; source headers (STMicroelectronics, 2015) say "either version 2 of the License, or (at your option) any later version" |
| [talloc](https://talloc.samba.org/) (Samba Team) | `assets/sandbox/*/libtalloc.so.2` | **LGPL-3.0-or-later** | 2026-09-30, `lib/talloc/talloc.h` in samba.git ("version 3 of the License, or (at your option) any later version") |
| [libandroid-shmem](https://github.com/termux/libandroid-shmem) | `assets/sandbox/*/libandroid-shmem.so` | BSD-3-Clause, Copyright (c) 2013 Sergii Pylypenko, (c) 2017 Fredrik Fornwall | 2026-09-30, upstream `LICENSE` |
| [Mermaid](https://github.com/mermaid-js/mermaid) 11.16.1 | `assets/web/mermaid.min.js` | MIT | 2026-08-10, npm registry |
| Kotlin, AndroidX, Jetpack Compose, Material 3, Material Symbols | Gradle dependencies | Apache-2.0 | 2026-08-10 |
| [kotlin-textmate](https://github.com/ivan-magda/kotlin-textmate) 0.2.0 (TextMate tokenizer) | Gradle dependency | MIT | 2026-08-11, upstream `LICENSE` |
| [joni](https://github.com/jruby/joni) + jcodings (Oniguruma regex, pulled in by the tokenizer) | Gradle dependency | MIT | 2026-08-11, upstream `LICENSE` |
| Gson | Gradle dependency | Apache-2.0 | 2026-08-11 |
| Firebase Messaging, Crashlytics, Performance, Remote Config (BoM) and the `google-services` / Crashlytics Gradle plugins | `:telemetry-firebase` Gradle dependencies | Apache-2.0 per their POMs | 2026-09-28, Google Maven POMs; [decision 0031](docs/decision/0031-telemetry-firebase-behind-neutral-module.md) |
| **Firebase Analytics** / `play-services-measurement-api` 23.2.0 | `:telemetry-firebase` Gradle dependency | **Proprietary** (Android Software Development Kit License), not open source. The firebase-android-sdk README says its repository contains the source for all Android Firebase SDKs "except Analytics and Auth" | 2026-09-28 POMs; README re-checked 2026-09-30 |
| [Tink](https://github.com/tink-crypto/tink-java) 1.23.0 (`tink-android`; the app uses only `Ed25519Verify`, registry signature checks, [decision 0016](docs/decision/0016-extension-registry-static-index-ed25519.md) amendment) | Gradle dependency | Apache-2.0 | 2026-09-24, Maven Central POM |
| kotlinx-serialization-json 1.11.0, kotlinx-coroutines | Gradle dependencies | Apache-2.0 | 2026-09-24, Maven Central POM |
| VS Code `language-configuration.json` files (57 languages, tag 1.139.0) | `assets/grammars/config/*.json` | MIT | 2026-09-24, microsoft/vscode LICENSE.txt at tag |
| easyIDE extension SDK core: manifest schema, built-in command list, manifest parser and validator, canonical JSON and signature code (first-party, [decision 0015](docs/decision/0015-extension-sdk-licensing-apache.md)) | `services/shared/extension-schema/` (the `:extension-schema` module, used by `:extensions`) and `tools/easyide-ext/` | **Apache-2.0** (unlike the rest of the app) | 2026-09-24 |
| [Geist and Geist Mono](https://github.com/vercel/geist-font) v1.7.2 (fonts 1.800 / 1.700), hinting stripped | `res/font/geist_*.ttf`, license text `assets/licenses/Geist-OFL.txt` | OFL-1.1 (no Reserved Font Name) | 2026-09-24, repo `OFL.txt` + GitHub license API; [decision 0019](docs/decision/0019-visual-identity.md) |
| [Material Icon Theme](https://github.com/material-extensions/vscode-material-icon-theme) 5.38.1 (PKief.material-icon-theme, Open VSX vsix, sha256 in the pack README), icons and theme file unmodified apart from dropping unreferenced icons | `assets/extensions/easyide.material-icons/`, licence text `assets/licenses/MaterialIconTheme-MIT.txt` | MIT, Copyright (c) 2025 Material Extensions. The language and tool logos inside the icons remain trademarks of their owners and are used only as file-type indicators, as in VS Code | 2026-09-25, repo LICENSE + Open VSX vsix `LICENSE.txt`; [decision 0029](docs/decision/0029-material-icon-theme-default-and-icon-customisation.md) |
| **229 TextMate grammars** via [tm-grammars](https://github.com/shikijs/textmate-grammars-themes) (MIT packaging) | `assets/grammars/*.json` (228), plus Python (MIT) in the built-in pack `assets/extensions/easyide.python/` since 2026-09-24 | MIT x194, Apache-2.0 x22, TextMate-permissive x8, MPL-2.0 x3, ISC x1, BSD-3-Clause x1 | 2026-08-11, per-grammar from the `tm-grammars` NOTICE |

### Obligations this creates

- **PRoot (GPL-2.0-or-later)** and **talloc (LGPL-3.0-or-later)** are copyleft.
  easyIDE invokes PRoot as a **separate process** and does not link against it,
  so this is aggregation -- it does **not** place easyIDE's own Kotlin source
  under the AGPL. What it does require is that anyone we distribute the APK to can
  obtain the complete corresponding source of PRoot and talloc, and that the
  LGPL library remains replaceable. Satisfied by shipping this file with the
  upstream URLs above, plus a written offer of source on request to
  ravibh5522@gmail.com.
- **libandroid-shmem (BSD-3-Clause)** requires its copyright notice and license
  text to be reproduced in binary distributions. The APK currently carries only the
  Geist and Material Icon Theme texts in `assets/licenses/`, so the PRoot/GPL-2.0,
  talloc/LGPL-3.0 and libandroid-shmem texts still have to be added there.
- **Firebase Analytics is proprietary.** It is a separate binary, not part of
  easyIDE's source, but it is inside the APK; whether bundling it is compatible with
  every copyleft component is unresolved (decision 0032). It also excludes builds with
  `:telemetry-firebase` from F-Droid's main repo (decision 0031).
- **Closed-source distribution** of easyIDE's own code is possible only because the
  copyleft components above run as separate processes or replaceable shared
  libraries. It would first require relicensing the source, which the copyright
  holder can do and the CLA in [CONTRIBUTING.md](CONTRIBUTING.md) is meant to permit.
  Any change that links a GPL/LGPL component into the app process ends that. Not
  legal advice.
- If PRoot is ever **linked into** the app process rather than exec'd, this
  analysis no longer holds and the licensing model must be revisited.

#### Bundled grammars: what was deliberately excluded

The grammar set is filtered by licence at build time by
[tools/build-grammars.py](tools/build-grammars.py), so that no
bundled grammar carries terms that conflict with distributing the APK
(originally written for the commercial model in [decision 0008](docs/decision/0008-noncommercial-source-available-licensing.md), now superseded by [0032](docs/decision/0032-relicense-agpl-3.md)).
**31 of the 260 upstream grammars are not shipped**: 5 are GPL-3.0 (`ada`,
`gnuplot`, `nginx`, `org`, `racket`), 1 is marked `GNU` (`ahk2`), and 25 have no
licence that could be established from the upstream repository. Those 25 are not
a licensing judgement, only an unresolved question -- each can be added once its
upstream terms are confirmed.

The 8 grammars recorded as `TextMate-permissive` come from `github.com/textmate/*`
bundles, whose stated terms are: *"Permission to copy, use, modify, sell and
distribute this software is granted."* Verified 2026-08-11 against
`textmate/yaml.tmbundle`.

The MPL-2.0 grammars (`bird2`, `hcl`, `terraform`) are file-level copyleft: they
may be shipped unmodified alongside proprietary code, and only those files carry
the obligation.

Four grammars (`jinja`, `xml`, `stata`, `wikitext`) are **structurally repaired**
at build time -- their `captures` blocks deviate from the TextMate schema and do
not parse otherwise. The repair is mechanical and recorded in the build script;
no grammar rules are changed.

[GitHub Linguist](https://github.com/github-linguist/linguist) (MIT) supplies the
extension-to-scope mapping. It is read at **build time only** and no Linguist
code or data ships in the APK.

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
| [BusyBox](https://busybox.net/license.html) | **GPL-2.0-only** (versions after 1.2.2 may only be distributed under GPLv2, not later versions) | Not bundled. The opt-in chroot backend calls `busybox` from the device's `PATH` (`ChrootLauncher.DEFAULT_BUSYBOX`). Verified 2026-09-30. If a BusyBox binary is ever shipped in the APK, BusyBox's own guidance is to ship the corresponding source alongside (GPL 3(a)), not just a written offer, and to add it to the bundled table above. |
| [Eclipse Theia](https://github.com/eclipse-theia/theia) | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | Deferred -- see [decision 0006](docs/decision/0006-native-ide-shell-before-theia.md). When it lands, EPL-2.0's file-level copyleft applies only to modified Theia files; our extension code calling its public APIs is a combined work and is unaffected ([decision 0001](docs/decision/0001-ide-foundation-theia.md)). |
| [PDF.js](https://github.com/mozilla/pdf.js), [SheetJS CE](https://github.com/SheetJS/sheetjs) | Apache-2.0 | Planned renderers, see [decision 0003](docs/decision/0003-multi-stage-panels-theia-widgets.md) |

Every future addition to this list must have its license and maintenance status
verified from primary sources before adoption.
