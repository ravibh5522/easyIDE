# Extension SDK - LSP Client (lifecycle)

Low-level design of server lifecycle in the `:lsp` module: the session state machine,
`LanguageServerManager` keying and routing, start admission, memory budget enforcement with
kill order and `onTrimMemory`, and crash backoff.

Status: PROPOSED (2026-09-23). Nothing here is implemented.
Core (JSON-RPC, transport, sync, `LspSession`, threading, config, tests):
[lsp-client.md](lsp-client.md). Features: [lsp-features.md](lsp-features.md).
Sources of truth: [arch.md 7.6-7.7](../arch.md#76-server-lifecycle-state-machine),
[sdk-reference.md](../sdk-reference.md) (settings keys, `easyide.languageServers`, when-clauses).
Feature area: arch.md 5.2 LSP language intelligence. Milestone: M2.

Threading: session state transitions run on the session's serial dispatcher, manager state
on the manager's serial dispatcher, sampling on IO (lsp-client.md sec 8). Settings keys and
`LspPolicy` constants: lsp-client.md sec 9. Tests: lsp-client.md sec 11 (state machine
table test, backoff/eviction/admission with fake sampler). Keys and defaults follow
sdk-reference; session key is `(env, project, serverId)` (sec 2.1).

## 1. Server lifecycle state machine

### 1.1 States

```kotlin
sealed interface SessionState {
    data object NotInstalled : SessionState          // command not found in env (probe: `command -v argv[0]`)
    data class Stopped(val reason: StopReason) : SessionState
    data object Starting : SessionState
    data object Initializing : SessionState
    data object Running : SessionState
    data class Idle(val since: Long) : SessionState
    data object Stopping : SessionState
    data class Backoff(val attempt: Int, val retryAt: Long) : SessionState
    data class Failed(val reason: FailReason, val stderrTail: List<String>) : SessionState
}
enum class StopReason { NEVER_STARTED, IDLE_TIMEOUT, EVICTED, USER, CONFIG_CHANGED, PROJECT_RELEASED }
sealed interface FailReason { SpawnFailed; InitTimeout; ProtocolError; CrashLoop; OverBudget; EnvironmentNotReady; Disabled }
```

### 1.2 Transitions (exhaustive; any pair not listed is ignored and logged)

| From | Event | Guard | To | Action |
|---|---|---|---|---|
| NotInstalled | install finished / user "retry" | probe ok | Stopped(NEVER_STARTED) | - |
| Stopped(any but USER) | doc of language needed | budget admits (sec 3) | Starting | spawn |
| Stopped(USER) | user "start" | budget admits | Starting | spawn |
| Stopped / Failed | budget refuses | - | unchanged | status "paused (memory)" |
| Starting | spawn ok | - | Initializing | start reader/writer/stderr; send initialize |
| Starting | spawn throws | - | Failed(SpawnFailed) | log cause |
| Initializing | initialize result ok | - | Running | initialized, config, didOpen all |
| Initializing | timeout `startupTimeoutSec` | - | Failed(InitTimeout) | kill |
| Initializing / Running / Idle | process exit or transport closed | not requested by us | Backoff(n) or Failed(CrashLoop) | see 1.3 |
| Initializing / Running / Idle | protocol error | - | Backoff(n) or Failed(CrashLoop) | kill, as a crash |
| Running | last eligible doc closed | - | Idle(now) | arm `idleShutdownSec` timer |
| Idle | doc opened | - | Running | cancel timer; didOpen |
| Idle | idle timer | - | Stopping | shutdown sequence, then Stopped(IDLE_TIMEOUT) |
| Running / Idle | evict (sec 3) | - | Stopping | then Stopped(EVICTED) |
| Running / Idle | own RSS over budget 2 samples | first time this session | Stopping | then Starting (one restart) |
| Running / Idle | own RSS over budget 2 samples | already restarted once | Stopping | then Failed(OverBudget), notice |
| Running / Idle / Backoff | config for this key changed | - | Stopping | then Stopped(CONFIG_CHANGED); restarts on need |
| Running / Idle / Backoff | user "stop" | - | Stopping | then Stopped(USER) |
| Running / Idle | `releaseProject` | - | Idle | didClose all (servers stay warm until idle timer) |
| Stopping | exited or killed | - | Stopped(reason) / Failed(reason) | per the triggering row |
| Backoff | retry timer | - | Starting | spawn |
| Backoff | user "stop" / config changed | - | Stopped(USER / CONFIG_CHANGED) | cancel timer |
| Failed | user "restart" | - | Starting | reset crash history |
| any | server disabled (`lsp.servers.<k>.enabled=false`, `lsp.enabled=false` for all its languages, extension disabled) | - | Stopping -> Failed(Disabled) | - |
| Failed(Disabled) | re-enabled | - | Stopped(NEVER_STARTED) | - |

Every transition is emitted on `state` and to `LspLog` as `state <from> -> <to> (<event>)`.

### 1.3 Crash backoff

`CrashHistory` holds crash timestamps within `LspPolicy.CRASH_WINDOW_MS` (5 min, arch.md
7.6). On crash: `n = crashes in window`. If `n > lsp.restart.maxRetries` -> Failed(CrashLoop)
with the stderr tail and a "restart" action. Else Backoff(n, now + `lsp.restart.backoffMs *
2^(n-1)`). A session that stays Running for a full window clears its history. Evicted,
idle and user stops are not crashes.

### 1.4 Mapping to when-clause context (sdk-reference When-clause context)

| `SessionState` | `lspState:<lang>` | `lspReady:<lang>` |
|---|---|---|
| Stopped, NotInstalled, Failed(Disabled) | `off` | false |
| Starting, Initializing, Backoff | `starting` | false |
| Running, Idle | `ready` | true |
| Failed(OverBudget), Stopped(EVICTED) | `overBudget` | false |
| Failed(other) | `crashed` | false |

With several servers for a language, `lspState:<lang>` reports the best state among them in
the order ready > starting > overBudget > crashed > off. `lspSupports:<lang>:<feature>` is
true if any RUNNING session for the language `supports(feature)`.

## 2. `LanguageServerManager`

### 2.1 Keys and routing

```kotlin
data class ServerKey(val environmentId: String, val projectId: String, val serverId: String) // serverId = "<extId>/<id>" or a user key
data class ServerConfig(                     // resolved from easyide.languageServers + lsp.servers layers
    val serverId: String, val languages: Set<String>, val command: List<String>, val env: Map<String, String>,
    val initializationOptions: Json?, val settingsSection: String?, val rootMarkers: List<String>,
    val memoryBudgetMb: Int, val idleShutdownSec: Int, val startupTimeoutSec: Int,
    val features: FeatureFilter, val priority: Int, val enabled: Boolean,
)
data class FeatureFilter(val only: Set<LspFeature>?, val exclude: Set<LspFeature>)

interface ServerConfigSource { fun serversFor(env: String, project: String): StateFlow<List<ServerConfig>> }

class LanguageServerManager(
    private val configs: ServerConfigSource,
    private val deps: ManagerDeps,           // process factory, mapper factory, store factory, policy, clock, log
    scope: CoroutineScope,
) {
    val sessions: StateFlow<Map<ServerKey, SessionState>>
    fun sessionsFor(env: String, project: String, languageId: String): List<LspSession> // RUNNING-first, priority desc
    fun documentStore(env: String, project: String): DocumentStore
    fun ensureStarted(env: String, project: String, languageId: String)
    fun releaseProject(env: String, project: String)
    fun restart(key: ServerKey); fun stop(key: ServerKey)
    fun onTrimMemory(level: Int)
    fun onVisibleUrisChanged(env: String, project: String, visible: Set<String>, focused: String?)
}
```

The task statement keys by (env, project, language); a language can have several servers
(the Python pack pairs pyright with ruff via `features.only`), so the session key is
`(env, project, serverId)` and language is a routing index `languageId -> [serverId]` (arch.md
6.3 already says `(E, P, server)`). Per project, never shared across projects: every project
binds to the same `/workspace` (arch.md 6.3 step 1).

`rootMarkers`: v1 advertises `rootUri = file:///workspace` always (single root, arch.md 7.8);
`rootMarkers` only decides whether the server is eligible at all (at least one marker exists
under the project, or the list contains `.git`/is default). Nested-root selection is O4.

Config changes: `ServerConfigSource` emits on any settings layer change; a changed
`command`, `env`, `initializationOptions` restarts (row "config changed"); a changed
`settingsSection` value only sends `didChangeConfiguration`; `memoryBudgetMb` applies at the
next sample.

### 2.2 Start admission (`lsp.maxServers`)

Live = Starting + Initializing + Running + Idle + Backoff. Before `Starting`: if
`live >= lsp.maxServers` or projected sum of budgets `> lsp.globalMemoryBudgetMb`, run the
kill order (sec 3.2) until admitted; if only the focused editor's servers remain, refuse
(Stopped stays, status "paused (memory)").

## 3. Memory budget enforcement

### 3.1 Sampling

`MemorySampler` (IO dispatcher, one per manager) every `LspPolicy.MEMORY_SAMPLE_MS` for each
live session: `rss = ProcTree.rssKb(pid)`; kept as `lastRssKb` and a 2-sample over-budget
counter. Budget per server: `ServerConfig.memoryBudgetMb` (default
`lsp.defaultMemoryBudgetMb`). Global: `lsp.globalMemoryBudgetMb`.

### 3.2 Kill order (arch.md 7.7, exact)

```kotlin
fun evictionOrder(sessions: List<SessionView>, focusedUri: String?, visible: Set<String>): List<ServerKey>
```
1. Idle sessions, least recently used first (`lastUsedAt`: last request or didChange).
2. Running sessions with no doc in `visible`, LRU first.
3. `MemoryPressureHook` callbacks registered by other modules (the WASM host registers
   "drop idle instances") - invoked here, not ordered by the manager.
4. Running sessions serving `focusedUri`, largest RSS first.

Terminal sessions, editor buffers and the Claude Code CLI are never touched.
Evicted sessions go `Stopped(EVICTED)` and restart lazily on the next need.

Triggers and targets:

| Trigger | Stop evicting when |
|---|---|
| sum RSS > `lsp.globalMemoryBudgetMb` | sum <= budget * `LspPolicy.EVICT_TARGET_RATIO` |
| admission (2.2) | admitted |
| `onTrimMemory(level >= TRIM_MEMORY_RUNNING_LOW)` while foreground | steps 1-2 exhausted |
| `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` (app backgrounded) | all Idle stopped |
| `onTrimMemory(level >= TRIM_MEMORY_BACKGROUND)` | steps 1-3 exhausted |
| `onTrimMemory(TRIM_MEMORY_COMPLETE)` | everything including step 4 |

Wiring: `EasyIdeApplication` (today only `onCreate`) overrides `onTrimMemory(level)` and
forwards to `container.languageServerManager.onTrimMemory(level)`. `ComponentCallbacks2`
level semantics on current Android versions must be verified (O5); the sampler is the
primary mechanism and does not depend on them. `SandboxForegroundService` keeps the process
alive while terminals run; servers get no extra protection from it.
