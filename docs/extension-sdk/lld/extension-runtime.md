# Extension SDK - Extension Runtime (LLD)

Low-level design of the `:extensions` module: manifest loading and validation, descriptor
model, enablement, activation, contribution registry, when-clauses and the L1 action engine.

Status: PROPOSED (2026-09-23). Nothing here is implemented.
Contract: [../sdk-reference.md](../sdk-reference.md). Rationale: [../arch.md](../arch.md)
(sec 5.3 Extension capabilities, sec 6 Architecture, sec 9 Security, sec 12 M3/M4).
Related: [customization.md](customization.md), [lsp-client.md](lsp-client.md),
[wasm-host.md](wasm-host.md), [registry-and-install.md](registry-and-install.md), [cli.md](cli.md).

Paths: `app/` = `services/mobile/app/src/main/java/dev/easyide/app/`, `sandbox/` =
`services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/`, `ext/` =
`services/mobile/extensions/src/main/kotlin/dev/easyide/ext/` (new module).

## 1. Scope and module boundaries

| In this LLD | Elsewhere |
|---|---|
| `ManifestParser`, `ManifestValidator`, `SchemaValidator` | download, verify, unpack, `current` flip, `state.json` (registry-and-install.md) |
| `ExtensionDescriptor`, `ExtensionRegistry` | settings storage and resolution (customization.md) |
| `ActivationManager`, `CrashJournal`, safe-mode trigger | `WasmHost` (wasm-host.md), `ServerSupervisor` (lsp-client.md) |
| `ContributionRegistry` and adapters | `CommandRegistry`, `Keymap` themselves (ux-overhaul Pillar 4, M0) |
| `WhenParser`, `ContextKeyService`, `ActionRunner`, `VariableResolver`, `ShellQuote` | `easyide-ext` internals (cli.md) |

`:extensions` is a **pure Kotlin/JVM module** (no Android imports): unit-testable on the
JVM, and the `easyide-ext` CLI reuses the parser, validator and when-clause engine
verbatim (arch.md risk "schema drift"). Android pieces (observers, terminal tabs, Compose)
stay in `app/` and are reached through ports (sec 9). Dependencies: `app -> :extensions`,
`app -> :sandbox-runtime`, `:ext-wasm -> :extensions`; the schema JSON is a resource.

JSON: the app already uses `org.json` (`app/ui/screens/workspace/syntax/GrammarIndex.kt`,
`sandbox/store/PersistedStateJson.kt`). `:extensions` parses with it at the I/O edge and
converts at once to its own immutable `JsonValue`, so nothing downstream sees `org.json`
types. JVM tests/CLI need the `org.json:json` artifact, whose license and maintenance must
be verified from its primary source before adoption (Open issues 1).

## 2. Manifest loading and validation

**Responsibility.** Turn a package directory into an `ExtensionDescriptor` or diagnostics.
Called by the installer, by `easyide-ext validate`, and at startup only when the
descriptor cache is stale (2.3).

```kotlin
interface PackageFiles {                        // unpacked dir, zip or dev folder; paths relative, "/"-separated
    fun exists(path: String): Boolean
    fun read(path: String): ByteArray           // I/O boundary: throws IOException
    fun list(): List<String>
}
class ManifestParser(private val schema: SchemaValidator, private val locale: Locale) {
    fun parse(files: PackageFiles): ParseResult // synchronous, stateless; callers run it on Dispatchers.IO
}
sealed interface ParseResult {
    data class Ok(val descriptor: ExtensionDescriptor, val warnings: List<Diagnostic>) : ParseResult
    data class Invalid(val errors: List<Diagnostic>, val warnings: List<Diagnostic>) : ParseResult
}
data class Diagnostic(val severity: Severity, val code: String,   // stable id, e.g. "E_CAP_UNDECLARED"
                      val pointer: String,                          // JSON Pointer, e.g. "/easyide/actions/python.runFile"
                      val file: String, val message: String)
```

### 2.1 Pipeline

Each phase runs only if earlier phases produced no ERROR; warnings accumulate.

| # | Phase | Checks | Severity |
|---|---|---|---|
| 1 | Read | `package.json` present, <= `extensions.limits.fileMb`, UTF-8 | E |
| 2 | Parse | strict JSON (VS Code tooling must accept it) -> `JsonValue` | E, line/col |
| 3 | NLS | whole-string `%key%` from `l10n/package.nls.<locale>.json`, then `package.nls.json`; missing key keeps the literal | W |
| 4 | Schema | `SchemaValidator` against `manifest.schema.json` | E |
| 5 | Identity | name/publisher regex, SemVer, `engines.easyide` parses and contains `AppApi.VERSION` | E |
| 6 | Files | every referenced path normalised (no `..`, not absolute) and present; unreferenced files | E / W |
| 7 | Cross-refs | menu/keybinding/statusBar/keyRow commands resolve to `contributes.commands` or a built-in id; every `easyide.actions` key is a declared command; ids unique per point; keyRow key has exactly one of insert/snippet/key/command | E (unknown built-in id: W, may be newer) |
| 8 | When-clauses | every `when`/`enablement` parses (sec 6); unknown context keys | E / W |
| 9 | Capability audit | `requiredCapabilities()` of every action (8.3) declared | E `E_CAP_UNDECLARED` |
| 10 | Scope | computed scope (sdk-reference `easyide.scope`); declared `global` but computed `environment` | E |
| 11 | Content | grammars (`GrammarReader`), themes, snippets, language-configuration, icon themes parse; theme colour keys with no easyIDE token | E / W |
| 12 | Unknown | unknown `contributes.*`, `*` activation, `engines.vscode` | W |

Phase 11 uses the same readers as the runtime, so "validates" means "loads". Host-function
use by WASM modules is audited by `easyide-ext validate` only (wasm-host.md).

### 2.2 SchemaValidator

`services/shared/extension-schema/manifest.schema.json` is the single schema for app and
CLI. No JSON Schema library is a dependency today (each candidate would need license and
maintenance verification; the common JVM ones pull in Jackson), so `SchemaValidator`
implements a **fixed subset** of draft 2020-12: `type, enum, const, required, properties,
additionalProperties, patternProperties, items, prefixItems, min/maxItems, minimum,
maximum, min/maxLength, pattern, oneOf, anyOf, $ref` (local `#/$defs` only), `default`,
`description`. A build test fails if the schema file uses any other keyword.

Uses: manifests, setting values against contributed property schemas (customization.md
sec 5), the CLI. API: `SchemaValidator(root).validate(value, pointer): List<Diagnostic>`,
`forSubschema(schema)`.

A `pattern` longer than `ExtensionPolicy.MAX_PATTERN_LENGTH` is rejected (bounds backtracking).

### 2.3 Descriptor cache

arch.md sec 10 budgets manifest load < 20 ms for 20 extensions, so startup never parses
`package.json`. After install the installer writes the canonical descriptor to
`<versionDir>/.descriptor.json` with `descriptorFormat` and `AppApi.VERSION`;
`ExtensionRegistry` loads those. A mismatch re-parses that package on IO and rewrites the
cache. It is derived data: deleting it is always safe.

## 3. Data model

```kotlin
@JvmInline value class ExtensionId private constructor(val value: String) { companion object { fun of(raw: String): ExtensionId } } // lower-cased
data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: List<String>) : Comparable<SemVer>
data class SemVerRange(val raw: String) { fun contains(v: SemVer): Boolean }

sealed interface JsonValue    // immutable: Null, Bool, Num(Double), Str, Arr(List), Obj(Map); args, results, setting values
enum class Layer { L0, L1, L2 }; enum class InstallScope { GLOBAL, ENVIRONMENT }   // L3/L4 not modelled before M8
enum class Source { BUILT_IN, REGISTRY, OPEN_VSX, SIDELOAD, DEV }

sealed interface Capability   // one object/data class per sdk-reference capability id; Network(hosts), FsProject(write)
sealed interface ActivationEvent  // OnLanguage(id), OnCommand(id), OnView(id), OnStage(id), WorkspaceContains(glob), OnStartupFinished

data class ExtensionDescriptor(
    val id: ExtensionId, val version: SemVer, val displayName: String, val description: String?,
    val license: String?, val engines: SemVerRange, val categories: List<String>,
    val scope: InstallScope, val layers: Set<Layer>,               // L2 iff easyide.wasm
    val capabilities: Set<Capability>,                             // declared
    val activationEvents: List<ActivationEvent>,
    val contributes: Contributions,                                // one typed list per contribution point
    val actions: Map<String, Action>, val inputs: Map<String, InputSpec>,
    val wasm: WasmSpec?, val memoryBudgetMb: Int?,
    val root: String,                                              // host path; never shown to guests
    val guestRoot: String,                                         // /opt/easyide/extensions/<id> = ${extensionPath}
)
```

`Contributions` has one field per sdk-reference contribution point (`languages`,
`grammars`, ..., `keyRows`, `languageServers`, `sandbox`, `viewData`), each a list of data
classes mirroring the reference's field lists. Every `when`/`enablement` is stored parsed
(`WhenExpr?`), every file reference resolved to a host path under `root`.

**ContributionRef** (identity for hide/order overrides and the inspector):

```kotlin
data class ContributionRef(val kind: Kind, val location: String?, val id: String) {  // toString/parse:
    enum class Kind { MENU, VIEW, STATUS_BAR, STAGE, KEY_ROW, COMMAND, KEYBINDING, THEME, GRAMMAR, SNIPPET, SERVER }
}                                                  // "menu:editor/title:python.runFile", "view:python.venvs"
```

## 4. ExtensionRegistry (installed x enabled)

Extension `x` is **enabled** for `RuntimeScope(envId, projectId)` iff:

1. `x` is installed `GLOBAL`, or `ENVIRONMENT` in the active `envId`;
2. `extensions.enabled` resolves true and safe mode is off (`Source.BUILT_IN` excepted);
3. `x.id` not in `extensions.disabled` resolved for the scope (P scope; arrays replace per
   customization.md sec 3.3, so the UI always writes the full list for a layer);
4. if the active profile lists `enabledExtensions`, `x.id` is in it (global-scope packs only);
5. the `state.json` approval covers `x.capabilities` exactly (a capability added by an
   update disables `x` until re-approved - arch.md sec 9 "never silently granted");
6. `x` is not crash-disabled (5.3) or revoked.

```kotlin
interface InstalledExtensions { val installed: StateFlow<List<InstalledRecord>> }  // ExtensionStore, registry-and-install.md
data class RuntimeScope(val envId: String?, val projectId: String?)

class ExtensionRegistry(installed: InstalledExtensions, settings: SettingsResolver, safeMode: SafeModeState,
                        crashDisabled: StateFlow<Set<ExtensionId>>, scope: CoroutineScope) {
    fun enabled(runtime: RuntimeScope): StateFlow<EnabledSet>
    fun isEnabled(id: ExtensionId, runtime: RuntimeScope): Boolean
}
data class EnabledSet(val version: Long, val descriptors: List<ExtensionDescriptor>)  // ordered by installedAt, then id
```

`EnabledSet` order is the tie-break for every conflict rule (earliest installed first, so a
new install never displaces a winner). Recomputed on `Dispatchers.Default`; emitted only
when the `(id, version)` set changes.

## 5. Activation

Declarative L1 contributions are live as soon as the extension is enabled (sdk-reference
`activationEvents`). Activation affects only **L2** (instantiate, `ext_activate`;
wasm-host.md) and **language servers** (become eligible; the supervisor still starts them
on the first document; lsp-client.md). An extension with neither is `ACTIVE` once enabled.

### 5.1 Event sources

`ActivationEventBus` is a `SharedFlow<ActivationEvent>` with `emit(e)`.

| Event | Emitted by | When |
|---|---|---|
| `OnLanguage` | editor open-tab / language-change path (`WorkspaceViewModel`) | first time per language per session |
| `OnCommand` | `CommandDispatcher` before an L2-owned command | every run (no-op when active) |
| `OnView` / `OnStage` | view/stage becoming visible (`StageState.kt`) | first reveal |
| `WorkspaceContains` | `WorkspaceScanner` on project open: one IO walk for the union of enabled globs, skips `.git`, stops at `ExtensionPolicy.WORKSPACE_SCAN_MAX_FILES` | on match |
| `OnStartupFinished` | `MainActivity`, first frame + `ExtensionPolicy.STARTUP_IDLE_DELAY_MS` | once |

### 5.2 State machine (per extension; env-scoped packs per environment)

```
 DISABLED -enable-> INACTIVE -event-> ACTIVATING -ok-> ACTIVE -disable/env stop/evict-> DEACTIVATING -> INACTIVE
                                        | error, activateTimeoutMs  | trap/crash         (deactivateTimeoutMs)
                                        v                           v
                                      FAILED -retry-> ACTIVATING  CRASHED -next event-> ACTIVATING
                                                                    | >= wasm.maxCrashes in crashWindowSec
                                                                    v
                                         CRASH_DISABLED (persisted) -user re-enables-> INACTIVE
 any state -disable-> DISABLED
```

```kotlin
enum class ActivationState { DISABLED, INACTIVE, ACTIVATING, ACTIVE, CRASHED, FAILED, DEACTIVATING, CRASH_DISABLED }
fun interface Activator { suspend fun activate(d: ExtensionDescriptor): Result<Unit> }

class ActivationManager(registry: ExtensionRegistry, bus: ActivationEventBus,
                        activators: List<Activator>,        // WasmHost adapter, ServerSupervisor eligibility
                        journal: CrashJournal, clock: Clock, scope: CoroutineScope) {
    val states: StateFlow<Map<ExtensionId, ActivationState>>
    suspend fun ensureActive(id: ExtensionId): ActivationState   // onCommand path; bounded by activateTimeoutMs
    fun retry(id: ExtensionId)
}
```

One activation in flight per extension (`Mutex` per id; concurrent events join it), on
`Dispatchers.Default` (WASM work moves to the extension's worker in `WasmHost`). FAILED (bad
module, ABI mismatch) is not crash-counted. States feed `extensionEnabled:<id>` and the Extensions screen.

### 5.3 Crash journal and the safe-mode trigger

In-process failures (traps, server crashes) are contained above. What cannot be contained
is **the app process dying** while an extension registers contributions or activates (OOM
on a huge theme, a pathological grammar). That is what automatic safe mode is for (arch.md
sec 5.5; success metric "safe mode reachable 100%").

```kotlin
class CrashJournal(file: File) {                 // <files>/extensions/activation-journal.json
    fun begin(id: ExtensionId, phase: Phase)     // REGISTER_CONTRIBUTIONS | ACTIVATE; write + fsync before the step
    fun end(id: ExtensionId)
    fun onStartup(): StartupVerdict
}
data class StartupVerdict(val suspects: List<ExtensionId>, val consecutive: Int, val enterSafeMode: Boolean)
```

- A record left over at startup means the previous process died inside that step; the
  `consecutive` counter persists across launches and resets after a clean run reaches
  `OnStartupFinished` with no open records.
- `consecutive >= ExtensionPolicy.SAFE_MODE_AFTER_CONSECUTIVE_CRASHES` (2, arch.md) sets
  session-only safe mode and a banner naming the suspects: "Disable <name> and restart",
  "Exit safe mode". Safe-mode semantics: customization.md sec 11.
- Journal writes are file I/O (a real boundary): a failure is logged, the step proceeds.

## 6. When-clauses

### 6.1 Lexer and grammar

Tokens: `IDENT` = `[A-Za-z_][A-Za-z0-9_.:\-]*` (so `lspSupports:python:formatting` and
`config.editor.tabSize` are one token); `STRING` single-quoted with `\'`; `NUMBER`;
`REGEX` `/.../flags` lexed only right after `=~` (flags `i m s u`); operators
`! && || ( ) == != < <= > >= =~`; keywords `in`, `not in`, `true`, `false`. Anything else
is a parse error at its offset. Precedence low to high: `||`, `&&`, `!`, comparison.

```
expr := and ('||' and)*        and := unary ('&&' unary)*       unary := '!' unary | cmp
cmp  := primary (op value | 'in' container | 'not' 'in' container | '=~' REGEX)?
primary := '(' expr ')' | IDENT | 'true' | 'false'
value := STRING | NUMBER | IDENT (unquoted right-hand word = string)    container := IDENT | STRING
```

A `STRING` container splits on `,` (`envDistro in 'debian,ubuntu'`); an `IDENT` container
is a context key holding an array or object.

```kotlin
sealed interface WhenExpr { val keys: Set<String> }   // referenced keys, for change filtering
// Key(name), Const(bool), Not(e), And(parts), Or(parts), Compare(key, op, literal),
// Matches(key, regex: Regex), In(key, container: KeyRef | List<String>, negated)
object WhenParser {
    fun parse(text: String): WhenParseResult     // Ok(expr) | Error(offset, message)
    fun normalize(e: WhenExpr): String           // canonical text; "-command" matching, conflict detection
}
```

### 6.2 Evaluation

Falsy: `undefined`, `null`, `false`, `""`, `0`.

| Form | Semantics |
|---|---|
| `key` | truthy(value) |
| `key == lit`, `!=` | compare as strings (numbers/bools stringified); `undefined == x` false, `undefined != x` true |
| `< <= > >=` | both as numbers; either non-numeric -> false |
| `key =~ /re/` | value is a string and `regex.containsMatchIn(value)`, else false |
| `key in c` | string value contained in array / is a key of object / in quoted list |
| `key not in c` | negation, but false when the key is undefined |

Pure and allocation-free over a snapshot. Trees are parsed once at load and shared.
Unknown keys are `undefined`, never an error (sdk-reference).

### 6.3 ContextKeyService

```kotlin
class ContextKeyService(settings: SettingsResolver) {
    val snapshot: StateFlow<ContextSnapshot>
    fun set(key: String, value: JsonValue?)          // null removes; thread-safe atomic swap
    fun setAll(values: Map<String, JsonValue?>)      // one version bump
    fun observe(expr: WhenExpr): Flow<Boolean>       // re-evaluates only when a key in expr.keys changed
}
class ContextSnapshot internal constructor(val version: Long, values: Map<String, JsonValue>,
                                            languageId: String?, runtime: RuntimeScope) {
    operator fun get(key: String): JsonValue?        // "config.<key>" resolved lazily via SettingsResolver
}
```

Snapshots are immutable copy-on-write maps (~100 keys; cheap next to the recomposition
they drive). `config.*` is never mirrored. Keybinding dispatch reads `snapshot.value` on Main.

| Provider (in `app/`) | Keys | Source today |
|---|---|---|
| `EditorContextProvider` | `editorLangId`, focus/readonly/dirty/selection keys, `resource*` | `WorkspaceViewModel.kt` tab state, `EditorPane.kt` |
| `FocusContextProvider` | `terminalFocus`, `terminalProcessRunning`, `explorerFocus`, `scmFocus`, `focusedView`, `stageVisible:<id>`, `focusedStage` | `StageState.kt`, `TerminalPane.kt` |
| `DeviceContextProvider` | `hardwareKeyboard`, `inputMode`, `windowSizeClass`, `devicePosture` | `ui/foundation/WindowSize.kt`, `Configuration.keyboard` |
| `EnvContextProvider` | `envId`, `envState`, `envBackend`, `envDistro`, `envArch`, `envHasCommand:<n>` | `EnvironmentManager`, `LinuxEnvironment.isReady`, probe cache (8.5) |
| `LspContextProvider` | `lspReady:<l>`, `lspState:<l>`, `lspSupports:<l>:<f>` | `ServerSupervisor` |
| `GitContextProvider` | `gitRepo`, `gitBranch`, `gitDirty` | `GitPanelState` refresh |
| `AppContextProvider` | `isOffline`, `isSafeMode`, `extensionEnabled:<id>` | connectivity, `SafeModeState`, `ActivationManager.states` |

## 7. ContributionRegistry

**Responsibility.** Hold every contribution of built-ins and enabled extensions in typed
stores and project them into the app's registries. It renders nothing and runs nothing.

```kotlin
sealed interface Owner { object BuiltIn : Owner; data class Ext(val id: ExtensionId) : Owner }
data class Owned<T>(val owner: Owner, val ref: ContributionRef, val value: T)

class ContributionStore<T>(val kind: ContributionRef.Kind) {
    val entries: StateFlow<List<Owned<T>>>                 // built-ins first, then EnabledSet order
}
class ContributionRegistry(registry: ExtensionRegistry, journal: CrashJournal, scope: CoroutineScope) {
    val commands: ContributionStore<CommandContribution>   // ... one store per contribution point
    val menus: ContributionStore<MenuItemContribution>     // ref.location = menu id
    fun inspect(ref: ContributionRef): InspectorEntry?     // owner, manifest pointer, overriding user setting
}
```

On each `EnabledSet` emission the registry diffs by `(id, version)` on `Dispatchers.Default`:
removed owners leave every store, added owners are inserted inside
`CrashJournal.begin(id, REGISTER_CONTRIBUTIONS)`, unchanged owners are untouched; each
store emits at most once per diff.

### 7.1 Conflict rules

| Collision | Winner; loser |
|---|---|
| command id = built-in id | built-in; extension command dropped, `E_COMMAND_SHADOWED` logged |
| command id in two extensions | earlier in `EnabledSet`; later dropped, its menus/keybindings bind to the winner |
| `configuration` key collision | built-in, then earlier (customization.md sec 5) |
| grammar `scopeName` = bundled / in two extensions | extension over bundled (the point of a pack; bundled is the fallback); between extensions, earlier |
| language id in several sources | merged: extensions/filenames/patterns union; `configuration` from the earliest that has one |
| theme label, icon theme, key row, stage, status item, view id | earlier (duplicate themes listed as `"<label> (<publisher>)"`); server keys `<extId>/<id>` cannot collide |

`validate` warns when a command, setting, view or key-row id is not prefixed `<name>.`.

### 7.2 Adapters into the app

| Store | Target | Anchor |
|---|---|---|
| commands | `CommandRegistry` (Pillar 4, M0): `Command(id, title, icon, enabledWhen = enablement, run = { actionEngine.run(owner, id, it) })`; L2-owned commands dispatch to `WasmHost` after `ensureActive` | planned |
| keybindings | `Keymap` extension layer: above built-in defaults, below user (customization.md sec 7) | planned |
| menus | `MenuModel.itemsFor(menuId, snapshot)`: filter by `when` + command `enablement`, apply `workbench.contributions.hidden/order` (customization.md sec 6), sort `navigation` first then group, then `@order` | `EditorTabBar.kt`, `FileContextMenu.kt` |
| configuration(+Defaults) | `SettingsSchema.register(owner, ...)` and the extension-default layer | customization.md sec 5 |
| themes, icon themes | `ThemeCatalog` (colours via `ThemeColorMap`, `tokenColors` -> `SyntaxRole` via `ScopeRules`), `IconThemeCatalog` | `ui/theme/EditorColors.kt`, `SyntaxColors.kt`; customization.md sec 8-9 |
| languages, grammars | `GrammarCatalog` (7.3) | `syntax/GrammarIndex.kt`, `syntax/TextMateHighlighter.kt` |
| language configuration, snippets | L0 language core stores (M1) | new `app/ui/screens/workspace/language/` |
| key rows | `KeyRowCatalog`; `TerminalKeyboard.KEYS` registered as built-in `keyRow:builtin.terminal` | `TerminalKeys.kt`, `TerminalKeyRow.kt`; customization.md sec 10 |
| status items, stages, views, viewData | status bar model, stage table, view hosts | `WorkspaceChrome.kt`, `StageState.kt` |
| languageServers | `ServerSupervisor` definitions, merged with `lsp.servers` (customization.md sec 12) | lsp-client.md |
| sandbox | installer; runtime reads `requires` for `envHasCommand` probes | registry-and-install.md |

Adapters are the only code that knows both sides; targets carry just an `Owner` tag.

### 7.3 Grammars: anchoring to TextMateHighlighter and GrammarIndex

Today `TextMateHighlighter.init` builds one `Registry(grammarSource = ...)` that resolves a
scope via `GrammarIndex.assetFor` and reads `assets/grammars/<file>` with
`GrammarReader.readGrammar`; `GrammarIndex.scopeFor(fileName)` maps files (filename beats
extension). Extension grammars join the same highlighter:

```kotlin
internal class GrammarCatalog(private val bundled: GrammarIndex) {
    fun update(languages: List<Owned<LanguageContribution>>, grammars: List<Owned<GrammarContribution>>,
               associations: Map<String, String>)            // resolved files.associations
    fun scopeFor(fileName: String, firstLine: String?): String?
    fun sourceFor(scope: String): GrammarSource?              // Asset(file) | ExtFile(hostPath)
    val changedScopes: SharedFlow<Set<String>>
}
```

`scopeFor` precedence: `files.associations` > extension filename > extension extension /
pattern > extension `firstLine` > bundled index. Proposed changes to `TextMateHighlighter.kt`:

1. `grammarSource = { scope -> catalog.sourceFor(scope)?.read() }`; `ExtFile.read()` opens
   the host file with the same `GrammarReader` (JSON or plist). The existing `runCatching`
   around grammar load stays: third-party grammar files are a real boundary. Extension
   grammars tokenize under `ExtensionPolicy.GRAMMAR_LINE_TIME_LIMIT_MS` per line; past it the
   rest of that document stays plain text and the grammar is logged (threat-model M-22).
2. New `@Synchronized fun invalidate(scopes: Set<String>)` (collects `changedScopes`): drops
   those scopes from `grammars` and `unavailable` and every `documents` entry whose
   `grammar` came from them, so enabling or disabling a pack re-highlights open tabs on the
   next `highlight`. The `Registry` is rebuilt if a replaced scope was already loaded.
3. `supports`/`grammarFor` go through `catalog.scopeFor`; `highlight` passes
   `source.substringBefore('\n').take(ExtensionPolicy.FIRST_LINE_MAX_CHARS)` as `firstLine`.
4. `ScopeRules` stays the only scope-to-role table; `DocumentHighlighter` is untouched.
   `embeddedLanguages`/`injectTo` depend on vendored `kotlin-textmate` support (Open issues 2).

## 8. ActionRunner

**Responsibility**. Run the L1 action vocabulary for commands and
serve the same operations to L2 host functions (arch.md sec 6.3 step 5): one set of checks.

### 8.1 Model and interface

`Action` is a sealed interface with one data class per sdk-reference vocabulary row
(`RunInTerminal`, `RunTask`, `SandboxExec`, `OpenFile`, `OpenUrl`, `ApplyEdit`,
`InsertSnippet`, `SetConfig`, `ToggleConfig`, `LspRequest`, `ExecuteCommand`,
`ShowQuickPick`, `ShowInputBox`, `ShowMessage`, `RevealStage`, `Sequence`), fields as in
the reference, plus `bindAs` (`"as"`). Every string param is parsed at load into a
`Template` (literal and variable segments), so malformed `${...}` is a validation error.

```kotlin
class ActionRunner(registry: ExtensionRegistry, variables: VariableResolver, ports: ActionPorts,
                   log: ExtensionLog, policy: ExtensionPolicy, scope: CoroutineScope) {
    suspend fun run(owner: ExtensionId, commandId: String, args: JsonValue?): ActionOutcome
    suspend fun runStep(ctx: ActionContext, action: Action): StepResult     // L2 host-function entry
}
sealed interface ActionOutcome     // Done(value) | Cancelled (prompt dismissed: silent) | Failed(stepPath, code, message)
class ActionContext(val owner: ExtensionId, val commandId: String, val args: JsonValue?,
                    val granted: Set<Capability>,             // declared AND approved
                    val snapshot: ContextSnapshot, val editor: EditorState?,   // both frozen at invocation
                    val results: MutableMap<String, JsonValue>, val inputs: MutableMap<String, JsonValue>)
```

### 8.2 Execution

- One `ActionContext` per invocation; snapshot and editor state are frozen, so `${file}` is
  the file the user tapped Run on even if a prompt lets them switch tabs.
- `Sequence` runs steps in order in the invoking coroutine; a failure aborts unless
  `continueOnError`; the result is the last step's. `bindAs` stores each result before the next step.
- Single-flight per `(owner, commandId)`: a re-invocation while running is rejected with
  "<title> is already running" (`runInTerminal` returns immediately, so it never blocks).
- `CommandDispatcher` launches runs in an app-scoped `SupervisorJob` on `Dispatchers.Default`;
  UI steps switch to Main, process steps to IO. Closing the workspace cancels its runs.

### 8.3 Capability checks

1. **Load time** (validator phase 9): `requiredCapabilities(action)` per the sdk-reference
   table, `Sequence` = union. Undeclared = the extension does not load ("never a runtime surprise").
2. **Run time**, before each step, against `ctx.granted`, plus checks only knowable after
   substitution: `openFile`/`applyEdit` path outside `/workspace` needs `fs.outsideProject`;
   `setConfig`/`toggleConfig` of a key not in the owner's `configuration` needs `ui.settings`,
   and protected keys (customization.md 5.2, `EXTENSION_UNWRITABLE`) are refused regardless;
   `executeCommand` runs the target with **the target owner's** grants, never the caller's;
   `openUrl` must be `https` and always shows the confirm sheet.

A run-time denial is `Failed("E_CAPABILITY")`; with a valid manifest only dynamic checks produce it.

### 8.4 Variables and shell quoting

```kotlin
class VariableResolver(settings: SettingsResolver, commands: CommandDispatcher, envShell: EnvShellEnvironment) {
    suspend fun expand(t: Template, ctx: ActionContext, mode: QuoteMode): String
}
enum class QuoteMode { PLAIN, SHELL, ARGV_ELEMENT }
object ShellQuote { fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'" }  // POSIX sh
```

- Variables per sdk-reference. `${env:NAME}` reads the environment's shell env
  (`GuestEnvironment.defaults` in `sandbox/backend/SandboxLauncher.kt` plus `terminal.env`),
  never `System.getenv()`. `${config:key}` resolves for the frozen scope and language.
  `${command:id}` executes the command, nesting bounded by
  `ExtensionPolicy.MAX_COMMAND_VARIABLE_DEPTH` (a cycle fails the step). `${input:id}` is a
  prompt result or runs the matching `easyide.inputs` entry once per invocation.
- `${result:name}`: strings as-is; a `sandboxExec` capture result substitutes its `stdout`
  minus one trailing newline; other values as compact JSON. `itemsFrom` receives the raw value.
- Unresolvable variable -> step error. Quoting is decided by parameter class, never by the author:

| Class | Params | Rule |
|---|---|---|
| `SHELL` | `runInTerminal.command`, `easyide.sandbox.install[].run` | template literal text passes through; every substituted value is `ShellQuote.quote`d, so it is exactly one shell word and cannot inject `;`, `$()` or globs |
| `ARGV_ELEMENT` | each `sandboxExec.command[]`, `languageServers[].command[]` | no quoting; one argv entry, no shell; NUL fails the step |
| `PLAIN` | paths, prompts, labels, URLs | verbatim |

So a substituted setting is one shell word (`"python3 -X dev"` names one program);
multi-word configuration belongs in an array setting passed as `sandboxExec` argv (author-guide.md).

### 8.5 Process steps and the terminal path

| Step | Mechanism | Reuses |
|---|---|---|
| `runInTerminal` | `TerminalHost.terminal(name)` reuses the tab this action opened under that name, else opens one; writes `cd <q(cwd)> && K=<q(v)> ... <command>\r` via `TerminalSession.write`; `clear` sends the clear sequence first; `focus` reveals the bottom stage | `WorkspaceViewModel.createTerminalTab`, `PtyTerminalTab`, `LinuxEnvironment.interactiveShellParams` |
| `sandboxExec`, `output: terminal` | **command PTY**: new `LinuxEnvironment.commandPtyParams(envId, hostProjectDir, argv, cwd, env)` builds the same `LaunchRequest` as `SandboxShell.interactiveParams` with `command = argv`; wrapped in a `TerminalSession` tab titled with the command title; exit code from `EasyTerminalSessionClient.onSessionFinished` (`TerminalSession.getExitStatus()`); the finished tab keeps its scrollback | `SandboxShell.interactiveParams`, `EasyTerminalSessionClient` |
| `sandboxExec`, `capture` / `silent` | non-PTY process via `ServerProcessFactory` (separate stdout/stderr pipes, lsp-client.md); each stream read into a buffer capped at `extensions.actions.captureKb`, truncation flagged | planned `sandbox/` class beside `ShellRunner` |
| `runTask` | resolve the task, then as `sandboxExec` terminal mode, problem matchers on the stream | - |

`TerminalProcess` is not used for capture: `SandboxShell.start` sets
`redirectErrorStream(true)`, but the result contract has separate `stdout`/`stderr`.
`timeoutSec` (default `extensions.actions.execTimeoutSec`) and coroutine cancellation kill
the process (`destroyForcibly` / `TerminalSession.finishIfRunning()`): `E_TIMEOUT`, or
silent on cancel. If `envState != ready` the step fails `E_UNAVAILABLE` without spawning.
Processes get the clean environment the launchers already build plus declared `env`;
never a git token or API key (0012). `envHasCommand:<name>` probes run `sh -c 'command -v <q(name)>'` via the capture path once
per environment per session and after each install; cached in `EnvContextProvider`.

### 8.6 Errors

Per sdk-reference: a failing step aborts its sequence, shows a snackbar with the command
title and writes step path (`steps[2].sandboxExec`), code and stderr tail to the Extension
Log; a cancelled prompt aborts silently. try/catch only at real boundaries: process spawn
and I/O, `openFile`/`applyEdit` file access, calls into `WasmHost`. Pure steps return typed failures.

## 9. Ports into the app

`ActionPorts` bundles `TerminalHost`, `EditorPort`, `PromptPort`, `ConfigWriter`,
`LspPort`, `ProcessPort`, `StagePort`; the load-bearing ones:

```kotlin
interface TerminalHost {
    suspend fun terminal(name: String, owner: ExtensionId): TerminalHandle          // reuse-or-open
    suspend fun commandTerminal(title: String, envId: String, argv: List<String>, cwd: String?,
                                env: Map<String, String>): CommandTerminal          // awaitExit(): Int, kill()
}
interface ProcessPort { suspend fun exec(envId: String, argv: List<String>, cwd: String?, env: Map<String, String>,
                                         captureLimitBytes: Int, timeoutMs: Long): ExecResult }
interface PromptPort { suspend fun quickPick(s: QuickPickSpec): List<JsonValue>?    // null = user cancelled
                       suspend fun inputBox(s: InputBoxSpec): String?; suspend fun confirmUrl(url: String): Boolean }
```

`WorkspaceViewModel.kt` is over the 600-line cap (ux-overhaul Pillar 7): `TerminalHost` is a new `WorkspaceTerminals` controller.

## 10. Config keys and policy constants

Settings read: `extensions.{enabled, safeMode, disabled, developerMode, limits.*}`,
`extensions.actions.{execTimeoutSec, captureKb}`, `extensions.wasm.{activateTimeoutMs,
deactivateTimeoutMs, maxCrashes, crashWindowSec}`, `files.associations`,
`workbench.contributions.*`. Values with no sdk-reference key live in one table:

```kotlin
object ExtensionPolicy {   // SAFE_MODE_AFTER_CONSECUTIVE_CRASHES from arch.md sec 5.5; rest are starting values
    const val SAFE_MODE_AFTER_CONSECUTIVE_CRASHES = 2; const val STARTUP_IDLE_DELAY_MS = 1_000L
    const val WORKSPACE_SCAN_MAX_FILES = 20_000;       const val MAX_PATTERN_LENGTH = 512   // schema + when regex
    const val MAX_COMMAND_VARIABLE_DEPTH = 4;          const val FIRST_LINE_MAX_CHARS = 256
    const val GRAMMAR_LINE_TIME_LIMIT_MS = 50L         // how to interrupt tokenizeLine: Open issues 2
}
```

## 11. Persistence

This module owns `<versionDir>/.descriptor.json` (cache), `<files>/extensions/activation-journal.json`
and the in-memory Extension Log ring (exported on demand). `state.json` (versions,
approvals, pins, crash-disabled set) belongs to registry-and-install.md; crash-disables are
written through `InstalledExtensions`. Enablement is settings (customization.md).

## 12. Testing

JVM unit tests in `services/mobile/extensions/src/test/`; no device needed except where noted.

| Area | Tests |
|---|---|
| Parser/validator | one golden fixture per diagnostic code; the sdk-reference Python and theme examples parse clean; comments rejected; NLS fallback order |
| Schema subset | positive/negative case per keyword; build test that the schema uses only the subset |
| Capability audit | each action type x declared/undeclared; sequence union; dynamic checks (outside-project path, foreign `setConfig`, cross-extension `executeCommand` with target grants) |
| When-clauses | lexing `lspSupports:python:formatting`; precedence `!a && b \|\| c`; every row of 6.2 incl. undefined; `in` with key/array/object/list; regex flags; error offsets; `normalize` round trip |
| ContextKeyService | `observe` skips unrelated keys (counting evaluator); `setAll` emits once; lazy `config.*` |
| Enablement | truth table over the six rules of sec 4; profile allowlist; capability delta disables until approval |
| Activation | every transition with a fake `Activator` and `Clock` (ok, fail, timeout, crash, crash window); concurrent events join one activation |
| Crash journal | leftover record twice -> `enterSafeMode`; clean start resets; unwritable journal does not block startup |
| ContributionRegistry | add/remove/update diff; each row of 7.1; one emission per store per diff |
| Grammars | `GrammarCatalog.scopeFor` precedence; `invalidate` re-highlights an open doc (Robolectric: reads assets) |
| ActionRunner | fake ports: order, `as`/`${result:}`, `continueOnError`, cancel vs fail, single-flight, timeout kill, frozen snapshot |
| Shell quoting | property test: random strings (quotes, `$()`, newlines, globs, unicode) through `sh -c "printf %s <quoted>"` on the host `sh` print the input exactly |
| End to end | `easyide-ext test` scenarios (cli.md) reuse these fakes; M3 exit: Python pack as `.easyext` behaves as the in-app version |

Hooks: `ActionPorts` fakes, injected `Clock`, `ContextKeyService` over a fixed map, policy values via constructors.

## 13. Open issues

1. Verify `org.json:json` license/maintenance from its repository before adding it for JVM
   tests and CLI; the alternative is a small hand-written parser in `:extensions`.
2. Does vendored `kotlin-textmate` support `injectTo`, `embeddedLanguages` and interrupting
   `tokenizeLine` (M-22)? Check its source; unsupported fields stay validate warnings.
3. `CommandRegistry`, `Keymap`, `MenuModel` are M0 and do not exist; re-check 7.2 signatures when they land.
4. arch.md open question 8 (first-run confirm for `sandboxExec`) is unanswered; `PromptPort` is the hook for a confirm.
5. `commandPtyParams` placement (`LinuxEnvironment` vs `SandboxShell`) and the no-proot
   fallback via `ShellRunner` are sandbox-runtime calls; `ChrootLauncher` must accept the
   same argv-form `LaunchRequest` (unverified on a rooted device).

## Deviations

- Resolved in arch.md sec 6.2/6.3: user enablement is settings (`extensions.disabled`,
  profiles); `state.json` keeps approvals, pins and system disables (revoked, crash-disabled,
  awaiting re-approval); WASM disable is `maxCrashes` within `crashWindowSec`.
