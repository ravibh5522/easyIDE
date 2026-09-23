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
| [kotlin-textmate](https://github.com/ivan-magda/kotlin-textmate) 0.2.0 (TextMate tokenizer) | Gradle dependency | MIT | 2026-08-11, upstream `LICENSE` |
| [joni](https://github.com/jruby/joni) + jcodings (Oniguruma regex, pulled in by the tokenizer) | Gradle dependency | MIT | 2026-08-11, upstream `LICENSE` |
| Gson | Gradle dependency | Apache-2.0 | 2026-08-11 |
| kotlinx-serialization-json 1.11.0, kotlinx-coroutines | Gradle dependencies | Apache-2.0 | 2026-09-24, Maven Central POM |
| VS Code `language-configuration.json` files (57 languages, tag 1.139.0) | `assets/grammars/config/*.json` | MIT | 2026-09-24, microsoft/vscode LICENSE.txt at tag |
| easyIDE extension manifest schema (first-party, [decision 0015](docs/decision/0015-extension-sdk-licensing-apache.md)) | `services/shared/extension-schema/` (packaged into `:extensions`) | **Apache-2.0** (unlike the rest of the app) | 2026-09-24 |
| **229 TextMate grammars** via [tm-grammars](https://github.com/shikijs/textmate-grammars-themes) (MIT packaging) | `assets/grammars/*.json` | MIT x194, Apache-2.0 x22, TextMate-permissive x8, MPL-2.0 x3, ISC x1, BSD-3-Clause x1 | 2026-08-11, per-grammar from the `tm-grammars` NOTICE |

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

#### Bundled grammars: what was deliberately excluded

The grammar set is filtered by licence at build time by
[tools/build-grammars.py](tools/build-grammars.py), because easyIDE is sold
commercially ([decision 0008](docs/decision/0008-noncommercial-source-available-licensing.md)).
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
| [Eclipse Theia](https://github.com/eclipse-theia/theia) | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | Deferred -- see [decision 0006](docs/decision/0006-native-ide-shell-before-theia.md). When it lands, EPL-2.0's file-level copyleft applies only to modified Theia files; our extension code calling its public APIs is a combined work and is unaffected ([decision 0001](docs/decision/0001-ide-foundation-theia.md)). |
| [PDF.js](https://github.com/mozilla/pdf.js), [SheetJS CE](https://github.com/SheetJS/sheetjs) | Apache-2.0 | Planned renderers, see [decision 0003](docs/decision/0003-multi-stage-panels-theia-widgets.md) |

Every future addition to this list must have its license and maintenance status
verified from primary sources before adoption.
