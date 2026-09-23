# Extension SDK - Threat Model

STRIDE-style threat model for the language core, extension runtime (L1), WASM host (L2),
registry/install path and customization layers: what we protect, from whom, and what we
honestly cannot.

Status: PROPOSED (2026-09-23). Nothing here is implemented.
Sources of truth: [arch.md sec 9](arch.md#9-security-and-trust-model),
[sdk-reference.md#capabilities](sdk-reference.md#capabilities),
[sdk-reference.md#registry-index-format](sdk-reference.md#registry-index-format).
System view: [hld.md](hld.md). Related ADRs: [0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md),
[0005](../decision/0005-sandbox-environment-sharing-model.md),
[0012](../decision/0012-git-token-in-process-env-not-credential-socket.md).

## 1. Ground truth (read first)

1. **proot and chroot+BusyBox are not isolation** (0002). Every sandbox process - terminal
   shells, language servers, install steps, Claude Code CLI, any package the user installs -
   runs as the **app's Linux uid**. Assume sandbox code can do anything the app uid can:
   read/write every file in the app's private data dir, including `state.json`, settings
   files, extension dirs and the registry cache, and in principle reach the app's Android
   Keystore keys over binder. Keystore protects data at rest against off-device extraction,
   not against code running as the app.
2. **L2 WASM is the only enforced boundary** - a capability boundary we implement over
   Chicory's interpreter, not an audited sandbox (arch sec 9 "Never claimed").
3. L1 declarative extensions contain no code, but their actions can start sandbox
   processes; from that point enforcement is **disclosure only**.
4. Consequence: the security value of signing, prompts and capabilities is in **keeping
   untrusted code out of the sandbox and out of privileged in-app calls in the first place**.
   Once hostile code runs in the sandbox, local integrity of app data is not guaranteed.

## 2. Assets

| # | Asset | Why it matters | Where it lives |
|---|---|---|---|
| A1 | User source code and project files | confidentiality, integrity | `<files>/projects/<id>/` (bind at `/workspace`) |
| A2 | Git token | write access to user's remote repos | `GitCredentials` (AES-GCM, Keystore key); in env of one `git` process only (0012) |
| A3 | Claude Code credentials | paid API access, conversation history | Claude Code CLI's own files **inside the rootfs home** (sandbox-readable, see R3) |
| A4 | Per-extension secrets (`secrets.read`) | third-party tokens the user entered | Keystore-backed store, per-extension namespace |
| A5 | Extension approvals, TOFU pins, enablement | gate for capability use and updates | `<files>/extensions/state.json` (approvals, pins, system disables); user enablement in settings (`extensions.disabled`) |
| A6 | Registry root public key | root of all registry trust | APK, `extensions.registries` default |
| A7 | Publisher private keys | ability to ship updates as a publisher | author machines (never on device unless author is on device) |
| A8 | Installed extension payloads (grammars, themes, WASM modules) | loaded by the app process | global and per-env extension dirs; env ones bind-mounted at `/opt/easyide/extensions/<id>` |
| A9 | Settings (all layers), keybindings, profiles | drive which commands and servers run | app store, env/project `settings.json`, user files |
| A10 | App availability and device resources | memory, battery, UI responsiveness | app process, sandbox processes |
| A11 | UI integrity | the user must be able to trust prompts, publisher names, URLs | Compose UI |
| A12 | Clipboard contents | often holds passwords/tokens | Android clipboard |
| A13 | Other projects in the same environment | 0005: envs are shared across projects | env rootfs, other project dirs |

## 3. Trust boundaries

```
  UNTRUSTED NETWORK                        | DEVICE
                                           |
  Registry index repo --(B3 https,sig)-----+--> RegistryClient/SignatureVerifier --+
  Package hosts -------(B3 https,sha256)---+-->                                    |
  Open VSX ------------(B3 https,sha256)---+-->                                    |
  Package mirrors <----(sandbox, any host)-+-----------------------+               |
                                           |                       |               v
                           +---------------+-----------------------+-------------------+
                           | APP PROCESS (trusted code: L0, :lsp, :extensions, UI)     |
                           |                                                           |
                           |   ContributionRegistry <--(B4 ext<->ext: ids, commands,   |
                           |   ActionRunner              settings, executeCommand)     |
                           |        |                                                  |
                           |        |            +===========================+         |
                           |        +--(B2)----->| WASM guest (Chicory)       |        |
                           |   capability table  | only import: host_call     |        |
                           |                     +===========================+         |
                           |   SettingsLayers <--(B5 project .easyide/*.json from repos)|
                           |   LSP client     <--(B6 LSP server output, JSON-RPC)       |
                           +------+------------------------------+--------------------+
                                  | (B1 app<->sandbox)            ^ stdio, files,
                                  | spawn argv, env, PTY          | shared app-uid data dir
                           +------v------------------------------+--------------------+
                           | SANDBOX (proot / chroot+BusyBox) - SAME UID, NO BOUNDARY  |
                           | language servers, install steps, shells, Claude Code CLI  |
                           +-----------------------------------------------------------+
  ==== enforced boundary     ---- boundary we validate at but cannot enforce against
```

B1 is drawn as a boundary because the app must **validate what it reads from the sandbox**
(outputs, files, server messages) - not because it keeps sandbox code contained.

## 4. Attacker profiles

| # | Profile | Capability | Goal |
|---|---|---|---|
| P1 | **Malicious extension** (published or sideloaded) | ships a manifest, grammars, themes, WASM, install steps, server commands; gets whatever the user approves | steal A1-A4, persist, mine, spoof UI |
| P2 | **Compromised publisher key** | signs a valid-looking update for an existing, trusted extension | push P1 behaviour to existing users via update |
| P3 | **Malicious repository** cloned by the user, containing `.easyide/settings.json`, `.easyide/tasks.json`, crafted files | only data the app and servers read; no install, no approval | get a command executed in the sandbox or in-app without a prompt; DoS |
| P4 | **Network MITM** (hostile Wi-Fi, captive portal, compromised CDN) | read/modify traffic between app and registry/package hosts; not the sandbox's own traffic's endpoints | substitute packages, freeze or roll back the index, suppress revocations |
| P5 | Compromised upstream toolchain (npm/pip package pulled by an install step, or a malicious language server release) | code execution in the sandbox during install or server run | same as P1 inside the sandbox |
| P6 | Malicious/compromised language server output (P1, P5 or a buggy server) | arbitrary JSON-RPC to the client | in-app effects: edits, file opens, URL opens, UI spoofing, DoS |

Out of scope: physical attacker with an unlocked device, rooted device with other malicious
apps, a malicious easyIDE app build, Android OS compromise.

## 5. STRIDE per boundary

Legend: **E** = enforced by us, **D** = disclosure only, **V** = validation at the boundary,
**R** = residual (see sec 7).

### B1 - app <-> sandbox

| STRIDE | Threat | Mitigation | Type |
|---|---|---|---|
| S | A sandbox process impersonates a language server or rewrites a server binary (another pack's `npm -g` step replaces it) | none possible inside the sandbox; install steps shown verbatim; per-env scope makes blast radius one env | D, R |
| T | Sandbox code edits `state.json` (approvals, pins), settings layers, extension dirs, registry cache, `/opt/easyide/extensions/<id>` (bind is not read-only under proot) | load-time schema validation, recorded sha256 of WASM modules checked at instantiate (detects naive tampering only) | V, R |
| T | Sandbox writes project `.easyide/settings.json` to add a server command | same trust rule as a cloned repo (B5): project-layer command-bearing keys need project trust | V |
| R | No reliable attribution of which sandbox process did what | Extension Log records what the **app** launched (argv, env keys - not values, extension id) | V |
| I | Sandbox reads Claude Code credentials in the rootfs home, other projects' files (0005), `/proc/<pid>/environ` of a running `git` (0012) | 0012 keeps the git token to one process lifetime; nothing else possible | R |
| I | App leaks host env into sandbox | `SandboxShell`/`LinuxEnvironment` clear the inherited Android env, then add declared env only (0012 consequence); servers get clean env + declared `env` | E |
| I | Git token placed in a server or install-step env | never: no capability reaches `GitCredentials`; `ServerProcessFactory` builds env from config only | E |
| D | Server/install consumes RAM, CPU, disk; fork bombs | per-server and global memory budgets, kill order, `lsp.maxServers`, `extensions.actions.execTimeoutSec`; disk not bounded | E (memory), R (CPU/disk) |
| E | Sandbox code escalates to app-uid powers | it already has them (ground truth 1) | R |

### B2 - app <-> WASM guest

| STRIDE | Threat | Mitigation | Type |
|---|---|---|---|
| S | Guest calls `commands.register` for a command it did not declare | refused unless declared in its own `contributes.commands` | E |
| T | Guest passes out-of-bounds `ptr/len`, malformed length prefix, invalid UTF-8/JSON | host bounds-checks every read against guest memory size; `extensions.wasm.maxMessageKb`; parse errors -> `E_ARGS` | E |
| T | Guest edits files outside its grant | `fs.project(write)`; paths outside `/workspace` need `fs.outsideProject`; paths are normalised guest paths, `..` resolved before check | E |
| R | Guest actions not attributable | every `host_call` denial and every mutating call logged with extension id | E |
| I | Guest reads editor text, files, clipboard, secrets without grant | `fs.project(read)`, `clipboard`, `secrets.read` checked per call against declared AND approved set | E |
| I | Guest exfiltrates via `net.fetch` | https only, declared hosts only (suffix match `*.x`), redirects to undeclared hosts not followed, loopback/private addresses refused, `netMaxResponseKb` | E |
| I | Guest reads git token / Claude key | not reachable by any host function | E |
| I | Guest reaches sandbox via `sandbox.exec` | needs `sandbox.exec`; the spawned process is then unconstrained | E at call, D after |
| D | Infinite loop, memory blow-up, host-call storm, deep recursion | `fuelPerCall`, `maxMemoryMb`, `callTimeoutMs`/`activateTimeoutMs`, `maxHostCallsPerCall`; trap discards instance; `maxCrashes` in `crashWindowSec` disables | E |
| D | Re-entrant call: guest runs a command that calls back into the same instance | re-entry into a busy instance refused with `E_UNAVAILABLE` | E (lld/wasm-host.md sec 9.2) |
| E | Escape from Chicory into the JVM | JVM memory safety; no WASI imports; no reflection bridge; residual interpreter bugs | R |
| E | Guest uses `config.set` to raise its own powers | own keys only; others need `ui.settings`; protected keys never writable (sec 6, M-11) | E |

### B3 - app <-> registry, package hosts, Open VSX

| STRIDE | Threat | Mitigation | Type |
|---|---|---|---|
| S | MITM serves a fake index or fake package | `index.json.sig`, `revocations.json.sig` against pinned root key; entry signature against active publisher key; sha256 + size of package bytes | E |
| S | Compromised publisher key (P2) signs a malicious update | revocation list; TOFU pin `publisher -> keyId`, change only via `rotation` signed by old key; **no auto-update**; update sheet shows capability delta and changelog; index PRs reviewed by maintainers | E + human gate, R |
| S | Typosquatted publisher / display name ("easyIDE Official") | prompt shows publisher id and "registry publisher" vs "unsigned, local" / "Open VSX" in fixed chrome; maintainers review new publishers | D, R |
| T | Rollback/freeze: MITM replays an older signed index to hide a revocation or pin a vulnerable version | reject an index whose `generatedAt` is older than the last verified one; UI shows index age; staleness warning past a named threshold | E (rollback), R (freeze) |
| T | Zip slip, symlinks, nested archives, duplicate names, zip bomb | package rules in sdk-reference (relative paths, no `..`, no symlinks, case-insensitive dupes), `extensions.limits.*` (compressed, unpacked, per file), unpack to temp dir + rename | E |
| R | Publisher denies publishing a version | signed entry + git history of the index repo | E |
| I | Registry learns what the user browses/installs | static files from a git host; host sees IPs and fetched paths; no accounts, no telemetry | R (accepted) |
| D | Registry/host unreachable | offline cache of last verified index + packages | E |
| E | Open VSX `.vsix` brings code | only declarative subset read; `main`/`browser` ignored; labelled "not signed by an easyIDE-registry publisher"; Microsoft Marketplace never | E |
| E | Sideloaded `.easyext` | verified against `.sig` + TOFU pin when present, else "unsigned" warning, same capability sheet, never auto-updated | D |

### B4 - extension <-> extension

| STRIDE | Threat | Mitigation | Type |
|---|---|---|---|
| S | Extension B contributes a command/setting/view id already owned by A or by a built-in | registry refuses the colliding contribution (built-ins always win; first installed wins among extensions), conflict shown in Extension Log and inspector | E |
| S | Command titled like a built-in ("Git: Push") | palette and menus show the contributing extension; contribution inspector | D |
| T | B's keybinding overrides A's or a built-in's chord | user layer beats extensions; keymap UI shows conflicts and source | D |
| T | B writes A's settings via `setConfig` | own keys only; foreign keys need `ui.settings`; protected keys never (M-11) | E |
| I | B reads A's storage or secrets | per-extension namespaces in storage and secrets; no cross-namespace function | E |
| E | Confused deputy: B (no `sandbox.exec`) calls `executeCommand` on A's `sandboxExec` command | allowed by design (runs with A's approval); command args are **not** substitutable into A's shell strings (no args variable exists); cross-extension calls logged | E (no arg injection), R (timing) |
| E | Sandbox-scoped B tampers with A's server binaries in the same env | inherent to a shared env | R |

### B5 - user settings files in project repos (`.easyide/settings.json`, `tasks.json`)

| STRIDE | Threat | Mitigation | Type |
|---|---|---|---|
| E | Cloned repo defines `lsp.servers.<id>.command` / `env` (e.g. `LD_PRELOAD`, `NODE_OPTIONS`) so opening a file runs attacker code in the sandbox with no prompt | **project trust** (arch open question 2 -> "prompt once per project"): command-bearing keys from the project layer are ignored until the user trusts the project; the prompt shows the exact argv and env | E (lld/customization.md sec 12) |
| E | Repo sets `initializationOptions` that make a server load plugins from the repo (e.g. TS server plugins) | `initializationOptions` from the project layer treated as command-bearing (same trust gate) | E (lld/customization.md sec 12) |
| E | Repo overrides an extension setting interpolated into a shell string (`${config:python.interpreter}`) | extension-declared `scope` limits layers: `environment`-scoped keys are not read from the project layer; values substituted into shell strings are shell-quoted | E |
| E | Repo `tasks.json` defines a harmful task | tasks run only on explicit user action; the Run menu shows the command line; first run from an untrusted project asks | D + prompt |
| T | Repo sets `extensions.disabled`, `workbench.contributions.hidden`, `editor.defaultFormatter`, `files.associations` | allowed (P scope, low impact); settings rows show the winning layer and the file | D |
| T | Repo tries to set G-scope keys (`extensions.registries`, `extensions.safeMode`, `extensions.developerMode`, `openVsx.enabled`) | G-scope keys are ignored outside the user global layer; logged once | E |
| D | Huge/deeply nested JSON, pathological `when` regex in keybindings | file size and nesting caps at parse, regex evaluated with the same timeout as when-clauses | E |
| I | Settings bundle export leaks secrets | export excludes secrets (arch sec 5.5) | E |

### B6 - LSP server output -> app

| STRIDE | Threat | Mitigation | Type |
|---|---|---|---|
| S | `window/showMessage` / `showMessageRequest` mimics an easyIDE prompt | rendered in a server-labelled surface ("pyright says"), never as a system dialog | E |
| T | `workspace/applyEdit` or a code action edits files outside `/workspace` or host paths | `PathMapper` maps guest `file://` only; unmappable or host URIs rejected; env files read-only; outside-project edits shown in preview before apply | E |
| T | Rename/format produces a huge edit set | rename preview; edits applied as one undo unit | D |
| I | `window/showDocument` / document links / hover markdown with `https` or `file` links | external URIs go through the `openUrl` confirm sheet (full URL); markdown rendered without HTML and without remote image loads | E |
| D | Giant message, unbounded `Content-Length`, flood of notifications, deep JSON | per-message size cap and parse depth cap (named limits, lld/lsp-client.md), bounded queues, stale-version drop, `lsp.requestTimeoutMs` | E |
| D | Server never answers / hangs | per-method timeouts; `lsp.startupTimeoutSec`; supervisor kill | E |
| E | Server returns a `Command` that the client might run locally | server commands only ever go back to the server via `workspace/executeCommand`; never mapped to the local command registry | E |
| I | Server reads more of the project than the user expects | it runs in the sandbox; it can read everything in the env | R |

Other in-app parsers (not a boundary of their own, but reached by P1/P3 data):
extension TextMate grammars (regex ReDoS on the app's tokenizer thread), SVG/PNG icons,
theme JSON. Mitigations: tokenization is cancellable between lines today
(`DocumentHighlighter.tokenizeThrough(checkCancelled)`), but a single catastrophic regex
inside `tokenizeLine` is not; extension grammars need a per-line time limit or watchdog
(lld/extension-runtime.md), SVG rendered without external references and with size caps.

## 6. Mitigations mapped to mechanisms

| Id | Mitigation | Mechanism | Counters | Owner LLD |
|---|---|---|---|---|
| M-01 | Signed index + publisher-signed entries + sha256 | ed25519, RFC 8785 JCS, root key in APK | P2 (partly), P4 | registry-and-install |
| M-02 | TOFU publisher pin + signed rotation | `state.json` pins | P2, P4 | registry-and-install |
| M-03 | Revocation list, disable-on-refresh | `revocations.json` | P1, P2 | registry-and-install |
| M-04 | Index rollback check + age display | last `generatedAt` | P4 | registry-and-install |
| M-05 | No silent install/update; capability delta re-approval | capability sheet, `extensions.autoCheckUpdates` notify-only | P1, P2 | registry-and-install |
| M-06 | Verbatim "what will run" sheet + "not contained" copy | install steps, server argv shown before approval | P1, P5 | registry-and-install |
| M-07 | Load-time capability consistency (L1) | `ManifestParser` + `CapabilityTable` | P1 (honest manifests only) | extension-runtime |
| M-08 | Per-call capability enforcement (L2) | `host_call` dispatch table | P1 | wasm-host |
| M-09 | WASM resource limits | `extensions.wasm.*` keys | P1 | wasm-host |
| M-10 | Package structure + size rules | installer + `easyide-ext validate` | P1, P4 | registry-and-install, cli |
| M-11 | Security-relevant settings keys never writable by extensions, even with `ui.settings`: `extensions.*`, `lsp.servers`, `profiles.active` (and keybinding files) | `setConfig` / `config.set` deny-list | P1 | customization |
| M-12 | Project trust for command-bearing project keys | trust prompt once per project | P3 | customization |
| M-13 | Scope-limited layers (G keys user-only; extension `scope`) | `SettingsResolver` | P3 | customization |
| M-14 | Clean env for servers and actions; no secret in any env | `ServerProcessFactory`, `SandboxShell` clear() | P1, P5 | lsp-client |
| M-15 | Git token only in one git process (0012); no capability reaches it | `GitCredentials` not injected into `:extensions` | P1, P5 | - (0012) |
| M-16 | Path mapping rejects non-guest URIs; env files read-only | `PathMapper` | P6 | lsp-client |
| M-17 | LSP message/queue/timeout caps; stale drop | `JsonRpcConnection`, `DocumentSync` | P6 | lsp-client |
| M-18 | `openUrl` confirm sheet for every external URL | UI | P1, P6 | extension-runtime |
| M-19 | Contribution id collision refusal + source labels | `ContributionRegistry`, palette, inspector | P1 | extension-runtime |
| M-20 | Safe mode (manual, launcher, auto after 2 activation crashes) | `ActivationManager` | P1 | customization |
| M-21 | Memory budgets and kill order | `MemoryPolicy`, `ServerSupervisor` | P1, P5, P6 | lsp-client |
| M-22 | Grammar tokenization time limit | tokenizer watchdog | P1, P3 | extension-runtime |
| M-23 | WASM module sha256 check at instantiate | recorded at install | tampering (naive) | wasm-host |

## 7. Residual risks (stated honestly)

| # | Residual risk | Why it remains | Accepted because / next step |
|---|---|---|---|
| R1 | Any approved L1 pack with `sandbox.exec`/`sandbox.install`/`lsp.spawn`, and any server it installs, can read and modify everything in its environment and every project bound into it | proot/chroot+BusyBox are not isolation (0002) | disclosure copy on every such prompt; chroot with real uids is the only path to more, and is not this feature |
| R2 | Code in the sandbox can tamper with app data (approvals, pins, settings, extension payloads, registry cache) and possibly use the app's Keystore keys | same uid as the app | signing protects **delivery**, not local state after compromise; sha256 checks catch accidents only |
| R3 | Claude Code credentials live in the rootfs home and are readable by every sandbox process (including language servers and install steps). arch sec 9 now scopes "never readable" to in-app layers (L0, L1 host, L2) | Claude Code CLI runs in the sandbox and stores its own credentials there | state this in the capability prompt copy for sandbox capabilities |
| R4 | Git token readable via `/proc/<pid>/environ` during a `git` invocation | 0012 consequence | narrow window by design; chroot backend could close it |
| R5 | P2 before revocation: a compromised key can ship an update with the **same** capabilities; the user sees a normal update sheet | no auto-update, but users tap "update" | changelog + diff shown; maintainers' PR review; revocation latency = index refresh cadence |
| R6 | Index freeze by a persistent MITM or a dead registry host | client cannot distinguish "no updates" from "withheld" | index age shown; staleness warning |
| R7 | Chicory interpreter bugs could let a guest break the capability boundary | third-party runtime, unaudited | pin versions, fuzz host ABI (ST-18), follow upstream releases; ADR-F records it |
| R8 | CPU and disk exhaustion by sandbox processes | no cgroups/quotas under proot | server kill on budget; user can stop env |
| R9 | Confused-deputy triggering of another extension's approved command at unexpected times | `executeCommand` is intentionally composable | no argument injection; logged |
| R10 | Shared environment: one pack's install step can replace another pack's server binaries | 0005 env sharing | per-env scope; verify step re-run on demand |
| R11 | Language servers see the whole environment and can phone home | sandbox networking is unrestricted; `network(...)` is disclosure only under proot | disclosure |
| R12 | Registry host sees client IPs and fetch patterns | static hosting | no accounts, no telemetry |

## 8. Security test cases

IDs are local to this file; test-plan.md maps them to layers and milestones.

| Id | Boundary | Case | Expected |
|---|---|---|---|
| ST-01 | B3 | package bytes altered by 1 bit | sha256 mismatch, hard fail, no "install anyway" |
| ST-02 | B3 | `index.json` altered, `.sig` unchanged | root signature fail, previous verified index kept |
| ST-03 | B3 | entry signed by revoked publisher key | refused; installed copy disabled on next refresh, user notified, not uninstalled |
| ST-04 | B3 | publisher keyId changes without `rotation` | TOFU pin mismatch, hard fail |
| ST-05 | B3 | valid `rotation` signed by old key | accepted, pin updated |
| ST-06 | B3 | older signed index replayed (`generatedAt` < last) | rejected, age shown |
| ST-07 | B3 | zip with `../x`, absolute path, symlink, nested archive, case-dup names | each rejected by validate and at install |
| ST-08 | B3 | zip bomb over `extensions.limits.unpackedMb` / `fileMb` | aborted, temp dir removed |
| ST-09 | B3 | update adding `sandbox.exec` | new approval required; declining keeps old version |
| ST-10 | B3 | `.vsix` with `main` code | installed as "partial", code ignored, compatibility report shown |
| ST-11 | B1 | install `verify` exits non-zero | version dir deleted, previous `current` intact |
| ST-12 | B1 | server env inspection (`env` inside server) | only launcher defaults + declared `env`; no Android env, no token |
| ST-13 | B1/B2 | grep all process envs and WASM-visible data during a `git fetch` | token absent everywhere except the one `git` process |
| ST-14 | B2 | guest calls each host function without its capability | `E_CAPABILITY`, logged, no side effect |
| ST-15 | B2 | infinite loop / memory grow past cap / host-call storm | trap with `E_LIMIT`/`E_TIMEOUT`, instance discarded, UI responsive |
| ST-16 | B2 | `maxCrashes` traps within `crashWindowSec` | extension disabled until user re-enables |
| ST-17 | B2 | out-of-bounds ptr, bad length prefix, invalid UTF-8, oversize message | `E_ARGS`, host unaffected |
| ST-18 | B2 | ABI fuzzing of `host_call` payloads (JSON structure fuzz) | no host exception escapes; no capability bypass |
| ST-19 | B2 | `net.fetch` to undeclared host, http, redirect to undeclared host, `127.0.0.1`, private range via DNS | all refused |
| ST-20 | B2 | `fs.write` to `/workspace/../x`, `/etc/passwd` without `fs.outsideProject` | refused |
| ST-21 | B2 | re-entrant `commands.execute` into the same busy instance | `E_UNAVAILABLE`, no deadlock |
| ST-22 | B4 | extension contributes built-in command id / another extension's id | refused, conflict logged |
| ST-23 | B4 | `setConfig` on `extensions.*`, `lsp.servers`, other extension's key, with and without `ui.settings` | protected keys always refused; foreign keys need `ui.settings` |
| ST-24 | B4 | read another extension's storage/secret namespace | not addressable |
| ST-25 | B5 | cloned repo with `lsp.servers.x.command`, `env.LD_PRELOAD`, `initializationOptions` plugin paths | nothing spawned until project trusted; prompt shows argv/env |
| ST-26 | B5 | repo sets G-scope keys and an `environment`-scoped extension key | ignored at project layer, logged once |
| ST-27 | B5 | 50 MB / 10k-depth `settings.json` | parse capped, layer ignored, app responsive |
| ST-28 | B6 | server `applyEdit` to `file:///data/...` host path and to env file | rejected / read-only |
| ST-29 | B6 | `Content-Length: 2147483647`, notification flood, 10k-depth JSON | connection closed or capped, server restarted, UI responsive |
| ST-30 | B6 | hover markdown with raw HTML, remote image, `javascript:` link | HTML not rendered, no fetch, link refused or via confirm sheet |
| ST-31 | B6 | `window/showDocument` external URI | confirm sheet with full URL |
| ST-32 | parsers | extension grammar with catastrophic regex on a crafted line | tokenization aborted for that file, notice, editor responsive |
| ST-33 | UI | display name "easyIDE (official)", 10k-char description, RTL override chars | prompt chrome shows publisher id + source label, strings truncated and bidi-sanitised |
| ST-34 | safe mode | 2 consecutive activation crashes | next start in safe mode, all non-built-in extensions off |
| ST-35 | copy | every prompt for `sandbox.*`, `lsp.spawn`, `network` | contains the "not contained" wording; no copy claims isolation (lint over string resources) |

## 9. Deviations and open points

- Resolved: arch.md sec 9 now limits the Claude-key claim to in-app layers (R3). M-11, M-12,
  M-22, M-04 (with staleness warning), `net.fetch` private-address refusal and WASM
  re-entrancy refusal are adopted by their owner LLDs; their limits are sdk-reference
  settings keys or "Fixed limits" policy constants (`LspPolicy.MAX_MESSAGE_BYTES` /
  `MAX_JSON_DEPTH`, `SettingsPolicy.MAX_FILE_BYTES`, `RegistryPolicy.staleIndexWarnDays`,
  `ExtensionPolicy.GRAMMAR_LINE_TIME_LIMIT_MS`) - constants because no settings layer may relax them.
- arch open question 8 (per-invocation confirm for `sandboxExec`) is left open; this model
  does not depend on it because install-time approval already grants full env access (R1).
