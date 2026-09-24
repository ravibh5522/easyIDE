# Extension SDK - WASM host (LLD)

Low-level design of `:ext-wasm`, the L2 layer that runs extension logic compiled to core wasm inside the app process.

Status: IMPLEMENTED in `services/mobile/ext-wasm` and hosted by `:app` (`app/extensions/wasm/`, 2026-09-24: ports bridged, `LogicHost` + `Activator` wired; see Deviations for the app-side gaps). ART spike results: [0014](../../decision/0014-wasm-logic-layer-chicory.md#spike-results-2026-09-24). Design context: [arch.md](../arch.md) sec 6.2, 6.3 ("WASM action call"), 9, 10, 12 (M7).
Contract (ABI, host functions, capabilities, settings keys): [sdk-reference.md#wasm-host-api](../sdk-reference.md#wasm-host-api).
Feature area: arch.md sec 5.3 (Extension capabilities, `easyide.wasm` row). Needs ADR-F (0014) before M7.

## 1. Scope and responsibility

`:ext-wasm` owns, for every enabled extension with an `easyide.wasm` block:

- loading, validating, metering and caching the module;
- one instance and one worker thread per extension;
- the ABI v1 message pump (`alloc`/`free`/`ext_activate`/`ext_handle`/`host_call`);
- the host function dispatch table and the **only** capability enforcement point for L2;
- resource limits (memory, fuel, wall clock, host calls per call, message size);
- event and command delivery, provider requests, deactivation;
- trap/crash accounting and the crash-loop disable.

It does not own: manifest parsing, activation-event matching, approvals (`:extensions`,
[extension-runtime.md](extension-runtime.md)), the L1 action implementations (`ActionRunner`,
shared), the LSP client ([lsp-client.md](lsp-client.md)), or UI.

What this layer is and is not (repo rule, arch.md sec 9): L2 is a capability boundary we
implement over Chicory's interpreter. It is **not** an audited isolation boundary, and anything
an L2 extension starts through `sandbox.exec` runs unconstrained in the environment (proot and
chroot+BusyBox provide no per-extension isolation, 0002).

## 2. Runtime embedding (Chicory)

### 2.1 Verified vs to-verify

| Fact | Status | Source |
|---|---|---|
| License Apache-2.0 | verified 2026-09-23 | repo LICENSE / footer (sdk-reference) |
| Latest release 1.7.5 (2026-03-24), active | verified 2026-09-23 | releases API (sdk-reference) |
| Pure JVM, zero native deps | verified | README (arch.md sec 13) |
| Repo has an `android-tests` module | verified 2026-09-23 | repo tree |
| `Instance.builder(module).build()`, `instance.export(name)` -> `ExportFunction` | verified 2026-09-23 | chicory.dev/docs Quick Start |
| `Instance.Builder.withMemoryLimits` caps `memory.grow` below the module max | **verified on ART 2026-09-24** (grow returns -1 at the cap; pages allocated on grow) | 0014 spike |
| Built-in fuel / gas metering | **absent in 1.7.5** (verified in source 2026-09-24); our metering pass (sec 4) is required | Chicory 1.7.5 sources |
| Cooperative interruption of a running call (`Thread.interrupt` honoured by the interpreter loop) | **partial**: polled on `call`/`call_indirect`/`br` only; a `br_if` loop ignores it (ART spike). Not used; the fuel global is the interruption channel | 0014 spike, Chicory sources |
| Per-opcode execution listener in the interpreter | exists as `withUnsafeExecutionListener`, documented experimental and hot-path; not used | Chicory 1.7.5 sources |
| Calling a guest export (`alloc`) from inside a host function (re-entrancy) | **verified on ART 2026-09-24** (11 us round trip) | 0014 spike |
| Interpreter speed on ART (instructions/s on the reference device) | **~7 M instr/s** raw, ~15x below HotSpot; AOT-compiling the APK does not help | 0014 spike |
| Build-time/runtime AOT compilers | exist (`build-time-compiler`, `compiler` modules); runtime AOT emits JVM bytecode and **likely does not apply on ART** (arch.md sec 13); build-time AOT to dex unexplored | repo tree |

Design consequence: **this LLD does not rely on any to-verify runtime feature for safety.**
Fuel and interruption are implemented by our own metering pass (sec 4), which works on any
interpreter. If the spike shows Chicory has cheap native metering or interruption, the pass
can be replaced behind `Meter` without touching the ABI.

### 2.2 Module boundary

`:ext-wasm` is a **Kotlin/JVM library module with no Android dependencies** (`services/mobile/ext-wasm`),
so the same code runs in JVM unit tests and inside `easyide-ext test` ([cli.md](cli.md)). It is
the only module that depends on Chicory (arch.md sec 6.2 "isolates the runtime dependency").
Android-specific pieces (clipboard, UI prompts, editor access) arrive through interfaces
implemented in `:app` (sec 6).

Gradle: `libs.chicory.runtime` pinned in `gradle/libs.versions.toml` (exact version, no ranges).
License + maintenance status recorded in ADR-F (0014) per repo rule.

## 3. Module load, compile, cache

### 3.1 Load pipeline

```
bytes (wasm/<file>.wasm from the active version dir)
  -> size check           <= extensions.wasm.maxModuleMb
  -> sha256               cache key
  -> parse                Chicory Parser -> WasmModule
  -> static validation    (3.2)
  -> metering pass        (4) -> metered bytes, cached on disk
  -> parse metered bytes  -> WasmModule, cached in memory (LRU by sha256)
  -> instantiate          (5)
```

### 3.2 Static validation (load refused on any failure, reason in Extension Log)

- Imports: exactly the set `{easyide.host_call: (i32,i32)->i32}`. Any other import (WASI,
  other modules, imported memory/table/global) is refused - this is what makes "can reach nothing
  except host_call" true.
- Exports present with exact signatures: `memory`, `alloc (i32)->i32`, `free (i32,i32)->()`,
  `ext_abi_version ()->i32`, `ext_activate (i32,i32)->i32`, `ext_handle (i32,i32)->i32`.
- Memory: exactly one memory; initial pages * 64 KiB <= effective memory cap (sec 7). If the
  module declares no max or a max above the cap, the host clamps it via `MemoryLimits` (if
  the spike confirms it enforces `memory.grow`); otherwise the module is refused and
  `easyide-ext validate` reports "declare a memory max <= N pages".
- No `start` function (a start function would run guest code before limits are armed).
- Global name `__easyide_fuel` and any export named `__easyide_*` are reserved; refused if present.

### 3.3 Caches

| Cache | Key | Where | Eviction |
|---|---|---|---|
| Metered bytes | `sha256(original) + "-m" + Meter.VERSION` | `<files>/extensions/wasm-cache/<key>.wasm` | deleted when no installed version references the sha; whole dir safe to delete |
| Parsed `WasmModule` | same key | memory, `WasmModuleCache` LRU | size `WasmPolicy.moduleCacheEntries`; dropped on `onTrimMemory` |

The on-disk cache is an optimisation, never trusted for integrity: its file is re-hashed against
a sidecar `<key>.sha256` of the metered output on read; mismatch -> recompute. `Meter.VERSION`
bumps invalidate every entry.

## 4. Metering pass (fuel + interruption)

Runs on the parsed module before instantiation. Rewrites only the code section and appends one
global, so **no function, type, or global index shifts** (no import is added - an added import
would renumber every defined function).

- Append a mutable `i64` global `__easyide_fuel` (not exported to the guest's view of names;
  the host reads/writes it through the instance's global table).
- At every function entry and every `loop` header insert:
  `global.get $f; i64.const <cost>; i64.sub; global.set $f; global.get $f; i64.const 0; i64.lt_s; if; unreachable; end`
  (wasm has no `global.tee`). `<cost>` is the instruction count of the checkpoint's *region*:
  every instruction whose innermost enclosing `loop` is this one (or, for the entry
  checkpoint, that is in no loop). Sound because wasm branches backwards only to loop headers,
  so each region instruction runs at most once per pass of its checkpoint; both `if` arms are
  charged (over-counting allowed). Implemented in `binary/InstructionScanner.kt`.
- Host sets `__easyide_fuel = extensions.wasm.fuelPerCall` before each guest entry
  (`ext_activate`, `ext_handle`, and after every `host_call` returns it is **not** refilled - fuel
  is per top-level call, host-call time excluded).
- Exhaustion executes `unreachable` -> trap -> mapped to `E_LIMIT` (sec 10).

Interruption (wall clock, cancel, deactivate timeout) reuses the same global: the watchdog
(sec 8) writes `__easyide_fuel = -1` from another thread, so the next metered checkpoint traps.
Cross-thread visibility: observed on ART (10-13 ms with a 10 ms tick) and guaranteed by making
mutable globals volatile through Chicory's `GlobalFactory` (no measurable cost). The watchdog
rewrites -1 on every tick, because the guest's read-modify-write can overwrite one store; the
host also refuses every `host_call` once an interrupt is pending.

Chicory's `WasmWriter` only frames raw sections (and uses an API 33 method), so `Meter` has its
own encoder: it re-emits only the code and global sections and copies every other section
byte-for-byte. Supported code: MVP, sign-ext, sat-trunc, bulk memory, reference types,
multi-value, tail calls; SIMD, threads, exception handling and GC are refused by name.

## 5. Instance model

- **One instance per (extension, activation)**, created lazily on the first matching activation
  event ([extension-runtime.md](extension-runtime.md) `ActivationManager` calls `WasmHost.activate`).
- Global scope extensions that are enabled per environment still get one instance for the app
  process; `ext_activate` carries the current `env`, and `config.didChange`-style events carry
  env switches (`env.didChange` is not in v1: an env switch deactivates and re-activates).
- An instance is discarded on: trap, fuel exhaustion, timeout, deactivate, disable, uninstall,
  update (new version), environment stop for environment-scoped extensions, memory pressure
  (arch.md sec 7.7 kill order step 3: idle WASM instances). The next event re-instantiates.
- Guest state is not persistent across instances; guests use `storage.*` for anything durable.

Instance states:

```
 UNLOADED --activation event--> LOADING --load/validate fail--> FAILED(reason)
                                   | instantiated
                                   v
                              ACTIVATING --ext_activate ok--> ACTIVE <--+
                                   |  trap/timeout/ok:false             |  call done
                                   v                                    |
                                DISCARDED <--trap/limit-- BUSY ---------+
                                   ^                       ^ event/command/provider request
                                   |                       |
        crash window full --> DISABLED(crashLoop)        ACTIVE
                                   ^
 ACTIVE --deactivate--> DEACTIVATING --answer or deactivateTimeoutMs--> UNLOADED
```

`DISCARDED` returns to `UNLOADED` unless the crash window is full (sec 10).

## 6. ABI v1 (exactly as sdk-reference)

Core wasm, no WASI, no Component Model. `v` = 1.

| Direction | Symbol | Signature | Host behaviour |
|---|---|---|---|
| guest export | `memory` | memory | read/written only through bounds-checked `GuestMemory` |
| guest export | `alloc` | `(len: i32) -> i32` | host calls it to obtain a buffer for every message it sends in |
| guest export | `free` | `(ptr: i32, len: i32)` | host calls it on every response buffer it has consumed |
| guest export | `ext_abi_version` | `() -> i32` | must return `1`, else load refused |
| guest export | `ext_activate` | `(ptr, len) -> i32` | activation message in; returns response pointer |
| guest export | `ext_handle` | `(ptr, len) -> i32` | event/command/request in; response pointer, `0` = no response |
| host import `easyide` | `host_call` | `(ptr, len) -> i32` | request in; returns pointer to response in guest memory (allocated via guest `alloc`) |

Buffer formats:

- **Host -> guest input** (`ext_activate`, `ext_handle` args): host calls `alloc(n)`, writes `n`
  bytes of raw UTF-8 JSON (no prefix) at the returned pointer, calls the export with `(ptr, n)`.
  The guest owns that buffer and frees it (`free(ptr, n)`), as in the sdk-reference Rust sample.
- **Guest -> host request** (`host_call(ptr, len)`): raw UTF-8 JSON in guest-owned memory; the
  host copies it out and never frees it.
- **Returned pointers** (from `ext_activate`, `ext_handle`, `host_call`) address
  `[u32 little-endian length][UTF-8 JSON]`; the **receiver** frees with `free(ptr, 4 + length)`.
  For `host_call` the host allocates (guest `alloc(4 + n)`) and the guest frees.
- Every message <= `extensions.wasm.maxMessageKb`. Host checks: `ptr + len` inside memory,
  length prefix within bounds and limit, valid UTF-8, JSON object with `v == 1`. Violation ->
  the call fails `E_ARGS` (guest -> host) or the instance is discarded (malformed response).

Messages (shapes from sdk-reference, unchanged):

```
request   {"v":1,"id":7,"fn":"editor.getText","args":{"range":null}}
response  {"v":1,"id":7,"ok":true,"result":"..."}
error     {"v":1,"id":7,"ok":false,"error":{"code":"E_CAPABILITY","message":"..."}}
event     {"v":1,"type":"event","event":"workspace.didSave","data":{"path":"/workspace/a.py"}}
command   {"v":1,"type":"command","id":3,"command":"acme.count","args":[]}
activate  {"extensionId","version","apiVersion","capabilities":[granted],"settings":{...},"env":{"id","distro","arch"}}
deactivate {"v":1,"type":"deactivate"}
```

Error codes: `E_CAPABILITY`, `E_ARGS`, `E_NOT_FOUND`, `E_TIMEOUT`, `E_CANCELLED`, `E_LIMIT`,
`E_UNAVAILABLE`, `E_INTERNAL`. Additive functions are discoverable via `host.functions`.

Additive within v1 (listed in sdk-reference WASM host API): provider
requests use one more `ext_handle` message kind, `{"v":1,"type":"request","id":n,"method":"provider.completion","params":{...}}`,
answered with a normal response object (sec 11).

## 7. Resource limits

All values are settings keys (sdk-reference "Settings keys"), resolved once per instantiation
into an immutable `WasmLimits`; a settings change applies at the next instantiation.

| Limit | Key (default) | Enforcement point |
|---|---|---|
| Module size | `extensions.wasm.maxModuleMb` (8) | load (3.1); also `validate` |
| Memory | `min(easyide.wasm.memoryMb ?: maxMemoryMb, extensions.wasm.maxMemoryMb)` (64) | static validation + `MemoryLimits` on `memory.grow` |
| Fuel per top-level call | `extensions.wasm.fuelPerCall` (50000000) | metering pass (4) |
| Host calls per top-level call | `extensions.wasm.maxHostCallsPerCall` (1000) | `HostCallRouter` counter; exceeding -> `E_LIMIT` response, then instance discarded at call end |
| Wall clock per call | `extensions.wasm.callTimeoutMs` (2000) | watchdog (8); counts guest time only, time blocked in a host function awaiting the user is excluded |
| Activation | `extensions.wasm.activateTimeoutMs` (5000) | watchdog, whole `ext_activate` |
| Deactivation answer | `extensions.wasm.deactivateTimeoutMs` (2000) | watchdog; on expiry the instance is dropped anyway |
| Message size | `extensions.wasm.maxMessageKb` (4096) | `GuestMemory` read/write |
| `net.fetch` response | `extensions.wasm.netMaxResponseKb` (4096) | `NetFunctions` streaming read |
| Storage | `extensions.storage.quotaKb` (5120) | `StorageFunctions.set` |
| Crash loop | `extensions.wasm.maxCrashes` (3) within `crashWindowSec` (300) | `CrashWindow` (10) |
| Master switch | `extensions.wasm.enabled` (true) | `WasmHost.activate` refuses with `E_UNAVAILABLE` |

Internal (not user settings, declared once in `WasmPolicy`, a single declarative table per the
no-hardcoding rule): `moduleCacheEntries`, `eventQueueCapacity`, `watchdogTickMs`,
`maxPendingHandles`.

## 8. Threading

- Each instance owns a single-thread executor (`wasm-<extensionId>`), exposed as a
  `CoroutineDispatcher`. **All** guest entry and all `GuestMemory` access happen on it, so the
  instance is never touched concurrently and needs no locks.
- Callers (`ActivationManager`, command registry, event bus, completion merger) never block:
  they `send` a `WasmJob` into the instance's bounded `Channel` (`WasmPolicy.eventQueueCapacity`)
  and await a `Deferred` if they need a result.
- Host functions run on the worker thread. Those that need the main thread (UI prompts, editor
  reads) `runBlocking` a hop to `Dispatchers.Main.immediate` through the `:app` bridge; this
  blocks only this extension's worker, never main. The watchdog pauses the guest-time clock
  while a host function is running.
- Long operations (`sandbox.exec`, `net.fetch`) return `{"handle":n}` immediately and run on
  `Dispatchers.IO`; completion is enqueued as an event (`sandbox.output`, `sandbox.exit`,
  `net.response`) at most `WasmPolicy.maxPendingHandles` per instance.
- One shared `Watchdog` coroutine (`Dispatchers.Default`, tick `WasmPolicy.watchdogTickMs`)
  checks every BUSY instance's deadline and cancel flag and interrupts via the fuel global (4).
- Main-thread budget: nothing in this module runs on main (arch.md sec 10: < 4 ms main-thread
  work per response applies to the UI apply step only).

## 9. Host function dispatch and capability enforcement

### 9.1 Dispatch table

A static, declarative table keyed by `fn` name. Each row names the capability it needs; the
router checks it **before** decoding args. This table is the single enforcement point for L2.

```kotlin
data class HostFn(
    val name: String,                      // "editor.getText"
    val requires: CapabilityRule,          // None | Cap(id) | FsPath(read|write) | Network | OwnKeyOr(ui.settings)
    val handler: suspend HostCallScope.(args: JSONObject) -> Any?,
)
```

| Group | Rule | Handler delegates to |
|---|---|---|
| `host.*`, `log.write` | none | `WasmHost` itself; `ExtensionLog` |
| `editor.active`, `editor.getText` | `fs.project(read)` | `EditorBridge` (`:app`, backed by `WorkspaceViewModel` state) |
| `editor.applyEdits`, `setSelections`, `insertSnippet`, `decorate` | `fs.project(write)` (`insertSnippet`: none, per sdk-reference "always available") | `ActionRunner` (`applyEdit`, `insertSnippet`) / `EditorBridge` |
| `fs.read/stat/list/watch` | `FsPath(read)`: path under `/workspace` needs `fs.project(read)`, else also `fs.outsideProject` | `ProjectFiles` via `PathMapper` |
| `fs.write/delete/rename` | `FsPath(write)` | `ProjectFiles` via `PathMapper` |
| `events.subscribe` | none (each event's data is filtered by the same fs rule) | `EventRouter` |
| `config.get` / `config.set` | own keys none; other keys `ui.settings` | Pillar 5 `SettingsStore` |
| `ui.showMessage/QuickPick/InputBox/setStatusBarItem/revealStage` | none (own items only) | `UiBridge` (`:app`) |
| `ui.setViewData` | `ui.stage` for stage content; own declared views otherwise | `ContributionRegistry` |
| `commands.execute` | the target command's own capability (resolved via command registry) | Pillar 4 command registry |
| `commands.register` | command must be in `contributes.commands` | command registry |
| `lsp.request/notify/status` | `lsp.request` | `LspFacade` ([lsp-client.md](lsp-client.md)) |
| `sandbox.exec`, `sandbox.kill` | `sandbox.exec` | `ActionRunner` `sandboxExec` paths (extension-runtime.md 8.5): `capture` via `ProcessPort` / `ServerProcessFactory` (separate stdout/stderr), `terminal` via `commandPtyParams` |
| `clipboard.read/write` | `clipboard` | `ClipboardBridge` (`:app`, `ClipboardManager`) |
| `net.fetch` | `network(hosts)`: https only, host suffix match on `*.x`; redirects to undeclared hosts not followed; loopback, link-local and private addresses refused after DNS resolution | `NetFunctions` (`HttpURLConnection`, pattern of `RootfsProvisioner.download`) |
| `storage.*` | none (own namespace) | `ExtensionStorage` |
| `secrets.get` | `secrets.read` | per-extension Keystore namespace beside `GitCredentials` (separate alias/file) |
| `providers.register` (additive, sec 11) | none; kind must be declared in `easyide.wasm.providers` | `ProviderRegistry` |

### 9.2 Capability check

```
granted = declared (easyide.capabilities) INTERSECT approved (state.json approval record)
```

Computed once at activation into an immutable `CapabilitySet` (from `:extensions`). `network`
hosts are parsed into a `HostMatcher`. Unknown `fn` -> `E_NOT_FOUND`. Denied -> `E_CAPABILITY`
response to the guest + one Extension Log line per (fn, call site) per instance; the call does
not trap the instance (a guest may probe and degrade).

Hard invariants enforced here, independent of capabilities:

- `sandbox.exec` env = clean env (`GuestEnvironment.defaults`) + guest-supplied `env`; the host
  strips `EASYIDE_GIT_TOKEN` (the variable `GitRemote` uses, 0012) and any key in
  `WasmPolicy.reservedEnvKeys`. No capability grants the git token or Claude API key.
- `fs.*` paths are guest paths; `PathMapper` resolves and canonicalises, and rejects any path
  whose host resolution leaves the allowed root (symlink escape) with `E_CAPABILITY`.
- `secrets.get` reads only `ext/<extensionId>/<name>`; there is no enumeration of other namespaces.
- A host call that would re-enter the same BUSY instance (e.g. `commands.execute` of its own
  command) is refused with `E_UNAVAILABLE`, never queued (deadlock; threat-model ST-21).

## 10. Crash, trap and error handling

Real boundaries here: guest code (traps), the Chicory runtime, host I/O inside handlers.

| Failure | Detected by | Effect on call | Effect on instance |
|---|---|---|---|
| Trap (`unreachable`, OOB, div by zero, stack overflow) | `ChicoryException` (`TrapException`, `WasmRuntimeException`, "call stack exhausted") | caller gets `E_INTERNAL` | discarded, counted |
| Fuel exhaustion | trap at metering checkpoint with fuel <= 0 and no interrupt flag | `E_LIMIT`, logged | discarded, counted |
| Wall clock / cancel | watchdog interrupt flag set | `E_TIMEOUT` / `E_CANCELLED` | discarded, counted (cancel: not counted) |
| Memory grow beyond cap | `memory.grow` returns -1 (guest decides) or trap | as the guest behaves | counted only if it traps |
| Malformed response buffer | `GuestMemory` bounds / JSON parse | `E_INTERNAL` | discarded, counted |
| Host function throws (I/O) | `HostCallRouter` catch at the handler boundary | guest gets `ok:false` with mapped code (`E_UNAVAILABLE` for env stopped) | kept |
| `ext_activate` returns `ok:false` | response | activation failed | discarded; reported to `ActivationManager` (its "2 consecutive -> safe mode" rule applies) |
| JVM `StackOverflowError`/`OutOfMemoryError` in interpreter | caught at the guest-entry boundary only | `E_LIMIT` | discarded, counted; OOM also triggers `onTrimMemory`-style cache drop |

`CrashWindow`: timestamps of counted failures per extension; `>= extensions.wasm.maxCrashes`
within `extensions.wasm.crashWindowSec` -> `ExtensionStateStore.disable(id, reason = CRASH_LOOP)`,
contributions greyed, notification "Disabled after repeated crashes - re-enable in Extensions".
The extension stays disabled until the user re-enables it (sdk-reference).

Every failure writes one Extension Log entry: extension id, version, entry point, error code,
fuel used, guest time, last `fn` called. No stack of guest memory is logged.

## 11. Event delivery and providers

### 11.1 Events

- `EventRouter` holds per-instance subscriptions from `events.subscribe{names}`.
- Sources: workspace open/change/save/close and selection (from `WorkspaceViewModel` /
  editor bridge), `config.didChange` (settings store), `lsp.didChangeState` (LSP facade),
  `fs.changed` (`ProjectFileWatcher`, filtered by the `fs.watch` glob).
- Delivery is ordered per instance through its channel. `workspace.didChange` and
  `editor.didChangeSelection` are **coalesced** per path while queued (only the latest pending
  is kept) so a fast typist never fills the queue. If the queue is still full, the oldest
  non-coalescible event is dropped and an `events.dropped` count is attached to the next event.
- Events do not activate an extension by themselves; only `activationEvents` do
  ([extension-runtime.md](extension-runtime.md)). Events for an UNLOADED instance are dropped.

### 11.2 Commands

Command registry resolves a command owned by a WASM extension -> `WasmHost.executeCommand`
-> `{"type":"command",...,"context":{...}}` (context = when-clause snapshot of arch.md 6.3 step 2)
-> response `result` returned to the caller (`executeCommand`/`${command:id}` users).

### 11.3 Providers (completion, hover) merged with LSP

Additive v1 function: `providers.register{kind, languages}` with `kind` in
`completion`, `hover`, `codeLens`, `documentSymbol` (v1 set: completion and hover only).
Requests arrive as `{"type":"request","method":"provider.<kind>","params":{uri, position, languageId, version, triggerCharacter?}}`
(LSP-shaped params with guest paths) and responses use LSP result shapes (`CompletionItem[]`,
`Hover`).

- `ProviderRegistry` exposes WASM providers to the language UI through the same
  `CompletionSource` / `HoverSource` interfaces the LSP client implements
  ([lsp-client.md](lsp-client.md)), so the completion merger treats a WASM provider as one more
  source beside LSP servers, snippets and word completion. Ranking: LSP > WASM > snippets > words
  by default; LSP server `priority` semantics are not reused.
- Deadline: a provider request uses `extensions.wasm.callTimeoutMs`; a late or failed
  provider is simply absent from the merge (no error UI), and a response whose `version` is
  stale is dropped, same rule as LSP (arch.md 7.4).
- Hover: results from all sources are concatenated in the card, LSP first.

## 12. Public interfaces (Kotlin, concise)

```kotlin
// :ext-wasm (pure JVM)
class WasmHost(
    private val loader: WasmModuleLoader,
    private val functions: HostFunctionTable,
    private val limits: () -> WasmLimits,              // resolved from settings
    private val log: ExtensionLog,
    private val crashes: CrashPolicy,                   // callback into ExtensionStateStore
    private val clock: () -> Long,
) {
    suspend fun activate(ext: WasmExtension, activation: ActivationMessage): Result<Unit>
    suspend fun deactivate(extensionId: String)
    suspend fun executeCommand(extensionId: String, command: String, args: JSONArray, context: JSONObject): HostResult
    fun postEvent(extensionId: String, event: String, data: JSONObject)   // non-blocking
    suspend fun providerRequest(extensionId: String, method: String, params: JSONObject): HostResult
    fun onTrimMemory(level: Int)                                            // drop idle instances + module cache
    fun state(extensionId: String): StateFlow<InstanceState>
}

data class WasmExtension(
    val id: String, val version: String, val moduleFile: File, val moduleSha256: String,
    val manifestMemoryMb: Int?, val capabilities: CapabilitySet,
)
data class WasmLimits(
    val maxModuleBytes: Long, val maxMemoryPages: Int, val fuelPerCall: Long, val maxHostCallsPerCall: Int,
    val callTimeoutMs: Long, val activateTimeoutMs: Long, val deactivateTimeoutMs: Long,
    val maxMessageBytes: Int, val netMaxResponseBytes: Int, val storageQuotaBytes: Long,
)
sealed interface HostResult { data class Ok(val result: Any?) : HostResult; data class Err(val code: ErrorCode, val message: String) : HostResult }
enum class ErrorCode { E_CAPABILITY, E_ARGS, E_NOT_FOUND, E_TIMEOUT, E_CANCELLED, E_LIMIT, E_UNAVAILABLE, E_INTERNAL }

class WasmModuleLoader(cacheDir: File, meter: Meter, moduleCache: WasmModuleCache) {
    fun load(file: File, expectedSha256: String, limits: WasmLimits): Result<LoadedModule>
}
class Meter { fun instrument(module: ByteArray): ByteArray; companion object { const val VERSION = 1 } }
class GuestMemory(/* wraps Chicory Memory */) {
    fun readRequest(ptr: Int, len: Int, maxBytes: Int): JSONObject
    fun readPrefixed(ptr: Int, maxBytes: Int): Pair<JSONObject, Int>   // value, bytes to free
    fun writeInput(json: ByteArray): Pair<Int, Int>                      // via guest alloc
    fun writePrefixed(json: ByteArray): Int                              // via guest alloc(4+n)
}

// Bridges implemented in :app (Android side)
interface EditorBridge { suspend fun active(): JSONObject?; suspend fun getText(range: JSONObject?): String; suspend fun decorate(extId: String, kind: String, items: JSONArray) }
interface UiBridge { suspend fun showMessage(extId: String, req: JSONObject): String?; suspend fun quickPick(extId: String, req: JSONObject): Any?; suspend fun inputBox(extId: String, req: JSONObject): String? }
interface ClipboardBridge { fun read(): String?; fun write(text: String) }
```

`HostFunctionTable` is built once in `AppContainer` from the declarative rows (9.1) and the
bridges; the CLI builds it from fakes.

## 13. Persistence

| Data | Location | Owner |
|---|---|---|
| Metered module cache | `<files>/extensions/wasm-cache/` | `WasmModuleLoader` (derived; deletable) |
| Extension storage (`storage.*`) | `<files>/extensions/storage/<extId>/{global,env-<envId>,project-<projectId>}.json` | `ExtensionStorage`; atomic temp+rename writes; quota checked on write |
| Secrets | Keystore-backed file, alias per extension namespace, separate from `GitCredentials` file | `ExtensionSecrets` |
| Crash timestamps | memory only; the resulting disable is persisted by `ExtensionStateStore` (`state.json`) | `CrashWindow` |

Paths come from `ExtensionPaths` (defined in [registry-and-install.md](registry-and-install.md) sec 11),
never string concatenation.

## 14. Integration points with existing code

| Existing code | Use |
|---|---|
| `ServerProcessFactory` (sandbox-runtime, lsp-client.md) and `LinuxEnvironment.commandPtyParams` (extension-runtime.md 8.5) | back `sandbox.exec` through `ActionRunner` as argv (no shell string); stdout/stderr stream into `sandbox.output` events |
| `sandbox-runtime/.../backend/SandboxLauncher.kt` `GuestEnvironment.defaults` | clean env for `sandbox.exec` |
| `sandbox-runtime/.../git/GitRemote.kt` (`EASYIDE_GIT_TOKEN`) | the variable the host must strip (0012) |
| `sandbox-runtime/.../files/ProjectFiles.kt`, `ProjectFileWatcher.kt` | `fs.*`, `fs.watch` events |
| `sandbox-runtime/.../SandboxPaths.kt` `projectDir`, `guestProjectPath()` | guest path mapping (`PathMapper` impl) |
| `app/.../ui/screens/workspace/WorkspaceViewModel.kt` | active editor, open docs, save events for `EditorBridge`/`EventRouter` |
| `app/.../AppContainer.kt` | constructs `WasmHost` (singleton, application scope) |
| `app/.../EasyIdeApplication.kt` | forwards `onTrimMemory` to `WasmHost.onTrimMemory` |
| `sandbox-runtime/.../git/GitCredentials.kt` | pattern only (Keystore AES) for `ExtensionSecrets`; never read by this module |

## 15. Testing hooks

| Test | Layer | What |
|---|---|---|
| ABI conformance suite | JVM unit (`:ext-wasm`) | `.wat` fixtures compiled at build time: good module; each missing export; wrong signature; extra import (WASI); `start` function; memory without max; `ext_abi_version` != 1. Each asserts the exact load error |
| Buffer protocol | JVM unit | length prefix at memory edge, prefix larger than memory, `4+len` overflow of i32, non-UTF-8, `v` != 1, message exactly at and 1 byte over `maxMessageKb` |
| Limits | JVM unit | infinite `loop` -> `E_LIMIT` within `fuelPerCall`; host-call storm -> `E_LIMIT` at `maxHostCallsPerCall`; slow loop with high fuel -> `E_TIMEOUT` via watchdog; `memory.grow` past cap returns -1 |
| Capability matrix | JVM unit | for every `HostFn` row: declared+approved -> ok; declared only -> `E_CAPABILITY`; network host suffix cases; `fs` path escape via `..` and symlink -> `E_CAPABILITY`; `EASYIDE_GIT_TOKEN` never in `sandbox.exec` env |
| Crash window | JVM unit with fake clock | 3 traps in 300 s -> disabled; 3 traps spread over 301 s -> not |
| Metering pass | JVM unit + property | instrumented module is valid (re-parse), behaviourally equal on the spec test subset, and no index shifts (exports/elem/call targets unchanged) |
| ABI fuzzing | JVM, Jazzer (license to verify before adoption) or a seeded random harness | fuzz (1) request JSON into `HostCallRouter`, (2) raw guest memory + pointers into `GuestMemory`, (3) wasm binaries into `WasmModuleLoader` + `Meter`. Oracle: no host exception escapes the boundary; instance discarded or error returned |
| Samples | JVM integration | Rust and AssemblyScript template outputs from `easyide-ext init` (built in CI) run a command and a view provider (M7 exit criterion) |
| Device spike | instrumented, reference device | interpreter speed (instr/s), cross-thread global write visibility, re-entrant `alloc` from a host function, `MemoryLimits` behaviour on ART. Results recorded in ADR-F |

Hooks: `WasmHost` takes `clock`, `limits` and `HostFunctionTable` by constructor; `Watchdog`
tick is injectable; `InstanceState` is a `StateFlow` observable from tests and the
contribution inspector.

## 16. Open issues

1. Resolved 2026-09-24: Chicory re-entrancy works on ART (0014 spike).
2. Resolved 2026-09-24: fuel global writes are visible cross-thread (volatile globals, spike).
3. Time excluded while a host function awaits the user: a guest can hold its worker forever
   behind a quick pick. Acceptable (user-visible) but no per-extension "max prompt time" yet.
4. Whether global-scope WASM extensions should get one instance per environment instead of one
   per process (simpler env semantics, more memory).
5. Jazzer vs hand-rolled fuzzer: new dependency needs license/maintenance verification.

## Deviations

- Resolved: crash-window disable, memory/timeout defaults and `editor.applyEdits` now match in
  arch.md; `providers.register`, `{"type":"request"}` and `easyide.wasm.providers` are in
  sdk-reference; `sandbox.exec` follows extension-runtime.md 8.5.
- `WasmPolicy.*` internal constants are not settings keys; they are listed here so they have one
  declarative home.
- Implementation notes (2026-09-24, `services/mobile/ext-wasm`):
  - JSON is `kotlinx.serialization.json` `JsonElement`/`JsonObject` (pure JVM), not `org.json`.
  - Load order is size -> sha256 -> metering pass on raw bytes -> one Chicory parse of the metered
    bytes -> static validation (sec 3.1 parses twice; parsing dominates load time on ART). The
    disk-cache sidecar stores the metered sha256 and the fuel global index.
  - No `Thread.interrupt`: Chicory misses `br_if` loops; the fuel global covers every loop.
  - `onTrimMemory(level)` is `trimMemory()`: Android trim levels are mapped in `:app`.
  - Imports: only `easyide.host_call` may be imported; a guest that never calls the host may
    import nothing.
  - `fs.project(write)` implies `fs.project(read)` (prompt text "read and change").
  - The dropped-event count rides on the next event as `"events.dropped": n`.
  - Worker threads get an 8 MiB JVM stack (`WasmPolicy.WORKER_STACK_BYTES`), about 10000 guest
    frames on the spike device.
  - `:ext-wasm` depends on no project module: capabilities arrive as the granted id list and all
    effects go through the ports in `host/HostPorts.kt`, which `:app` implements. hld.md rule 4
    allows an `:extensions` edge; it is not needed yet.
  - Re-entrancy guard: a call from any WASM worker thread into a busy instance is refused
    (`E_UNAVAILABLE`), covering cycles through other extensions, not only an instance's own commands.
- App integration notes (2026-09-24, `app/extensions/wasm/`):
  - The module sha256 is recorded at first load in `<files>/extensions/wasm-modules.json` (keyed by
    module path; `ModuleShas`), because `state.json` has no module hash yet; the installer can take
    this over. Every later load is checked against it.
  - Crash loops: `WasmHost`'s own crash window calls `CrashPolicy`, which logs the reason and
    persists the disable through `ExtensionInventory.setCrashDisabled` (the runtime then marks the
    extension CRASH_DISABLED); `ActivationManager.reportCrash` is not used for L2.
  - Command failures carry the guest's error code: `CommandOutcome.Failed` gained an `error`
    (default `E_INTERNAL`), so a denied host call shows as `E_CAPABILITY` in the Extension Log.
  - `commands.execute` gates on the first (by id) capability of the target's declarative action
    (the port contract carries one id); WASM targets act under their own grants.
  - Not yet: contributed views are not rendered anywhere, so `ui.setViewData` is stored
    (`WasmUiState.viewData`); `editor.decorate` and `lsp.notify` answer `E_UNAVAILABLE`;
    `fs.watch` sees only saves made in the app; `secrets.get` returns null (no secrets UI);
    `net.fetch` checks resolved addresses separately from the connection's own lookup (DNS
    rebinding gap); outside-project paths map to the rootfs and absolute symlinks there are refused.
