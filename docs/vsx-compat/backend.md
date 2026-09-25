# VS Code extensions: backend gap analysis (part 1 of 2)

Scope: the non-UI half of running Open VSX code extensions under **route 2b** (ADR 0031, D1): VS Code's
node extension host vendored unmodified, a thin JS main-thread adapter in the guest, JSON-RPC/stdio to
Kotlin. Part 1 covers the host process, transport, activation, storage, secrets, configuration, file
systems, watching, documents, trust and the manifest/package blockers. [backend-2.md](backend-2.md)
covers tasks, terminals, git, auth, processes/native code, network, l10n, telemetry, extension-set
management, the language-feature router (D10), the summary table, the WP-HOST-* / WP-API-* lists and
the UNVERIFIED list.

Binding inputs: D1-D13 (lead decisions), ADR [0031](../decision/0031-vendor-vscode-extension-host.md),
ADR [0032](../decision/0032-node-runtime-provisioning.md), ADR 0033/0034 (webviews, Play stance; not
re-decided here). Evidence: [research/ourcode-backend.md](research/ourcode-backend.md) (our code,
primary; machine twin [data/ourcode-backend.json](data/ourcode-backend.json)),
[research/route-spike.md](research/route-spike.md), [research/vscode-src.md](research/vscode-src.md),
[research/policy-licence.md](research/policy-licence.md), [registry-install.md](registry-install.md)
(WP-REG-*), [security-licensing.md](security-licensing.md) (WP-SEC-*), [ui.md](ui.md) (WP-UI-*).
Corpus numbers are computed from [data/corpus.json](data/corpus.json) and
[data/corpus-usage.json](data/corpus-usage.json) (snapshot 2026-09-25T14:49Z, scanVersion
`4913c6e67664`, 156 extensions, weights = Open VSX downloadCount; the files may still be updated, so
re-run the queries before quoting them elsewhere). "w%" = share of corpus download weight.

## 0. Legend, aliases, and what the vendored host gives us

Gap class: **Have** / **Partial** / **Missing-backend** / **Missing-UI** / **Missing-both** / **Not-planned**.
Effort (one engineer): S <= 3 d, M <= 2 wk, L <= 5 wk, XL > 5 wk (same scale as security-licensing.md sec 7).
Risk = delivery risk (Low/Med/High).

Our-code aliases (paths relative to the worktree, same as research/ourcode-backend.md sec 0):
`SCH/` = `services/shared/extension-schema/src/main/kotlin/dev/easyide/extensions/`, `SCHJ` =
`services/shared/extension-schema/manifest.schema.json`, `EXT/` = `services/mobile/extensions/src/main/kotlin/dev/easyide/extensions/`,
`LSP/` = `services/mobile/lsp/src/main/kotlin/dev/easyide/lsp/`, `SBX/` = `services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/`,
`APP/` = `services/mobile/app/src/main/java/dev/easyide/app/`. VS Code paths `vs/...` = `src/vs/...` in
microsoft/vscode `0b16cb9` (main, 1.140.0; research/vscode-src.md header). The pinned stable tag of D1
will shift line numbers slightly; symbols stay.

Three owners under route 2b (D1): **host** = vendored ext host (no edits); **adapter** = our JS in the
guest implementing `MainThread*Shape` methods; **Kotlin** = app side (UI + Android state).

What the vendored host already implements (so no port is needed; all under `vs/workbench/api/common/`
unless noted): the whole `vscode` namespace object and `require('vscode')`/ESM interception
(`extHostRequireInterceptor.ts:62,148`, `api/node/extHostExtensionService.ts:170-172`), `ExtensionContext`
construction, `Memento` with write batching (`extHostMemento.ts:12,41`), `Uri`/types/converters
(`extHostTypes.ts`, `extHostTypeConverters.ts`), `TextDocument` line model and edit application
(`extHostDocumentData.ts`), `WorkspaceEdit` DTO conversion, configuration merge and `inspect()`
(`extHostConfiguration.ts:265-286`), activation ordering, dependency checks and eager/`onStartupFinished`
handling (`extHostExtensionActivator.ts:166-330`, `extHostExtensionService.ts:614-719`), event emitter
ordering and disposal, cancellation, `workspace.fs` for `file:` via Node (`api/node/extHostDiskFileSystemProvider.ts`),
file-watcher glob filtering on the host side (`extHostFileSystemEventService.ts:134-231`), diagnostic
collections, `l10n.t` formatting (`extHostLocalizationService.ts:103`), telemetry gating
(`extHostTelemetry.ts:58-68`), proxy patching of `http/https/net/tls` (`api/node/proxyResolver.ts:32-73`),
secret-storage and auth-session bookkeeping. The spike activated 7/8 real extensions against a stub
main side (route-spike sec 3). **Our job is the main side**: ~328 in-scope methods over 53 shapes
(route-spike sec 4), split between adapter JS and Kotlin ports as stated per topic below.

## 1. Host process

### 1.1 Node runtime provisioning (ADR 0032)

| Aspect | Content |
|---|---|
| VS Code | Builds with Node 24.18.0 (`.nvmrc`), server target 24.21.0 (`remote/.npmrc`); host calls `module.registerHooks` unconditionally (`api/node/extHostExtensionService.ts:130`), needs Node >= 22.15 (route-spike sec 7). VS Code does not validate `engines.node` (`vs/platform/extensions/common/extensionValidator.ts:260,340`, policy-licence sec 3.1). |
| easyIDE today | No Node by default; guest is Ubuntu noble whose `nodejs` is 18.19.1 (policy-licence sec 3.2); presets `apt-get install nodejs npm` (`services/mobile/app/src/main/assets/extensions/easyide.yaml/package.json:40-42`). Reusable: `SBX/download/VerifiedDownloader.kt` (sha256), rootfs pin pattern `APP/data/SandboxImages.kt:58-72`. |
| Corpus | 22 extensions declare `engines.node`; highest floors: `eamodio.gitlens` ">= 24", `hashicorp.terraform` "^24.0.0", `redhat.ansible` ">=24.13.1", `waderyan.gitblame` ">=22.22.0", `streetsidesoftware.code-spell-checker` ">=22.20.0". |
| Gap | Missing-backend (+ a progress/consent line in the enable sheet: Missing-UI, owned by WP-UI) |
| Under 2b | Kotlin: sandbox step downloads `node-v24.x-linux-arm64.tar.xz` (30,843,004 B for v24.21.0, sha256 in policy-licence sec 3.3), checks the pinned sha256, unpacks to `/opt/easyide/node/<ver>/` per environment, keeps the previous dir until the new host started once (ADR 0032). Launch by absolute path, never `node` from `PATH`. Set `NODE_COMPILE_CACHE=/root/.cache/easyide/node-compile` (warm cache: hello 248 ms vs 350 ms, route-spike sec 3). Unpack `.xz` on the Kotlin side (xz-utils presence in ubuntu-base is UNVERIFIED) or verify `xz` first. |
| Effort / risk / WP | M / Med (V8 JIT under proot unmeasured = ADR 0031 gates G1/G2) / **WP-HOST-2**, gate run **WP-HOST-3** |

### 1.2 Process model and lifecycle (D3)

| Aspect | Content |
|---|---|
| VS Code | One ext host per window; lazy start via `LazyCreateExtensionHostManager` (`vs/workbench/services/extensions/common/abstractExtensionService.ts:820,866-867`). Host bootstrap: transport from env (`api/node/extensionHostProcess.ts:180-291`), 1-byte `Ready`, main sends `IExtensionHostInitData` JSON, host checks commit (`:347`), watches `parentPid` (`:354-385`), sends `Initialized` (`:390`) (research/vscode-src.md sec 3). Local host env: `VSCODE_HANDLES_UNCAUGHT_ERRORS`, `VSCODE_ESM_ENTRYPOINT` (`vs/workbench/services/extensions/electron-browser/localProcessExtensionHost.ts:231-232`); server adds `VSCODE_NLS_CONFIG` (`vs/server/node/extensionHostConnection.ts:46`). Extensions cannot exit the host (`process.exit` patched, `extensionHostProcess.ts:97-157`). |
| easyIDE today | Single spawn point `SBX/shell/ServerProcessFactory.kt:67-88` (`/bin/sh -c 'exec "$0" "$@"'`, stdio pipes, `TERM=dumb`), launcher keyed `ServerKey(environmentId, projectId, serverId)` (`LSP/session/ServerConfig.kt:13`, `APP/lsp/SandboxServerLauncher.kt:57-62`), proot `--kill-on-exit` (`SBX/backend/ProotLauncher.kt:20-57`), cleared env (`SBX/shell/SandboxShell.kt:159-171`), `/workspace` bind of one project (`ProotLauncher.kt:34-36`). Session state machine with backoff: `LSP/session/SessionStateMachine.kt`, `LSP/LspPolicy.kt:50-60`. Workspace sessions are held/parked (`APP/session/ParkPolicy.kt`, ADR 0023). `services/mobile/exthost` does not exist (`docs/extension-host/tracker.md:8`). |
| Gap | Partial (spawn path Have; host supervisor Missing-backend) |
| Under 2b | Process tree per workspace session `(envId, projectId)`: `proot` -> `adapter.js` (stdio JSON-RPC to Kotlin) -> `fork(extensionHost.js)` connected over a guest Unix socket (`VSCODE_EXTHOST_IPC_HOOK=/root/.easyide/run/exthost-<session>.sock`, the path the spike used; route-spike sec 3). Adapter sends initData: `version`/`commit` of the pinned tag, `parentPid: 0` (no `@vscode/native-watchdog`, route-spike sec 1), `autoStart: true`, `uiKind: Desktop`, `environment.appName "easyIDE"`, `appHost "desktop"`, `remote.isRemote false` (D9), storage/log homes (sec 4), `extensions` snapshot built by the adapter from the enabled set (sec 3, backend-2 sec 21). Env = allow-list (WP-SEC-5, M-VSX-04) + `VSCODE_HANDLES_UNCAUGHT_ERRORS=true`, `VSCODE_NLS_CONFIG` (app locale), `NODE_COMPILE_CACHE`, V8 heap cap (1.3). Kotlin `ExtHostSupervisor` states: `Stopped -> Provisioning(node) -> Starting -> Ready -> Unresponsive -> Restarting(backoff) -> Failed(safe-mode banner)`, plus `Parked`. Started only when an enabled `host.run` extension's activation event fires (D3, M-VSX-02). Restart: 3 tries with the `LspPolicy` backoff, then banner (D3). Liveness: VS Code's own RPC marks a host unresponsive after 3 s without ack (`vs/workbench/services/extensions/common/rpcProtocol.ts:119`); the adapter reports that to Kotlin, and Kotlin pings the adapter (`$/ping`, 5 s) to catch a frozen adapter. |
| Parked workspaces | ADR 0023 keeps several workspaces held; one host each would multiply ~100-300 MB. Rule (proposal): `extensions.host.maxLive` (default 1 = active workspace); a parked workspace's host is stopped after `extensions.host.parkedStopSec` (proposal 120 s) or immediately under pressure (1.3) and restarted lazily on return (replaying `*`, `workspaceContains`, `onLanguage` of open docs). State survives because Memento is persisted outside the process (sec 4). |
| Effort / risk / WP | L / Med / **WP-HOST-4** |

### 1.3 Memory budget, MemoryPolicy integration and shedding order

| Aspect | Content |
|---|---|
| VS Code | No host memory policy; the desktop host simply lives with the window. |
| Measured (x86 proxy) | Hello 97-113 MB RSS; GitLens 146 MB HWM; Claude Code 165 MB; ESLint/Prettier ~103 MB (route-spike sec 3). Adapter process adds a Node floor (bare Node 42 MB, route-spike sec 3; adapter itself UNVERIFIED). Device gates G3 <= 150 MB idle, G4 <= 300 MB with GitLens+Claude Code+ESLint (ADR 0031). |
| easyIDE today | Kill order over LSP sessions only: `IDLE -> NOT_VISIBLE -> PRESSURE_HOOKS -> FOCUSED` (`LSP/manager/MemoryPolicy.kt:7,58-68`), hook interface `:35-37`, pressure mapping `:44-49`, `onTrimMemory` mapping `APP/lsp/LspRuntime.kt:122-160`, budget `lsp.globalMemoryBudgetMb`=1200 and `lsp.maxServers`=3 (`APP/data/settings/LspSettingsSchema.kt:52-65`), RSS by `/proc` walk (`APP/lsp/ProcTree.kt`, `SandboxServerLauncher.kt:79-90`), parked-workspace ending `APP/session/ParkPolicy.kt`. The host has no slot. |
| Gap | Partial |
| Under 2b | Kotlin: add `Victim.Host(key, step)` to `evictionOrder` (not a `PressureHooks` side effect, so it is visible and ordered). Host RSS = **process-tree** RSS (`ProcTree`), because language servers that extensions spawn (rust-analyzer, pyrefly, clangd, Java) are children of the host, not LSP sessions of ours. Proposed placement: parked-workspace hosts at `IDLE` (first), the active host with no visible extension UI, no running task/terminal and no provider call for 5 min at `NOT_VISIBLE`, otherwise the host sits in `FOCUSED` ordered by RSS with LSP sessions. Host tree RSS counts against `lsp.globalMemoryBudgetMb`; new keys (R-ENG-07/08 no hard-coding): `extensions.host.maxOldSpaceMb` (V8 `--max-old-space-size`; value from G4, proposal 768), `extensions.host.maxLive`, `extensions.host.parkedStopSec`. Revisit 1200 MB / 3 servers after G4 (research/ourcode-backend.md C9). |
| Low-memory shedding order (proposal) | 1 idle LSP sessions; 2 parked-workspace hosts (stop); 3 hidden webviews without `retainContextWhenHidden` (WP-UI-8 budget, serialise); 4 LSP sessions not visible; 5 `PressureHooks` (WASM idle instances; retained hidden webviews); 6 active host if idle per the rule above (stop; restart lazily on next activation event); 7 focused sessions and the focused host, largest RSS first. Extension-spawned servers are never killed individually (their `LanguageClient` would restart them and lose state); only as part of the host tree. |
| Effort / risk / WP | M / Med / **WP-HOST-6** (mechanism; security acceptance ST-VSX-10 stays in WP-SEC-13) |

### 1.4 Crash journal, attribution, supervision outcome

| Aspect | Content |
|---|---|
| VS Code | Activation errors are reported per extension (`$onExtensionActivationError`, `extHostExtensionService.ts:188`; missing dependency `MissingExtensionDependency`, `extHostExtensionActivator.ts:267,318`); activation timing via `$onWillActivateExtension`/`$onDidActivateExtension` (`:432`). Host crash = "Extension host terminated unexpectedly" with restart offer (renderer side). |
| easyIDE today | `EXT/host/CrashJournal.kt:36-60` (fsynced begin/end around REGISTER/ACTIVATE; 2 consecutive -> safe mode, `SCH/ExtensionPolicy.kt:12`), `EXT/host/ActivationManager.kt:111-127` (`reportCrash`, crash window -> `CRASH_DISABLED`), keys are `extensions.wasm.*` (`SCH/settings/ExtensionSettings.kt:30-33`). |
| Gap | Partial |
| Under 2b | Adapter forwards `$onWillActivateExtension` -> `journal.begin(ACTIVATE, id)`, `$onDidActivateExtension`/`$onExtensionActivationError` -> `journal.end`. Host death while a begin is open = crash of that extension (`reportCrash`); death with no open begin = host-level crash (counts toward the 3-restart budget, D3, not toward any extension). Activation error (exception in `activate`) is **not** a crash: extension state `FAILED` with the host's message, host keeps running (VS Code behaviour). Host-neutral keys: `extensions.host.maxCrashes`, `extensions.host.crashWindowSec`; keep WASM keys for WASM. Add a "last host logs" attachment (adapter ring of host stderr + `MainThreadConsole.$logExtensionHostMessage`). |
| Effort / risk / WP | S / Low / **WP-HOST-7** |

## 2. Transport

| Aspect | Content |
|---|---|
| VS Code | `PersistentProtocol` (13-byte header, acks, keep-alive 5 s; `vs/base/parts/ipc/common/ipc.net.ts:263-313,474-478`) carrying `RPCProtocol` (12 message types, JSON or mixed args with buffer refs, `$mid` URIs, 3 s unresponsive, trailing `CancellationToken` -> `Cancel` message; `rpcProtocol.ts:119,461-496,716-756,940-960`). Proxy ids are creation-order and shift almost every release (route-spike sec 4). |
| easyIDE today | JSON-RPC 2.0 with `$/cancelRequest` both ways, per-request jobs (`LSP/jsonrpc/JsonRpcConnection.kt:50-150`), Content-Length framing with 8 KB header / 64 MB body / depth 256 limits (`LSP/jsonrpc/Framing.kt:27-29`, `LSP/LspPolicy.kt:13-24`); notifications pumped one at a time in wire order (`JsonRpcConnection.kt:50-56`). |
| Gap | Have (link) / Missing-backend (UI protocol + adapter) |
| Under 2b | Two hops. (a) host <-> adapter: VS Code's own protocol over a guest Unix socket; free, adapter is bundled from the same tag so ids match (D1). (b) adapter <-> Kotlin: reuse `JsonRpcConnection`/`Framing` in a new `:exthost` Kotlin module (`:lsp` stays Android-free). Method list = docs/extension-host/arch.md re-scoped (D1) with amendments: `$/cancelRequest` not `$/cancel` (arch.md:35), raw `packageJSON`, `tree/getParent`/`resolveItem`, coalesced doc deltas. Rules: every Kotlin->adapter request has a timeout; adapter coalesces chatty notifications per 16 ms frame (decorations, status bar, tree refresh, progress) because Kotlin pumps notifications serially; order between document deltas and provider requests is preserved by sending both on the one stream without awaiting in between; binary (`fs` for virtual schemes, webview resources) as base64 with the 64 MB cap, larger payloads chunked; adapter never writes to its own stdout except frames (host stdout/stderr are captured by the adapter, `consoleForward` in initData). Budget: RPC p50/p99 x86 2.6/6.9 ms per hop (route-spike sec 3); target end-to-end completion < 50 ms (G5 note). |
| Effort / risk / WP | M / Low / **WP-HOST-5** |

## 3. Activation model and lazy activation

| Aspect | Content |
|---|---|
| VS Code | 42 activation events, 19 generated implicitly from contributions (`vs/platform/extensionManagement/common/implicitActivationEvents.ts:14-59`; list in research/vscode-src.md sec 1). Renderer keeps `_allRequestedActivateEvents` and calls the host only if some extension listens (`abstractExtensionService.ts:989-1051`). The **host** evaluates `*`, `onStartupFinished` (delayed) and `workspaceContains` itself (`extHostExtensionService.ts:614-719`): file names via `fs.exists`, globs via main `$checkExists` (`:712`; `vs/workbench/services/extensions/common/workspaceContains.ts:33-109`; `extHost.protocol.ts:1974`). All other events come from the main side as `$activateByEvent(event, kind)` (`extHost.protocol.ts:2607`). Dependencies are activated first; cycles rejected (`extensionDescriptionRegistry.ts:122`). |
| easyIDE today | 6 kinds (`SCH/manifest/ExtensionDescriptor.kt:22-47`; `*` read as `onStartupFinished`; unknown dropped with warning, `ManifestParser.kt:153-161`); no implicit events; `ActivationManager` per-id mutex and states (`EXT/host/ActivationManager.kt:22,51-182`), `Activator` interface (`:35-39`); emit sites `EXT/ExtensionsRuntime.kt:128-139,201`; timeout reuses `extensions.wasm.activateTimeoutMs`=5000 (`ActivationManager.kt:142,146`). |
| Corpus | Declared (w%): onLanguage 54.5, workspaceContains 39.4, onCommand 32.0, onDebugResolve 24.2 (OOS-DAP), onStartupFinished 18.0, onWebviewPanel 14.6, onNotebook 10.6 (OOS-NB), onUri 6.3, onTerminalShellIntegration 5.4, onFileSystem 5.1, onCustomEditor 4.2, onTaskType 2.1, onView 1.5, `*` 0.5. Implicit: onCommand 96.5, onLanguage 58.5, onView 44.2, onTaskType 25.5, onWalkthrough 19.5, onCustomEditor 14.6 (`corpus-usage.json` `activationEvents`, `implicitActivationEvents`). |
| Gap | Partial |
| Decision (who evaluates) | **The ext host activates; Kotlin detects events.** Kotlin is the only side that sees editor/UI events, so it keeps an index `event string -> extension ids` over the raw strings plus implicit events (a data table mirroring `implicitActivationEvents.ts`, R-ENG-07) and, when an indexed event fires, (1) starts the host if stopped (lazy start, D3) and (2) sends `activateByEvent(event)`; the adapter forwards `$activateByEvent(event, Normal)` (or `Immediate` for `onCommand` before execution and for `onView` before resolving a tree). Events Kotlin raises: `onLanguage:<id>` (document opened/language changed), `onCommand:<id>`, `onView:<id>`, `onWebviewPanel:<type>` (restore), `onCustomEditor:<type>`, `onUri` (WP-SEC-11), `onFileSystem:<scheme>` (opening a URI of that scheme), `onTaskType`, `onTerminalProfile`, `onTerminal`, `onTerminalShellIntegration`, `onWalkthrough`, `onAuthenticationRequest:<id>`, `onSearch:<scheme>`, `onOpenExternalUri`. Events the host raises itself with no Kotlin work: `*`, `onStartupFinished`, `workspaceContains` (adapter answers `$checkExists` with an in-guest glob walk bounded like `SCH/ExtensionPolicy.kt:14` `WORKSPACE_SCAN_MAX_FILES`=20000). OOS-track events (`onDebug*`, `onNotebook*`, `onChat*`, `onLanguageModel*`, `onMcp*`) are indexed but never raised (D8). |
| ActivationManager role | Becomes a mirror, not the activator for code extensions: a new `NodeHostActivator` implements `handles/activate/deactivate` by sending events and awaiting `$onDidActivateExtension`/`$onExtensionActivationError`; per-extension states come from the adapter (sec 1.4). Timeout: no kill on slow activation (VS Code has none); `extensions.host.activateWarnMs` (proposal 10 s) shows "still activating"; liveness is the RPC watchdog (1.2). |
| Effort / risk / WP | M / Med (event coverage) / **WP-API-1** |

## 4. Extension storage and Memento

| Aspect | Content |
|---|---|
| VS Code | Host keeps `Memento` in memory and writes whole objects through `MainThreadStorage.$initializeExtensionStorage(shared, id)` / `$setValue(shared, id, value)` / `$registerExtensionStorageKeysToSync` (`extHost.protocol.ts:847-850`), batched by `RunOnceScheduler` (`extHostMemento.ts:41`). Paths: `globalStorageUri = globalStorageHome/<id lower-case>` (`api/common/extHostStoragePaths.ts:90`), `storageUri` under `workspaceStorageHome/<workspace id>` with a lock file on `file:` (`api/node/extHostStoragePaths.ts:18-45`, skippable by `environment.skipWorkspaceStorageLock`), `logUri` under `logsLocation`; homes come from initData `environment.globalStorageHome/workspaceStorageHome` and `logsLocation` (`vs/workbench/services/extensions/common/extensionHostProtocol.ts:28-80`). |
| easyIDE today | WASM `WasmStoragePort` per extension x {global, env-<id>, project-<id>} JSON, temp+fsync+rename, quota `extensions.storage.quotaKb` (`APP/extensions/wasm/WasmStoragePort.kt:5-20`). No guest-visible storage dirs (research/ourcode-backend.md 2.1 `storage.paths`). |
| Corpus | `globalState.get/update` in >= 70 of 156 bundles (unminified-name lower bound, rg over the cached corpus); `ExtensionMode` 26.4 w%. |
| Gap | Partial |
| Under 2b | Free in host: Memento API, keys, batching, `ExtensionContext` URIs. Adapter: `MainThreadStorage` -> Kotlin `storage/get`, `storage/set`. Kotlin: generalise `WasmStoragePort` to `ExtensionStoragePort` (same atomic write + quota), scoping **globalState = (envId, extId)** (environments own extensions, D4) and **workspaceState = (envId, projectId, extId)**; `setKeysForSync` accepted and ignored (no Settings Sync, OOS-MS). Guest paths in initData (per environment because the rootfs is per environment): `globalStorageHome=/root/.easyide/exthost/globalStorage`, `workspaceStorageHome=/root/.easyide/exthost/workspaceStorage` (workspace id = `projectId`, one host per workspace so the lock never contends; set `skipWorkspaceStorageLock: true` to avoid a proot file-lock edge case), `logsLocation=/root/.easyide/exthost/logs/<sessionId>`. Wipe on uninstall (per extension) and on kill switch (M-VSX-03). Caveat to disclose: any co-hosted extension can read these files (T-VSX-02). |
| Effort / risk / WP | S / Low / **WP-HOST-8** |

## 5. Secrets (SecretStorage)

| Aspect | Content |
|---|---|
| VS Code | `MainThreadSecretState.$getPassword/$setPassword/$deletePassword/$getKeys(extensionId, key)` (`extHost.protocol.ts:240-244`), change event `ExtHostSecretState.$onDidChangePassword` (`:2587`). |
| easyIDE today | `NoSecrets` (`APP/extensions/wasm/WasmSmallPorts.kt:40-47`); Keystore AES-GCM pattern `SBX/git/GitCredentials.kt:14-24,74,87-90`. EncryptedSharedPreferences is deprecated (policy-licence sec 4); the guest cannot reach Keystore. |
| Corpus | `secrets.store/get(` found in >= 23 bundles (GitLens, GitLab, AWS toolkit, Continue, devsense PHP, ...). |
| Gap | Missing-backend |
| Under 2b | Adapter forwards the four calls verbatim; Kotlin `ExtensionSecrets` keyed `(envId, extId)`, refuses ids not enabled in that environment, logs access (M-VSX-06, WP-SEC-7), fires `$onDidChangePassword`. Honest limit stays: plaintext is readable by co-hosted extensions once fetched (T-VSX-09). |
| Effort / risk / WP | S / Low / **WP-API-6** (Kotlin store = WP-SEC-7) |

## 6. Settings / configuration parity

| Aspect | Content |
|---|---|
| VS Code | Host receives `$initializeConfiguration(IConfigurationInitData)` = defaults, policy, application, userLocal, userRemote, workspace, folders + `configurationScopes` (`extHost.protocol.ts:116-118,2376`), then `$acceptConfigurationChanged(data, change)` (`:2377`); `userRemote` is merged over `userLocal` whenever non-empty (`vs/platform/configuration/common/configurationModels.ts:1001-1008`). `inspect()` returns default/global/workspace/workspaceFolder values and `languageIds` (`extHostConfiguration.ts:265-286`). Writes via `MainThreadConfiguration.$updateConfigurationOption` (`extHost.protocol.ts:247`). Defaults must include `contributes.configuration` defaults or extensions break (better-comments failed in the spike, route-spike sec 3). |
| easyIDE today | Layers `BUILT_IN, EXTENSION, USER, ENVIRONMENT, PROJECT` (`APP/data/settings/LayerDoc.kt:10`), resolver (`SettingsResolver.kt:38-100`), `inspect` with provenance (`:19-22,115`), language overrides `[lang]` (`Setting.kt:18-28`), `configurationDefaults` incl. `[lang]` (`SCH/manifest/ContributesDecoder.kt:112-121`), scope map inline: application/machine -> G, machine-overridable -> E, language-overridable -> L, window/resource -> P (`APP/data/settings/ContributedSettings.kt:49-53`), port `SCH/settings/SettingsPort.kt:19-53`. Project file is `.easyide/settings.json` (`APP/data/settings/SettingsFiles.kt:78`); `.vscode/settings.json` is not read (grep). |
| Corpus | `getConfiguration` 95.3 w%, `onDidChangeConfiguration` 89.6 w%. |
| Gap | Partial (resolver Have; VS Code-shaped export, folder scope, `.vscode` import Missing-backend) |
| Under 2b | Free in host: merge, `get/has/inspect/update` API shape, `affectsConfiguration`, language-override lookup. Kotlin builds the models: BUILT_IN + EXTENSION (incl. every enabled `.vsix`'s `contributes.configuration` defaults and `configurationDefaults`) -> `defaults`; USER -> `userLocal`; **ENVIRONMENT -> `userRemote`** (a machine-level layer over user, exactly VS Code's remote-machine semantics; `env.remoteName` stays undefined per D9, since the merge does not depend on it); PROJECT -> `workspace` **and** the single folder model (single-folder workspace; whether VS Code reports `workspaceFolderValue` equal to `workspaceValue` in that case is UNVERIFIED, test against the pinned tag). Send only defaults + non-empty layers (arch.md C16: a full snapshot is large). `configurationScopes` from the manifest `scope` strings, kept as raw strings (the P-for-window/resource mapping stays our resolver's business). Writes: `ConfigurationTarget.Global` -> USER, `Workspace`/`WorkspaceFolder` -> PROJECT, `overrideIdentifier` -> `[lang]` block; protected keys refused (`telemetry.*`, `http.proxy*`, `extensions.*`, WP-SEC-12). Forced values: `telemetry.telemetryLevel "off"` in a policy-like layer (D9). Optional read-only import of `.vscode/settings.json` into PROJECT (many cloned repos carry one); decide in WP. |
| Effort / risk / WP | M / Med (inspect/folder fidelity) / **WP-API-2** |

## 7. File system providers, virtual documents, search

| Aspect | Content |
|---|---|
| VS Code | `workspace.fs` on `file:` runs in the node host through its disk provider (`api/node/extHostDiskFileSystemProvider.ts`); other schemes via `MainThreadFileSystem.$registerFileSystemProvider` etc. (`extHost.protocol.ts:1999`); `registerTextDocumentContentProvider` via `MainThreadDocumentContentProviders` (`:286`); `findFiles` -> `MainThreadWorkspace.$startFileSearch` (`:1972`); `findTextInFiles` -> ripgrep through the dynamically imported `@vscode/ripgrep-universal` (`vs/base/node/ripgrep.ts:9-10`, `api/node/extHostSearch.ts:19`). |
| easyIDE today | Host-side project CRUD `SBX/files/ProjectFiles.kt:28-254`; no scheme router, no virtual documents (research/ourcode-backend.md 2.2 `fs.provider`, `doc.content-provider` Missing); quick-open index only, no text search (`APP/ui/shell/workspace/SearchPanel.kt:36-40`). |
| Corpus | `workspace.fs` 76.8 w%, `findFiles` 56.6, `registerTextDocumentContentProvider` 43.5, `registerFileSystemProvider` 10.2, `findTextInFiles` 0.2; `onFileSystem` activation 5.1. |
| Gap | `file:` fs: Have (free, in guest). Providers/virtual docs: Missing-both. `findFiles`: Missing-backend. `findTextInFiles`: Missing-backend. |
| Under 2b | Adapter: implement `$startFileSearch` in JS inside the guest (glob walk of `/workspace` honouring `files.exclude`/`search.exclude`, `maxResults`, cancellation) - no Kotlin. `$checkExists` shares it (sec 3). `findTextInFiles`: point the host's ripgrep import at guest `rg` (`apt install ripgrep` step + a tiny `@vscode/ripgrep-universal` stand-in module exporting `rgPath=/usr/bin/rg`); low priority. Providers: adapter keeps the provider registry; Kotlin adds a **scheme router**: editor/explorer open of a non-`file` URI -> `fsp/stat|readFile|readDirectory|writeFile|...` or `content/provide(uri)` to the adapter; virtual docs open read-only unless the provider is writable; `onDidChange` of a content provider refreshes the open doc. Activation `onFileSystem:<scheme>` raised by Kotlin before the first call (sec 3). Needed for GitLens revision/compare docs (`gitlens:`), `git:` diffs from vscode.git (backend-2 sec 14). |
| Effort / risk / WP | M (router + provider bridge) / Med / **WP-API-4** |

## 8. File watching

| Aspect | Content |
|---|---|
| VS Code | `createFileSystemWatcher` -> host filters by glob and sends `MainThreadFileSystemEventService.$watch(extId, session, uri, {recursive, excludes, includes, filter}, correlate)` (`extHostFileSystemEventService.ts:134-231`, `extHost.protocol.ts:2016-2018`); main sends `ExtHostFileSystemEventService.$onFileEvent` and runs will/did file-operation participants (`$onWillRunFileOperation`, `$onDidRunFileOperation`, `:2636-2639`). Uncorrelated watchers honour `files.watcherExclude` (`:175-187`). Desktop main uses `@parcel/watcher` in a utility process. |
| easyIDE today | `SBX/files/ProjectFileWatcher.kt:27-79`: Android `FileObserver` on the explorer's **expanded dirs only**, non-recursive, 400 ms debounce, mask CREATE/DELETE/MOVED_*/CLOSE_WRITE (`:76-78`). LSP advertises `didChangeWatchedFiles` but never sends it (`LSP/protocol/ClientCapabilities.kt:139-142`). WASM `fs.changed` only on in-app saves (`APP/extensions/wasm/WasmRuntime.kt:194-200`). |
| proot fact | termux/proot translates the path argument of `inotify_add_watch` (`src/syscall/enter.c:2555-2560` at termux/proot `d4d2a19`, cloned 2026-09-25), so inotify on bind-mounted `/workspace` should work from Node in the guest. Event delivery, recursive `fs.watch` under proot and the inotify watch limit on the device are **UNVERIFIED** (the app's own `FileObserver`s share the same uid budget). |
| Corpus | `createFileSystemWatcher` 80.3 w%; `onDid{Create,Delete,Rename}Files` 49-53 w%, `onWill*Files` 46.6 w%. Extensions also watch directly: `fs.watch(` in 6 bundles, `chokidar` in 14, `@parcel/watcher` (native) in 1 (`bradlc.vscode-tailwindcss`). |
| Gap | Partial (Missing-backend for extension watchers and file-operation events) |
| Under 2b | **The adapter owns extension watchers in the guest**: `$watch` -> Node `fs.watch(path, {recursive})` (Node 20+ implements recursive on Linux by walking and adding one inotify watch per directory) with `files.watcherExclude` (VS Code default: `.git/objects/**`, `.git/subtree-cache/**`, `.hg/store/**` and `*/` variants, `vs/workbench/contrib/files/browser/files.contribution.ts:294-307`; we add `**/node_modules/**` for recursive watches on the device) applied before descending, events batched 50 ms and sent as `$onFileEvent`; one physical watcher per root shared by all sessions (de-duplicate like VS Code's main side). Kotlin: for app-initiated operations (explorer create/rename/delete, save) send `fileOperation/willRun` (awaited, participants may return a `WorkspaceEdit`, timeout = VS Code's `files.participants.timeout` setting (`files.contribution.ts`)) and `didRun` immediately, so they do not wait for inotify. Separately (not extension-facing), fix LSP `didChangeWatchedFiles` with a Kotlin project-wide watcher or by consuming the adapter's stream when the host runs (research/ourcode-backend.md C12). Fallback if inotify fails under proot: adapter polling with `fs.watchFile`-style mtime scan of watched globs (degraded). |
| Effort / risk / WP | M / High (UNVERIFIED proot/inotify limits) / **WP-API-5** |

## 9. Text document model and sync at scale

| Aspect | Content |
|---|---|
| VS Code | Main pushes `$acceptDocumentsAndEditorsDelta` with `IModelAddedData {uri, versionId, lines, EOL, languageId, isDirty, encoding}` (`extHost.protocol.ts:2388-2396,2460`), then `$acceptModelChanged(uri, {changes (end-to-start), eol, versionId, isUndoing, isRedoing, isFlush}, isDirty)` (`:2402`; `vs/editor/common/textModelEvents.ts:87-95`), `$acceptModelSaved`, `$acceptDirtyStateChanged`, `$acceptModelLanguageChanged` (`:2398-2400`). Models larger than `_MODEL_SYNC_LIMIT` = 50 MB are never synced (`vs/editor/common/model/textModel.ts:188,368`; `shouldSynchronizeModel`, `vs/editor/common/model.ts:1624-1627`). `openTextDocument` -> `$tryOpenDocument(uri, {encoding})` (`extHost.protocol.ts:294`). |
| easyIDE today | `DocumentStore`/`DocSnapshot{uri, openId, languageId, version, text, saveCount}` full text per version (`LSP/docs/DocumentStore.kt:18-84`); one-range diff per flush (`LSP/text/TextDiff.kt:30`); UTF-16 positions (`LSP/text/LineIndex.kt:12-54`); UTF-8 decode only (`SBX/files/ProjectFiles.kt:110`); editable <= 256 KB, read-only prefix viewer to 8 MB (2 MB prefix) (`SBX/files/FileContent.kt:48-54`); EOL detected for the status bar only (`APP/ui/screens/workspace/layout/StatusFacts.kt:42-45`); dirty state in the tab model (`APP/session/ExternalChange.kt:7-30`). |
| Corpus | `onDidChangeTextDocument` 76.7 w%, `openTextDocument` 66.4, `textDocuments` 64.5, `onWillSaveTextDocument` 52.4, `EndOfLine` 16.2, `setTextDocumentLanguage` 11.8. |
| Gap | Partial |
| Under 2b | Free in host: line model, `getText/positionAt/offsetAt/getWordRangeAtPosition`, `version`, event objects, `WorkspaceEdit` conversion. Kotlin: add `eol`, `isDirty`, `isUntitled` and `encoding` (`"utf8"` until encodings exist) to `DocSnapshot`; emit add/remove deltas when the editor opens/closes a doc and the editor/tab delta (WP-UI-6). Deltas: one coalesced range per flush is a valid `changes` array (VS Code itself batches multi-cursor edits in one event), `versionId` increments per flush; flush on every keystroke boundary before any provider request (ordering, sec 2); set `isUndoing/isRedoing` from our undo stack; `isFlush` on reload-from-disk. Size tiers (proposal): editable docs (<= 256 KB) sync live; read-only fully loaded docs (<= 8 MB) sync once as read-only; prefix-only views (> 8 MB) are **not** synced (partial text would lie). **Adapter-owned background documents**: `openTextDocument(file:)` for a file not open in the editor is served by the adapter reading the guest file (up to VS Code's 50 MB) and adding it as a document; Kotlin is told only if it must show it. This keeps language extensions (which open many files) off the Kotlin hop and off the 256 KB editor cap. Encodings (UTF-8 BOM, UTF-16LE/BE detect, `files.encoding`) are a later WP; until then non-UTF-8 files are opened read-only with a notice. `setTextDocumentLanguage` needs a re-key API on `DocumentStore` (Missing). |
| Effort / risk / WP | L / Med / **WP-API-3** |

## 10. Workspace trust

| Aspect | Content |
|---|---|
| VS Code | Trust in initData workspace (`$initializeWorkspace(workspace, trusted)`, `extHost.protocol.ts:2508`), `$onDidGrantWorkspaceTrust` (`:2511`), `requestWorkspaceTrust` -> `$requestWorkspaceTrust` (`:1983`; `extHostWorkspace.ts:960-967`). Extensions declare `capabilities.untrustedWorkspaces {supported: true|false|'limited', restrictedConfigurations}`; extensions with `supported: false` are disabled in Restricted Mode (`vs/workbench/services/extensionManagement/browser/extensionEnablementService.ts:603`, `vs/workbench/services/extensions/common/extensionManifestPropertiesService.ts:216-217`). |
| easyIDE today | `APP/data/settings/ProjectTrust.kt:1-31,106-120` (exec-bearing project settings need consent; `TrustState`). No restricted-mode model for extensions. |
| Corpus | `workspace.isTrusted` 24.8 w%, `onDidGrantWorkspaceTrust` 18.0, `requestWorkspaceTrust` 0.3. |
| Gap | Partial |
| Under 2b | Kotlin maps `TrustState.TRUSTED or NOT_REQUIRED` -> trusted in `$initializeWorkspace`; `$requestWorkspaceTrust` shows our trust sheet; grant fires `$onDidGrantWorkspaceTrust`. Restricted mode: Kotlin's activation index skips extensions with `untrustedWorkspaces.supported == false` in untrusted projects and strips `restrictedConfigurations` from the PROJECT layer sent to the host. Note: trust gates behaviour, not isolation (proot is not a boundary, security-licensing.md sec 0). |
| Effort / risk / WP | S / Low / **WP-API-16** |

## 11. Manifest schema blocker and package limits

| Aspect | Content |
|---|---|
| VS Code | Manifest is read raw from `extension/package.json` (the only manifest read, registry-install.md sec 6.2 step 4), validated leniently (`vs/platform/extensions/common/extensionValidator.ts:242`), NLS `%key%` resolved at scan (`extensionNls.ts:30-80`); unknown contribution points are ignored; any file layout inside `extension/` is allowed. |
| easyIDE today | `SCHJ:5` requires `engines.easyide` and has `additionalProperties:false` at the top level (verified: line 5 of the file), so `main`, `browser`, `extensionDependencies`, `extensionPack`, `extensionKind`, `capabilities`, `scripts`, `dependencies` are errors; `contributes` is closed (`SCHJ:48`); names `^[a-z0-9][a-z0-9-]{0,62}$` (`SCHJ:35`); command `icon` string only (`SCHJ:128`); no `submenu` menu items (`SCHJ:137`); `===`/`!==` in `when` -> `E_WHEN_SYNTAX` error (`SCH/manifest/DecodeContext.kt:41-44`). Layout: no symlinks, **no nested archives** (`.zip .jar .tgz .gz .xz .vsix ...`, `SCH/manifest/PackageFiles.kt:57,124`), no case-insensitive duplicates (`:126-127`); limits 50 MB package / 200 MB unpacked / 20 MB per file (`SCH/settings/ExtensionSettings.kt:22-24`); `PackageUnpacker` rejects symlink entries and drops Unix modes (`APP/extensions/install/PackageUnpacker.kt:38-61`). Only `.vsix` path today: `VsCodeIconThemeAdapter.kt:25-59` (keeps `iconThemes` only). |
| Corpus | 153/156 extensions are code (99.3 w%): every one fails `SCHJ:5`. Largest: `openai.chatgpt` vsix 242 MB / 582 MB unpacked, `Oracle.oracle-java` 311 MB unpacked, `redhat.java` 132 MB vsix; rust-analyzer's server binary 40.7 MB > the 20 MB per-file cap (registry-install.md sec 10 row 7); Java/JRE bundles contain `.jar` files (nested-archive rule). |
| Gap | Missing-backend (blocker for everything) |
| Under 2b | Never push `.vsix` manifests through `manifest.schema.json` (D4). Two readers of the same raw bytes: (a) **adapter** builds `IExtensionDescription` (identifier, `extensionLocation` = guest dir, raw `packageJSON` after NLS, `activationEvents` + implicit, `main`/`browser`, `enabledApiProposals` filtered by the D8 allow-list, `isBuiltin` for our built-ins) - the host needs nothing else; (b) **Kotlin** tolerant reader (research/ourcode-backend.md C1) producing an `ExtensionDescriptor` for contributions the app renders (commands, menus incl. submenus, views, keybindings, configuration, languages, grammars, snippets, themes...), keeping unknown points as data and downgrading `when` syntax errors to per-item warnings (`===`/`!==` accepted, C7). NLS via existing `SCH/manifest/Nls.kt` (whole-string `%key%`). Package rules for the vsx path only: nested archives allowed, symlinks allowed only if they resolve inside the extension dir, case duplicates allowed (Linux fs), exec bits preserved (WP-REG-4), limits raised per WP-REG-8. `.easyext` rules unchanged. Capability `host.run` synthesised (WP-SEC-4). |
| Effort / risk / WP | L / Med / **WP-HOST-9** (install mechanics are WP-REG-4/WP-REG-8) |

Continued in [backend-2.md](backend-2.md).
