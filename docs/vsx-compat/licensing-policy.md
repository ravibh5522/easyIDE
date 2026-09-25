# VS Code extensions: licensing, Google Play policy, Open VSX terms, privacy

Status: AUDIT (2026-09-25). Documentation only. **Nothing in this file is legal advice or a compliance
conclusion.** Section 4 is the input to ADR 0034 (Play policy stance), which the lead ratifies after legal review.
Companion: [security-licensing.md](security-licensing.md) (sections 1, 2, 7 work packages, 8 UNVERIFIED).
This file holds sections 3-6 with the same global numbering.

Evidence: [research/policy-licence.md](research/policy-licence.md) (primary; all Play/licence quotes, retrieved
2026-09-25), [research/openvsx-registry.md](research/openvsx-registry.md) §(d), (j), [data/corpus.json](data/corpus.json).
Related: [README.md](README.md), [registry-install.md](registry-install.md), [backend.md](backend.md),
[ui-webviews.md](ui-webviews.md), [../extension-sdk/threat-model.md](../extension-sdk/threat-model.md),
ADRs [0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md),
[0008](../decision/0008-noncommercial-source-available-licensing.md),
[0012](../decision/0012-git-token-in-process-env-not-credential-socket.md),
[0015](../decision/0015-extension-sdk-licensing-apache.md),
[0029](../decision/0029-material-icon-theme-default-and-icon-customisation.md),
[0030](../decision/0030-vscode-extension-host-in-sandbox.md).

## 3. Licensing

### 3.1 Per-extension licence capture and display

Sources of licence information for one Open VSX version ([research/openvsx-registry.md](research/openvsx-registry.md) §(j)):

| Field | Meaning | Evidence |
|---|---|---|
| API `license` | raw `package.json` `license` string, not normalised (may be an SPDX id, `SEE LICENSE IN <file>`, a `%nls%` key, free text, or null) | `ExtensionProcessor.java:327` |
| API `files.license` | the licence **file**: vsixmanifest `<License>` path, else asset `Microsoft.VisualStudio.Services.Content.License` | `ExtensionProcessor.java:566-571` |
| `extension/package.json` `license` | same string as API, may contain `%key%` resolved via `package.nls.json` (e.g. GitLens `%gitlens.license%`) | [research/policy-licence.md](research/policy-licence.md) §2.4 |
| Publisher Agreement default | "If you fail to specify a license in the Listing Information for an Offering, the Offering and Offering Contents will be made available under the MIT license" (`publisher-agreement-v1.1.md:53`) | whether this or a packaged LICENSE governs is legal question L-8 |

Corpus reality (156 extensions, `data/corpus.json` `license`): `MIT` 74, **null 24**, `SEE LICENSE IN ...` 29,
`Apache-2.0` 7, `EPL-2.0` 3, `BSD-3-Clause` 2, `LicenseRef-LICENSE` 4, one each of `MIT OR Apache-2.0`, `MPL-2.0`, `NCSA`,
`GPL-3.0-or-later`, `EPL-1.0`, the Claude Code proprietary string, `%gitlens.license%`, bare file names (`LICENSE`, `LICENSE.md`) and a long tail. So the API field alone is
unusable for ~40 % of the corpus.

Display and capture rules (Proposal, owner [registry-install.md](registry-install.md), UI in [ui-webviews.md](ui-webviews.md)):

| Case | Chip | Action before install |
|---|---|---|
| valid SPDX expression, OSI-approved ids only | the expression (e.g. `MIT`, `MIT OR Apache-2.0`) | link to `files.license` |
| `SEE LICENSE IN x`, `LicenseRef-*`, `%key%` resolving to either, free text, non-OSI SPDX | "See licence" (warning tone) | licence text shown in the install sheet; install button below it |
| null string and no `files.license` | "No licence declared (Open VSX publisher terms: MIT)" citing Agreement :53 | shown, flagged for L-8 |
| null string but `files.license` present (Vue.volar, mhutchie.git-graph) | "See licence" | text shown; never inferred MIT from the agreement |
| known extra terms (table 3.2 "Extra terms" column, data file) | "Additional terms" | link to the vendor terms page |

Always keep the licence file(s) of the installed version on disk with the extension (as ADR 0029 does for Material
Icon Theme), record `{id, version, licenseString, licenseFileSha256}` in install state, and show them on the extension
page. The known-extra-terms list is a declarative data file (R-ENG-07), not code.

### 3.2 Must-work extensions: licence and redistribution flags

"Redistributable by us" is the reading in [research/policy-licence.md](research/policy-licence.md) §2.4 ("only 'Yes
(MIT terms)' when the text clearly permits distribution with notice"), **not legal advice**, and even "Yes" does not
authorise bundling (rule 3.3). Native = files detected as ELF executables or `.node` addons in the linux-arm64/universal
build (`data/corpus.json` `nativeBinaries`).

| id | ver | Licence (API / file) | Redistributable by us? | Flag | Native | Extra terms / notes |
|---|---|---|---|---|---|---|
| meta.pyrefly | 1.3.9001 | MIT | Yes (MIT terms) | user-downloads | 1 ELF (`bin/pyrefly`) | pre-release is `latest` |
| ms-python.python | 2026.4.0 | MIT; file notes Pylance is proprietary | Yes for this vsix | user-downloads | 0 | `extensionPack`: pylance (proprietary, Open VSX presence UNVERIFIED), debugpy, python-envs (licence null) |
| Anthropic.claude-code | 2.1.282 | "© Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements ..." | **No** | **user-downloads only** | 2 (238,084,088-byte `claude` ELF) | legal page: "preinstalling or running Claude Code in your products or services ... requires agreeing to our Commercial Terms of Service"; "The Claude Code binary must not be modified"; NOTICE.md:87 "not redistributable" (L-12) |
| Shopify.ruby-lsp | 0.10.6 | MIT | Yes (MIT terms) | user-downloads | 0 | |
| redhat.java | 1.57.2026092508 | EPL-2.0 | Only with EPL-2.0 obligations (counsel) | **user-downloads only** | 66 files | embedded JRE UNVERIFIED; Red Hat telemetry prompt |
| golang.Go | 0.56.1 | MIT (+860 lines attributions) | Yes (MIT + attribution file) | user-downloads | 0 | |
| llvm-vs-code-extensions.vscode-clangd | 0.6.0 | MIT | Yes (MIT terms) | user-downloads | 0 | clangd fetched separately (Apache-2.0 WITH LLVM-exception UNVERIFIED) |
| eamodio.gitlens | 2026.9.250515 | `%gitlens.license%` -> "SEE LICENSE IN LICENSE"; MIT except `plus/` under LICENSE.plus | **No** (bundle contains plus code) | **user-downloads only** | 0 | LICENSE.plus: "it is forbidden to copy, merge, publish, distribute, sublicense, and/or sell the Software"; never patch plus code (L-11) |
| Vue.volar | 3.3.11 | API null; file MIT | Yes (MIT per file) | user-downloads | 0 | L-8 |
| esbenp.prettier-vscode | 12.4.0 | MIT | Yes (MIT terms) | user-downloads | 0 | bundled prettier notices UNVERIFIED |
| redhat.vscode-yaml | 1.25.2026092308 | MIT | Yes (MIT terms) | user-downloads | 0 | Red Hat telemetry |
| rust-lang.rust-analyzer | 0.4.3061 | MIT OR Apache-2.0 | Yes | user-downloads | 1 ELF (server) | |
| ms-azuretools.vscode-docker | 2.0.0 | "SEE LICENSE IN LICENSE.md" -> MIT | Yes (MIT terms) | user-downloads | 0 | depends on vscode-containers (licence UNVERIFIED) |
| GitHub.vscode-pull-request-github | 0.166.1 | MIT | Yes (MIT terms) | user-downloads | 0 | needs `vscode.github-authentication` (our auth provider) |
| PKief.material-icon-theme | 5.38.1 | MIT | Yes | **already bundled** (ADR 0029, NOTICE.md) | 0 | declarative only |
| dbaeumer.vscode-eslint | 3.0.34 | MIT | Yes (MIT terms) | user-downloads | 0 | |
| Dart-Code.dart-code | 3.143.20260901 | "SEE LICENSE IN LICENSE" -> MIT | Yes (MIT terms) | user-downloads | 0 | |
| charliermarsh.ruff | 2026.84.0 | MIT | Yes (MIT terms) | user-downloads | 1 ELF (`ruff`) | binary licence UNVERIFIED |
| bradlc.vscode-tailwindcss | 0.16.0 | MIT | Yes (MIT terms) | user-downloads | 0 | |
| streetsidesoftware.code-spell-checker | 4.9.3 | GPL-3.0-or-later | Only with GPL-3.0 obligations | **user-downloads only** | 0 | copyleft |
| mhutchie.git-graph | 1.30.0 | API null; file: "Permission is NOT GRANTED to publish, distribute, sublicense, and/or sell derivative works" | **No** | **user-downloads only** | 0 | not OSI; last publish 2021-04-17 |
| usernamehw.errorlens | 3.28.0 | MIT | Yes (MIT terms) | user-downloads | 0 | |

Summary: 22 must-work ids; 5 flagged **user-downloads only** by licence (claude-code, gitlens, git-graph,
code-spell-checker, redhat.java), 1 already bundled; the remaining 16 are permissive but still user-downloads under rule 3.3.
5 ship native code.

### 3.3 Redistribution rule

1. **Default: user-downloads only.** easyIDE fetches the `.vsix` from Open VSX at the user's request onto the user's
   device. We do not bundle third-party extensions in the APK, in our static registry (ADR 0016), or in any mirror/CDN we
   operate.
2. **Exception** requires both: (a) the licence clearly permits redistribution (permissive, notices satisfiable) and
   (b) the owner approves in writing (ADR or NOTICE.md entry, following the NOTICE.md rule "Every future addition to this
   list must have its license and maintenance status verified from primary sources before adoption", NOTICE.md:13-35).
   Precedent: Material Icon Theme (ADR 0029).
3. Copyleft (GPL-3.0, EPL-2.0) or proprietary/restricted (claude-code, gitlens plus, git-graph, Pylance): never bundled or
   mirrored; question L-10 asks counsel about source-offer duties under our commercial licence.
4. On-device cache of downloaded `.vsix` files for offline reinstall is allowed by rule 1 (no ToU clause forbids it,
   [research/openvsx-registry.md](research/openvsx-registry.md) §(d)); cache is per device, never uploaded or shared
   (L-14 asks counsel to confirm).
5. Microsoft-published extensions from Open VSX are handled like any other MIT extension; the VS Marketplace ToU
   restriction to "In-Scope Products" is L-7. We never contact marketplace.visualstudio.com (ADR 0030 Context).

### 3.4 Vendoring VS Code MIT code (extHost pieces, `vscode.d.ts`, webview `pre/` scripts)

ADR 0030 decision 2: "We write the `vscode` module ourselves ... not port VS Code's `extHost*` sources"; "Nothing is
bundled but our own JS." If any MIT file is nevertheless copied (types, `extHostTypes.ts` classes, webview
`pre/index.html` / `service-worker.js`, codicon CSS), the obligations are
([research/policy-licence.md](research/policy-licence.md) §2.5):

| # | Obligation / interaction | Source |
|---|---|---|
| V-1 | MIT: "The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software." Keep the Microsoft header per file; add a NOTICE.md row (component, path, MIT, source commit `0b16cb9`, date), same pattern as the bundled `language-configuration.json` row | `$VS/LICENSE.txt:1-5`; NOTICE.md table |
| V-2 | Target path decides the surrounding licence: the exthost JS (`services/mobile/exthost/js`) executes extensions, so under ADR 0015's amendment it is **app code (PolyForm NC, ADR 0008)**; MIT files inside it stay MIT for downstream recipients (PolyForm cannot relicense them) - labelling of mixed files is L-9 | ADR 0015 amendment ("What executes extensions stays under 0008"); ADR 0008 |
| V-3 | If vendored into an Apache-2.0 SDK path (e.g. types for a published `@easyide/*` typing package), keep the MIT notice; Apache code must not import app code | ADR 0015 "Apache code may not import app code" |
| V-4 | The VS Code **product** licence (not MIT) does not apply to source; never copy from a VS Code build/installer (product licence forbids "remove, minimize, block or modify any notices") | code.visualstudio.com/license |
| V-5 | Theia `plugin-ext` is EPL-2.0 OR GPL-2.0 with CP exception: rejected (ADR 0030 Alternatives) | theia NOTICE.md:17-28 |
| V-6 | `@vscode/vsce-sign` is proprietary ("only with Microsoft Visual Studio, ... Visual Studio Code ..."): never vendored or called | npm `@vscode/vsce-sign` 2.1.0 LICENSE.txt |
| V-7 | Distribution of the PolyForm NC app must carry the licence text and `Required Notice:` lines (LICENSE:35-42); third-party notices ride along in NOTICE.md / in-app licences screen | LICENSE; NOTICE.md |

### 3.5 Codicons (CC-BY-4.0)

`@vscode/codicons`: npm `license: CC-BY-4.0`; repo `LICENSE` = Creative Commons Attribution 4.0 (icons/font),
`LICENSE-CODE` = MIT ([research/policy-licence.md](research/policy-licence.md) §2.3). Webviews expect `codicon.css` + font
(ourcode-ui: attribution line planned in `assets/licenses`, [research/ourcode-ui.md](research/ourcode-ui.md)).
Rule: if the font is bundled, add a NOTICE.md "Bundled in the APK" row with CC-BY-4.0, copyright holder, source URL, version,
and "modified: yes/no"; show the same in the in-app licences screen. Whether NOTICE.md alone satisfies CC-BY 4.0
attribution for glyphs rendered inside extension webviews is L-9.

### 3.6 Node.js licence and provisioning

Node.js: MIT header "Node.js is licensed for use as follows:" plus ~2,860 lines of bundled third-party licences (V8,
OpenSSL, ICU, libuv, ...), https://raw.githubusercontent.com/nodejs/node/main/LICENSE. Noble's archive `nodejs` is
18.19.1 (EOL 2025-04-30), below corpus `engines.node` floors ([research/policy-licence.md](research/policy-licence.md) §3.2).

| Option | Who downloads | Licence handling | Play angle (sec 4) |
|---|---|---|---|
| A. `apt install nodejs` in guest (ADR 0030 today) | a sandbox step started by the user enabling the first `host.run` extension; Ubuntu archive | Debian/Ubuntu copyright files inside the guest; NOTICE.md "Downloaded at runtime, not bundled" row | same class as any apt package |
| B. Pinned official tarball (URL + sha256 in app, like `SandboxImages.kt:58-80`; v22.23.3 / v24.21.0 linux-arm64) | the app, on user action | NOTICE.md "Downloaded at runtime" row + link to Node's LICENSE; if the app itself fetches it, whether that is "distribution" is L-13 | app-initiated native code download (Q in L-1) |
| C. NodeSource apt repo | sandbox step, third-party repo | as A, plus repo trust | as A |
| D. Bundle Node in the APK | us | full Node LICENSE (2,864 lines) must ship; APK +30-57 MB | code is inside the APK (no download), but executing it needs the jniLibs path (ADR 0002 addendum) |

Recommendation (engineering, not legal): B or C for version reasons; do **not** bundle (D) unless ADR 0034 needs it.
Record the choice in NOTICE.md "Downloaded at runtime, not bundled".

## 4. Google Play policy analysis (input to ADR 0034)

No outcome is assumed. Quotes are verbatim from [research/policy-licence.md](research/policy-licence.md) §1 (retrieved
2026-09-25).

### 4.1 Clauses

| Policy | Verbatim | URL |
|---|---|---|
| Device and Network Abuse (exec code) | "An app distributed via Google Play may not modify, replace, or update itself using any method other than Google Play's update mechanism. Likewise, an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play. This restriction does not apply to code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs (such as JavaScript in a webview or browser)." | support.google.com/googleplay/android-developer/answer/16559646 (redirect from 9888379) |
| DNA (interpreted code) | "Apps or third-party code, like SDKs, with interpreted languages (JavaScript, Python, Lua, etc.) loaded at run time (for example, not packaged with the app) must not allow potential violations of Google Play policies." | same |
| DNA example | "Apps or third party code (for example, SDKs) that download executable code, such as dex files or native code, from a source other than Google Play." | same |
| DNA example (webview) | "Apps or third party code (for example, SDKs) containing a webview with added JavaScript Interface that loads untrusted web content (for example, http:// URL) or unverified URLs obtained from untrusted sources (for example, URLs obtained with untrusted Intents)." | same |
| DNA key consideration | "Don't use third-party SDKs in your app that download executable code (like dex or .so files) from outside Google Play (except in VMs/interpreters)." | same |
| Malware: backdoors | "you must remove any code that acts as a backdoor, which is defined as code facilitating unwanted or harmful remote-controlled operations." | support.google.com/googleplay/android-developer/answer/9888380 |
| Malware: hostile downloaders | "... This policy does not apply to major browsers or file-sharing apps, as long as they only download software with the user's explicit consent and initiation." Threshold: "At least 5% of apps downloaded by it are PHAs with a minimum threshold of 500 observed app downloads (25 observed PHA downloads)." | same |
| Malware: privilege | "your app must not contain code that gains elevated privileges or breaks the Android security sandbox." | same |
| User Data: third-party code | "If you include third party code (for example, an SDK) in your app, you must ensure that the third party code used in your app, and that third party's practices with respect to user data from your app, are compliant with Google Play Developer Program policies..." | support.google.com/googleplay/android-developer/answer/10144311 |
| User Data: prominent disclosure | required "In cases where your app's access, collection, use, or sharing of personal and sensitive user data may not be within the reasonable expectation of the user"; "Must be within the app itself", "Must be displayed in the normal usage of the app", consent "Must require affirmative user action" | same |
| Android 10 W^X (platform, not policy) | "Untrusted apps that target Android 10 cannot invoke execve() directly on files within the app's home directory." | developer.android.com/about/versions/10/behavior-changes-10 |

No clause names IDEs, terminals, shells, package managers or Linux userlands (grep of the DNA page).

### 4.2 What the current design already does (before VS Code extensions)

| Fact | Source |
|---|---|
| Ships executables in the APK as `libproot.so`, `libprootloader.so`, `libprootloader32.so`; `useLegacyPackaging = true` "proot is exec'd, not dlopen'd"; `extractNativeLibs="true"` | `services/mobile/app/build.gradle.kts:117-123`; `AndroidManifest.xml:17`; `ProotInstaller.kt:97-99` |
| Downloads an Ubuntu 24.04 arm64 rootfs (sha256-pinned) at runtime | `APP/data/SandboxImages.kt:58-80` |
| Users `apt-get install` arbitrary native packages; they run "by proot's own loader rather than by execve from the Android process"; exec from `app_data_file` is denied by SELinux, from the native-lib dir allowed | ADR 0002:35-64 |
| Language packs install servers via apt/npm/pip (e.g. `easyide.python` capabilities `sandbox.install`, `network(npm, pypi)`) | [research/ourcode-backend.md](research/ourcode-backend.md) §6 |
| Observation only: Termux's Play build (targetSdk 37, proot loader in jniLibs, "10M+ Downloads", updated Jun 21, 2026) is listed on Play; whether it allows arbitrary apt installs is UNVERIFIED | [research/policy-licence.md](research/policy-licence.md) §1.6 |

So downloaded native code executed through proot is **already** part of the product. ADR 0034 must decide on that
baseline and on the delta below together.

### 4.3 Delta added by VS Code extensions

| # | Delta | Character (fact, not classification) |
|---|---|---|
| D-1 | Node.js runtime in the guest | native aarch64 ELF (a JS VM) downloaded after install (sec 3.6); runs via proot loader |
| D-2 | Extension JS | interpreted by Node, loaded at run time from Open VSX; Node gives it **no** Android API access (whether the carve-out "where either provides indirect access to Android APIs" helps or hurts is L-2) |
| D-3 | `.node` native addons inside `.vsix` | shared objects `dlopen`ed by Node inside the guest; 72 files in the corpus |
| D-4 | ELF executables inside `.vsix` | 52 files across the corpus (e.g. `claude` 238 MB, `rust-analyzer`, `pyrefly`, `ruff`); 32/156 extensions, ~33 % of download weight ship D-3 or D-4 (`data/corpus.json`) |
| D-5 | Binaries downloaded by extensions at run time (language servers, JREs) | not mediated by our installer; which extensions do it is UNVERIFIED |
| D-6 | Extension webviews with a bridge | we plan `addWebMessageListener` with exact origin, not `addJavascriptInterface` ([security-licensing.md](security-licensing.md) M-VSX-10); the DNA webview example names "added JavaScript Interface that loads untrusted web content" - L-16 |
| D-7 | Extension telemetry / network calls | third-party code the user installs; relation to "third party code ... in your app" is L-5 |
| D-8 | A browsable catalogue of downloadable code | relation to "hostile downloaders" (5 % PHA threshold, "explicit consent and initiation") is L-6 |

### 4.4 Options for distribution channels

| Option | What ships | Code extensions | Cost | Notes |
|---|---|---|---|---|
| O-A Play build without code extensions | Play flavour with `extensions.code.enabled=false` at build time | declarative parts of `.vsix` only (themes, grammars, snippets, icon themes, language config); `.vsix` with `main` refused or installed "partial" (existing ST-10 behaviour) | low | does not change the proot/apt baseline (4.2) |
| O-B Play build, code extensions behind an in-app opt-in | same APK, runtime flag off by default, user enables in Settings with disclosure | full, after opt-in | low | whether an opt-in changes the policy analysis is L-3/L-6 |
| O-C Sideload-only feature | Play flavour = O-A; a separate direct-download APK (website / GitHub Releases) with the flag on | full | medium (second signing/update channel; self-update forbidden in the Play build by the DNA "modify, replace, or update itself" clause) | same package name vs separate id is an owner choice |
| O-D F-Droid / third-party store build | flag on | full | medium | F-Droid main-repo inclusion normally requires a FLOSS licence; ours is PolyForm NC (ADR 0008 "not open source") - UNVERIFIED, check F-Droid Inclusion Policy; a self-hosted F-Droid repo is an alternative |
| O-E Everything on, all channels | flag on everywhere | full | lowest | carries the full question list 4.6 on Play |

### 4.5 Recommended engineering posture (no compliance claim)

1. **Feature-flag everything that executes third-party code**, per distribution flavour and overridable at runtime only
   *downwards* on Play: `extensions.code.enabled` (host + `host.run`), `extensions.code.nativeAllowed` (install `.vsix`
   with D-3/D-4 files), `sandbox.apt.enabled` (already-existing baseline, so ADR 0034 can decide it in the same place).
   Flags are build-config data (R-ENG-07), not scattered `if (BuildConfig...)` checks. (WP-SEC-15)
2. With a flag off, the UI says "Not available in this version of easyIDE" and links to the channel docs; it never
   says "for policy reasons" or claims compliance.
3. Keep every download **user-initiated** with a disclosure sheet (security-licensing.md SC-13); no background install,
   no auto-update, no silent dependency installs.
4. Keep the Play build free of self-update code paths (DNA clause 1) regardless of option.
5. Keep webview bridges on `addWebMessageListener` with exact origins; never `addJavascriptInterface` for extension
   content (D-6).
6. Keep the declarative path (Tier 1, O-A) fully functional so a Play build is still useful.
7. Do not publish store copy about code extensions until ADR 0034 records counsel's answer.

### 4.6 Flag for legal review - no conclusion drawn

L-1..L-15 are from [research/policy-licence.md](research/policy-licence.md) §6 (condensed); L-16..L-20 are added here.

| # | Question |
|---|---|
| L-1 | Does executing, inside a proot guest, native aarch64 code the user downloads after install (apt packages, Node.js, extension-bundled binaries such as `claude`, ruff, pyrefly, rust-analyzer, redhat.java platform build) constitute the app "download[ing] executable code (such as dex, JAR, .so files) from a source other than Google Play"? |
| L-2 | Does the carve-out "code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs" cover (a) JS extensions in Node in the guest, (b) glibc binaries via proot's loader, (c) neither? Does it matter that none have Android API access? |
| L-3 | Is it relevant that only APK-embedded binaries (`libproot*.so`) are `execve`d by the Android process (Android 10 W^X), given the policy text is mechanism-neutral? |
| L-4 | Can any reliance be placed on Termux's Play listing, and should we seek a pre-launch policy consultation with Google Play? |
| L-5 | Are user-installed Open VSX extensions "third party code ... in your app" for User Data (Prominent Disclosure, Data safety form)? Is forcing `telemetryLevel=off` sufficient; must the Data safety section reflect extension traffic? |
| L-6 | Could the extension browser be a "hostile downloader" risk (5 % PHA threshold), and what vetting (sec 2 controls) is advisable? |
| L-7 | Does the VS Marketplace ToU "In-Scope Products" restriction attach to Microsoft-published extensions obtained from Open VSX (MIT there)? Does ms-python's pack pulling proprietary Pylance change that? |
| L-8 | For a null API `license` (Vue.volar, git-graph), does the Publisher Agreement MIT default or the packaged LICENSE govern? |
| L-9 | Labelling of vendored MIT files inside PolyForm-NC / Apache-2.0 paths; is NOTICE.md sufficient CC-BY-4.0 attribution for codicons shown in webviews? |
| L-10 | May we pre-bundle or mirror MIT/Apache extensions with notices? What of EPL-2.0 (redhat.java) and GPL-3.0 (code-spell-checker) source-offer duties alongside our commercial licence? |
| L-11 | GitLens `plus/` code under LICENSE.plus: is facilitating user download and running it in our host acceptable (no patching)? |
| L-12 | Does launching the user-installed, unmodified Claude Code extension/CLI count as "preinstalling or running Claude Code in your products or services" requiring Anthropic Commercial Terms? Naming constraints? |
| L-13 | If the app (not the user via apt) downloads Node.js, is that distribution; must Node's full third-party licence text ship? |
| L-14 | Does on-device caching of `.vsix` files, or listing them in our static index (ADR 0016), create redistribution exposure given the Open VSX ToU grants no IP licence? |
| L-15 | Does supporting the rooted chroot backend create Malware "Rooting"/"Elevated privilege" exposure though the app does not root the device? |
| L-16 | Does an extension webview (untrusted HTML from a third-party extension, bridge via `addWebMessageListener` rather than `addJavascriptInterface`) fall under the DNA example "a webview with added JavaScript Interface that loads untrusted web content"? |
| L-17 | Is the delta in 4.3 material relative to the existing apt/proot baseline (4.2), i.e. could a Play build with O-A differ in outcome from one with O-E? |
| L-18 | Does a runtime opt-in (O-B) versus a build-time removal (O-A) change the analysis? |
| L-19 | Obligations if the same app is distributed on Play and via direct APK with different feature sets (O-C): listing text, Data safety, store description consistency? |
| L-20 | Does the Open VSX download ToU line ("By clicking download, you accept this website's Terms of Use.") need to be presented to users of a third-party client, and in what form? |

Count: **20 questions**.

## 5. Open VSX terms of use, rate limits, mirror/caching

### 5.1 Terms of Use (open-vsx.org `/documents/terms-of-use.md`, dated February 11, 2021)

| Line | Verbatim |
|---|---|
| :5 | "By accessing, browsing, or using this Website including downloading Content from this Website, you acknowledge that you have read, understand, and agree to be bound by these terms." |
| :9 | "This Website makes available Content from entities other than the Eclipse Foundation (\"Third Party Publishers\"), under terms and licenses (including proprietary licenses) provided by such Third Party Publishers. It is your responsibility when accessing and using any Content to comply with terms and licenses associated with that Content." |
| :11 | "The Eclipse Foundation may remove, update, or modify Content from this Website at any time without notice." |
| :13 | "By making the Content available for access by you on this Website, neither the Eclipse Foundation nor the Members grant any licenses to any copyrights, patents or any other intellectual property rights in the Content" |
| :15 | logos/trademarks: "no licenses or other rights in or to such logos and/or trademarks are granted to you." |
| :28 | "MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SECURITY" |
| :36 | "the right to block access from a particular Internet address to this Website." |

No clause on automated access, API use, caching or mirroring (whole 36-line text searched). The API's
`info.termsOfService` points at https://www.eclipse.org/legal/termsofuse.php (fetched, not analysed - UNVERIFIED).
FAQ (eclipse.org/legal/open-vsx-registry-faq): "If you are consuming extensions in applications that use the Open VSX
Registry (e.g. in Gitpod, VSCodium or Eclipse Theia IDE) there will be no usage impact."; "All extensions in the Open VSX
Registry are made available without fee, subject to the terms of the respective licenses."
Publisher Agreement v1.1 :45 grants Eclipse the right to "make available to end-users (including through multiple tiers
of distribution)" - a grant to Eclipse, not to us.

Consequences we adopt: show the licence before install (3.1); show the ToU line in the install sheet (SC-13);
do not use Open VSX or publisher logos/trademarks in our branding (:15); treat content removal (:11) as a revocation
signal, not a guarantee.

### 5.2 Rate limits

| Item | Evidence |
|---|---|
| Standard tier | "**Standard Limit**: < 3 requests per second (RPS) / <**10,800 Requests Per Hour**"; "This limit applies to standard community requests, including extension searches, metadata retrievals, and downloads via IDEs or CLI tools." (open-vsx.org wiki `Rate-limiting.md:9-11`) |
| On limit | "When the limit for your current tier is reached, the API returns an HTTP 429 Too Many Requests response." (:12); headers `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` (`RateLimitServletFilter.java:37-39,112,119`); live `x-ratelimit-limit: 10800` |
| Keyed by IP | "multiple users may share a single egress IP address. Collective traffic from one network may trigger the community limit." (:57) |
| Higher tiers | token-based (`ovsx_rl_` prefix), header name UNVERIFIED |

Client rules (details in [registry-install.md](registry-install.md)): serial requests, <= 1 RPS steady, honour
`Retry-After` without retry storms, time-based metadata cache, one `v2/-/query` per installed id per day (or one
`version-changes` call), CDN host `openvsx.eclipsecontent.org` allowed. CI corpus crawls stay under 3 RPS / 10,800 per hour
per IP or request OSS elevated access (`Rate-limiting.md:10`).

### 5.3 Mirror / caching stance

| Activity | Stance |
|---|---|
| On-device cache of metadata and `.vsix` by sha256 | allowed (rule 3.3.4); bounded size; cleared with the extension or from Settings |
| Our own proxy/mirror of Open VSX packages | **not operated.** Would be redistribution under each licence (ToU :13 grants nothing); needs per-licence review + owner approval (rule 3.3.2) and L-14 |
| Metadata-only mirror (Open VSX mirror mode: "The packages themselves are **not** copied", `doc/mirror.md:3-9`) | not needed now; revisit only if rate limits bite, after L-14 |
| Listing Open VSX extensions inside our static registry (ADR 0016) | not done; the static index stays for easyIDE-native packages |
| Telemetry to Open VSX | none beyond HTTP requests themselves; no account, no user id |

## 6. Privacy

### 6.1 easyIDE itself

No telemetry, analytics or crash upload from easyIDE (threat-model.md B3 "I" row and R12: "no accounts, no telemetry").
Network endpoints the app itself contacts for extensions: `open-vsx.org`, `openvsx.eclipsecontent.org`,
`raw.githubusercontent.com` (kill list), plus the rootfs/Node download hosts (sec 3.6).

### 6.2 Forcing extension telemetry off

VS Code semantics ([research/policy-licence.md](research/policy-licence.md) §5.1): setting `telemetry.telemetryLevel`
(values `off|crash|error|all`, default `all`, scope APPLICATION, `telemetryService.ts:321-336`); `env.isTelemetryEnabled`
= level is `all` (`extHostTelemetry.ts:58-68`); `env.createTelemetryLogger` loggers carry `isUsageEnabled`/`isErrorsEnabled`.

Our shim (WP-SEC-17):

| Surface | Value |
|---|---|
| `vscode.env.isTelemetryEnabled` | `false`, constant; `onDidChangeTelemetryEnabled` never fires `true` |
| `env.createTelemetryLogger(sender)` | logger with `isUsageEnabled=false`, `isErrorsEnabled=false`; sender never called |
| `workspace.getConfiguration('telemetry')` | `telemetryLevel='off'`, `enableTelemetry=false`, `enableCrashReporter=false`; keys protected (not writable by extensions, M-11 extension in security-licensing.md) |
| Host env (M-VSX-04 allow-list) | `DO_NOT_TRACK=1`, `DISABLE_TELEMETRY=1`, `DISABLE_ERROR_REPORTING=1`, `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1` (inherited by children such as the `claude` CLI) |
| Extension-specific settings defaults (configurationDefaults layer, data file) | `gitlens.telemetry.enabled=false`, `redhat.telemetry.enabled=false` |

Per-extension evidence (bundle greps, [research/policy-licence.md](research/policy-licence.md) §5.2):

| Extension | Default | Gate observed | Effect of our settings |
|---|---|---|---|
| eamodio.gitlens | `gitlens.telemetry.enabled` true; "BOTH this setting and VS Code telemetry must be enabled" | `isTelemetryEnabled` x2, `when` on `config.telemetry.telemetryLevel != off` | off path taken (both gates) |
| Anthropic.claude-code | none in contributes | extension: `env.isTelemetryEnabled && !CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC && !DISABLE_TELEMETRY && !DO_NOT_TRACK`; CLI honours `DISABLE_TELEMETRY`, `DISABLE_ERROR_REPORTING`, `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC` | off (flag + env); the CLI still talks to Anthropic's API for its function |
| ms-python.python | none | `@vscode/extension-telemetry`, `isTelemetryEnabled` x2, `telemetryLevel` x25 | off path exists (runtime behaviour UNVERIFIED) |
| redhat.java / redhat.vscode-yaml | `redhat.telemetry.enabled` null (opt-in prompt) | `isTelemetryEnabled`, `telemetryLevel`, `segment.io` | off; prompt behaviour with our default false UNVERIFIED |
| GitHub.vscode-pull-request-github | none | `@vscode/extension-telemetry` dep | follows level (UNVERIFIED in bundle) |
| others in 3.2 | no telemetry setting/deps in manifest | not grepped | UNVERIFIED |

### 6.3 What extensions may still send, and disclosure

Even with telemetry off, extensions make **functional** network calls we neither see nor filter
([security-licensing.md](security-licensing.md) sec 1.4): Claude Code -> Anthropic API (user's prompts and code
context), GitHub PR -> GitHub API, GitLens -> GitKraken services for plus features, language servers -> package
registries, extensions' own downloads, and they can ignore our flags. Disclosure (Proposal, UI in [ui-webviews.md](ui-webviews.md)):

1. Install sheet line for every code extension: "This extension can send data over the network. easyIDE turns telemetry
   off but cannot see or block what the extension sends." plus the publisher's privacy link when the manifest/listing
   has one (Publisher Agreement Section 6 requires publishers to disclose data collection in the listing).
2. Settings > Privacy page: easyIDE sends no telemetry; list of installed code extensions with their data-related links;
   the forced settings above shown read-only.
3. Store listing / Data safety text and whether extension traffic must be declared: L-5, not decided here.
