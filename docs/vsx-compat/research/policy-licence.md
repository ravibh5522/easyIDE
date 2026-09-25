# Policy, licensing and platform facts (agent POLICY-LICENCE)

Retrieval date for every URL below: **2026-09-25** (UTC), unless stated. Raw copies of every fetched page are in
`$SP/research-policy/` (`*.html` + extracted `*.txt`, `ext/*.api.json|.license|.manifest`). Paths `services/...`,
`docs/...` are relative to WT (`/home/user/easyIDE-wt/audit-vsx`). `$VS` = microsoft/vscode main @ `0b16cb9` (2026-09-25).
Nothing here is a legal conclusion; section 6 lists questions for counsel.

---

## 1. Google Play policy on downloaded executable code, and Android W^X

### 1.1 Device and Network Abuse (primary text)

URL requested: https://support.google.com/googleplay/android-developer/answer/9888379?hl=en
It now **redirects** to `https://support.google.com/googleplay/android-developer/answer/16559646` (title "Device and Network Abuse - Play Console Help"). Raw: `research-policy/play_dna.txt`.

Verbatim, "Full Policy" section (play_dna.txt:32-36):

> "An app distributed via Google Play may not modify, replace, or update itself using any method other than Google Play's update mechanism. Likewise, an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play. This restriction does not apply to code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs (such as JavaScript in a webview or browser)."

> "Apps or third-party code, like SDKs, with interpreted languages (JavaScript, Python, Lua, etc.) loaded at run time (for example, not packaged with the app) must not allow potential violations of Google Play policies."

Examples list (play_dna.txt:44,47,49):

> "Apps or third party code (for example, SDKs) that download executable code, such as dex files or native code, from a source other than Google Play."
> "Apps or third party code (for example, SDKs) containing a webview with added JavaScript Interface that loads untrusted web content (for example, http:// URL) or unverified URLs obtained from untrusted sources (for example, URLs obtained with untrusted Intents)."
> "Apps that circumvent Android sandbox protections in order to derive user activity or user identity from other apps."

Key Considerations (play_dna.txt:52,58): "Respect the FLAG_SECURE setting, and on-device containers must respect REQUIRE_SECURE_ENV." / "Don't use third-party SDKs in your app that download executable code (like dex or .so files) from outside Google Play (except in VMs/interpreters)."

Policy summary (play_dna.txt:30): "...such as performing self-updates outside the Play Store, downloading unauthorized executable code, exploiting security vulnerabilities, facilitating hacking..."

**No clause in this page mentions IDEs, terminals, shells, package managers or Linux userlands by name** (grep for `terminal|IDE|shell|package manager` returned nothing relevant). The only carve-out wording is the VM/interpreter one quoted above. Whether a proot-hosted glibc userland (native aarch64 ELF code, executed through proot's ptrace loader) or Node.js (a native binary that is itself a JS VM) falls inside "code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs" is **not answered by the text** - see Q1-Q4 in section 6.

Policy center https://play.google/developer-content-policy/ (HTTP 200) is a navigation page (sections incl. "Malware", "Mobile Unwanted Software", "Families"); it carries no additional exec-code text (grep `executable code|interpreter` = 0 hits, `play_center.txt`).

### 1.2 Malware policy (https://support.google.com/googleplay/android-developer/answer/9888380?hl=en, `play_malware.txt`)

| Topic | Verbatim | line |
|---|---|---|
| Backdoors | "you must remove any code that acts as a backdoor, which is defined as code facilitating unwanted or harmful remote-controlled operations." | 57 |
| Hostile downloaders | "Google Play prohibits \"hostile downloaders\"—apps that download other Mobile Unwanted Software (MUwS). An app is flagged as a hostile downloader if it's believed to be designed to spread MUwS or if at least 5% of its downloads are determined to be MUwS. This policy does not apply to major browsers or file-sharing apps, as long as they only download software with the user's explicit consent and initiation." | 137 |
| Hostile downloader threshold | "At least 5% of apps downloaded by it are PHAs with a minimum threshold of 500 observed app downloads (25 observed PHA downloads)." | 141 |
| Elevated privilege | "your app must not contain code that gains elevated privileges or breaks the Android security sandbox." | 174 |
| Rooting | "Google Play allows non-malicious rooting but prohibits malicious rooting code. You must inform users in advance about rooting..." | 210 |

Relevance: extension marketplace = user-initiated download of third-party code (Open VSX), some of which bundles native binaries (section 2.4: claude-code ships a 238 MB `claude` ELF; ruff/pyrefly/rust-analyzer/redhat.java ship per-platform builds). proot `-0` gives fake uid 0 inside the guest only (ADR 0002 Addendum 2: "cosmetic, not real privilege") - no Android privilege escalation. The chroot opt-in backend needs a rooted device (ADR 0002 title) - Rooting clause relevance is a Q for counsel.

### 1.3 User Data policy (https://support.google.com/googleplay/android-developer/answer/10144311?hl=en, `play_userdata.txt`)

> "If you include third party code (for example, an SDK) in your app, you must ensure that the third party code used in your app, and that third party's practices with respect to user data from your app, are compliant with Google Play Developer Program policies... These requirements also apply to third-party AI integrations (such as products, services, code) and you remain responsible for ensuring compliance with this policy, including limited use, disclosure and consent." (line 34)

Prominent Disclosure (line 77-92): required "In cases where your app's access, collection, use, or sharing of personal and sensitive user data may not be within the reasonable expectation of the user"; disclosure "Must be within the app itself", "Must be displayed in the normal usage of the app", consent "Must require affirmative user action".

Implication (for Q5): extensions that send telemetry by default (section 5.3: GitLens, ms-python, redhat.*, claude-code, GitHub PR) run inside our app. Whether user-installed extensions count as "third party code ... in your app" is unanswered by the text.

**Families policy**: only listed in the policy center navigation; not fetched in detail. The User Data page says: "If your app is for children, you must comply with the Google Play Families policy" (line 69). Relevant only if the target audience includes children. UNVERIFIED detail - fetch https://support.google.com/googleplay/android-developer/answer/9893335 if the audience question arises.

### 1.4 Android 10 W^X (primary source)

https://developer.android.com/about/versions/10/behavior-changes-10 (`android10.txt:730-740`), heading "Removed execute permission for app home directory":

> "Execution of files from the writable app home directory is a W^X violation. Apps should load only the binary code that's embedded within an app's APK file. Untrusted apps that target Android 10 cannot invoke execve() directly on files within the app's home directory. In addition, apps that target Android 10 cannot in-memory modify executable code from files which have been opened with dlopen() and expect those changes to be written through to disk, because the library cannot have been mapped PROT_EXEC through a writable file descriptor. This includes any shared object (.so) files with text relocations."

### 1.5 How easyIDE handles it (our code/docs, read-only)

| Fact | Source |
|---|---|
| `compileSdk = 37`, `targetSdk = 37`, `minSdk = 26` | services/mobile/app/build.gradle.kts:21,25-26 |
| `packaging { jniLibs { useLegacyPackaging = true } }` with comment "proot is exec'd, not dlopen'd, so it must exist as a real file in the native-library directory" | services/mobile/app/build.gradle.kts:117-123 |
| `android:extractNativeLibs="true"` | services/mobile/app/src/main/AndroidManifest.xml:17 |
| Executables shipped as `libproot.so`, `libprootloader.so`, `libprootloader32.so` | services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/bootstrap/ProotInstaller.kt:97-99 |
| Resolved from `context.applicationInfo.nativeLibraryDir`, fails with BackendUnavailable if not executable | ProotInstaller.kt:51-59 |
| `PROOT_LOADER`, `PROOT_LOADER_32`, `LD_LIBRARY_PATH` env for the loader + `libtalloc.so.2` (asset copied to app data, dlopen only) | services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/shell/SandboxShell.kt:179-189; ProotInstaller.kt:28-33 |
| Measured on Xiaomi Pad 6, SELinux Enforcing, targetSdk 37: exec from `app_data_file` denied (`avc: denied { execute_no_trans }`), from native-lib dir (`apk_data_file`) works; apt-installed guest binaries run "by proot's own loader rather than by execve from the Android process" | docs/decision/0002-sandbox-backend-proot-default-chroot-optin.md:35-64 |
| Hard links denied in app data; extractor copies them | 0002:66-76 |
| Guest image = `ubuntu-base-24.04.3-base-arm64.tar.gz` from cdimage.ubuntu.com, sha256 pinned, signature checked when pinned | services/mobile/app/src/main/java/dev/easyide/app/data/SandboxImages.kt:58-80 |

Technical note (not a policy conclusion): the W^X mechanism is satisfied at the SELinux level for the *Android* process (only APK-embedded code is `execve`d). Code inside the guest (apt packages, Node, extension-bundled ELF binaries) is native code that was downloaded after install and is mapped/executed by proot's loader. The Android 10 text addresses `execve()` and `dlopen` write-through; the Play policy addresses "download executable code ... native code" regardless of mechanism. These are two different questions.

### 1.6 Termux-like apps: what primary sources say

| Claim | Source (retrieved 2026-09-25) |
|---|---|
| F-Droid/GitHub Termux targets SDK 28: `targetSdkVersion=28` | https://raw.githubusercontent.com/termux/termux-app/master/gradle.properties |
| "Due to SDK behavior changes and new Google Play policy, Termux does not receive updates on Play Store anymore." / "Google requires the target SDK level to be set to at least 29 ... we cannot do so and have to use SDK level 28." (page is old; wording predates the current Play build) | termux/termux-packages wiki `Termux-and-Android-10.md` (git clone of https://github.com/termux/termux-packages.wiki.git, last commit 2026-09-20) |
| "There is currently a build of Termux available on Google Play for Android 11+ devices, with extensive adjustments in order to pass policy requirements there. This is under development and has missing functionality and bugs" | termux-app README.md:120 (raw.githubusercontent.com) |
| Play build source: `targetSdkVersion=37`, `minSdkVersion=30`; `libproot-loader.so` downloaded at build time into `src/main/jniLibs/<abi>/`; bootstrap zip compiled into a native lib (`System.loadLibrary("termux-bootstrap")`); `useLegacyPackaging = true` | git clone https://github.com/termux-play-store/termux-apps @ f212745 (2026-06-28): gradle.properties:6-9, termux-app/build.gradle.kts:77-78,170-188, TermuxInstaller.java:307-315 |
| Play listing live: title "Termux - Apps on Google Play", developer "Fredrik Fornwall", "Updated on Jun 21, 2026", "10M+ Downloads" | https://play.google.com/store/apps/details?id=com.termux&hl=en (HTTP 200) |

So: a Termux build using the same jniLibs trick as easyIDE is currently listed on Play. That is an observation, not evidence of policy compliance or of how Google reviewed it. Whether the Play build lets users `apt install` arbitrary native packages at runtime was **not verified** (UNVERIFIED; check termux-play-store/termux-packages README and the app's package-manager code).

---

## 2. Licences (primary sources)

### 2.1 VS Code source vs product

| Item | Verbatim header / clause | Source |
|---|---|---|
| microsoft/vscode repo | "MIT License" / "Copyright (c) 2015 - present Microsoft Corporation" / "Permission is hereby granted, free of charge, to any person obtaining a copy..." | $VS/LICENSE.txt:1-5 |
| VS Code **product** licence | "This license applies to the Visual Studio Code product. Source Code for Visual Studio Code is available at https://github.com/Microsoft/vscode under the MIT license agreement" | https://code.visualstudio.com/license |
| product: restrictions | "You may not ... reverse engineer, decompile or disassemble the software...; remove, minimize, block or modify any notices of Microsoft or its suppliers in the software; use the software in any way that is against the law; share, publish, rent or lease the software, or provide the software as a stand-alone offering for others to use." | same |
| product: extensions | "Those packages are under their own licenses, and not this agreement. Microsoft does not distribute, license or provide any warranties for any of the third party packages. By accessing or using our extension marketplace, you agree to the extension marketplace terms located at https://aka.ms/vsmarketplace-ToU." | same |
| VS Marketplace ToU (PDF, `aka.ms/vsmarketplace-ToU` -> cdn.vsassets.io/.../Microsoft-Visual-Studio-Marketplace-Terms-of-Use.pdf) | "Marketplace Offerings are intended for use only with In-Scope Products and Services and you may not install, reverse-engineer, import or use Marketplace Offerings in products and services except for the In-Scope Products and Services." / "You may not import, install, or use Offerings published by Microsoft or GitHub, or Microsoft affiliates in any products or services except for the In-Scope Products and Services." In-Scope = "Microsoft Visual Studio, Visual Studio Code, GitHub Codespaces, Azure DevOps, Azure DevOps Server, and successor products" | `research-policy/msmkt_tou.txt:16-18,49-51,85-87` |

Consequence for us (fact, not advice): we build from the MIT source / our own shim (ADR 0030), not the product; the product licence does not apply to MIT source. The Marketplace ToU binds users of marketplace.visualstudio.com; we source from Open VSX (Q7 asks whether Microsoft-published extensions re-hosted on Open VSX carry any marketplace-derived restriction - the Open VSX-hosted LICENSE files for ms-python.python / ms-azuretools.vscode-docker / GitHub.* are plain MIT, see 2.4).

### 2.2 Open VSX Terms of Use

https://open-vsx.org/terms-of-use is an SPA (body empty to curl); the bundle routes `/terms-of-use` to `/documents/terms-of-use.md`, fetched from https://open-vsx.org/documents/terms-of-use.md ("Open-VSX Registry Website Terms of Use", dated **February 11, 2021**; `research-policy/ovsx_terms.md`). Download button text in bundle: "By clicking download, you accept this website's Terms of Use."

> "By accessing, browsing, or using this Website including downloading Content from this Website, you acknowledge that you have read, understand, and agree to be bound by these terms."
> "This Website makes available Content from entities other than the Eclipse Foundation (“Third Party Publishers\"), under terms and licenses (including proprietary licenses) provided by such Third Party Publishers. It is your responsibility when accessing and using any Content to comply with terms and licenses associated with that Content."
> "By making the Content available for access by you on this Website, neither the Eclipse Foundation nor the Members grant any licenses to any copyrights, patents or any other intellectual property rights in the Content or any other materials."
> "The Eclipse Foundation may remove, update, or modify Content from this Website at any time without notice."

There is **no clause on redistribution or mirroring**; redistribution rights come solely from each extension's licence. The ToU has no rate-limit/automated-access clause (other agents cover API rate limits).

Publisher Agreement v1.1 (https://open-vsx.org/documents/publisher-agreement-v1.1.md), Section 4a:
> "All Registry Offerings and associated Offering Contents must be licensed to end-users. ... If you fail to specify a license in the Listing Information for an Offering, the Offering and Offering Contents will be made available under the MIT license (https://opensource.org/licenses/MIT)"
Section 3e grants Eclipse the right "to host, install, use, reproduce, transmit ... and make available to end-users (including through multiple tiers of distribution)". Section 6 requires publishers to disclose data collection in the listing. (Note: the MIT-default clause matters for Vue.volar and mhutchie.git-graph whose API `license` is null, but both ship an explicit LICENSE file, which governs - Q8.)

### 2.3 Other runtimes/frameworks

| Component | Verbatim header | Source |
|---|---|---|
| Node.js | "Node.js is licensed for use as follows:" / "Copyright Node.js contributors. All rights reserved." / "Permission is hereby granted, free of charge..." (MIT); file continues with ~2,860 lines of bundled third-party licences (V8, OpenSSL, ICU, libuv, ...) | https://raw.githubusercontent.com/nodejs/node/main/LICENSE lines 1-12 |
| Eclipse Theia | `SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0`; repo also has `LICENSE-vscode.txt` ("This license covers code originally copied from the vscode repository") and `LICENSE-MIT.txt` | `git clone --depth 1 --filter=blob:none` https://github.com/eclipse-theia/theia @ 74ea9a0 (2026-09-25) into `research-policy/theia` (no prior clone at `$SP/theia`); NOTICE.md:17-28 |
| @vscode/codicons (icon font used by webviews/UI) | npm `license: CC-BY-4.0` (v0.0.46-24); repo LICENSE = CC Attribution 4.0 (icons), LICENSE-CODE = MIT | registry.npmjs.org/@vscode/codicons/latest; raw.githubusercontent.com/microsoft/vscode-codicons/main/LICENSE |
| Ubuntu base rootfs | Mixed, mostly GPL/LGPL; already recorded as "Downloaded at runtime, not bundled" | NOTICE.md:78-83 |

### 2.4 Extension licences (Open VSX API + packaged LICENSE)

API = `https://open-vsx.org/api/{ns}/{name}` `license` field; LICENSE = file at `files.license` URL (for multi-platform extensions the API default resolved to the `alpine-arm64` or `universal` build; platform noted). "Redistributable by us?" = only "Yes (MIT terms)" when the text clearly permits distribution with notice; otherwise "user-downloads only". This is a reading of the licence text, not legal advice.

| id | ver | API `license` | LICENSE first line / summary | Redistributable by us? | Notes |
|---|---|---|---|---|---|
| eamodio.gitlens | 2026.9.250515 | `%gitlens.license%` (nls -> "SEE LICENSE IN LICENSE") | "The following license applies to all files in this repository, except for those in or under any directory named \"plus\", which are covered by LICENSE.plus." then MIT, (c) 2021-2026 Axosoft LLC dba GitKraken, (c) 2016-2021 Eric Amodio | **user-downloads only** | LICENSE.plus (in vsix, `extension/LICENSE.plus`): "...the Software and its functionality may only be used if you ... have agreed to ... the GitKraken End User License Agreement, available at https://gitkraken.com/eula ... and otherwise have a valid subscription for the correct number of user seats..." / "Except as set forth above, it is forbidden to copy, merge, publish, distribute, sublicense, and/or sell the Software." The shipped `dist` bundles plus code, so the vsix as a whole is not clean MIT. Telemetry on by default (5.3). |
| Anthropic.claude-code | 2.1.282 | "© Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements outlined here: https://code.claude.com/docs/en/legal-and-compliance." | no LICENSE file (`files.license` absent); package.json:7 same string | **user-downloads only** | Ships 238,084,088-byte native ELF `extension/resources/native-binary/claude` (linux-arm64 build). Legal page (https://code.claude.com/docs/en/legal-and-compliance): "preinstalling or running Claude Code in your products or services (e.g. in hosted sandboxes or other agent infrastructure) requires agreeing to our Commercial Terms of Service and complying with the conditions below: The Claude Code binary must not be modified. ... customers may not remove, disable, or restrict any authentication method built into it" / "Customers may not pay for, resell, or intermediate Claude usage on their end users' behalf." / "you can't use the Claude Code or Anthropic names or logos as part of your own product, feature, or company name". Consistent with NOTICE.md:83 "not redistributable". |
| esbenp.prettier-vscode | 12.4.0 | MIT | "MIT License" (c) 2017 Esben Petersen | Yes (MIT terms) | bundles prettier (MIT; UNVERIFIED third-party notices inside vsix) |
| dbaeumer.vscode-eslint | 3.0.34 | MIT | "Copyright (c) Microsoft Corporation / All rights reserved. / MIT License" | Yes (MIT terms) | |
| redhat.vscode-yaml | 1.25.2026092308 | MIT | "MIT License" (c) 2017 Red Hat Inc. and others | Yes (MIT terms) | Red Hat telemetry dep (5.3) |
| redhat.java | 1.57.2026092508 | EPL-2.0 | "Eclipse Public License - v 2.0" (276 lines) | Yes under EPL-2.0 (source-availability obligations) - counsel to confirm | Per-platform builds (linux-arm64 exists) embed a JRE (UNVERIFIED; check `jre/` in the linux-arm64 vsix and its licence) |
| golang.Go | 0.56.1 | MIT | "vscode-go / The MIT License (MIT) / Original Work Copyright (c) 2015-2020 Microsoft Corporation / Current Work ... The Go Authors" + 860 lines of third-party attributions | Yes (MIT terms + attribution file) | |
| rust-lang.rust-analyzer | 0.4.3061 | MIT OR Apache-2.0 | "This software is licensed under either of the Apache License, Version 2.0, or the MIT License." | Yes | linux-arm64 build ships the server binary |
| ms-python.python | 2026.4.0 | MIT | "PLEASE NOTE: This is the license for the Python extension ... The Pylance extension is only available in binary form and is released under a Microsoft proprietary license" then MIT (c) Microsoft | Yes for this vsix (MIT); **extensionPack pulls ms-python.vscode-pylance (proprietary)** | extensionPack = pylance, debugpy, vscode-python-envs (manifest). Pylance on Open VSX: not checked (UNVERIFIED) |
| charliermarsh.ruff | 2026.84.0 | MIT | "MIT License" (c) 2022 Charles Marsh + vscode-python-tools-extension-template MIT | Yes (MIT terms) | platform builds bundle `ruff` binary (licence of binary MIT per ruff repo - UNVERIFIED) |
| meta.pyrefly | 1.3.9001 | MIT | "MIT License" (c) Meta Platforms, Inc. and affiliates | Yes (MIT terms) | platform builds bundle binary |
| llvm-vs-code-extensions.vscode-clangd | 0.6.0 | MIT | "The MIT License (MIT)" (c) 2019 The LLVM Developers | Yes (MIT terms) | clangd itself downloaded separately (Apache-2.0 WITH LLVM-exception - UNVERIFIED) |
| Vue.volar | 3.3.11 | **null** | "MIT License" (c) 2021-present Johnson Chu | Yes (MIT terms per LICENSE) | API field missing |
| bradlc.vscode-tailwindcss | 0.16.0 | MIT | "MIT License" (c) Tailwind Labs, Inc. | Yes (MIT terms) | |
| GitHub.vscode-pull-request-github | 0.166.1 | MIT | "MIT License" (c) Microsoft Corporation | Yes (MIT terms) | engines.node ">=20"; `@vscode/extension-telemetry` dep; depends on `vscode.github-authentication` |
| ms-azuretools.vscode-docker | 2.0.0 | "SEE LICENSE IN LICENSE.md" | "Visual Studio Code Extension for Docker / Copyright (c) Microsoft Corporation / All rights reserved. / MIT License" | Yes (MIT terms) | extensionDependencies: ms-azuretools.vscode-containers (licence not checked, UNVERIFIED); last Open VSX publish 2025-05-29 |
| Shopify.ruby-lsp | 0.10.6 | MIT | "The MIT License (MIT)" (c) 2022-present Shopify Inc. + grammar attributions | Yes (MIT terms) | |
| Dart-Code.dart-code | 3.143.20260901 | "SEE LICENSE IN LICENSE" | "MIT License" (c) 2016 Danny Tuppeny | Yes (MIT terms) | |
| PKief.material-icon-theme | 5.38.1 | MIT | "The MIT License (MIT)" (c) 2025 Material Extensions | Yes - **already bundled** (NOTICE.md table, ADR 0029) | |
| mhutchie.git-graph | 1.30.0 | **null** | "Copyright (c) 2019-present, mhutchie" ... "to use, copy, modify, merge, and to permit persons..." / "**Permission is NOT GRANTED to publish, distribute, sublicense, and/or sell derivative works of the Software.**" | **user-downloads only** | Not OSI MIT. Last publish 2021-04-17 |
| usernamehw.errorlens | 3.28.0 | MIT | "MIT License" (c) 2023 Alexander | Yes (MIT terms) | |
| streetsidesoftware.code-spell-checker | 4.9.3 | GPL-3.0-or-later | "GNU GENERAL PUBLIC LICENSE / Version 3, 29 June 2007" (c) 2016-2023 Jason Dent | Only with GPL-3.0 obligations (corresponding source); keep **user-downloads only** unless counsel agrees | copyleft; separate program (runs in ext host) |

### 2.5 Interaction with our own licensing (LICENSE, LICENSE-COMMERCIAL.md, NOTICE.md, ADR 0008, 0015)

| Our fact | Source |
|---|---|
| App = PolyForm Noncommercial 1.0.0 + paid commercial licence; "Required Notice: Copyright 2026 Ravi" | LICENSE:1-12; ADR 0008:32-36 |
| PolyForm "Notices" section: distributees must receive the licence text and `Required Notice:` lines | LICENSE:35-42 |
| "Third-party components bundled with or downloaded by this software carry their own licenses" | LICENSE:7-8 |
| NOTICE.md is the single ledger, split "Bundled in the APK" vs "Downloaded at runtime, not bundled"; rule: "Every future addition to this list must have its license and maintenance status verified from primary sources before adoption." | NOTICE.md:13-35,78-97 |
| Commercial licence "does not change the license of the third-party components listed in NOTICE.md" | LICENSE-COMMERCIAL.md:44-45 |
| SDK core (schema, validator, CLI, runners) is Apache-2.0; "Apache code may not import app code" | ADR 0015:24-53,62-70 |
| Precedent: VS Code `language-configuration.json` files (MIT, tag 1.139.0) already bundled and listed | NOTICE.md table row |
| ADR 0030: "We write the `vscode` module ourselves (`services/mobile/exthost/js`), not port VS Code's `extHost*` sources and not adopt Theia's `plugin-ext`"; "Nothing is bundled but our own JS." | docs/decision/0030-vscode-extension-host-in-sandbox.md:24-31 |

If MIT code from `$VS` (e.g. `extHostTypes.ts` classes, `vscode.d.ts` types, webview `pre/index.html` + `service-worker.js` from `$VS/src/vs/workbench/contrib/webview/browser/pre/`, codicon CSS) is vendored:
1. MIT condition: "The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software." -> keep the Microsoft header in each vendored file and add a NOTICE.md row ("Bundled in the APK" or the new exthost JS bundle), component, path, MIT, date, source commit - the same pattern as the language-configuration row.
2. MIT is permissive; combining MIT files into a PolyForm-NC work is permitted by MIT (no copyleft). The vendored files themselves stay MIT for downstream recipients (PolyForm cannot relicense someone else's MIT grant) - counsel Q9 on how to label mixed files.
3. If vendored into the Apache-2.0 SDK paths (ADR 0015), MIT -> Apache inclusion needs only the MIT notice retained.
4. Codicon **font/icons are CC-BY-4.0**, not MIT: attribution requirement differs; add separately if bundled.
5. Theia `plugin-ext` would bring EPL-2.0 file-level copyleft (already rejected in ADR 0030; NOTICE.md "Not currently included" row).
6. `vscode.d.ts` is MIT (same repo). Copying it into the shim for typing = vendoring; same notice rule.
7. Node.js is not bundled (apt / download per section 3) -> goes into NOTICE.md "Downloaded at runtime" table; if we ever ship Node in the APK, its full LICENSE (2,864 lines incl. OpenSSL/ICU/V8) must ship.

---

## 3. Node runtime for the proot Ubuntu arm64 guest

### 3.1 What VS Code itself pins

| Item | Value | Source |
|---|---|---|
| `.nvmrc` (dev Node) | `24.18.0` | $VS/.nvmrc |
| Remote server / reh Node target | `target="24.21.0"`, `runtime="node"`, `disturl="https://nodejs.org/dist"` | $VS/remote/.npmrc |
| Desktop Electron | `target="43.7.3"` | $VS/.npmrc |
| root package.json `engines` | none (grep empty) | $VS/package.json |
| VS Code validates only `engines.vscode` (not `engines.node`) | extensionValidator.ts:260,340; grep for `engines.node` in $VS/src/vs = 0 hits | $VS/src/vs/platform/extensions/common/extensionValidator.ts |

Corpus extension manifests declaring `engines.node` (dev-time constraint, not enforced by VS Code): gitlens `>= 24`, code-spell-checker `>=22.20.0`, vscode-pull-request-github `>=20`, golang.Go `>=16.14.2` (from `ext/*.manifest`). Practical floor for our host: **Node 22 minimum, Node 24 preferred** (matches VS Code server 24.21.0).

### 3.2 Ubuntu archive `nodejs` per series

Launchpad API (https://api.launchpad.net/1.0/ubuntu/+archive/primary?ws.op=getPublishedSources&source_name=nodejs&exact_match=true&status=Published) + packages.ubuntu.com search:

| Series | nodejs version | arm64 | Node EOL (nodejs/Release schedule.json) |
|---|---|---|---|
| jammy 22.04 LTS | 12.22.9~dfsg-1ubuntu3.6 (updates/security) | yes | EOL long past |
| **noble 24.04 LTS (our guest)** | **18.19.1+dfsg-6ubuntu5** (Release only; no newer in updates) | yes | v18 end **2025-04-30** |
| questing 25.10 | 20.19.4+dfsg-1 | yes | v20 end 2026-04-30 |
| resolute 26.04 LTS | 22.22.1+dfsg+~cs22.19.15-1ubuntu1 | yes | v22 end 2027-04-30 |
| stonking (dev series) | 24.19.0+dfsg+~cs24.13.3-1ubuntu1 | yes | v24 end 2028-04-30 |

Guest image: Ubuntu 24.04.3 (noble) arm64 base, services/mobile/app/src/main/java/dev/easyide/app/data/SandboxImages.kt:58-72; the "Ubuntu + Node.js" preset runs `setupFor("nodejs", "npm")` (SandboxImages.kt:37-42), and ADR 0030:25-26 says "Node comes from the environment (`apt-get install nodejs`)". **Finding: on noble that yields Node 18.19.1, which is EOL and below every corpus `engines.node` floor and below VS Code's own 24.x.** ADR 0030 and the preset text ("Node.js and npm from the Ubuntu archive") need revisiting.

noble glibc: `libc6 2.39-0ubuntu8` / `2.39-0ubuntu8.9` (packages.ubuntu.com search libc6 suite=noble).

### 3.3 Official Node binaries (nodejs.org)

From https://nodejs.org/dist/index.json:

| Line | Latest | Date | LTS | npm | linux-arm64 |
|---|---|---|---|---|---|
| v22 | **v22.23.3** | 2026-09-23 | Jod (maintenance since 2025-10-21, EOL 2027-04-30) | 10.9.9 | yes |
| v24 | **v24.21.0** | 2026-09-07 | Krypton (active LTS; maintenance from 2026-10-20, EOL 2028-04-30) | 11.19.0 | yes |
| v26 | v26.10.0 | 2026-09-21 | not yet (LTS 2026-10-28 per schedule) | 11.19.1 | yes |

Tarballs (sizes = HEAD `Content-Length`; sha256 from `SHASUMS256.txt.asc`):

| File | Bytes | sha256 |
|---|---|---|
| node-v22.23.3-linux-arm64.tar.xz | 30,172,012 | a44aeb94849a299b22df10b9e622ec2f605c2183501bc40590705131de7c740f |
| node-v22.23.3-linux-arm64.tar.gz | 56,655,163 | 5ced2d48d1d7198739b7f86804de0171aefb6823b684b12341d3321afc3cb0b2 |
| node-v24.21.0-linux-arm64.tar.xz | 30,843,004 | 6ad1325edbdb5649c379b75a237147a666c95d4f9ae8d340fef2d1575d289ad2 |
| node-v24.21.0-linux-arm64.tar.gz | 57,824,078 | 724282c3b43aec998aa9527380465b45d229e021b58035f5f4f63095eabfe5d5 |

glibc floor (BUILDING.md "Supported platforms", v22.x line 111 and v24.x line 111):
> `| GNU/Linux | arm64 | kernel >= 4.18[^1], glibc >= 2.28 | Tier 1 | e.g. Ubuntu 20.04, Debian 10, RHEL 8 |`
> v24.x:171 "linux-arm64 | RHEL 8 with gcc-toolset-12"; footnote 183: "Binaries produced on these systems are compatible with glibc >= 2.28".
Noble glibc 2.39 >= 2.28: satisfied. Kernel: proot does not virtualize the kernel (ADR 0002 Addendum 2: `uname -a` = real host kernel); footnote [^1]: "Older kernel versions may work. However, official Node.js release binaries are built on RHEL 8 systems with kernel 4.18." Android kernels < 4.18 exist on older devices (minSdk 26) - UNVERIFIED impact.

Integrity (node README.md:96-105, raw.githubusercontent.com/nodejs/node/main/README.md):
> "Download directories contain a `SHASUMS256.txt.asc` file with SHA checksums for the files and the releaser PGP signature."
> "You can get a trusted keyring from nodejs/release-keys ... https://github.com/nodejs/release-keys/raw/HEAD/gpg/pubring.kbx"
Note github.com web is 403 from this sandbox; the keyring fetch was not tested (UNVERIFIED reachable).

### 3.4 NodeSource apt (https://deb.nodesource.com)

Root page redirects to nodesource.com/products/distributions; the apt repo itself answers:
- `https://deb.nodesource.com/node_24.x/dists/nodistro/Release`: "Suite: nodistro", "Architectures: amd64 arm64 armhf x86_64", "Date: Wed, 9 Sep 2026 18:41:35 UTC".
- arm64 Packages: `nodejs 24.21.0-1nodesource1`, Size 38,282,508, Installed-Size 235,621 KB, Depends `libc6 (>= 2.28), libgcc1 (>= 1:3.0), libstdc++6 (>= 7), python3, ca-certificates`; `node_22.x`: `22.23.3-1nodesource1`, Size 36,950,328, Installed-Size 227,693 KB.
- Repo signing key/`InRelease` signature not verified here (UNVERIFIED; verify with `gpgv` against the key NodeSource publishes). Note the `python3` dependency adds ~tens of MB on a bare ubuntu-base (UNVERIFIED size).

### 3.5 Provisioning options (facts only)

| Option | Version | Download | Verification | Update path | Caveats |
|---|---|---|---|---|---|
| A. `apt install nodejs` (noble archive) - current ADR 0030 | 18.19.1 | small (UNVERIFIED) | apt/Ubuntu archive signatures | Ubuntu security updates only within 18.x | EOL; below corpus floors |
| B. Official tarball, pinned in app like the rootfs (SandboxImages pattern: URL + sha256 constant) | 22.23.3 or 24.21.0 | ~30 MB .tar.xz (needs `xz` in guest; ubuntu-base has xz-utils? UNVERIFIED) or ~57 MB .tar.gz | sha256 pinned in APK, sourced from PGP-verified SHASUMS256.txt.asc (same procedure as SandboxImages.kt:60-66) | App release bumps the pin | Independent of Ubuntu series; x86_64 needs its own pin |
| C. NodeSource apt repo | 22.23.3 / 24.21.0 | 37-38 MB .deb | apt repo signature (key UNVERIFIED) | `apt upgrade` | 3rd-party repo trust; python3 dep |
| D. Switch guest to resolute 26.04 | 22.22.1 archive | new rootfs | Ubuntu signatures | Ubuntu | v22 EOL 2027-04-30; image change (ADR 0007) |

Unverified runtime risk for all options: V8 JIT (W^X memory, `mprotect`) under proot/ptrace on Android - ADR 0030 assumes it works; no measurement found in docs (grep). Needs a device test (`node -e 'console.log(process.versions)'` in the guest).

---

## 4. Android WebView and secrets (developer.android.com)

| Topic | Verbatim / fact | URL |
|---|---|---|
| WebViewAssetLoader | "Helper class to load local files including application's static assets and resources using http(s):// URLs inside a android.webkit.WebView class. Loading local files using web-like URLs instead of \"file://\" is desirable as it is compatible with the Same-Origin policy." / "This class is expected to be used within shouldInterceptRequest, which is invoked on a different thread than application's main thread." / "local files should only be hosted on domains your organization owns (at paths reserved for this purpose) or the default domain reserved for this: appassets.androidplatform.net." | https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader |
| shouldInterceptRequest | "Notify the host application of a resource request and allow the application to return the data. If the return value is null, the WebView will continue to load the resource as usual. Otherwise, the return response and data will be used." String overload "was deprecated in API level 21" | https://developer.android.com/reference/android/webkit/WebViewClient |
| addWebMessageListener (androidx.webkit, feature `WEB_MESSAGE_LISTENER`) | "Adds a WebMessageListener to the WebView and injects a JavaScript object into each frame that the WebMessageListener will listen on." Origin rules format `SCHEME "://" [HOSTNAME_PATTERN [":" PORT]]`, custom schemes allowed ("my-app-scheme://"), `*` wildcard; "The HTTPS scheme is strongly recommended for security ... When using a wildcard, the app must treat received messages as untrustworthy". Page side: `myObject.postMessage(message[, MessagePorts])`, message "a JavaScript String or ArrayBuffer" | https://developer.android.com/reference/androidx/webkit/WebViewCompat (webviewcompat.txt:14216-14250) |
| Other WebViewCompat | `createWebMessageChannel`, `postWebMessage`, `addDocumentStartJavaScript` (feature `DOCUMENT_START_SCRIPT`; "The injected object from addWebMessageListener API will be injected first"), `addJavaScriptOnEvent` (1.16.0, execution worlds), `setProfile(webView, profileName)` (feature `MULTI_PROFILE`) | same |
| Service workers | `ServiceWorkerControllerCompat`: "Manages Service Workers used by WebView."; `setServiceWorkerClient(ServiceWorkerClientCompat)` with its own `shouldInterceptRequest(WebResourceRequest)`; `getServiceWorkerWebSettings()` | https://developer.android.com/reference/androidx/webkit/ServiceWorkerControllerCompat |
| Multi-profile | `Profile`: "A Profile represents one browsing session for WebView. You can have multiple profiles and each profile holds its own set of data. The creation and deletion of the Profile is being managed by ProfileStore." Profile exposes `getWebStorage()`, cookie manager, `setCrossOriginIsolatedAllowlist` | https://developer.android.com/reference/androidx/webkit/Profile ; .../ProfileStore |
| setDataDirectorySuffix | "Define the directory used to store WebView data for the current process." / "Each directory can be used by only one process in the application." -> per **process**, not per WebView; use Profile for per-extension isolation within one process | https://developer.android.com/reference/android/webkit/WebView |
| Process model / memory | "On Android 8.0 (API level 26) and higher, WebView separates web content from your app's core functions across multiple processes (on low-RAM devices, it might fall back to a single process)"; renderer is "A separate sandboxed process (SandboxedProcessService)"; "Most WebView memory ... is allocated in native memory, not on the Java heap"; "native memory can silently grow to gigabytes". `onRenderProcessGone` / Renderer Importance API for OOM handling | https://developer.android.com/develop/ui/views/layout/webapps/manage-webview-memory ; .../managing-webview |
| Per-WebView memory cost in MB | **UNVERIFIED** - no primary figure found on either page. Measure on device (`dumpsys meminfo` of the sandboxed renderer with N webviews). |
| Content-Security-Policy in WebView | **UNVERIFIED** from Android docs (no page mentions CSP; grep over all fetched pages = 0). WebView is Chromium-based; verify CSP by device test or Chromium docs. VS Code webviews rely on `<meta http-equiv="Content-Security-Policy">` set by the extension (ADR 0030:70 plans "a strict CSP from the extension's own HTML"). |
| EncryptedSharedPreferences | "Added in 1.0.0 / Deprecated in 1.1.0" / "This class is deprecated. Use android.content.SharedPreferences instead." | https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences |
| security-crypto release notes | 1.1.0 stable July 30, 2025; 1.1.0-beta01 (June 4, 2025) and alpha07 (April 9, 2025) "Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore." | https://developer.android.com/jetpack/androidx/releases/security |
| Android Keystore | "...you can use them for cryptographic operations, with the key material remaining non-exportable." / "preventing the extraction of the key material from application processes and from the Android device as a whole"; StrongBox: "AES 128 and 256" supported; "For most apps, StrongBox is not necessary." | https://developer.android.com/privacy-and-security/keystore |

Implication for `secrets` capability (fact pattern, not design): AES-256-GCM key generated in `AndroidKeyStore` (non-exportable), ciphertext stored in ordinary files/SharedPreferences/DataStore; do not adopt EncryptedSharedPreferences (deprecated). The guest (proot) cannot call Keystore; secrets must be served over the host RPC from the Kotlin side (ADR 0012 already keeps git tokens out of the guest).

---

## 5. Privacy / telemetry

### 5.1 VS Code semantics ($VS @ 0b16cb9)

| Item | Fact | Source |
|---|---|---|
| Setting id | `TELEMETRY_SETTING_ID = 'telemetry.telemetryLevel'`; legacy `telemetry.enableTelemetry`, `telemetry.enableCrashReporter` | src/vs/platform/telemetry/common/telemetry.ts:99-101 |
| Values | `OFF='off'`, `CRASH='crash'`, `ERROR='error'`, `ON='all'`; levels NONE=0..USAGE=3 | telemetry.ts:103-115 |
| Default / scope | `'default': TelemetryConfiguration.ON`, `'scope': ConfigurationScope.APPLICATION`, `restricted: true`, policy `TelemetryLevel`; "off": "Disables all product telemetry." | src/vs/platform/telemetry/common/telemetryService.ts:321-336 |
| Level resolution | legacy `false` -> NONE; `undefined` -> ON; unknown -> NONE | src/vs/platform/telemetry/common/telemetryUtils.ts:138-160 |
| `env.isTelemetryEnabled` | "Indicates whether the users has telemetry enabled." | src/vscode-dts/vscode.d.ts:10807-10811 |
| `env.onDidChangeTelemetryEnabled` | "An Event which fires when the user enabled or disables telemetry." | vscode.d.ts:10813-10817 |
| `env.createTelemetryLogger(sender, options)` | | vscode.d.ts:10826-10832 |
| `TelemetryLogger` | `onDidChangeEnableStates`, `isUsageEnabled`, `isErrorsEnabled` | vscode.d.ts:19483-19498; `TelemetrySender` 19552; `TelemetryLoggerOptions.ignoreBuiltInCommonProperties` 19579-19584 |
| ext host mapping | `isTelemetryEnabled` = `level === USAGE` (so `error`/`crash` => false); logger `isUsageEnabled` needs product config `usage` && level>=USAGE, `isErrorsEnabled` needs `error` && >=ERROR | src/vs/workbench/api/common/extHostTelemetry.ts:58-68; wiring extHost.api.impl.ts:441-445 |

To force "off" in our shim: `env.isTelemetryEnabled === false`, never fire `onDidChangeTelemetryEnabled(true)`, `createTelemetryLogger` returns a logger with both enable flags false (sender never called), **and** `workspace.getConfiguration('telemetry').get('telemetryLevel') === 'off'` (plus `enableTelemetry=false`), because several extensions read the setting directly (below).

### 5.2 How corpus extensions gate telemetry (bundle grep counts in /root/.cache/easyide-corpus/x/*)

| Extension | Manifest setting (default) | Gates found in bundle | Notes |
|---|---|---|---|
| eamodio.gitlens | `gitlens.telemetry.enabled` **true**; description: "For GitLens to send any telemetry BOTH this setting and VS Code telemetry must be enabled." | `isTelemetryEnabled` x2, `onDidChangeTelemetryEnabled` x2; OpenTelemetry OTLP exporter deps; `sentry` x6 | also `when` clause reads `config.telemetry.telemetryLevel != off` (manifest line 18201) |
| Anthropic.claude-code | none in `contributes.configuration` | `ra()` = `env.isTelemetryEnabled && !CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC && !DISABLE_TELEMETRY && !DO_NOT_TRACK`; CLI side honours `DISABLE_TELEMETRY`, `DISABLE_ERROR_REPORTING`, `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC` | bundled native CLI separately reads env - pass these env vars to the host/CLI process to force off |
| ms-python.python | none | `@vscode/extension-telemetry` dep; `createTelemetryLogger` x4, `isTelemetryEnabled` x2, `telemetryLevel` x25, applicationinsights x131 | reads setting directly |
| redhat.java | `redhat.telemetry.enabled` default **null** ("Enable usage data and errors to be sent to Red Hat servers") | `@redhat-developer/vscode-redhat-telemetry`; `isTelemetryEnabled` x4, `telemetryLevel` x6, `segment.io` x6 | null = ask user (opt-in prompt; behaviour UNVERIFIED in code) |
| redhat.vscode-yaml | same as redhat.java | `isTelemetryEnabled` x8, `telemetryLevel` x12, `segment.io` x8 | |
| GitHub.vscode-pull-request-github | none | `@vscode/extension-telemetry` dep (manifest) | bundle not grepped (UNVERIFIED) |
| others in table 2.4 | no telemetry setting or telemetry deps in manifest | not grepped (UNVERIFIED) | |

"Sends by default" per manifest defaults: GitLens (true + VS Code on); ms-python and GitHub PR (via `@vscode/extension-telemetry`, which follows VS Code level - UNVERIFIED in their code); claude-code (follows `isTelemetryEnabled` + env); Red Hat (null -> opt-in). With our host reporting "off", all five checked extensions have a code path that reads it.

---

## 6. Questions for legal review (no conclusions given)

1. Does executing, inside a proot guest, native aarch64 code the **user** downloads after install (apt packages from ports.ubuntu.com, Node.js, extension-bundled binaries such as claude-code's 238 MB `claude`, ruff, pyrefly, rust-analyzer, redhat.java's platform build) constitute the app "download[ing] executable code (such as dex, JAR, .so files) from a source other than Google Play" under the Device and Network Abuse policy?
2. Does the carve-out "code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs" cover (a) Node.js/JS extensions running in Node in the guest, (b) glibc binaries run via proot's ptrace-mediated loader, (c) neither? Does it matter that none of these have Android API access at all?
3. Is it relevant that the only binaries `execve`d by the Android process are APK-embedded (`libproot*.so` in jniLibs), i.e. compliance with Android 10 W^X, given the policy text is mechanism-neutral?
4. Termux's Play build (termux-play-store, targetSdk 37, proot loader in jniLibs, 10M+ installs, updated 2026-06-21) is listed on Play. Can any reliance be placed on that, and should we seek a pre-launch policy consultation with Google Play?
5. Are user-installed Open VSX extensions "third party code ... in your app" for the User Data policy (Prominent Disclosure, Data safety form), given some send telemetry by default? Is forcing `telemetryLevel=off` sufficient, and must the Data safety section reflect extension traffic?
6. Could the extension browser be characterised as a "hostile downloader" risk (5% MUwS threshold), and what vetting (publisher pinning, sha256, allowlists per ADR 0009/0016) is advisable?
7. Microsoft-published extensions (ms-python.python, ms-azuretools.vscode-docker, GitHub.vscode-pull-request-github, dbaeumer.vscode-eslint) are MIT on Open VSX, but the VS Marketplace ToU restricts Marketplace Offerings to "In-Scope Products". Does any such restriction attach when obtained from Open VSX, and does ms-python's extensionPack pulling proprietary Pylance change that?
8. For extensions whose Open VSX `license` field is null (Vue.volar, mhutchie.git-graph) does the Publisher Agreement's MIT default or the packaged LICENSE govern? (git-graph's LICENSE forbids distributing derivative works.)
9. If we vendor MIT files from microsoft/vscode (types, webview `pre/` scripts, codicon CSS) into PolyForm-NC or Apache-2.0 paths, how should mixed-licence files be labelled in NOTICE.md and file headers; is CC-BY-4.0 (codicon font) attribution satisfied by NOTICE.md?
10. Can we pre-bundle or mirror MIT/Apache extensions (e.g. prettier, eslint, yaml, errorlens) in the APK or our registry (ADR 0016) with notices, and what about EPL-2.0 (redhat.java) and GPL-3.0 (code-spell-checker) - source-offer obligations under our commercial licence?
11. GitLens: its vsix contains `plus/` code under LICENSE.plus ("forbidden to copy, merge, publish, distribute"). Is merely facilitating user download and running it in our host acceptable; must Pro features be left untouched (no patching of plus code)?
12. Claude Code: does launching the user-installed, unmodified extension/CLI inside our app count as "preinstalling or running Claude Code in your products or services" requiring Anthropic Commercial Terms? Constraints: no modification, no removal of auth methods, no intermediated billing, naming rules ("runs Claude Code" allowed, no name/logo in our product name).
13. Providing Node.js via pinned official tarball vs NodeSource vs Ubuntu: any distribution obligations if the app itself downloads (rather than the user via apt) - is that "bundled", and does NOTICE.md need Node's full third-party licence text?
14. The Open VSX ToU (2021-02-11) says Eclipse grants no IP licence and content may be removed without notice. Does caching `.vsix` files on device or in our static index create any redistribution exposure?
15. Chroot opt-in backend requires root: any Play Malware "Rooting" or "Elevated privilege" exposure from documenting/supporting rooted devices, even though the app does not root the device?
