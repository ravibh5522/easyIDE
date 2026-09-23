# Extension SDK - High-Level Design

One-page-per-concern view of how the language core, extension runtime, WASM host and
registry fit into the existing app: modules, processes, data, scenarios, NFRs, failure modes.

Status: PROPOSED (2026-09-23). Nothing here is implemented.
Sources of truth: [arch.md](arch.md) (goals, catalogue, design) and
[sdk-reference.md](sdk-reference.md) (contract). This file never overrides them; conflicts
found while writing it are listed in [Deviations](#15-deviations-and-conflicts-found).
Security view: [threat-model.md](threat-model.md). Component detail: [LLD link map](#14-lld-link-map).

## 1. Scope

Covers the whole of arch.md sec 5 at system level: 5.1 No-server (L0), 5.2 LSP,
5.3 Extension capabilities (L1/L2), 5.4 Ecosystem, 5.5 Customization. Out of scope as in
arch.md sec 2: L3 Node host (M8, conditional), L4 webviews, DAP, any backend service.

## 2. System context

```
                         +--------------------------------------------+
   +--------+  touch,    |  Android device                            |
   |  User  |--keyboard->|                                            |
   +--------+  stylus    |  +--------------------------------------+  |
       ^                 |  | easyIDE app process (uid u0_aNNN)    |  |
       | prompts,        |  |                                      |  |
       | editor, logs    |  |  Compose UI  <->  L0 core / :lsp     |  |
       +-----------------+--|              <->  :extensions (L1)   |  |
                         |  |              <->  :ext-wasm (L2) ----+--+--> WASM guest
                         |  |                    Chicory interp.   |  |    (in-process,
                         |  |  Keystore (via binder) <- GitCredentials |    capability-gated)
                         |  +----+-------------------+-------------+--+
                         |       | stdio pipes        | PTY          |
                         |       | (LSP JSON-RPC)     | (terminal)   |
                         |  +----v-------------------v-----------+  |
                         |  | Sandbox environment(s)             |  |
                         |  | proot (default) / chroot+BusyBox   |  |
                         |  | language servers, toolchains,      |  |
                         |  | install steps, user shells,        |  |
                         |  | Claude Code CLI      NO ISOLATION  |  |
                         |  +------------------+-----------------+  |
                         +---------------------|--------------------+
                                  https        |  apt / npm / pip / go (install steps)
          +------------------------+           |
          | Registry index repo    |<----------+------------ app RegistryClient (https)
          | (static git host:      |           |
          |  index.json, sigs,     |           v
          |  publishers, revoc.)   |     Package mirrors (pypi, npm, ...)
          +------------------------+
          +------------------------+
          | Open VSX (optional,    |<----- app, only if extensions.openVsx.enabled
          | declarative subset)    |
          +------------------------+
          +------------------------+
          | Package hosts (entry   |<----- app downloads .easyext by entry.url
          | .url, e.g. GH releases)|
          +------------------------+
   Author workstation / env: tools/easyide-ext --(git push, PR)--> Registry index repo
                                              --(adb push, dev)--> app dev inbox
```

Actors: **User**; **extension author** (CLI, registry PR); **registry maintainers** (merge
index PRs, hold the offline root key - arch.md open question 4); **upstream package
mirrors** reached by sandbox install steps, not by the app.

## 3. Container / module decomposition

### 3.1 Existing (verified in `services/mobile/settings.gradle.kts`)

| Module | Holds today | Role in this design |
|---|---|---|
| `:app` | Compose UI, `AppContainer`, `UiPreferences`, `WorkspaceViewModel`, `EditorPane`, `syntax/` (`TextMateHighlighter`, `DocumentHighlighter`, `GrammarIndex`, `ScopeRules`), `TerminalKeyRow` | composition root; all UI; adapters that implement ports declared by `:lsp`/`:extensions` |
| `:sandbox-runtime` | `SandboxLauncher` (`ProotLauncher`, `ChrootLauncher`), `LaunchRequest`/`LaunchSpec`, `ShellRunner`, `SandboxShell`, `PtyShellParams`, `TerminalProcess`, `LinuxEnvironment`, `EnvironmentManager`, `SandboxPaths`, `SandboxStore`, `ProjectFileWatcher`, `GitCredentials`, `SandboxForegroundService` | gains `ServerProcessFactory` (non-pty stdio spawn, clean env) beside `ShellRunner` |
| `:terminal-emulator`, `:terminal-view` | vendored Termux PTY + view (0010-pty) | unchanged; install steps and `runInTerminal` stream into terminal tabs |

### 3.2 Proposed

| Module / path | Kind | Holds (arch.md sec 6.2) | Android deps |
|---|---|---|---|
| `:lsp` (`services/mobile/lsp`) | Kotlin JVM library | `JsonRpcConnection`, `LspSession`, `DocumentSync`, `PathMapper` (interface), `ServerSupervisor`, `MemoryPolicy` | none (JVM-testable) |
| `:extensions` (`services/mobile/extensions`) | Kotlin library | `ManifestParser`, `ExtensionStore`, `ContributionRegistry`, `ActivationManager`, `ActionRunner`, `WhenEvaluator`, `CapabilityTable`, `RegistryClient`, `SignatureVerifier`, `SettingsResolver` adapters | none in core packages; Android only behind ports |
| `:ext-wasm` (`services/mobile/ext-wasm`) | Kotlin library | `WasmHost`, ABI v1 codec, limits, host-function dispatch table | none; isolates the Chicory dependency |
| `services/shared/extension-schema/` | JSON Schema + test vectors (Apache-2.0, ADR-G) | manifest schema, when-clause grammar vectors, index/revocation schemas | n/a |
| `tools/easyide-ext/` | JVM CLI jar (Apache-2.0, ADR-G) | init, validate, package, sign, publish, test, dev | n/a |
| `app/.../ui/screens/workspace/lsp/` | package in `:app` | decorations, completion popup, hover card, Problems, Outline, lightbulb | Compose |
| `app/.../ui/screens/extensions/` | package in `:app` | browse, detail, capability prompt, Extension Log, contribution inspector | Compose |
| `easyide-extensions-index` | separate git repo | signed static index | n/a |

## 4. Module dependency rules

```
                 :app
       +--------+--+------+-----------+
       v        v         v           v
     :lsp   :extensions  :ext-wasm  :terminal-view --> :terminal-emulator
       ^        ^  |          |
       |        |  | (schema) +--> :extensions (ports, ActionRunner, CapabilityTable)
       |        |  v
       |        | services/shared/extension-schema  <---- tools/easyide-ext (build input)
       |        |
     (ports only; no module edge)
       :sandbox-runtime  <---- :app  (adapters: ServerProcessFactory -> :lsp port)
```

Rules (enforced by Gradle project edges; a CI check greps for forbidden imports):

1. `:app` may depend on every module. Nothing depends on `:app`.
2. `:lsp` depends on no project module. It declares ports (`ServerLauncher`, `PathMapper`,
   `SettingsQuery`, `MemoryProbe`) that `:app` implements with `:sandbox-runtime` types.
3. `:extensions` may depend on `extension-schema` (resources) only. It does **not** depend
   on `:lsp`; server contributions leave it as plain data (`ServerDefinition`) that `:app`
   hands to `ServerSupervisor`. The `lspRequest` action goes through a `LspPort`.
4. `:ext-wasm` may depend on `:extensions` (to reuse `ActionRunner` and `CapabilityTable`).
   `:extensions` never depends on `:ext-wasm`; it sees WASM through a `LogicHost` port.
5. `:sandbox-runtime` depends on no new module (keeps its role as the process/filesystem
   layer; no LSP or extension knowledge).
6. `tools/easyide-ext` consumes `extension-schema` as a build-time input and never links
   `:extensions` (license separation: app code is PolyForm NC per 0008, CLI is Apache-2.0).
7. No cycles. Adding an edge not in this diagram needs an update here and in the LLD.

## 5. Key interfaces between modules

One line each; signatures and semantics in the named LLD.

| Interface | Provider -> consumer | Purpose | LLD |
|---|---|---|---|
| `ServerLauncher` | `:app` (via `ServerProcessFactory`) -> `:lsp` | spawn guest argv in env E, cwd `/workspace`, clean env; return stdio streams + pid | lsp-client |
| `PathMapper` | `:app` (from `SandboxPaths`) -> `:lsp` | host file <-> guest `file://` URI, read-only marking for env files | lsp-client |
| `LspClient` (`ServerSupervisor` facade) | `:lsp` -> `:app` UI, `LspPort` | open/change/close docs, typed feature requests, state flow per server key | lsp-client |
| `DiagnosticsSink` | `:lsp` -> `:app` | versioned diagnostics per document for decorations/Problems | lsp-client |
| `MemoryProbe` | `:app` -> `:lsp` | RSS of a process tree, `onTrimMemory` level stream | lsp-client |
| `SettingsQuery` | `:app` (`SettingsStore` + `SettingsResolver`) -> `:lsp`, `:extensions`, `:ext-wasm` | resolve `(key, lang?, envId, projectId)`; change stream | customization |
| `ContributionSink` | `:app` -> `ContributionRegistry` | registers commands, keymap entries, menus, settings schema, themes, grammars, stages into Pillar 4/5 registries | extension-runtime |
| `ActionRunner` | `:extensions` -> command registry, `:ext-wasm` | execute L1 action vocabulary with variable substitution and capability check | extension-runtime |
| `WhenEvaluator` + `ContextKeys` | `:extensions` / `:app` provides keys | parse once, evaluate against live context-key snapshot | extension-runtime |
| `HostServices` ports (`EditorPort`, `SandboxPort`, `UiPort`, `LspPort`, `ClipboardPort`) | `:app` -> `:extensions` | everything an action or host function touches outside `:extensions` | extension-runtime |
| `LogicHost` | `:ext-wasm` -> `:extensions` | activate/deactivate/handle for L2 extensions | wasm-host |
| `CapabilityTable` | `:extensions` -> `:ext-wasm`, `ActionRunner` | declared AND approved capability set per extension | extension-runtime |
| `ExtensionStore` | `:extensions` -> `:app` | installed versions, `current` flip, enablement, approvals, pins | registry-and-install |
| `RegistryClient` + `SignatureVerifier` | `:extensions` -> `:app` | index fetch/verify, download, sha256/ed25519, revocation, TOFU | registry-and-install |
| `InstallStepRunner` | `:app` (terminal tab + `LinuxEnvironment`) -> installer | run `easyide.sandbox.install[]` visibly, report exit codes | registry-and-install |
| `SettingsLayers` | customization -> `SettingsQuery` | per-layer file load/validate/watch, `[lang]` blocks, profiles, safe mode | customization |
| Manifest JSON Schema | `extension-schema` -> `:extensions`, CLI | one schema for app and CLI | cli |

## 6. Runtime process and thread model

```
App process (Android, one uid)
  main thread ........ Compose, state apply only (< 4 ms per LSP response, arch sec 10)
  Dispatchers.Default  tokenization (existing EditorPane/DocumentHighlighter pass),
                       completion merge + fuzzy filter, when-clause snapshots
  Dispatchers.IO ..... file I/O, zip unpack, registry https, pipe reads/writes
  supervisorScope "lsp"   (IO.limitedParallelism(1) coordinator)
      ServerSupervisor state machine, budget checks, kill order
      per session: reader coroutine, writer coroutine, stderr drain -> Extension Log ring
      memory sampler ticker (MemoryPolicy sample interval)
  "extensions" coordinator (Default.limitedParallelism(1))
      ActivationManager, ContributionRegistry mutations, safe-mode counter
  "install" (IO.limitedParallelism(1))  one install/update/rollback at a time
  WASM: one dedicated worker thread per live L2 instance (sdk-reference WASM host API),
        watchdog on a shared scheduler for call/activate timeouts
  SandboxForegroundService keeps the process foreground while servers/installs run

Sandbox (child processes of the app, same uid)
  per server key (envId, projectId, serverId): one proot (or chroot) process tree,
      stdio pipes to the app, no PTY
  per terminal tab: PTY shell (existing PtyShellParams / TerminalProcess)
  install steps: run in a visible terminal tab, never silently
```

Rules: nothing blocks the main thread on I/O or a lock; every cross-module callback into UI
state is posted to main; coordinators are single-threaded so their state needs no locks.

## 7. Data stores and ownership

| Store | Location | Owner (sole writer) | Readers | Notes |
|---|---|---|---|---|
| UI prefs DataStore (`ui_preferences`) | app DataStore | `UiPreferences` today; `SettingsStore` (Pillar 5) after M0 | settings UI | becomes the **user global** layer |
| Sandbox state DataStore | `SandboxStore` (`PersistedState`) | `EnvironmentManager`, `ProjectManager` | everything needing env/project ids | unchanged |
| Env settings | `<files>/environments/<envId>/easyide/settings.json` | customization (`SettingsLayers`) | `SettingsQuery` | environment layer |
| Project settings, tasks | `<files>/projects/<projectId>/.easyide/{settings,tasks}.json` | the user (and anything in the sandbox - it is bind-mounted) | `SettingsLayers` | travels with git clones: **untrusted input**, see threat-model |
| User keybindings, snippets, profiles | app files dir (paths fixed in lld/customization.md) | customization | keymap, snippet engine | exported/imported as one bundle, secrets excluded |
| Global extensions | `<files>/extensions/global/<id>/<version>/` + `current` link | `ExtensionStore` | `ContributionRegistry`, `WasmHost` | themes, snippets, grammars, WASM-only |
| Env extensions | `<files>/environments/<envId>/extensions/<id>/<version>/` | `ExtensionStore` | same + guest via `/opt/easyide/extensions/<id>` bind | packs with `sandbox`/`languageServers` |
| Package cache | `<files>/extensions/cache/<sha256>.easyext` | `RegistryClient` | installer | content-addressed, offline install |
| Extension state | `<files>/extensions/state.json` | `ExtensionStore` | all of `:extensions` | approvals (exact capability sets), TOFU pins, system disables (revoked, crash-loop); user enablement is `extensions.disabled` |
| Parsed manifest index | under `<files>/extensions/` (name in LLD) | `ContributionRegistry` | startup | avoids zip reads; < 20 ms for 20 extensions |
| Registry cache | per registry id under `<files>/extensions/` | `RegistryClient` | browse, installer | last verified index + sigs, `generatedAt` |
| Extension storage | per extension, per scope | `WasmHost` storage functions | owning extension only | quota `extensions.storage.quotaKb` |
| Keystore-backed secrets | `GitCredentials` file (AES-GCM, Keystore key); new per-extension secret namespace | `GitCredentials`; secrets service | git process env only (0012); owning extension (`secrets.read`) | git token never reachable by extensions |
| Rootfs + toolchains | `<files>/environments/<envId>/rootfs/` | sandbox processes | `PathMapper` (read-only view of env files) | servers installed here by packs |
| Extension Log | in-memory ring per source | log service in `:app` | Extension Log panel | size `LspPolicy.LOG_RING_LINES`; not persisted except developer-mode export |

## 8. Key end-to-end scenarios

### 8.1 Open a file -> highlight + LSP diagnostics

```
User    EditorPane/WVM   TextMate(L0)  Supervisor(:lsp)  ServerLauncher  Server(sandbox)
 |--open a.py-->|             |              |                 |              |
 |              |--highlight->|              |                 |              |
 |<--colours----|<--spans-----|  (Default, debounced, existing pass)          |
 |              |--didOpen(E,P,python)------>|                 |              |
 |              |             |   resolve config (SettingsQuery), budget check|
 |              |             |              |--spawn(argv,E)->|--proot exec->|
 |              |             |              |<--stdio,pid-----|              |
 |              |             |              |--initialize---------------------->|
 |              |             |              |<--capabilities---------------------|
 |              |             |              |--initialized, didChangeConfiguration->|
 |              |             |              |--didOpen(a.py, v1)---------------->|
 |              |             |              |<--publishDiagnostics(v1)-----------|
 |              |<--DiagnosticsSink(v1, mapped ranges)                        |
 |<--squiggles, gutter, Problems count (main thread apply only)               |
```
Warm target < 3 s, cold < 10 s (arch sec 2). Highlighting never waits for the server.

### 8.2 Install an extension (registry)

```
User   ExtensionsUI  RegistryClient  SigVerifier  ExtensionStore  InstallStepRunner  Registry
 |--Install->|            |              |              |               |            |
 |           |--resolve-->|--(cached or https) index.json + .sig------------------->|
 |           |            |--verify root key, publisher key, revocations, TOFU pin |
 |           |            |--download .easyext to cache (or cache hit by sha256)   |
 |           |            |--sha256 + size check                                   |
 |           |--schema validate, engines check, capability sheet (verbatim steps)  |
 |--Approve->|--record approval (exact capability set)---->|        |            |
 |           |--unpack temp dir, rename to <scope>/<id>/<version>/ |               |
 |           |--sandbox.install? ------------------------------------>|--terminal tab|
 |<--streamed step output in terminal------------------------------------------|
 |           |<--verify exit 0 (else delete version dir, keep current)-|           |
 |           |--flip current, register static contributions-->|                   |
 |<--contributions live (< 10 s from cache, excluding install steps)             |
```

### 8.3 Run a contributed command (L1)

```
User  Button/Keymap  CommandRegistry  ActionRunner  WhenEvaluator  SandboxPort  Terminal
 |--tap Run->|           |               |              |             |           |
 |           |--dispatch>|--enablement?-------------->|              |           |
 |           |           |--run(python.runFile)------>|              |           |
 |           |           |               |--substitute ${config:...} ${file} (shell-quoted)
 |           |           |               |--capability sandbox.exec approved? (load-time)|
 |           |           |               |--runInTerminal------------>|--PTY tab-->|
 |<--output in "Python" terminal tab---------------------------------------------|
 failure: step aborts sequence -> snackbar with command title -> full error in Extension Log
```

### 8.4 WASM provider call (L2)

```
UI/event  CommandRegistry  WasmHost(:ext-wasm)  Guest   CapabilityTable  ActionRunner/ports
  |--cmd-------->|--handle(ext, msg)->|            |           |              |
  |              |                    |--alloc, write msg, ext_handle (worker thread)
  |              |                    |<--host_call{"fn":"editor.getText"}   |
  |              |                    |--check fn vs declared+approved------>|
  |              |                    |<--allowed----------------------------|
  |              |                    |--EditorPort.getText------------------------->|
  |              |                    |--write [len][json] via guest alloc  |        |
  |              |                    |<--response ptr (free by receiver)   |        |
  |<--result / UI effect--------------|  fuel, memory cap, timeout -> trap, discard instance
```

### 8.5 Settings change propagation

```
Writer (UI row / JSON file / setConfig)  SettingsLayers  SettingsResolver  Subscribers
   |--write layer file or store--------------->|              |               |
   |           (file watch for project/env files)|--invalidate layer cache-->|  |
   |                                             |              |--change(keys)->|
   |   Subscribers: editor (tab size etc.), theme, keymap (`when` re-eval),
   |   ContributionRegistry (hidden/order), ServerSupervisor:
   |     changed settingsSection -> didChangeConfiguration to affected sessions
   |     changed lsp.servers.<id>.{command,args,env} -> restart that session
   |     project-layer lsp.servers command change -> trust prompt first (threat-model)
```

### 8.6 Low-memory event

```
Android  EasyIdeApplication  MemoryProbe  ServerSupervisor        WasmHost     UI
  |--onTrimMemory(RUNNING_LOW+)->|---level-->|                        |          |
  |                              |           |--kill order (MemoryPolicy):       |
  |                              |           |  1 IDLE servers, LRU first        |
  |                              |           |  2 RUNNING, no visible editor     |
  |                              |           |  3 ------------------------>idle WASM deactivate
  |                              |           |  4 focused editor's server        |
  |                              |           |--shutdown/exit, SIGKILL after grace
  |                              |           |--state STOPPED "paused (memory)"-------->|
  terminal sessions and editor buffers are never touched; servers restart lazily on next need
```
Same path runs when the sampler sees the global sum over `lsp.globalMemoryBudgetMb`
(eviction < 2 s after breach). `onTrimMemory` is not hooked anywhere today; M2 adds it.

## 9. Non-functional requirements

Targets from arch.md sec 2 and sec 10; all unmeasured, reference device Xiaomi Pad 6 (8 GB).

| Area | Requirement | Target | Knob / source |
|---|---|---|---|
| Perf | first diagnostics, warm env | < 3 s | arch sec 2 |
| Perf | first diagnostics, cold | < 10 s | arch sec 2 |
| Perf | completion popup after response | < 150 ms p95 | `editor.quickSuggestionsDelay`, arch sec 2 |
| Perf | keystroke-to-glyph with LSP on | no regression vs off, 400 KB file | arch sec 2 |
| Perf | main-thread work per LSP response | < 4 ms | arch sec 10 |
| Perf | `didChange` flush | < 2 ms for 400 KB | `lsp.didChangeDebounceMs` |
| Memory | LSP RSS within budget | 100% of the time; eviction < 2 s after breach | `lsp.globalMemoryBudgetMb`, per-server `memoryBudgetMb` |
| Memory | concurrent servers | 3, LRU beyond | `lsp.maxServers` |
| Memory | WASM per instance | manifest-declared, capped | `extensions.wasm.maxMemoryMb` |
| Startup | cold start with 20 declarative extensions | < +50 ms vs none | arch sec 2 |
| Startup | manifest index load | < 20 ms for 20 extensions | arch sec 10 |
| Offline | install from cache, no network | < 10 s excl. install steps | arch sec 2, G6 |
| Offline | L0 features (5.1) | fully functional, no server, no network | G1 |
| Reliability | safe mode after bad extension | 100%, auto after 2 activation crashes | arch sec 5.5 |
| Reliability | server crash | backoff, then FAILED with stderr tail | `lsp.restart.maxRetries`, `lsp.restart.backoffMs` |
| Reliability | WASM trap | call fails, instance discarded; N traps disables | `extensions.wasm.maxCrashes`, `crashWindowSec` |
| Accessibility | every contribution reachable by touch and keyboard; popups/hover have TalkBack labels; diagnostics not colour-only (gutter icon + text) | acceptance tests per milestone | ux-overhaul Pillar 3/4 |
| Security | verified installs; no silent install/update; capability delta prompts | hard fail on any verification error | arch sec 9, threat-model |
| Security | git token, Claude key never reachable via any capability | 0 paths | 0012, arch sec 9 |
| Authoring | theme/snippet pack scaffold -> installed | < 15 min, zero code | arch sec 2 |

## 10. Deployment and packaging

| Ships in the APK | Downloaded / produced at runtime |
|---|---|
| `:app`, `:lsp`, `:extensions`, `:ext-wasm` (+ Chicory jar) | registry index, publisher files, revocations (from the index repo) |
| 229 bundled TextMate grammars + generated `language-configuration.json` (0010, `tools/build-grammars.py`) | `.easyext` packages (entry `url`), cached by sha256 |
| built-in themes, built-in commands/keymap/settings schema | language servers and toolchains, installed **into environments** by pack install steps (apt/npm/pip/go/rustup) |
| manifest JSON Schema copy from `extension-schema` | rootfs images (existing, 0007) |
| registry root public key(s) in `extensions.registries` default | Open VSX `.vsix` (opt-in, declarative subset) |
| no language server binaries | dev extensions pushed by `easyide-ext dev` |

First-party language packs are real `.easyext` packages from M3 (arch.md M3 exit criteria).
Whether the APK also seeds them into the offline cache is an open issue (sec 13).
`easyide-ext` ships as a jar and is preinstalled in easyIDE environments (sdk-reference CLI).

## 11. Observability

- **Local only. No telemetry**, no crash upload, no usage pings (no backend exists by design).
- Extension Log panel: per-extension and per-server rings (server stderr, activation
  failures, action errors, capability denials, WASM traps, deprecation warnings); size
  `LspPolicy.LOG_RING_LINES`; verbosity raised by `extensions.developerMode`.
- `lsp.trace` (`off`, `messages`, `verbose`) records JSON-RPC traffic into the same ring.
- Status bar server item: `lspState:<lang>` (`off`, `starting`, `ready`, `crashed`,
  `overBudget`) with restart and "show log".
- Contribution inspector: which extension contributed each button/key/setting, and which
  user override hides it; Settings rows show the winning layer.
- logcat: tagged, no file contents, no secrets, no tokens (existing `TAG` convention).
- Export: user may share the Extension Log via SAF; nothing leaves the device otherwise.

## 12. Failure modes and degradation matrix

| Failure | Detected by | Degrades to | User sees | Recovery |
|---|---|---|---|---|
| Server binary missing | `requires` probe / spawn fail | L0 features only for that language | status item "Install toolchain" | run pack install |
| Server crash | pipe EOF / exit code | L0 only while BACKOFF | "restarting" then FAILED + stderr tail | auto backoff, then manual restart |
| Server `initialize` timeout | `lsp.startupTimeoutSec` | L0 only | FAILED | retry |
| Server over its budget | sampler, 2 samples | restart once, then stopped | "stopped (memory)" | raise budget / manual start |
| Global budget / `onTrimMemory` | sampler / callback | kill order sec 8.6 | "paused (memory)" | lazy restart on need |
| Request timeout | `lsp.requestTimeoutMs` | feature silently absent for that request | nothing (no dialog) | next request |
| Doc over `LspPolicy.MAX_FULL_SYNC_BYTES` | `DocumentSync` | not synced; LSP off for that file | notice in editor | none |
| Environment stopped/failed | `envState` | all servers of E stopped; `sandboxExec` actions disabled via `envState == ready` | env badge | start env |
| Extension activation crash | `ActivationManager` | contributions greyed with retry | Extension Log | 2 in a row -> safe mode |
| Bad manifest / missing capability | load-time validation | extension not loaded | Extension Log entry | fix/update |
| WASM trap, fuel, timeout | `WasmHost` | call fails `E_LIMIT`/`E_TIMEOUT`; instance discarded | snackbar for UI-triggered calls | re-instantiate; N traps -> disabled |
| Install step non-zero / verify fails | `InstallStepRunner` | version dir deleted, previous `current` kept | terminal output + dialog | fix and retry |
| Signature/sha256/pin/revocation failure | `SignatureVerifier` | install refused (hard) | reason shown | none for registry packages |
| Offline | network callback / fetch fail | last verified index + cache | index age shown | refresh later |
| Invalid setting value | schema validation | next lower layer | row shows fallback + logs once | fix value |
| Extension grammar pathological regex | tokenizer cancellation | plain text for that file | notice | disable extension |
| App killed by LMK | process death | on relaunch: static contributions from cache, servers lazy | normal start | none |

## 13. Open issues (HLD-level)

1. Seed first-party packs into the APK's offline cache so first-run Python works offline?
2. CI enforcement of module rules (sec 4): Gradle edges + import grep, or a lint rule.
3. Resolved: project `lsp.servers` exec values need project trust, prompted once per project
   (lld/customization.md sec 12).
4. Where `onTrimMemory` is hooked (`EasyIdeApplication` vs `MainActivity`) - lld/lsp-client.md.

## 14. LLD link map

| Area | LLD | Main components |
|---|---|---|
| 5.2 LSP | [lld/lsp-client.md](lld/lsp-client.md) | `JsonRpcConnection`, `LspSession`, `DocumentSync`, `PathMapper`, `ServerSupervisor`, `LspPolicy` (memory part = `MemoryPolicy`), `ServerProcessFactory`; lifecycle in [lld/lsp-lifecycle.md](lld/lsp-lifecycle.md), features in [lld/lsp-features.md](lld/lsp-features.md) |
| 5.3 L1 runtime | [lld/extension-runtime.md](lld/extension-runtime.md) | `ManifestParser`, `ContributionRegistry`, `ActivationManager`, `WhenEvaluator`, `ActionRunner`, `CapabilityTable` |
| 5.5 Customization | [lld/customization.md](lld/customization.md) | `SettingsLayers`, `SettingsResolver`, overrides, keybindings, themes, profiles, safe mode, export/import |
| 5.3 L2 | [lld/wasm-host.md](lld/wasm-host.md) | `WasmHost`, ABI v1, limits, host functions |
| 5.4 Ecosystem | [lld/registry-and-install.md](lld/registry-and-install.md) | `RegistryClient`, `SignatureVerifier`, `ExtensionStore`, installer, Open VSX adapter, offline cache |
| 5.4 Authoring | [lld/cli.md](lld/cli.md) | `easyide-ext` internals |
| Cross-cutting | [threat-model.md](threat-model.md), [rules.md](rules.md), [test-plan.md](test-plan.md), [features.md](features.md), [glossary.md](glossary.md) | - |
| Decisions | ADR-E [0013](../decision/0013-extension-sdk-shape-manifest-easyext.md) (SDK shape), ADR-F [0014](../decision/0014-wasm-logic-layer-chicory.md) (WASM + Chicory), ADR-G [0015](../decision/0015-extension-sdk-licensing-apache.md) (Apache-2.0 SDK), ADR-H [0016](../decision/0016-extension-registry-static-index-ed25519.md) (registry + signing), ADR-I [0017](../decision/0017-lsp-client-hand-rolled-server-lifecycle.md) (LSP client + lifecycle) | all Proposed |

## 15. Deviations and conflicts found

- Resolved in arch.md / sdk-reference.md (2026-09-24): LSP budget, server cap, restart keys and
  WASM defaults now follow sdk-reference; `lsp.maxSyncBytes` / `memorySampleSec` /
  `logBufferLines` became `LspPolicy` constants; install layout follows arch sec 6.2 in both.
- Rule 6 (CLI consumes `services/shared/extension-schema`) stretches the repo rule that
  `services/shared` is "depended on by mobile/backend only"; needs a note in ADR-G.
