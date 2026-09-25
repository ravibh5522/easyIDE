# VS Code extensions: security (threat model delta, supply chain, work packages)

Status: AUDIT (2026-09-25). Documentation only. Nothing described as a mitigation below is
implemented unless it cites existing code. Split into two files (600-line rule):

| File | Sections |
|---|---|
| this file | 1 Threat model delta, 2 Supply-chain controls for Open VSX, 7 Work packages WP-SEC-*, 8 UNVERIFIED |
| [licensing-policy.md](licensing-policy.md) | 3 Licensing, 4 Google Play policy analysis (input to ADR 0034), 5 Open VSX terms and rate limits, 6 Privacy and telemetry |

Related: [README.md](README.md), [registry-install.md](registry-install.md), [backend.md](backend.md),
[ui-webviews.md](ui-webviews.md), base model [../extension-sdk/threat-model.md](../extension-sdk/threat-model.md)
(assets A1-A13, boundaries B1-B6, profiles P1-P6, mitigations M-01..M-23, residuals R1-R12),
ADRs [0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md),
[0008](../decision/0008-noncommercial-source-available-licensing.md),
[0012](../decision/0012-git-token-in-process-env-not-credential-socket.md),
[0015](../decision/0015-extension-sdk-licensing-apache.md),
[0016](../decision/0016-extension-registry-static-index-ed25519.md),
[0030](../decision/0030-vscode-extension-host-in-sandbox.md).

Evidence base (not re-researched here): [research/policy-licence.md](research/policy-licence.md),
[research/openvsx-registry.md](research/openvsx-registry.md), [research/ourcode-backend.md](research/ourcode-backend.md),
[research/ourcode-ui.md](research/ourcode-ui.md), corpus data [data/corpus.json](data/corpus.json).

Code paths are relative to the repo root. Aliases: `APP/` = `services/mobile/app/src/main/java/dev/easyide/app/`,
`SBX/` = `services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/`,
`SCH/` = `services/shared/extension-schema/src/main/kotlin/dev/easyide/extensions/`,
`EXT/` = `services/mobile/extensions/src/main/kotlin/dev/easyide/extensions/`, `$VS` = microsoft/vscode @ `0b16cb9`.

## 0. Fixed context (read first)

1. Extensions with code run as Node JS in **one host process per environment** inside the proot guest
   (ADR 0030 decision 1). All co-hosted extensions share one V8 heap, one `require` cache, one `process.env`.
2. **proot is not a security boundary** (ADR 0002 Consequences: "must never describe either backend as providing
   per-project isolation or protection against untrusted code"). Guest code runs as the app's Linux uid and can do
   whatever the app uid can with files (threat-model.md sec 1.1). Installing a code extension = running its code in
   the terminal (ADR 0030 Context).
3. Webviews render in Android `WebView`s **in the app process** (ADR 0030 Consequences). Their renderer is a separate
   sandboxed Chromium process on API 26+ ("A separate sandboxed process (SandboxedProcessService)",
   developer.android.com/develop/ui/views/layout/webapps/manage-webview-memory), but the Java-side bridge and
   `shouldInterceptRequest` handler run in our process. So B7 (below) is a boundary we **can** enforce.
4. Registry: Open VSX only (ADR 0030 Context: "Open VSX is the only lawful registry").
5. Secrets live on the Kotlin side under an Android Keystore key (pattern: `SBX/git/GitCredentials.kt:14-24,74`).
6. Telemetry from easyIDE: none; extension telemetry forced off (sec 6 of [licensing-policy.md](licensing-policy.md)).

Consequence: for code extensions the only enforced controls are (a) what enters the device (supply chain, sec 2),
(b) what the **Kotlin main side** does on the host's behalf (RPC surface, webview bridge, secrets, auth, URIs,
openExternal, UI), and (c) what is **not handed** to the guest (git token, Keystore). Everything the extension does
with `fs`, `child_process`, `net` inside the guest is disclosure-only.

## 1. Threat model delta (extends threat-model.md)

### 1.1 New assets and boundaries

| # | Asset / boundary | Why it matters | Where |
|---|---|---|---|
| A14 | Extension-host process (Node) | holds every co-hosted extension's code, secrets fetched via `secrets/get`, auth tokens | guest, one per env (ADR 0030 dec. 1) |
| A15 | Auth sessions (GitHub OAuth/PAT handed to extensions via `authentication.getSession`) | write access to user repos, org data | Kotlin, Keystore-encrypted; plaintext inside A14 after hand-out |
| A16 | Webview origin state (localStorage, IndexedDB, cookies, service workers) | extension UI state, cached tokens some extensions keep in webview storage | Android WebView profile data in the app data dir |
| A17 | Built-in commands callable by `commands.executeCommand` | a host call can trigger app actions (install, settings, git) | app process |
| A18 | Deep-link entry point (`onUri`) | external apps/websites can deliver data into an extension | Android intent filter (not built, `env.uri-handler` Missing, [research/ourcode-backend.md](research/ourcode-backend.md) §2.3) |
| B7 | **Webview page <-> app** (bridge + resource server + navigation) | enforced by us | `WebView` in app process |
| B8 | **Host RPC <-> Kotlin main** (JSON-RPC over stdio, ADR 0030 dec. 3) | enforced by us for every `main/*` method; caller identity inside the host is **self-asserted** | `:exthost` (not built) |
| B9 | **Device loopback / network** | guest shares the device network namespace; no proxy or filter by us | Android netd |

Important property of B8: the host sends `{ns: extId}` on `secrets/*` and `storage/*`
(`docs/extension-host/arch.md:154`). Inside one Node process any extension can forge that field, monkey-patch our
`vscode` module, or read another extension's closures. **Per-extension attribution on B8 is bookkeeping, not
authentication.** The only real unit of isolation on B8 is the host process (= environment).

### 1.2 Profiles added

| # | Profile | Capability |
|---|---|---|
| P7 | Malicious or compromised Open VSX extension (incl. typosquat, hijacked namespace, malicious update) | arbitrary Node + native code in the guest, full RPC surface of its host, its webviews |
| P8 | Honest extension rendering hostile data (repo content, PR comments, LLM output) in a webview | XSS inside that extension's webview |
| P9 | Other Android app or website on the device | fires deep links, connects to guest services listening on device loopback |

### 1.3 Threats (T-VSX) - STRIDE letter, mitigation, type

Type legend as in threat-model.md: **E** enforced by us, **D** disclosure only, **V** validated at boundary, **R** residual.

#### Arbitrary code in the guest (B1, P7)

| Id | S/T/R/I/D/E | Threat | Mitigations | Type |
|---|---|---|---|---|
| T-VSX-01 | I | Extension reads `/workspace`, every project bound into the env, rootfs home (Claude Code credentials, R3), `~/.gitconfig`, SSH keys, shell history | M-VSX-01, M-VSX-02, M-VSX-03 | D, R |
| T-VSX-02 | I, T | Extension reads/writes files of **other extensions** in the same env (globalStorage, installed code under `/opt/easyide/extensions/<id>`, `SBX/extensions/EnvironmentExtensionBinds.kt:20-28`) and patches their JS on disk | none possible; M-VSX-18 (install-time hash recorded, re-verified on host start: detects naive tampering only, like M-23) | R |
| T-VSX-03 | T, I | Extension reads/writes app data outside the rootfs as the app uid (state.json approvals, pins, settings, other environments' rootfs) | threat-model R2 unchanged; M-VSX-18 | R |
| T-VSX-04 | E | Persistence: writes `~/.bashrc`, `~/.profile`, `npm -g` shims, git hooks in `/workspace/.git/hooks`, so code survives uninstall and runs in the terminal / next git op | M-VSX-03 (kill switch stops host only), M-VSX-19 (uninstall copy states rootfs is not cleaned; "reset environment" is the clean path) | D, R |
| T-VSX-05 | I | Network exfiltration of A1/A3 from the guest | no filtering (sec 1.4); M-VSX-01 disclosure | R |
| T-VSX-06 | E | Extension spawns processes (126/156 corpus extensions flagged `spawnsProcesses`, `docs/vsx-compat/data/corpus.json`), including long-lived daemons that outlive the host | host started with proot `--kill-on-exit` (`SBX/backend/ProotLauncher.kt:20-57`) so children die with the host; M-VSX-03 | E (lifetime), R (what they do) |

#### Credential isolation (A2, A4, A15; B1, B8)

| Id | S/T/R/I/D/E | Threat | Mitigations | Type |
|---|---|---|---|---|
| T-VSX-07 | I | Git token (ADR 0012) reaches the host's env and so every extension and child | M-VSX-04 clean env allow-list for the host; host is never a parent of a token-bearing git; ST-VSX-01 | E |
| T-VSX-08 | I | Long-running host (or its child) reads `/proc/<pid>/environ` of a user-initiated token-bearing `git` (0012 window). With a host always running (GitLens polls git) the window is exercised far more often than with a terminal | none on proot (ADR 0012 Consequences: "Only the chroot backend, with real uids, could close it"); M-VSX-05 disclosure in the `host.run` sheet (pausing the host during git network ops is not proposed: it breaks GitLens) | R (raised likelihood vs R4) |
| T-VSX-09 | S, I | Extension B requests `secrets/get {ns:"A"}` or reads A's secret from shared memory | M-VSX-06: secrets keyed `(envId, extId)`; Kotlin refuses `ns` not enabled in that env; every access logged with ns. Honest limit: inside one host, A's plaintext is reachable by B | V, R |
| T-VSX-10 | I | Extension stores its own tokens in plain files (globalStorage, `~/.config`) instead of `SecretStorage`; any guest process reads them | none (extension's choice); disclosure | R |
| T-VSX-11 | I, E | Auth session over-exposure: GitHub token handed to extension X becomes readable by every extension in that host; scopes wider than needed; user's git PAT reused silently | M-VSX-07 auth provider in Kotlin: per `(extId, providerId, scopes)` consent sheet, tokens stored in Keystore-backed store, handed only in response to a `getSession` whose `extId` is enabled in that env, separate from the git PAT unless the user picks "use my git token" explicitly; Accounts sheet lists grants with revoke | V, D, R |
| T-VSX-12 | E | Guest code uses the app's Keystore keys (same uid) | threat-model ground truth 1 / R2 unchanged; Keystore protects at rest only | R |
| T-VSX-13 | E | Host calls built-in commands (`commands.executeCommand`) that act with app authority: install/enable extensions, write protected settings, trigger in-app git push with token to a remote the extension set in `.git/config` | M-VSX-08 host-callable command allow-list (data file, R-ENG-07), security-sensitive built-ins need a UI confirmation naming the calling extension; M-11 extended to `telemetry.*`, `extensions.*`, `security.*`, `http.proxy*` | E |

#### Webviews (B7, P7, P8)

| Id | S/T/R/I/D/E | Threat | Mitigations | Type |
|---|---|---|---|---|
| T-VSX-14 | T, E | XSS in extension HTML (P8: commit messages, PR bodies, LLM output); script gains the extension's `acquireVsCodeApi().postMessage` | extension's own CSP kept (`docs/extension-host/arch.md:149`); M-VSX-09 bridge carries messages **only** to the owning extension's handler (impact = what that handler accepts); message size cap | E (containment to one extension), R (extension bug) |
| T-VSX-15 | E | Bridge abuse: page JS reaches Android APIs | M-VSX-10: **no `addJavascriptInterface`** for extension webviews (the `MermaidView` pattern, `APP/ui/screens/workspace/MermaidView.kt:48-71`, uses it with `allowFileAccess=true` - not to be reused); use `WebViewCompat.addWebMessageListener` with an **exact** origin rule; exposed API = `postMessage/getState/setState` only | E |
| T-VSX-16 | I | Resource path traversal through `https://easyide-webview.local/<handle>/<guest-path>`: `..`, `%2e%2e`, double-encoding, symlinks from the extension dir to `/workspace/.git`, `/root`, `/proc` | M-VSX-11: decode once, canonicalize, resolve symlinks, require prefix of extension dir or declared `localResourceRoots`; map through the same `PathMapper` as LSP (M-16); refuse `/proc`, `/sys`, `/dev`, anything outside the rootfs; GET only; content-type from extension, `nosniff` | E |
| T-VSX-17 | S, I | Origin confusion: one shared origin `https://easyide-webview.local` for all webviews (`arch.md:149`) = shared localStorage/IndexedDB/cookies/service workers; webview of A fetches B's resources and reads B's state | M-VSX-12: origin per webview instance `https://<random-id>.easyide-webview.local` (VS Code likewise gives each webview its own origin under `vscode-cdn.net`, `$VS/src/vs/workbench/contrib/webview/common/webview.ts:21`), persistent state via `setState` only; androidx.webkit `Profile` per extension where `MULTI_PROFILE` is supported (developer.android.com/reference/androidx/webkit/Profile) | E |
| T-VSX-18 | S | Top-level navigation or `window.open` to an external site inside trusted chrome (phishing), or remote page receiving the bridge | M-VSX-13: `shouldOverrideUrlLoading` refuses every navigation off the webview's own origin and routes http(s) to the openExternal sheet; multiple windows off; bridge origin rule is exact so remote origins get no bridge | E |
| T-VSX-19 | I | `file://` / `content://` access from webview JS | M-VSX-14: file and content access off in `WebSettings` for extension webviews; all local bytes come through `shouldInterceptRequest` (WebViewAssetLoader model: "Loading local files using web-like URLs instead of \"file://\" is desirable as it is compatible with the Same-Origin policy", developer.android.com/reference/androidx/webkit/WebViewAssetLoader) | E |
| T-VSX-20 | I | Webview loads remote scripts/trackers (extension CSP permits) | we add no network (ADR 0030 Consequences "no network access added by us"); remote loads allowed only as the extension's CSP allows; http (non-TLS) subresources refused (mixed content) | D |
| T-VSX-21 | D | Webview memory (native, "can silently grow to gigabytes", manage-webview-memory page) | M-VSX-15: dispose hidden webviews unless `retainContextWhenHidden`; cap live webviews; `onRenderProcessGone` handled (no app crash) | E |

#### Supply chain (B3, P7)

| Id | S/T/R/I/D/E | Threat | Mitigations | Type |
|---|---|---|---|---|
| T-VSX-22 | E | Malicious extension published on Open VSX (scanners are best-effort; ToU: Eclipse "MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SECURITY", `terms-of-use.md:28`) | sec 2: reviewStatus gate, malicious list, `host.run` disclosure, kill list, safe mode | D, R |
| T-VSX-23 | S | Typosquat (`esbenp.prettier-vsocde`) or look-alike display name; Open VSX similarity check is "monitor only" (`application-production.yml:112-117`) | M-VSX-16 local similarity warning against the corpus id list; verified badge rules; publisher id in fixed chrome (ST-33) | D |
| T-VSX-24 | S | Compromised publisher account / namespace takeover / `@open-vsx` republish into someone else's namespace | M-VSX-17 publisher pin `(namespace, verified, publishedBy.loginName)` at install; change = warning + explicit confirm on update | D |
| T-VSX-25 | T | Update hijack: CDN/object-store tampering, registry key change, rollback to an old vulnerable version | sha256 + Ed25519 `.sigzip` against pinned key (M-VSX-20); key-id change = hard warning; no auto-update; downgrade only by explicit user choice | E |
| T-VSX-26 | E | Dependency/pack closure installs more code than the user picked (e.g. ms-python.python `extensionPack` pulls Pylance, debugpy, python-envs, [licensing-policy.md](licensing-policy.md) sec 3.2) | M-VSX-21 install sheet lists the full closure, each with its own licence/verified/native flags; each code member needs `host.run` | D |
| T-VSX-27 | E | Runtime package fetch: extension runs `npm install`/`pip install` at activation (unsigned, unpinned) | none (guest network, T-VSX-05); disclosure | R |

#### Native code, network, resources, UI, URIs

| Id | S/T/R/I/D/E | Threat | Mitigations | Type |
|---|---|---|---|---|
| T-VSX-28 | E | Native binaries: shipped in the `.vsix` (32/156 corpus extensions, 52 ELF executables + 72 `.node` addons, ~33 % of download weight; `corpus.json` `nativeBinaries`) or **downloaded at runtime** (e.g. a clangd download by vscode-clangd, UNVERIFIED which extensions do this) with no signature we can check | M-VSX-22 install sheet shows "ships native code: N files, X MB, arch"; M-VSX-23 best-effort log of executables started by the host tree (`/proc` walk, `APP/lsp/ProcTree.kt`) that live under extension/globalStorage dirs; Play impact in [licensing-policy.md](licensing-policy.md) sec 4 | D |
| T-VSX-29 | I | Network egress: host and its children reach any host; `http.proxy` not propagated (`net.proxy` Missing, guest env cleared `SBX/shell/SandboxShell.kt:159-171`) | we do **not** proxy, filter, log or TLS-inspect guest traffic; we **do**: (a) keep registry traffic in Kotlin (not the guest), (b) set telemetry-off env/config (sec 6), (c) disclose. `network(hosts)` is disclosure only (ADR 0013:64-67) | D, R |
| T-VSX-30 | I, E | Services started by extensions listen on device loopback (live-server, language servers over TCP, Jupyter) and are reachable by **any app on the device** (P9); the guest shares the device network namespace | disclosure; M-VSX-12 origin rule means webviews do not get a `portMapping` bypass; UNVERIFIED which corpus extensions listen on TCP | R |
| T-VSX-31 | D | Host memory/CPU exhaustion: GitLens-scale heaps, busy loop blocks all co-hosted extensions, disk filled by globalStorage/downloads | M-VSX-24: host slot in `MemoryPolicy` kill order (`services/mobile/lsp/src/main/kotlin/dev/easyide/lsp/manager/MemoryPolicy.kt:58-68`), RSS probe, V8 heap cap flag at spawn, RPC heartbeat watchdog -> "host unresponsive, restart/kill", host-specific activation timeout (today `extensions.wasm.activateTimeoutMs`=5000 ms, `SCH/settings/ExtensionSettings.kt:30`), crash journal -> safe mode (M-20); per-extension storage size shown | E (memory, liveness), R (CPU attribution per extension, disk) |
| T-VSX-32 | S | UI spoofing: every extension message is a modal KitDialog today (`APP/ui/screens/workspace/ext/ExtensionPrompts.kt:112`); input box `password:true` can phish for GitHub/Google passwords; quick pick imitating easyIDE settings; status bar items imitating system state | M-VSX-25 attribution chrome (extension display name + id + "Extension" label outside the extension-controlled area) on every message, quick pick, input box; password input boxes carry a fixed line "Requested by <ext>. easyIDE never asks for account passwords here."; only `modal:true` messages are dialogs, others are notifications; modal rate limit with "Mute this extension" | E (chrome), D |
| T-VSX-33 | E, S | URI handler abuse: any app or web page fires a deep link that activates an extension (`onUri`) with attacker-controlled path/query (auth callback injection, command trigger) | M-VSX-26: app-owned scheme (not `vscode://`), path `/<extId>/...`; confirm sheet showing target extension + full URI unless it matches a pending auth flow the extension started (state nonce); never activates a disabled or not-`host.run`-granted extension; rate-limited | E |
| T-VSX-34 | S, E | `env.openExternal` abuse: `intent:`, `content:`, `file:`, `tel:`, `sms:` URIs, rapid-fire opens, look-alike hosts | M-18 (confirm sheet with full URL, `APP/extensions/host/AppHostPort.kt:140`) plus M-VSX-27: scheme allow-list (`https`, `http`, `mailto`), IDN shown as punycode, extension attribution, rate limit | E |
| T-VSX-35 | E | `command:` links in markdown (hovers, tree tooltips, webviews with `enableCommandUris`) run arbitrary built-in or other-extension commands with args | M-VSX-28: honour `MarkdownString.isTrusted` / `enabledCommands`; untrusted markdown renders command links inert; M-VSX-08 allow-list applies | E |

### 1.4 Network egress: what we do and do not do

| We do | We do not |
|---|---|
| Fetch Open VSX metadata/packages from Kotlin (not the guest), https only, pinned key, `Retry-After` honoured ([registry-install.md](registry-install.md)) | proxy, filter, allow-list, log or inspect any guest traffic (host, extensions, children, language servers) |
| Force telemetry settings/env off for the host (sec 6) | guarantee an extension sends nothing (they can ignore the flag) |
| Refuse remote navigation inside webviews; keep extension CSP | add network permissions or CORS relaxations to webviews |
| Disclose "this extension can use the network without limits" in the `host.run` sheet | propagate the user's `http.proxy` (Missing today; would be a feature, not a control) |

### 1.5 Mitigations (M-VSX)

| Id | Mitigation | Mechanism | Counters | Owner doc |
|---|---|---|---|---|
| M-VSX-01 | `host.run` capability synthesized for every `.vsix` with `main` (the manifest cannot declare `easyide.capabilities`); install sheet copy: runs as you in this environment, can read all projects in it, use the network, start programs; not contained | new id in the closed set `SCH/capability/Capability.kt:11-64` (absent today); approval per install (`EXT/host/Enablement.kt:32,95-99`) | T-VSX-01..06 | [backend.md](backend.md) |
| M-VSX-02 | Host off until one `host.run` extension is enabled in that env | ADR 0030 dec. 6 | T-VSX-01 | backend.md |
| M-VSX-03 | Per-environment kill switch: stop host (+ proot `--kill-on-exit` children), mark all `host.run` extensions of env disabled, wipe host state (memento, webview profiles, secrets of those ext ids on user confirm) | ADR 0030 dec. 6; not implemented (grep "kill switch" = 0 in code) | T-VSX-01..06, 22 | backend.md |
| M-VSX-04 | Host env = explicit allow-list: `HOME, PATH, LANG, TERM, EASYIDE_EXTHOST=1` (`docs/extension-host/arch.md:27`) + telemetry-off vars (sec 6); built via `ServerProcessFactory` from config only (M-14); `SandboxShell.applyEnvironment` clears inherited env (`SBX/shell/SandboxShell.kt:159-171`) | existing launcher path | T-VSX-07 | backend.md |
| M-VSX-05 | Copy in the `host.run` sheet: while code extensions run, a git push/fetch token is briefly readable by them (ADR 0012) | UI string | T-VSX-08 | ui-webviews.md |
| M-VSX-06 | `ExtensionSecrets`: AES-GCM with Keystore key (GitCredentials pattern), file per `(envId, extId)`, refuses ns not enabled in the calling env, access log; never exposes `GitCredentials` | replaces `NoSecrets` (`APP/extensions/wasm/WasmSmallPorts.kt:40-47`); EncryptedSharedPreferences is deprecated ("Deprecated in 1.1.0", developer.android.com) - do not use | T-VSX-09 | backend.md |
| M-VSX-07 | Kotlin `AuthenticationProvider` for `github`: consent per `(extId, scopes)`, Keystore storage, Accounts sheet (list/revoke), token separate from git PAT by default, OAuth via Custom Tabs + URI handler | `ui.auth-ui` Missing ([research/ourcode-ui.md](research/ourcode-ui.md)) | T-VSX-11 | ui-webviews.md |
| M-VSX-08 | Host-callable built-in command allow-list (JSON data file); sensitive commands need UI confirmation naming the caller | `builtin-commands.json` extension | T-VSX-13, 35 | backend.md |
| M-VSX-09 | Webview messages routed only to the owning `(host, extId, handle)`; JSON, size cap, no Android objects | `webview/message` (`arch.md:71`) | T-VSX-14 | ui-webviews.md |
| M-VSX-10 | `addWebMessageListener` with exact per-webview origin rule, no `addJavascriptInterface`, `addDocumentStartJavaScript` for the `acquireVsCodeApi` shim | androidx.webkit `WEB_MESSAGE_LISTENER`, `DOCUMENT_START_SCRIPT` | T-VSX-15 | ui-webviews.md |
| M-VSX-11 | Resource server path canonicalization + root check + method/type rules | `shouldInterceptRequest` | T-VSX-16 | ui-webviews.md |
| M-VSX-12 | Unique origin per webview instance; per-extension `Profile` where supported | androidx.webkit `Profile`/`ProfileStore` | T-VSX-17, 30 | ui-webviews.md |
| M-VSX-13 | Navigation lock + external links via openExternal sheet | `shouldOverrideUrlLoading` | T-VSX-18 | ui-webviews.md |
| M-VSX-14 | File/content access off; everything via interceptor | `WebSettings` | T-VSX-19 | ui-webviews.md |
| M-VSX-15 | Webview lifecycle and count caps, renderer-gone handling | `onRenderProcessGone` | T-VSX-21 | ui-webviews.md |
| M-VSX-16 | Typosquat warning: Damerau-Levenshtein <= 2 on `namespace.name` or exact display-name match against top ids in `docs/vsx-compat/data/corpus.json` with a different id | install sheet | T-VSX-23 | registry-install.md |
| M-VSX-17 | Publisher pin (TOFU) on namespace, verified flag, `publishedBy.loginName`; change on update = warning + confirm | `state.json` pins (M-02 analogue) | T-VSX-24 | registry-install.md |
| M-VSX-18 | Record sha256 of each installed file set at install; re-hash `package.json` + `main` entry at host start; mismatch = "modified on disk" banner (detects naive tampering, not an attacker) | installer | T-VSX-02, 03 | registry-install.md |
| M-VSX-19 | Uninstall/kill-switch copy: rootfs changes made by the extension are not undone; offer "reset environment" | UI | T-VSX-04 | ui-webviews.md |
| M-VSX-20 | sha256 + Ed25519 `.sigzip` verification (sec 2) | `TinkEd25519` | T-VSX-25 | registry-install.md |
| M-VSX-21 | Closure-aware install sheet (deps + packs) | resolver | T-VSX-26 | registry-install.md |
| M-VSX-22 | Native-code disclosure from a zip scan at install (ELF header / `.node`) | installer | T-VSX-28 | registry-install.md |
| M-VSX-23 | Best-effort spawn log (process tree of host) into Extension Log | `ProcTree.kt` | T-VSX-28 | backend.md |
| M-VSX-24 | Host resource policy: kill-order slot, RSS budget key, V8 heap cap, heartbeat watchdog, host activation timeout key | `MemoryPolicy`, new settings keys | T-VSX-31 | backend.md |
| M-VSX-25 | Extension attribution chrome + password-field warning + modal rate limit | Kit prompts | T-VSX-32 | ui-webviews.md |
| M-VSX-26 | Deep-link router with confirm/nonce rules | Android intent filter + `onUri` | T-VSX-33 | backend.md |
| M-VSX-27 | openExternal scheme allow-list, punycode, rate limit | extends M-18 | T-VSX-34 | ui-webviews.md |
| M-VSX-28 | Markdown command-link trust (`isTrusted`, `enabledCommands`) | markdown renderer | T-VSX-35 | ui-webviews.md |
| M-VSX-29 | Audit log: install/update/enable/disable/kill, `host.run` grants, secrets and auth access, deep links, openExternal, sensitive commands - with ext id, env, time; exportable; no secret values | `APP/extensions/ExtensionLogRing.kt` persisted | all (repudiation) | backend.md |

### 1.6 Residual risks added

| # | Residual | Why | Accepted because / next step |
|---|---|---|---|
| RV-1 | Any `host.run` extension can read/modify everything the environment can see, including other extensions and their secrets once fetched | proot is not isolation; one host per env | disclosure; environments are the user's unit of separation (ADR 0005); chroot does not change this for co-hosted code |
| RV-2 | Git-token window (ADR 0012) is exercised more often with a live host | persistent guest reader | stated in copy; separate auth tokens for extensions |
| RV-3 | No per-extension network, CPU or disk control | no cgroups, no netfilter under proot | kill switch; disclosure |
| RV-4 | Runtime-downloaded native code and npm/pip installs are unverified | outside our install path | disclosure; Play posture in licensing-policy.md sec 4 |
| RV-5 | Loopback services visible to other apps | shared network namespace | disclosure |

### 1.7 Security test cases (extend threat-model.md sec 8)

| Id | Case | Expected |
|---|---|---|
| ST-VSX-01 | Read `process.env` in the host, `/proc/<host>/environ`, and env of a child spawned by an extension; during and after an in-app `git fetch` | only allow-listed keys; no `EASYIDE_GIT_TOKEN` anywhere except the git process |
| ST-VSX-02 | Extension B calls `secrets/get {ns:A}` where A is not enabled in env | refused, logged |
| ST-VSX-03 | Webview requests `..%2f..%2froot/.ssh/id_rsa`, a symlink to `/workspace/.git/config`, `/proc/self/environ` | 403/404, no bytes |
| ST-VSX-04 | Webview JS: `window.location='https://evil'`, `window.open`, `<a target=_blank>`, `fetch('file:///...')` | navigation refused -> openExternal sheet; file fetch fails |
| ST-VSX-05 | Webview of A reads localStorage written by webview of B | not visible |
| ST-VSX-06 | Page at a remote origin probes for the bridge object | undefined |
| ST-VSX-07 | Deep link to disabled extension / without nonce / 20 per second | confirm or refuse; no activation of disabled ext; rate-limited |
| ST-VSX-08 | `openExternal('intent://...')`, `file:///`, `content://`, `javascript:` | refused |
| ST-VSX-09 | `executeCommand` on each sensitive built-in from the host | confirm sheet or refusal per allow-list |
| ST-VSX-10 | Busy loop in `activate()`; 2 GB heap allocation | watchdog restart; heap cap OOM; crash journal -> safe mode after 2 |
| ST-VSX-11 | Tampered `.vsix` (1 bit), wrong `.sigzip`, unknown key id | hard fail / key-change warning |
| ST-VSX-12 | Lint over string resources: no copy calls proot, the host or environments "isolated", "sandboxed" or "secure" | zero hits (extends ST-35) |

## 2. Supply-chain controls for Open VSX

Facts from [research/openvsx-registry.md](research/openvsx-registry.md) §(c)-(e), (i), (k) (pinned `eclipse/openvsx@edab3e4`); design proposals marked "Proposal".

| # | Control | Facts / evidence | easyIDE rule (Proposal) | State in our code |
|---|---|---|---|---|
| SC-1 | SHA-256 of the whole `.vsix` | `.sha256` = lowercase hex SHA-256 of the whole file (`ExtensionProcessor.java:452-466`); corpus: 156/156 verified (`corpus.json` `sha256Verified`) | hard fail on mismatch, no "install anyway" (ST-01) | generic `checkBytes` (`SCH/registry/RegistryVerifier.kt:84`) |
| SC-2 | Ed25519 `.sigzip` | zip with `.signature.sig` (64-byte raw Ed25519 over **the entire .vsix bytes**, pure Ed25519), `.signature.manifest` (unsigned), empty `.signature.p7s` (`ExtensionVersionIntegrityService.java:137-202`); key served as PEM SPKI at `/api/-/public-key/{publicId}`; live key id `14ccb407-4e79-41ed-be5a-6d608325c45a` on every sampled version; verified with `openssl pkeyutl -verify -rawin` ("Signature Verified Successfully", 2026-09-25); corpus: 156/156 have `files.signature` | pin key id + 32 raw bytes (strip SPKI prefix `302a300506032b6570032100`) in `RegistryPolicy`; verify with `TinkEd25519`; unknown key id -> fetch over TLS, show "Open VSX signing key changed", require confirm (TOFU on registry key); bad signature = hard stop. Do **not** use `@vscode/vsce-sign` (proprietary: "only with Microsoft Visual Studio, ... Visual Studio Code ...") | Tink wrapper exists `APP/extensions/registry/TinkEd25519.kt:12-23`; no sigzip/PEM code |
| SC-3 | What the signature proves | registry key signed these bytes; not publisher identity, not safety | copy: "Signed by Open VSX" never "verified safe" | - |
| SC-4 | Review status | API `reviewStatus` published/under_review/rejected; publish-time ClamAV, YARA, Argus (enforced), secret detection, SHA-256 blocklist (`application-production.yml:76-200`) | install only `reviewStatus == published` | - |
| SC-5 | Extension-control kill list | `open-vsx/publish-extensions` `extension-control/extensions.json`: 944 `malicious`, 128 `deprecated` (live 2026-09-25); fetched from raw.githubusercontent.com | refresh daily with the update check; malicious id: refuse install, disable installed copies (`REVOKED`, `EXT/host/Enablement.kt:80`), never auto-uninstall (ADR 0016 rule); deprecated: banner + replacement link | revocation state exists for static index only |
| SC-6 | Takedown / revocation feed | `/api/-/version-changes` (preview) reports `ACTIVE/INACTIVE/REMOVED` | installed version `REMOVED/INACTIVE` = revocation notice (banner, suggest disable); not an automatic disable (content may be removed for non-security reasons, ToU :11) | - |
| SC-7 | Publisher pinning | per-version `verified`, `publishedBy{loginName,provider}`, namespace `verified` | M-VSX-17 | - |
| SC-8 | Verified-namespace display | "An extension version is regarded as _verified_ if its namespace is verified and its publishing user is a member of the namespace" (`Namespace-Access.md:25`); `@open-vsx` may republish into any namespace (:44); corpus: 151/156 verified, 5 not | shield only if `verified == true`; else "Unverified publisher" chip + login; `publishedBy.loginName == "open-vsx"` -> "Republished by Open VSX"; namespace verified shown separately | - |
| SC-9 | No auto-update by default | Open VSX: "Open VSX Registry itself does not provide any auto-update facility" (FAQ); VS Code checks every 12 h (`extensionsWorkbenchService.ts:977`) | daily notify-only check (ADR 0016); update sheet shows version, changelog, capability/native/licence/publisher deltas; per-extension "auto-update" opt-in allowed only for verified publishers (Proposal; default off) | rollback + `current` flip exist (`APP/extensions/install/LocalInstaller.kt:172-237,319-323`) |
| SC-10 | Per-extension enable/disable | `Enablement` reasons incl. USER_DISABLED, SAFE_MODE, CRASH_DISABLED, REVOKED (`EXT/host/Enablement.kt:45-100`) | reuse; disabling a code extension restarts the host (a loaded module cannot be unloaded from Node) | Have |
| SC-11 | Safe mode | `EXT/host/SafeModeState.kt:13-57`; 2 consecutive activation crashes -> safe mode (`SCH/ExtensionPolicy.kt:12`) | safe mode = host not started; crash keys moved to host-neutral names | Have (WASM keys) |
| SC-12 | Per-environment kill switch | ADR 0030 dec. 6 | M-VSX-03, reachable from the environment sheet and from a persistent notification while the host runs | Missing |
| SC-13 | Install-time disclosure | capability sheet (M-05, M-06) | sheet per closure member: `host.run`, native code (SC-14), network, licence (licensing-policy.md sec 3.1), verified/publisher, size, platform, pre-release; accepting shows Open VSX ToU line "By clicking download, you accept this website's Terms of Use." (quoted from the Open VSX bundle) | Missing for Open VSX |
| SC-14 | Native code flag | corpus scanner detects ELF/`.node` by header | shown in SC-13; Play build behaviour per ADR 0034 flag | - |
| SC-15 | Audit log | Extension Log ring exists (`APP/extensions/ExtensionLogRing.kt`) | M-VSX-29 persisted, exportable | Partial |
| SC-16 | Rate-limit hygiene | 10,800 req/h, < 3 RPS per IP (licensing-policy.md sec 5) | <= 1 RPS, `Retry-After`, one `v2/-/query` per id per day | - |

Order of checks at install (Proposal): reviewStatus -> kill list -> platform/engine -> download -> size limits -> sha256 ->
signature -> unzip rules (keep `extension/` only, reject `..`/absolute/symlink, preserve exec bits) -> native scan ->
disclosure sheet -> commit. Details and endpoints: [registry-install.md](registry-install.md).

## 7. Work packages

Effort: S <= 3 d, M <= 2 wk, L <= 5 wk, XL > 5 wk (one engineer). Risk = delivery/security risk.

| WP | Scope | Acceptance test | Effort | Risk |
|---|---|---|---|---|
| WP-SEC-1 | Open VSX integrity: sha256 + `.sigzip` Ed25519 with pinned key, key-change TOFU flow, hard stops; fix stale ADR 0016:48-49 / LLD "sha256 only" text | ST-01, ST-VSX-11 on rust-analyzer 0.4.3061 linux-arm64 sample + a mutated copy; unit test with the pinned key | S | Low |
| WP-SEC-2 | Kill list + reviewStatus + version-changes: daily fetch, refuse/disable/banner, deprecated replacement | fixture list containing an installed id -> disabled `REVOKED` on refresh, not uninstalled; `under_review` version not installable | S | Low |
| WP-SEC-3 | Publisher display + pin + typosquat warning (SC-7/8, M-VSX-16/17) | fixtures: unverified, `open-vsx` republish, login change on update, id at distance 1 from a top id -> each shows the specified chip/warning | S | Low |
| WP-SEC-4 | `host.run` capability + install/update disclosure sheet (closure, native, licence, network, ToU line), string lint | ST-VSX-12; sheet snapshot tests for ms-python.python (pack), claude-code (native, proprietary), gitlens (licence) | M | Med (copy review) |
| WP-SEC-5 | Host launch hygiene: env allow-list, telemetry-off env, `--kill-on-exit`, no token path; tests | ST-VSX-01 on device; ST-12/13 extended to the host | S | Low |
| WP-SEC-6 | Per-environment kill switch + state wipe + safe-mode integration + host-neutral crash keys | kill with 3 running extensions and a spawned child -> no guest process left (`ps` in terminal), all marked disabled, state wiped after confirm; ST-34 | M | Med |
| WP-SEC-7 | `ExtensionSecrets` (Keystore AES-GCM, `(envId, extId)`, access log) | ST-VSX-02; ciphertext on disk not decryptable after key deletion; git PAT never returned | S | Low |
| WP-SEC-8 | GitHub auth provider: consent per `(extId, scopes)`, Accounts sheet, revoke, OAuth via Custom Tabs + deep link nonce, no PAT reuse by default | GitHub PR extension obtains a session only after consent; revoke -> next `getSession` prompts; token absent from host env | L | High (OAuth app registration, UNVERIFIED client-id policy) |
| WP-SEC-9 | Webview security baseline: per-instance origin, `addWebMessageListener` exact origin, no JS interface, navigation lock, file/content access off, resource server canonicalization, CSP kept, lifecycle caps, renderer-gone | ST-VSX-03..06 automated in an instrumented test with a hostile fixture extension | L | High |
| WP-SEC-10 | UI anti-spoofing: attribution chrome, password warning, non-modal default, modal rate limit, markdown command-link trust | ST-33 extended; screenshot tests; `command:` link in untrusted hover inert | M | Low |
| WP-SEC-11 | Deep-link router (`onUri`) + openExternal scheme allow-list/rate limit | ST-VSX-07, ST-VSX-08 | M | Med |
| WP-SEC-12 | Built-in command allow-list for host calls + protected settings extension (`telemetry.*`, `http.proxy*`) | ST-VSX-09; ST-23 extended | S | Low |
| WP-SEC-13 | Host resource policy: kill-order slot, RSS budget key, V8 heap cap, heartbeat watchdog, host activation timeout | ST-VSX-10 on device; UI stays responsive | M | Med |
| WP-SEC-14 | Native-code scan at install + best-effort spawn log + audit log persistence/export | sheet shows ELF/.node counts for rust-analyzer/claude-code; spawn of `extension/server/rust-analyzer` appears in log | M | Low |
| WP-SEC-15 | Distribution flag `extensions.code.enabled` per build flavour (ADR 0034 input): Play / sideload / F-Droid builds can disable code extensions and native-bearing installs | Play-flavour build with flag off: `.vsix` with `main` installs declarative parts only or is refused with a clear message; flag on in direct build | M | High (policy, see licensing-policy.md sec 4) |
| WP-SEC-16 | Licence capture/display and NOTICE rules (licensing-policy.md sec 3) | licence chip for all 22 must-work ids matches the table in licensing-policy.md sec 3.2 | S | Low |
| WP-SEC-17 | Telemetry-off enforcement in the shim (`env.isTelemetryEnabled=false`, logger disabled, `telemetry.telemetryLevel='off'` read-only, env vars) + privacy disclosure | corpus fixture: GitLens, claude-code, ms-python, redhat.vscode-yaml take their off path (instrumented `fetch`/`https` hook in a test host sees no telemetry hosts) | S | Med (extensions may ignore) |
| WP-SEC-18 | Legal review package for ADR 0034 (the questions in licensing-policy.md sec 4.6) | counsel sign-off recorded in ADR 0034; no user-facing compliance claim before that | S (eng) | High (external) |

## 8. UNVERIFIED (this file)

| Item | How to verify |
|---|---|
| Whether any active Open VSX version lacks `files.signature` (corpus 156/156 have it) | sweep `/api/v2/-/query?includeAllVersions=true` for installed ids within 10,800 req/h |
| Which extensions download native binaries at runtime (clangd, redhat.java JRE, others) | static grep of corpus bundles for download URLs + chmod/spawn; device run with spawn log (WP-SEC-14) |
| Which corpus extensions listen on TCP ports | grep bundles for `listen(`/`createServer`; `ss -ltn` in the guest with each loaded |
| Exact per-webview origin scheme VS Code uses (subdomain per webview under `vscode-cdn.net`) | read `$VS/src/vs/workbench/contrib/webview/browser/webviewElement.ts` and `pre/index.html` |
| VS Code default `localResourceRoots` (extension dir + workspace folders) | `$VS/src/vs/workbench/api/common/extHostWebview.ts` / `webviewElement.ts` |
| `WebSettings` defaults for file/content access at targetSdk 37 | developer.android.com `WebSettings` reference; instrumented test |
| `MULTI_PROFILE` availability on target WebView versions | `WebViewFeature.isFeatureSupported(MULTI_PROFILE)` on test devices |
| Whether guest `process.env` contains `PROOT_LOADER`/`LD_LIBRARY_PATH` host paths ([research/ourcode-backend.md](research/ourcode-backend.md) §9) | `env` in the terminal |
| Node V8 heap cap option name/behaviour under proot | nodejs.org/api/cli.html; device test |
| GitHub OAuth app/device-flow constraints for a mobile client id | GitHub docs; owner decision |
| Whether VS Code itself confirms `onUri` deep links for extensions | read `$VS/src/vs/workbench/services/extensions/browser/extensionUrlHandler.ts` |
