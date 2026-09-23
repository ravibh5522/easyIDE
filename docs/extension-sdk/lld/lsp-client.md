# Extension SDK - LSP Client (core)

Low-level design of the `:lsp` module core: JSON-RPC, transport, process spawn, path mapping,
document sync, `LspSession`, logging, threading, configuration and tests.
Server lifecycle state machine, `LanguageServerManager`, memory budgets and crash backoff are
in [lsp-lifecycle.md](lsp-lifecycle.md); per-feature request pipelines, `ClientCapabilities`
and debounce/staleness rules are in [lsp-features.md](lsp-features.md).

Status: PROPOSED (2026-09-23). Nothing here is implemented.
Sources of truth: [arch.md sec 7](../arch.md#7-lsp-client-design) (LSP client design),
[sdk-reference.md](../sdk-reference.md) (settings keys, `easyide.languageServers`, when-clauses).
Feature area: arch.md 5.2 LSP language intelligence. Decision: ADR-I
(`docs/decision/0017-...`, Proposed). Milestone: M2 (core), M4 (extra features).

## 1. Responsibility and module boundaries

| Unit | Module | Owns | Does not own |
|---|---|---|---|
| `Framing`, `JsonRpcConnection` | `:lsp` | bytes <-> messages, id correlation, cancel, timeouts | process, LSP semantics |
| `LspSession` | `:lsp` | one server: init handshake, capabilities, typed requests, state | which server, when to start |
| `DocumentStore`, `DocumentSync` | `:lsp` | text snapshots, versions, incremental diff, open/close/save | editor UI state |
| `LanguageServerManager` | `:lsp` | keyed sessions, routing, lazy start, idle stop, backoff, budgets | spawning mechanics |
| `PathMapper` | `:lsp` interface; `SandboxPathMapper` in app | host path <-> guest URI | - |
| `ServerProcessFactory` | `:sandbox-runtime` (beside `ShellRunner`) | spawn a stdio-pipe process in an environment, clean env | JSON-RPC |
| `LspWorkspaceBridge` | app `ui/screens/workspace/lsp/` | maps `EditorTab` changes to `DocumentStore`, results to UI state | protocol |

`:lsp` is a Kotlin/JVM module (`services/mobile/lsp`) with no Android or Compose imports, so
every class below except the bridge and the process factory runs in plain JVM unit tests.
arch.md 6.2 calls the manager `ServerSupervisor`; this LLD uses `LanguageServerManager` for
the same component (one name in code: `LanguageServerManager`).

## 2. JSON-RPC 2.0 over stdio

### 2.1 Framing

Wire format (LSP base protocol): ASCII header lines terminated by `\r\n`, an empty line, then
exactly `Content-Length` bytes of UTF-8 JSON.

```
Content-Length: 52\r\n
\r\n
{"jsonrpc":"2.0","id":1,"method":"shutdown"}
```

Reader rules (`FrameReader`, pull-based over a `BufferedInputStream`):
- Read header lines byte by byte up to `\r\n`; a header line longer than
  `LspPolicy.MAX_HEADER_BYTES` is a protocol error.
- Header names case-insensitive. `Content-Length` required, decimal, `0 < n <=
  LspPolicy.MAX_MESSAGE_BYTES`. `Content-Type` accepted and ignored (charset is always
  UTF-8 per spec). Unknown headers ignored.
- Body read with `readNBytes(n)`; short read = EOF = transport closed.
- Length is in **bytes**, never chars: the body is decoded only after the full read.
- Any protocol error (bad header, oversize, JSON nested deeper than `LspPolicy.MAX_JSON_DEPTH`,
  malformed JSON, missing `jsonrpc`) closes the connection with
  `CloseReason.ProtocolError(detail)`; the session treats it as a crash.
  No resynchronisation attempt - a server that emits garbage on stdout (e.g. a stray
  `print`) is broken and its stderr tail is what the user needs.

Writer rules (`FrameWriter`): encode JSON to bytes once, write header + body with one
`write` of a pre-sized array, then `flush()`. Only the writer coroutine touches the stream
(sec 8).

### 2.2 Message types

```kotlin
sealed interface RpcId { data class Num(val v: Long) : RpcId; data class Str(val v: String) : RpcId }

sealed interface RpcMessage {
    data class Request(val id: RpcId, val method: String, val params: Json?) : RpcMessage
    data class Notification(val method: String, val params: Json?) : RpcMessage
    data class Response(val id: RpcId?, val result: Json?, val error: ResponseError?) : RpcMessage
}

data class ResponseError(val code: Int, val message: String, val data: Json? = null)

object ErrorCodes {                    // JSON-RPC + LSP 3.17 reserved codes
    const val PARSE_ERROR = -32700; const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601; const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603; const val SERVER_NOT_INITIALIZED = -32002
    const val REQUEST_FAILED = -32803; const val SERVER_CANCELLED = -32802
    const val CONTENT_MODIFIED = -32801; const val REQUEST_CANCELLED = -32800
}
```

`Json` is a typealias for the JSON tree type of the JSON library already used by the app
(`org.json`, see sec 12 O1). Classification of an incoming object: has `method` and `id` ->
Request; `method` without `id` -> Notification; `id` (possibly null) with `result` or `error`
-> Response; anything else -> protocol error.

Protocol types (`Position`, `Range`, `TextEdit`, `WorkspaceEdit`, `Diagnostic`, ...) are
Kotlin data classes in `dev.easyide.lsp.protocol` with hand-written `toJson()` /
`fromJson()` for only the methods in lsp-features.md (hand-rolled per arch.md 7.1; ADR-I
records the LSP4J comparison). Unknown fields are ignored on read; optional fields absent
on write.

### 2.3 Correlation

- Outgoing ids: `RpcId.Num` from a per-connection `AtomicLong` starting at 1. Never reused.
- `pending: HashMap<Long, PendingCall>` confined to the connection's serial dispatcher.
  `PendingCall(method, sentAtNanos, deferred: CompletableDeferred<Json?>)`.
- Response with unknown id: logged at `verbose` trace, dropped (normal after a cancel race).
- Response with `id: null` + error: protocol-level error from server, logged, dropped.
- Incoming server requests keep their `RpcId` verbatim (Num or Str) for the reply.
- On close, every pending deferred completes exceptionally with `ConnectionClosed`.

### 2.4 Cancellation and timeouts

```kotlin
suspend fun request(method: String, params: Json?, timeoutMs: Long): Json?
```
- Implemented as `withTimeoutOrNull(timeoutMs) { send; deferred.await() }`.
- Caller coroutine cancelled (typing superseded a completion, tab closed) -> in the
  `finally`: if still pending, remove it and send `$/cancelRequest {id}`; the eventual
  response is dropped as unknown-id.
- Timeout -> same cancel path, returns `null`; logged as `timeout <method> <ms>`; never a
  dialog (arch.md 7.4). Timeout values: `LspPolicy.timeoutFor(method)` = the
  `lsp.requestTimeoutMs` setting times a per-method multiplier from the policy table
  (sec 9); `initialize` uses `startupTimeoutSec` instead.
- Error `REQUEST_CANCELLED` or `SERVER_CANCELLED` -> treated as `null`, silent.
- Error `CONTENT_MODIFIED` -> `null`; pipelines that are version-bound re-request at most
  once after the next flush (lsp-features.md sec 3).
- Other errors -> `LspRequestException(code, message)`; pipelines log and show nothing
  except for user-initiated actions (rename, format) where a snackbar gives the message.
- Incoming `$/cancelRequest` from the server for a server->client request we are handling:
  the handler job for that id is cancelled and we reply `REQUEST_CANCELLED`.

### 2.5 Server-to-client traffic

| Method | Kind | Handling |
|---|---|---|
| `textDocument/publishDiagnostics` | notif | `DiagnosticStore` (lsp-features.md 4.1) |
| `window/logMessage`, `$/logTrace` | notif | `LspLog` ring, level from `MessageType` |
| `window/showMessage` | notif | `Error`/`Warning` -> snackbar via `LspUi`; others -> log only |
| `window/showMessageRequest` | request | `LspUi.ask(actions)`; dismissed -> `null` result |
| `window/workDoneProgress/create` | request | accept (empty result), track token |
| `$/progress` | notif | status bar item text for that server (begin/report/end) |
| `workspace/configuration` | request | `ConfigurationProvider.sections(key, items)` (arch.md 6.3 settings resolution) |
| `workspace/workspaceFolders` | request | `[{uri:"file:///workspace", name: projectName}]` |
| `workspace/applyEdit` | request | `EditApplier.apply(edit)` -> `{applied, failureReason?}` |
| `client/registerCapability`, `client/unregisterCapability` | request | `DynamicRegistry`: `didChangeWatchedFiles` globs to `ProjectFileWatcher`, `didChangeConfiguration` (arch.md 7.5) |
| `workspace/{semanticTokens,inlayHint,codeLens,diagnostic}/refresh` | request | ack, then re-run that pipeline for visible docs |
| `telemetry/event` | notif | dropped |
| anything else | request / notif | `METHOD_NOT_FOUND` / dropped, logged once per method |

Handlers run on the session dispatcher and must not block; UI prompts suspend.

## 3. Transport: spawning a server in the sandbox

### 3.1 Why not the terminal path

Existing launch code:
- `SandboxLauncher.buildLaunchSpec(LaunchRequest) -> LaunchSpec(argv, environment, workingDir)`
  (`sandbox-runtime/.../backend/SandboxLauncher.kt`), implemented by `ProotLauncher` and
  `ChrootLauncher`.
- `SandboxShell.start` wraps the command as `/bin/sh -c <string>`, clears the environment,
  adds `PROOT_LOADER`, `PROOT_LOADER_32`, `LD_LIBRARY_PATH`, and **merges stderr into
  stdout** (`redirectErrorStream(true)`); returns `TerminalProcess`, which reads by polling
  `available()` and splits lines, with `\r` overwrite handling.
- `SandboxShell.interactiveParams` returns `PtyShellParams` for Termux's `TerminalSession`
  (a pty).

None fits a language server: a pty translates `\n` -> `\r\n`, echoes input and applies line
discipline, which corrupts `Content-Length` framing; merged stderr would inject log text into
the JSON stream; line splitting and `\r` rewriting are wrong for a binary-exact protocol.
Servers need **three separate plain pipes**.

### 3.2 `ServerProcessFactory` (new, `sandbox-runtime/.../shell/ServerProcessFactory.kt`)

```kotlin
data class ServerLaunch(
    val environmentId: String,
    val hostProjectDir: File,
    val argv: List<String>,                 // guest argv, variables already expanded
    val env: Map<String, String>,           // declared env from ServerConfig only
)

class ServerProcess internal constructor(private val process: Process) {
    val stdin: OutputStream get() = process.outputStream
    val stdout: InputStream get() = process.inputStream
    val stderr: InputStream get() = process.errorStream
    val pid: Long get() = process.pid()
    val isAlive: Boolean get() = process.isAlive
    fun terminate()                          // SIGTERM (Process.destroy)
    fun kill()                               // SIGKILL (destroyForcibly)
    suspend fun awaitExit(): Int
}

class ServerProcessFactory(
    private val linuxEnvironment: LinuxEnvironment,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** @throws SandboxError if the environment is not ready or proot cannot start. */
    suspend fun spawn(launch: ServerLaunch): ServerProcess
}
```

Spawn procedure:
1. Refuse if `!linuxEnvironment.isReady(envId)` (no fallback to Android `sh`: servers live in
   the guest). The session maps this to `FAILED(EnvironmentNotReady)`.
2. Same preparation as `LinuxEnvironment.start`: `prootInstaller.ensureInstalled`,
   `provisioner.ensureGuestDefaults(rootfs)`. Implemented as a new
   `LinuxEnvironment.startServer(launch)` so the proot-ready logic stays in one place;
   `ServerProcessFactory` is the thin public entry that `:lsp` callers get.
3. Guest command: `["/bin/sh", "-c", "exec \"$0\" \"$@\"", argv...]`. Resolves `argv[0]`
   through the guest `PATH` with no string quoting (arguments pass as positional
   parameters), and `exec` makes the server replace the shell so there is one guest process.
4. `LaunchRequest(rootfs, hostProjectDir, guestProjectPath = paths.guestProjectPath(),
   command, extraEnvironment)` through the environment's `SandboxLauncher` (today
   `ProotLauncher`; `ChrootLauncher` when the environment uses the chroot backend).
   proot's `--kill-on-exit` (already in `ProotLauncher`) means killing proot kills the server.
5. `ProcessBuilder(spec.argv).directory(spec.workingDir)`, `redirectErrorStream(false)`,
   `environment().clear()` then exactly:
   `GuestEnvironment.defaults(GUEST_HOME)` (HOME, PATH, TERM, LANG) with `TERM=dumb`,
   the proot loader/library variables `SandboxShell` sets, then `ServerLaunch.env`.
   Nothing from the app process, no git token (decision 0012), no API keys. Cwd is
   `/workspace` via the launcher's `-w`.
6. Return `ServerProcess`. A `SandboxShell` sibling method builds the proot-side env so the
   loader-variable names stay in one companion object.

`stderr` is drained continuously by the session (sec 7) - an undrained pipe fills its
64 KB kernel buffer and blocks the server.

### 3.3 PID and process tree

`ServerProcess.pid` is proot's pid (or `su` under chroot). The server is a descendant. RSS is
sampled over the tree: `ProcTree.descendants(pid)` reads `/proc/<p>/task/<p>/children`
recursively (fallback: scan `/proc/*/stat` ppid) and sums `VmRSS` from `/proc/<p>/status`.
proot's own RSS is included; it is small and it is real cost. Under chroot the tree is root-
owned and may not be readable by the app uid - see O3.

## 4. Path mapping

```kotlin
interface PathMapper {
    /** Host file -> guest URI, or null when the file is not visible in the guest. */
    fun toGuestUri(host: File): String?
    /** Guest URI -> host file + access, or null when it has no host counterpart. */
    fun toHost(uri: String): HostLocation?
    val rootUri: String                      // "file:///workspace"
}
data class HostLocation(val file: File, val readOnly: Boolean, val projectRelative: String?)
```

`SandboxPathMapper(paths: SandboxPaths, projectId, environmentId)` in app:

| Guest | Host | Access |
|---|---|---|
| `file:///workspace` + `/rel` | `SandboxPaths.projectDir(projectId)/rel` | read-write, `projectRelative = rel` |
| `file:///dev`, `/proc`, `/sys` + anything | none (Android pass-through mounts) | null |
| `file:///<abs>` other | `SandboxPaths.rootfsDir(environmentId)/<abs>` | read-only (arch.md 7.2) |
| non-`file` scheme (`untitled:`, `jdt:` ...) | none | null |

Rules:
- The guest workspace prefix comes from `SandboxPaths.guestProjectPath()`; never a literal.
- URIs: percent-encode per RFC 3986 path segment on output; decode on input; compare
  after normalising `file:///` vs `file:/`, `.` / `..` segments, and a trailing `/`.
- `toHost` rejects results whose canonical path escapes the project dir or rootfs
  (a `..`-laden URI or a symlink pointing out) -> null. This is path hygiene, not a security
  boundary: the server can already read anything in the guest.
- Absolute symlinks inside the rootfs (`/usr/lib/python3 -> /usr/lib/python3.12`) are
  resolved by the server before it sends URIs; the mapper does not chase links.
- A location that maps to null is dropped from results; if every location of a navigation
  result is dropped, the UI says "Location is outside the project and environment".
- The editor opens `readOnly` locations through a new `ProjectFiles`-sibling reader for
  rootfs paths (not `ProjectFiles.open`, which is project-scoped); tab is `editable = false`.

Positions: Kotlin `String` is UTF-16, and so is the LSP default. The client advertises
`general.positionEncodings = ["utf-16"]` only, so offsets need no transcoding (utf-8 would
need it). `LineIndex` converts offset <-> `Position(line, character)`:

```kotlin
class LineIndex(text: String) {              // line-start offsets, built once per snapshot
    fun position(offset: Int): Position
    fun offset(pos: Position): Int           // clamps character to line length, line to last
    val lineCount: Int
}
```

## 5. Document sync

### 5.1 Data

```kotlin
data class DocSnapshot(val uri: String, val languageId: String, val version: Int, val text: String) {
    val lines: LineIndex by lazy { LineIndex(text) }
}

class DocumentStore {                         // one per (environment, project); shared by all sessions
    val snapshots: StateFlow<Map<String, DocSnapshot>>
    fun open(uri: String, languageId: String, text: String)        // version = 1
    fun update(uri: String, text: String)     // version + 1 when text differs (reference then equals)
    fun saved(uri: String, text: String)
    fun close(uri: String)
}
```

Per session, `DocumentSync` keeps for every open uri `SyncedDoc(lastSentVersion,
lastSentText, dirty)`. `lastSentText` is the same immutable `String` instance the store
held, so N sessions syncing one doc at the same version share one copy.

### 5.2 Incremental change (prefix/suffix diff)

```
old = lastSentText, new = snapshot.text
p = common prefix length                  (bounded by min(len))
s = common suffix length, s <= min(len) - p
range = [LineIndex(old).position(p), LineIndex(old).position(old.length - s)]
text  = new.substring(p, new.length - s)
-> didChange {textDocument:{uri, version: snapshot.version}, contentChanges:[{range, text}]}
```
O(n) once per flush, no edit history kept (arch.md 7.3). Budget: < 2 ms for a 400 KB doc
(arch.md sec 10). The resulting `EditDelta(p, oldEnd, newEnd, lineDelta)` is also published
to pipelines so decorations shift instead of flicker (lsp-features.md sec 3).

Sync kind from `serverCapabilities.textDocumentSync`:
- `Incremental` -> one range change per flush as above.
- `Full` -> whole text; if `text.length * 3 > LspPolicy.MAX_FULL_SYNC_BYTES` (UTF-8 upper
  bound, no encode needed) the doc is **not** synced to that server: `didClose` if open, and
  the editor notice says "Too large for <server>; language features off".
- `None` -> no didOpen/didChange/didClose to that server; features that need the text are
  off for it.

### 5.3 Triggers and ordering

| Event (from `LspWorkspaceBridge`) | Action per eligible session |
|---|---|
| tab opened (`openContent`) with an eligible language | `didOpen {uri, languageId, version, text}` |
| content changed (`onContentChanged`) | mark dirty; (re)arm flush timer `lsp.didChangeDebounceMs` |
| flush timer fires | send one `didChange` (sec 5.2) |
| any request about to be sent for that uri | flush synchronously first; request carries that version |
| saved (`onSaveActiveTab` success) | flush, then `didSave {uri, text?}` (`text` iff `save.includeText`) |
| tab closed (`onTabClosed`) | flush discarded, `didClose {uri}` |
| session reaches RUNNING | `didOpen` every open doc of its languages at the current version |
| file renamed/deleted by the explorer | `didClose` old uri (+ `didOpen` new if still open), then `didChangeWatchedFiles` |

Ordering guarantee: didOpen < didChange* < didSave < didClose per uri per session, because
all of them and every request go through the session's single writer in enqueue order.

Eligibility: tab is a `FileContent.Text` tab with `editable == true` or a read-only rootfs
file (synced, never edited); binary previews and **truncated** tabs are never synced (a
prefix of a file would produce false diagnostics).

Format-on-save: if `editor.formatOnSave` and the server supports `willSaveWaitUntil`, send it
before writing with `LspPolicy.timeoutFor("textDocument/willSaveWaitUntil")`, apply edits,
then write; otherwise run the formatting pipeline with the same timeout. Timeout -> save
proceeds unformatted (never blocks a save). `willSave` alone is not sent (arch.md 7.3).

### 5.4 Editor integration (`LspWorkspaceBridge`)

`WorkspaceViewModel` (app) owns `uiState.openTabs: List<EditorTab>` keyed by `relativePath`,
with `content`/`savedContent` Strings, and has `projectId` and `environmentId`. The bridge is
constructed by the ViewModel with those two ids and:
- collects `uiState.map { it.openTabs }.distinctUntilChanged()` on `Dispatchers.Default`,
  diffing by `relativePath` into open / close / content-changed (reference inequality on
  `content`, O(tabs), no text compare) and calls `DocumentStore`.
- is told of saves from the `onSaveActiveTab` success branch (content equality with
  `savedContent` is not a save signal - a revert-by-typing is not a save).
- language id: `LanguageResolver.languageFor(fileName, firstLine)` - M2 backs it with a
  table from `contributes.languages` (extensions, filenames, firstLine) plus
  `files.associations`; `GrammarIndex.scopeFor` is not a language id and is not reused.
- `onCleared()` calls `LanguageServerManager.releaseProject(envId, projectId)` (docs close;
  servers go IDLE, not killed - a quick re-entry keeps them warm).

`EditorTab` gains no protocol fields. LSP-derived UI state (diagnostics, hover, completion)
lives in a separate `LspEditorState` flow keyed by `relativePath` (lsp-features.md sec 4) so
the existing tab state stays a pure file model.

## 6. `LspSession`

```kotlin
class LspSession internal constructor(
    val key: ServerKey,
    val config: ServerConfig,
    private val deps: SessionDeps,           // factory, mapper, store, config provider, log, ui, policy, clock
) {
    val state: StateFlow<SessionState>
    val capabilities: StateFlow<ServerCapabilities?>
    fun supports(feature: LspFeature): Boolean          // capability AND ServerConfig.features filter
    suspend fun <R> request(method: LspMethod<*, R>, params: Any, uri: String?): Versioned<R>?
    fun notify(method: String, params: Json?)
    internal suspend fun start()             // manager only
    internal suspend fun stop(reason: StopReason)
}

data class Versioned<R>(val value: R, val uri: String?, val version: Int?)
data class LspMethod<P, R>(val name: String, val encode: (P) -> Json?, val decode: (Json?) -> R, val feature: LspFeature?)
```

`request` with a non-null `uri` flushes that doc, stamps `version`, and returns null if the
session is not RUNNING, the feature is unsupported, or the call timed out/cancelled.

Initialize handshake (arch.md 6.3 step 5):
1. `initialize {processId: null, clientInfo {name:"easyIDE", version}, locale, rootUri:
   mapper.rootUri, rootPath: "/workspace", workspaceFolders: [root], capabilities:
   ClientCapabilitiesBuilder.build(milestone, uiFlags), initializationOptions:
   config.initializationOptions, trace: lsp.trace}`. `processId` is null because the
   app pid means nothing inside the guest.
2. Validate result: `capabilities` object required; `positionEncoding` absent or `utf-16`
   (anything else -> FAILED(ProtocolError), since we offered only utf-16).
3. `initialized {}`; then if `settingsSection` is set, `workspace/didChangeConfiguration
   {settings: <resolved section>}`; register dynamic watchers already requested.
4. `didOpen` all eligible open docs; state RUNNING.

Shutdown: `shutdown` request (timeout `LspPolicy.SHUTDOWN_GRACE_MS`) -> `exit` notification ->
`awaitExit` up to the same grace -> `terminate()` -> grace -> `kill()`.

## 7. Logging and trace

- `LspLog` interface; app impl feeds the Extension Log (arch.md 5.2 "Server status,
  restart, log"). Per session ring of `LspPolicy.LOG_RING_LINES` lines: stderr lines,
  `window/logMessage`, state transitions, timeouts.
- stderr reader: a coroutine per session on IO reading bytes, splitting on `\n`, decoding
  UTF-8 with replacement, truncating lines at `LspPolicy.MAX_LOG_LINE_CHARS`. Never parsed.
- `lsp.trace` = `off` | `messages` | `verbose` (sdk-reference): sent as `trace` in
  `initialize` and `$/setTrace` on change. The client side records: `messages` = method,
  id, direction, byte size, latency; `verbose` = full bodies.
- Transcript recording: when `lsp.trace = verbose` and `extensions.developerMode`, the
  session also appends JSONL `{t, dir, msg}` to
  `<cacheDir>/lsp-transcripts/<serverId>-<epoch>.jsonl`, rotated at
  `LspPolicy.TRANSCRIPT_MAX_BYTES`. Contains file text; local only, deleted by "Clear
  logs"; never uploaded. These files seed the replay tests (sec 11).
- `Failed` states carry the last `LspPolicy.STDERR_TAIL_LINES` stderr lines for the UI.

## 8. Threading

| Work | Dispatcher | Rule |
|---|---|---|
| Frame reading (blocking `read`) | `Dispatchers.IO` | one coroutine per session; parses JSON before handing off |
| Frame writing | `Dispatchers.IO` | **single writer** coroutine draining `Channel<RpcMessage>(UNLIMITED)`; the only code that touches stdin |
| stderr draining | `Dispatchers.IO` | one coroutine per session |
| Session state, pending map, `DocumentSync`, handlers | `Dispatchers.Default.limitedParallelism(1)` per session | all mutable session state confined here; no locks |
| Manager state (sessions map, admission, eviction) | `Dispatchers.Default.limitedParallelism(1)` | one per manager |
| Diff computation, result decoding, position mapping | session dispatcher | off main (arch.md sec 10: < 4 ms main-thread work per response) |
| Applying results to UI state | `Dispatchers.Main` | only `StateFlow.update` of already-built values |
| Memory sampling | `Dispatchers.IO` | reports to manager dispatcher |

Enqueue order into the writer channel is the wire order; requests flush their doc by
enqueueing the `didChange` before themselves from the same serial dispatcher.

## 9. Configuration used

Settings keys (sdk-reference Settings keys; names verbatim):

| Key | Used for |
|---|---|
| `lsp.enabled` (L) | eligibility per language |
| `lsp.globalMemoryBudgetMb`, `lsp.defaultMemoryBudgetMb` | lsp-lifecycle.md sec 3 |
| `lsp.maxServers` | lsp-lifecycle.md sec 2.2 |
| `lsp.trace` | sec 7 |
| `lsp.idleShutdownSec`, `lsp.startupTimeoutSec` | defaults for the same-named server fields |
| `lsp.restart.maxRetries`, `lsp.restart.backoffMs` | lsp-lifecycle.md sec 1.3 |
| `lsp.didChangeDebounceMs`, `lsp.requestTimeoutMs` | sec 5.3, 2.4 |
| `lsp.servers` | `ServerConfig` resolution |
| `extensions.developerMode` | transcript recording |
| `editor.formatOnSave` (L) | sec 5.3 |

Internal constants (not user settings) live in one declarative table,
`dev.easyide.lsp.LspPolicy` (arch.md 7.7 names it `MemoryPolicy`; memory values are the
`MemoryPolicy` part of this table). Starting values, to be profiled on device:

| Constant | Start value | Why a constant, not a setting |
|---|---|---|
| `MAX_HEADER_BYTES` / `MAX_MESSAGE_BYTES` / `MAX_JSON_DEPTH` | 8 KB / 64 MB / 256 | protocol sanity, not a preference; deeper JSON is a `ProtocolError` (threat-model M-17) |
| `MAX_FULL_SYNC_BYTES` | 2 MB | arch.md 7.3 Full-sync cap |
| `METHOD_TIMEOUT_MULTIPLIER` | map: default 1, references/rename/formatting 3, workspace/symbol 2 | derived from `lsp.requestTimeoutMs` |
| `SHUTDOWN_GRACE_MS` | 2000 | |
| `CRASH_WINDOW_MS` | 300000 | arch.md 7.6 |
| `MEMORY_SAMPLE_MS` | 5000 | arch.md 7.7 RSS sampler period |
| `EVICT_TARGET_RATIO` | 0.85 | hysteresis so eviction does not oscillate |
| `LOG_RING_LINES` / `STDERR_TAIL_LINES` / `MAX_LOG_LINE_CHARS` | 2000 / 40 / 2000 | arch.md 7.1 Extension Log ring |
| `TRANSCRIPT_MAX_BYTES` | 16 MB | |

## 10. Error handling (real boundaries only)

| Boundary | Failure | Handling |
|---|---|---|
| process spawn | `IOException`, `SandboxError` | `Failed(SpawnFailed)` with message |
| stdout/stdin pipes | `IOException` on read/write | transport closed -> crash path (lsp-lifecycle.md 1.3) |
| framing / JSON parse | malformed input | `ProtocolError` -> crash path |
| server error responses | `ResponseError` | sec 2.4 |
| `/proc` reads | missing file (process gone) | sample skipped |
| path mapping | unmappable URI | null, dropped (sec 4) |
| edits to disk (`workspace/applyEdit` on closed files) | `ProjectFiles.writeText` `Result` failure | `applied: false, failureReason` |

Pure logic (diff, `LineIndex`, state transitions, eviction order, capability building) has
no try/catch.

## 11. Testing hooks

- **Framing**: property test - random message sequences encoded then fed to `FrameReader`
  split at every byte boundary and in random chunk sizes decode identically; malformed
  headers, oversize, short body, multi-byte UTF-8 straddling chunk edges.
- **FakeServer harness** (`:lsp` test fixtures): `FakeServer(script)` over `PipedInputStream`
  pairs implements the server side with a small DSL (`onRequest("textDocument/hover") {
  reply(...) }`, `delay`, `crash()`, `hang()`, `emitGarbage()`, `stderr(line)`), plus a
  `FakeServerProcessFactory` so `LanguageServerManager` runs unmodified.
- **Recorded transcripts**: JSONL from sec 7 captured against real servers (pyright, ruff,
  gopls, clangd, marksman) on a device, committed under `lsp/src/test/resources/transcripts/`
  after removing file text that is not from the sample projects. `TranscriptReplayer` plays
  the server side and asserts client messages match by method + params (ids and
  `processId`-like fields normalised).
- **DocumentSync**: random edit sequences; a model server applies the emitted incremental
  changes to its copy; after every flush the copy equals the client text; versions strictly
  increase; no didChange after didClose.
- **State machine**: table-driven test enumerating every row of lsp-lifecycle.md 1.2 with
  `kotlinx-coroutines-test` virtual time and a fake `Clock`; unlisted pairs are asserted
  ignored.
- **Backoff / eviction / admission**: fake `RssSampler`, fake visibility; asserts exact kill
  order and target ratio; `onTrimMemory` levels.
- **PathMapper**: round trips, percent-encoding, `..` escapes, rootfs read-only mapping,
  pass-through mounts return null.
- **Device (M2 exit)**: fault injection on a real server (`kill -9` from the terminal, a
  pack whose server prints to stdout, a server exceeding its budget) - verifies backoff,
  Failed(CrashLoop) and eviction on a tablet. JVM tests cannot verify proot pipe behaviour;
  an instrumented test spawns `cat` via `ServerProcessFactory` and checks byte-exact echo
  of a framed message, stderr separation and env cleanliness (`env` output contains no
  host variables).

## 12. Open issues

- O1 JSON library: arch.md 7.1 says use the JSON library already in the app. That is
  Android's platform `org.json`, which is not available to a pure JVM `:lsp` module or its
  unit tests (framework stub). Options: make `:lsp` depend on an `org.json` artifact
  (license and maintenance to be verified from primary sources first, per repo rule), or
  make `:lsp` an Android library tested with a real `org.json` test dependency. ADR-I
  decides; no dependency is adopted by this LLD.
- O2 Environments whose backend is chroot: `LinuxEnvironment.start` is proot-only today and
  `ChrootLauncher` has no caller; server spawning follows whatever backend selection lands.
- O3 Under chroot, the server tree is root-owned; `/proc/<pid>/status` may be unreadable by
  the app uid, and `destroy()` on `su` may not reach the server. Needs a device test; may
  need `su -c kill` and RSS via `su`.
- O4 Nested roots (monorepo with several `pyproject.toml`): v1 uses `/workspace` only.
- O5 Verify `ComponentCallbacks2` trim levels delivered on the target API levels.

## Deviations

- D1-D4 resolved: settings names/defaults follow sdk-reference.md; `LspPolicy` constants
  (Full-sync cap, sampler period, log ring) replace arch.md's old `lsp.maxSyncBytes` /
  `memorySampleSec` / `logBufferLines` keys; utf-16 only; session key
  `(env, project, serverId)`. arch.md sec 7/10 and sdk-reference now match.
