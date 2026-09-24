# Extension SDK - Customization (LLD)

Low-level design of everything user-overridable: settings layering and storage, schema
contributions, contribution hide/reorder, keybindings, themes, icon themes, key rows,
custom language servers and project trust, per-language toggles, profiles, safe mode,
export/import, the settings JSON editor and schema-generated UI.

Status: PROPOSED (2026-09-23). Nothing here is implemented.
Contract: [../sdk-reference.md](../sdk-reference.md) (Settings keys). Rationale:
[../arch.md](../arch.md) sec 5.5 Customization, sec 6.3 "Settings resolution". Foundation:
[ux-overhaul Pillar 5](../../ux-overhaul/arch.md) (settings schema, ADR-C). Related:
[extension-runtime.md](extension-runtime.md), [lsp-client.md](lsp-client.md).

Paths: `app/` = `services/mobile/app/src/main/java/dev/easyide/app/`, `sandbox/` =
`services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/`. New code lives in
`app/settings/` (Android-facing: DataStore, files, UI) over pure types in `:extensions`
(`SettingsResolver`, `SchemaValidator`, `JsonValue`, `WhenExpr`).

## 1. What exists today and what replaces it

| Existing | Today | Becomes |
|---|---|---|
| `app/data/UiPreferences.kt` | DataStore `ui_preferences`, four hand-written flow/setter/key triples (`theme_mode`, `onboarding_complete`, `default_environment_id`, `default_projects_folder_uri`) | keeps only app state (`onboardingComplete`); settings move to `SettingsStore` on the **same** DataStore instance (4.1) |
| `app/ui/screens/settings/SettingsViewModel.kt` | 5-flow `combine` into `SettingsUiState` | schema-driven state (sec 17); environment management stays |
| `app/ui/theme/ThemeMode.kt` | closed enum, "closed set for v1" | still the built-in palettes; extension themes layer on top (sec 8; opening the set is ADR-C) |
| `app/ui/theme/EditorColors.kt`, `SyntaxColors.kt` | fixed Dark/Light/AMOLED sets, `editorColorsFor(themeMode, systemInDark)` | output of `ThemeResolver` (sec 8) |
| `app/ui/screens/workspace/TerminalKeys.kt` | `TerminalKeyboard.KEYS`, one fixed row | the built-in key row `builtin.terminal` (sec 10) |
| `sandbox/SandboxPaths.kt` | `environmentDir(id)`, `projectDir(id)` | anchors for the environment and project settings files (4.1) |

## 2. Schema model

Pillar 5's `Setting<T>` extended with the four sdk-reference scopes and the fields layering needs:

```kotlin
enum class SettingScope { G, E, P, L }        // sdk-reference: G user only; E +environment; P every layer but [lang]; L every layer + [lang]
enum class Merge { REPLACE, OBJECT, OBJECT_2 } // objects merge key-wise across layers (1 or 2 levels); arrays and scalars replace

sealed class Setting<T>(
    val key: String, val category: Category, val title: Text, val description: Text,
    val default: T, val scope: SettingScope, val keywords: List<String>,
    val merge: Merge = Merge.REPLACE,
    val execBearing: Boolean = false,          // value can make the app spawn a process (sec 12)
    val appLevel: Boolean = false,             // never profile-scoped, never exported (sec 14)
    val deprecation: String? = null,
) {
    abstract fun decode(v: JsonValue): T?; abstract fun encode(v: T): JsonValue   // decode null = invalid
}   // subclasses: Bool, IntRange(min, max, step), Enum(values), Str(pattern?), StrList, Obj(schema), Secret
class ContributedSetting(key: String, val owner: ExtensionId, val schema: JsonValue.Obj, ...) : Setting<JsonValue>(...)
sealed interface Text { data class Res(@StringRes val id: Int) : Text; data class Literal(val s: String) : Text }
```

Built-in settings are one declarative table (`app/settings/BuiltInSettings.kt`): every
sdk-reference Settings key plus Pillar 5's list. Merge assignments: `lsp.servers` and both
colour-customization keys `OBJECT_2` (server key or `[Theme]` block, then field);
`files.associations`, `workbench.contributions.order`, `workbench.stages.placement`,
`editor.semanticTokenColorCustomizations` `OBJECT`; everything else `REPLACE` (VS Code's
rule: objects merge, arrays replace). `ContributedSetting` validates with the shared
`SchemaValidator`; an `object`-typed contributed property merges `OBJECT`.

A unit test asserts every built-in `default` decodes, so the lowest layer is always valid.
## 3. Layering and resolution

### 3.1 Layers

Low to high; each layer may hold `[lang]` blocks (`"[python]"`, and VS Code's combined
`"[python][markdown]"`), and inside one layer a `[lang]` value beats the plain value
(arch.md sec 5.5/6.3). The sdk-reference line "user global < `[lang]` < per-environment"
is this rule applied to the user layer.

| # | Layer | Source | Written by |
|---|---|---|---|
| 1 | BUILT_IN | `BuiltInSettings` defaults (+ built-in `[lang]` defaults) | code |
| 2 | EXTENSION | `configurationDefaults` of enabled extensions (sec 5.3) | manifests |
| 3 | USER | active profile (default = DataStore) | Settings UI, JSON editor, `setConfig` `user`/`language` |
| 4 | ENVIRONMENT | `<files>/environments/<envId>/easyide/settings.json` | UI, file edit, `setConfig` `environment` |
| 5 | PROJECT | `<project>/.easyide/settings.json` | UI, file edit, git, sandbox processes, `setConfig` `project` |

Scope filter: G reads layers 1-3 plain only; E adds 4; P reads 1-5 plain; L reads 1-5 plain
and `[lang]`. A value in a layer its scope excludes is ignored and flagged by the JSON
editor (sec 16).

### 3.2 Algorithm

```kotlin
data class SettingsQuery(val languageId: String?, val envId: String?, val projectId: String?)
enum class LayerId { BUILT_IN, EXTENSION, USER, ENVIRONMENT, PROJECT }
data class Provenance(val layer: LayerId, val language: String?, val source: String)   // file path, "extension <id>", "profile <name>"
data class Resolved<T>(val value: T, val winner: Provenance, val shadowed: List<Provenance>)

interface SettingsResolver {
    fun <T> get(s: Setting<T>, q: SettingsQuery): Resolved<T>
    fun raw(key: String, q: SettingsQuery): Resolved<JsonValue>?     // unknown keys: highest raw value, unvalidated
    fun snapshot(q: SettingsQuery): StateFlow<ResolvedSettings>
    fun <T> observe(s: Setting<T>, q: SettingsQuery): Flow<T>        // snapshot.map { get }.distinctUntilChanged()
}
```

```
resolve(s, q):
  cands = []
  for layer in [PROJECT, ENVIRONMENT, USER, EXTENSION, BUILT_IN] allowed by s.scope:     // high -> low
      if s.scope == L and q.languageId != null: add layer.lang[q.languageId][s.key]
      add layer.plain[s.key]
  valid = cands where s.decode(value) != null      // invalid: logged once per (layer, key, value hash), skipped
  if s.merge == REPLACE: return valid.first()      // BUILT_IN always present and valid
  return fold(valid low -> high, mergeObjects(depth = 1 or 2)), winner = highest contributor
```

- "Invalid falls back to the next lower layer" (arch.md sec 5.5) is the `valid` filter.
- Resolution is pure and cheap (<= 10 candidates). `ResolvedSettings` memoizes per key for one
  `LayersVersion` (tuple of layer versions); any layer change yields a new, empty memo.

### 3.3 Arrays and lists

Arrays replace wholesale, including `extensions.disabled` and `workbench.contributions.hidden`,
so a project can re-enable or un-hide by writing a list without the entry. The UI always
writes the **full effective list** (inherited entries plus the change) into the target
layer, so "hide this in the project" never un-hides the user's other items.

## 4. Storage and change propagation

### 4.1 Stores

| Layer | Storage | Notes |
|---|---|---|
| USER (default profile) | DataStore `ui_preferences`; key `setting:<key>` = compact JSON string; `setting:[python]` = JSON object | the delegate `Context.preferencesStore` (private in `UiPreferences.kt`) moves to `app/data/PreferencesStore.kt` as `internal`: DataStore forbids two instances on one file in a process |
| USER (named profile) | `<files>/user/profiles/<name>.json` | sec 14 |
| ENVIRONMENT | `File(SandboxPaths.environmentDir(envId), "easyide/settings.json")` | beside `rootfs/`, not inside it: guests do not see it |
| PROJECT | `File(SandboxPaths.projectDir(projectId), ".easyide/settings.json")` | visible in the guest as `/workspace/.easyide/settings.json`: writable by sandbox processes, arrives via git clone (sec 12) |
| keybindings | `<files>/user/keybindings.json` (or profile), `<project>/.easyide/keybindings.json` | sec 7 |
| user snippets | `<files>/user/snippets/<lang>.json` | consumed by the L0 snippet engine |
| trust records | DataStore `trust:lsp:<projectId>` | sec 12 |

Migration (DataStore `DataMigration`): `theme_mode` -> `appearance.themeMode`,
`default_environment_id` -> `sandbox.defaultEnvironment`, `default_projects_folder_uri` ->
`files.defaultProjectsFolder` (Pillar 5 names), old keys removed; meanwhile
`UiPreferences.themeMode` etc. read through `SettingsStore` so existing callers keep working.

### 4.2 Files

- Read with `JsoncReader` (comments, trailing commas, as VS Code `settings.json`; offsets kept).
- Written with `JsoncEditor.set(text, path, value?)`: a minimal edit replacing or inserting one
  property, preserving comments; then temp file + atomic rename. File I/O is a real boundary:
  a failure shows a snackbar and leaves the old file.
- A file that fails to parse keeps its **last good** parse in memory (empty at cold start)
  and raises a banner "project settings.json has errors - using last valid version". A file
  over `SettingsPolicy.MAX_FILE_BYTES` or nested deeper than `MAX_JSON_DEPTH` counts as a
  parse failure without being fully read (threat-model ST-27).
- `SettingsFileWatcher`: `FileObserver` (`CLOSE_WRITE | MOVED_TO | DELETE`) on both dirs, the
  `sandbox/files/ProjectFileWatcher.kt` pattern in its own instance (that one covers only
  expanded tree dirs); debounced by `SettingsPolicy.FILE_RELOAD_DEBOUNCE_MS`; self-writes skipped by hash.

```kotlin
data class LayerDoc(val version: Long, val plain: Map<String, JsonValue>,
                    val lang: Map<String, Map<String, JsonValue>>, val errors: List<Diagnostic>)
interface LayerSource { val doc: StateFlow<LayerDoc>; suspend fun write(edits: List<SettingEdit>) }
data class SettingEdit(val key: String, val language: String?, val value: JsonValue?)   // null removes
class SettingsStore(user: LayerSource, envs: (String) -> LayerSource, projects: (String) -> LayerSource,
                    extensionDefaults: StateFlow<LayerDoc>, schema: SettingsSchema) : SettingsResolver
interface ConfigWriter { suspend fun set(key: String, value: JsonValue?, target: ConfigTarget, q: SettingsQuery) }
```

### 4.3 Propagation

Every layer is a `StateFlow<LayerDoc>`. `snapshot(q)` combines the flows relevant to `q` on
`Dispatchers.Default` into one `ResolvedSettings` per change (a batch edit = one emission).

| Consumer | Mechanism |
|---|---|
| Compose | `ResolvedSettings` CompositionLocal from `MainActivity` for the active workspace (Pillar 5) |
| Terminal | font/colours in `AndroidView.update`; shell/env at session creation (`TerminalPane.kt`, `WorkspaceViewModel.kt`) |
| LSP | `LspConfigBridge` recomputes each server's `settingsSection` and resolved server config; changed -> `didChangeConfiguration` / restart (lsp-client.md) |
| When-clauses | `ContextSnapshot.get("config.<k>")` resolves lazily; `ContextKeyService` re-evaluates expressions whose `config.*` keys changed value |
| Extensions | `ExtensionRegistry` observes `extensions.enabled/disabled/safeMode` |
| Highlighting | `files.associations` -> `GrammarCatalog.update` (extension-runtime.md 7.3) |

## 5. Schema contributions

### 5.1 Registration

`ContributionRegistry.configuration` feeds `SettingsSchema.register(owner, sections)`;
each property becomes a `ContributedSetting` (title/description/`markdownDescription`
rendered as plain text with links, `enum` + `enumDescriptions`, min/max, `order`,
`deprecationMessage`). Section `title` becomes a Settings category under "Extensions".

VS Code `scope` mapping:

| Manifest `scope` | SettingScope |
|---|---|
| `application`, `machine` | G |
| `machine-overridable`, `environment` | E |
| `window`, `resource`, `project`, absent | P |
| `language-overridable` | L |

### 5.2 Conflicts

| Case | Rule |
|---|---|
| contributed key = built-in key | property ignored, Extension Log + validate warning; use `configurationDefaults` to change a default |
| same key in two extensions | owner = earlier in `EnabledSet`; later ignored. Ownership decides `setConfig` "own keys" (extension-runtime.md 8.3) |
| extension writes a protected key | `SettingsPolicy.EXTENSION_UNWRITABLE` (`extensions.*`, `lsp.servers`, `profiles.active`, keybinding files) is refused for `setConfig` / `config.set` even with `ui.settings` (threat-model M-11) |
| default fails its own schema | property registered with no default (`null`), warning |
| owner disabled or uninstalled | schema entry removed; stored values stay (re-enable restores); JSON editor shows them as "from disabled extension <id>" |

### 5.3 configurationDefaults

Build the EXTENSION layer: fold each enabled extension's `configurationDefaults` (plain and
`[lang]` blocks) in `EnabledSet` order, later overriding earlier (VS Code semantics; the
inspector shows who set it). Targets may be built-in or other extensions' keys; values
that fail the target schema are dropped with a warning.

## 6. Contribution hide, reorder, placement

Keys (P scope): `workbench.contributions.hidden` (refs `<kind>:<location>:<id>`, parsed with
`ContributionRef.parse`), `workbench.contributions.order` (`{location: [id...]}`),
`workbench.stages.placement` (`{stageId: left|main|right|bottom}`).

```kotlin
data class ContributionOverrides(val hidden: Set<ContributionRef>, val order: Map<String, List<String>>,
                                 val placement: Map<String, StageSlot>)
fun <T> applyOverrides(location: String, items: List<Owned<T>>, o: ContributionOverrides,
                       defaultOrder: Comparator<Owned<T>>): List<Owned<T>>
```

Algorithm per location: drop hidden refs; sort by `defaultOrder` (menus: group rules;
status bar: `priority`); then ids listed in `order[location]` move to the front in listed
order; unlisted ids keep default order after them (sdk-reference). Unknown ids are ignored
at runtime and warned in the JSON editor (the extension may just be disabled).

Location vocabulary: every menu id (sdk-reference Menu ids), `statusBar.left`,
`statusBar.right`, `activitybar`, `panel`, `stages`, `keyRows`.

Built-ins are contributions too (`Owner.BuiltIn`) and can be hidden, except
`SettingsPolicy.NON_HIDEABLE` (palette, Settings entry, safe-mode banner) so no override
locks the user out; hiding one is a JSON-editor warning.

## 7. Keybindings

### 7.1 Layers and files

Low to high: BUILT_IN (`Keymap` default table, Pillar 4) < EXTENSION (`contributes.keybindings`,
`EnabledSet` order) < USER (`keybindings.json` of the active profile) < PROJECT
(`.easyide/keybindings.json`). Entry: `{key, command, when?, args?}`; for contributions
`linux` replaces `key` when present, `mac`/`win` ignored, `cmd` = Meta (sdk-reference).
Project keybindings are not trust-gated: they can only invoke commands, which carry their
own checks.

```kotlin
data class KeyPress(val mods: Int /* CTRL|SHIFT|ALT|META */, val key: KeyName)
data class KeyChord(val presses: List<KeyPress>)                  // 1 or 2 presses ("ctrl+k ctrl+s")
data class Binding(val chord: KeyChord, val command: String, val whenExpr: WhenExpr?, val whenText: String?,
                   val args: JsonValue?, val layer: LayerId, val index: Int)
object KeyNames { fun parse(s: String): KeyChord?; fun fromAndroid(e: KeyEvent): KeyPress? }  // one declarative table
class KeymapResolver { fun effective(layers: List<List<KeybindingEntry>>): List<Binding> }
```

`KeyNames` is the single table mapping VS Code key names to `KeyEvent.KEYCODE_*` (the
ui-shell keybinding-table rule); modifiers are normalised to `ctrl+shift+alt+meta` order.

### 7.2 Resolution and removal

```
effective = []
for layer in BUILT_IN, EXTENSION, USER, PROJECT; for entry in layer (file order):
    if entry.command starts with "-":
        remove b from effective where b.command == entry.command.drop(1)
            and (entry.key == null or b.chord == chord(entry.key))
            and (entry.when == null or WhenParser.normalize(b.when) == WhenParser.normalize(entry.when))
    else append Binding(entry)
```

Dispatch (`onPreviewKeyEvent` on Main, if `keyboard.hardwareShortcutsEnabled`): matching-chord
candidates scanned last to first (later layer, then later entry wins); the first whose `when`
is true on `ContextKeyService.snapshot.value` fires; if its command's `enablement` is false
the key is consumed and nothing runs (VS Code behaviour). A first press of any 2-press chord
enters a pending state (status bar), cleared by the next key or `SettingsPolicy.CHORD_TIMEOUT_MS`.

### 7.3 Conflict detection

Static, recomputed with `effective` and shown in the Keyboard Shortcuts screen and JSON editor:

- **Same chord**: two bindings with equal chords may both fire unless their `when`s provably
  exclude each other. `mayOverlap(a, b)` is false only if both are conjunctions of atoms and
  contain a contradiction (`k == x` vs `k == y`, `k` vs `!k`, `k == x` vs `k != x`);
  otherwise true. A conflict lists the winner (later) and the shadowed ones.
- **Prefix**: a 1-press binding equal to the first press of an active 2-press chord makes
  the chord unreachable when both `when`s may overlap.
- **Reserved**: chords in `SettingsPolicy.RESERVED_CHORDS` (handled by Android before the
  app) warn "the system handles this shortcut".

UI edits write the user file: rebind = `-command` for the old chord + a new entry; remove =
`-command`; reset = delete that command's user entries.

## 8. Themes and colour customization

### 8.1 Selection

`appearance.themeMode` (Pillar 5, today's `ThemeMode`) chooses the built-in palette.
`workbench.colorTheme` (G) = a theme label; unset or a built-in label means the built-in
palette; a contributed label selects that theme, whose `uiTheme` picks the base palette for
tokens it does not define (`vs` -> LIGHT, `vs-dark` -> DARK, `hc-black` -> HIGH_CONTRAST,
`hc-light` -> LIGHT with high-contrast adjustments). Letting non-enum themes in is the ADR-C
change ux-overhaul already requires ("opens the closed set").

### 8.2 Model and mapping

```kotlin
data class TokenStyle(val color: Color?, val bold: Boolean?, val italic: Boolean?, val underline: Boolean?)
data class ThemeDefinition(val label: String, val base: ThemeMode, val colors: Map<ColorToken, Color>,
                           val roles: Map<SyntaxRole, TokenStyle>, val semantic: Map<String, TokenStyle>,
                           val semanticHighlighting: Boolean?)
object ThemeColorMap { val BY_VSCODE_KEY: Map<String, ColorToken> }  // "editor.background" -> background,
    // "editorLineNumber.foreground" -> gutterText, "statusBar.background" -> statusBar, "tab.activeBackground" -> tabActive, ...
class ThemeResolver { fun resolve(inputs: ThemeInputs): ResolvedTheme }   // -> EditorColors + M3 ColorScheme
```

`ColorToken` = `EditorColors` fields today, `EasyIdeColors` tokens after ADR-A; `ThemeColorMap`
is the only VS Code-key translation (validate lists unmapped keys).

`tokenColors` collapse onto the 21 `SyntaxRole`s through `ScopeRules` so the table stays the
single scope-to-role source: a new `internal fun ScopeRules.prefixesFor(role): List<String>`
exposes its rules; for role R, a theme rule selector S matches prefix P of R when `P == S` or
`P` starts with `S + "."`; the longest matching S wins, ties go to the later rule (TextMate
order). Descendant selectors use their last segment; exclusions (`-`) are ignored. Roles with
no match keep the base palette.

v1 applies `color`; `bold/italic/underline` are carried and applied once
`DocumentHighlighter.annotate` looks up a `TokenStyle` instead of `SyntaxColors[role]` (a
small, separate change).

### 8.3 Merge order (later wins)

| Output | Order |
|---|---|
| UI/editor colours | base palette < theme `colors` < `workbench.colorCustomizations` top-level < its `"[<active theme label>]"` block |
| Syntax roles | base < theme `tokenColors` < `editor.tokenColorCustomizations.textMateRules` (collapsed as above) < `.roles` (easyIDE role names, e.g. `"keyword": "#C792EA"`) < the same two inside `"[<theme>]"` |
| Semantic | theme `semanticTokenColors` < `editor.semanticTokenColorCustomizations.rules` < `"[<theme>]".rules`; enabled = `editor.semanticHighlighting.enabled` (L): `true`/`false`, or `configuredByTheme` -> theme `semanticHighlighting` ?: false; the customization's own `enabled` overrides |

Colours: `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`; invalid entries are skipped and flagged.
Semantic selectors `type.modifier:language` overlay TextMate roles (arch.md sec 7.9; the
role table question is arch.md open question 7). All three keys are G scope (user layer,
so profile-scoped). `ThemeResolver` runs on Default, memoized by input hash; the theme
picker's preview cards (Pillar 5) resolve candidates without applying them.

## 9. Icon themes

`workbench.iconTheme` (G): an id, or unset for the built-in file-type icons (Pillar 3).
`IconThemeCatalog` parses contributed VS Code icon theme JSON (`fonts` ignored). Lookup,
first hit wins: file: `fileNames` (case-insensitive) > `fileExtensions` (longest multi-part
first, `d.ts` before `ts`) > `languageIds` (language from `GrammarCatalog`) > `file`;
folder: `folderNames`/`folderNamesExpanded` > `folder`/`folderExpanded`; the `light`
section applies when the resolved base is light. PNG decodes with `BitmapFactory`; SVG needs
a renderer the app does not have (Open issues 2). Decoded icons are cached (LRU of
`SettingsPolicy.ICON_CACHE_ENTRIES`).

## 10. Key rows

```kotlin
sealed interface KeyAction { data class Insert(val text: String) : KeyAction; data class Snippet(val body: String) : KeyAction
                             data class Key(val chord: KeyChord) : KeyAction; data class Command(val id: String) : KeyAction }
data class RowKey(val label: String, val action: KeyAction, val longPress: KeyAction?)
data class KeyRow(val id: String, val title: String, val whenExpr: WhenExpr?, val keys: List<RowKey>, val owner: Owner)
```

Sources: built-in `builtin.terminal` (today's `TerminalKeyboard.KEYS`: arrows/Esc/Tab/^C as
`Key`, symbols as `Insert`), contributed `easyide.keyRows`, user `keyRows.layouts` (G,
profile-scoped). A user layout with the id of an existing row **replaces** it (that is how a
contributed row is reordered or edited: copy, then change). Target semantics: in the
terminal `Insert` writes bytes, `Key` goes through the terminal's key encoder, `Snippet`
inserts its body with placeholders stripped; in the editor they insert text, send a key
event, and run the snippet engine. `Command` runs the command in both.

Active row: `keyRows.active` (L, so `"[python]": {"keyRows.active": "python.symbols"}`
works): an id -> that row if present and not hidden; `auto` -> first row, user layouts then
contributed (`EnabledSet` order) then built-in, whose `when` is true for the focused surface
and not hidden via `keyRow:<id>`; fallback `builtin.terminal`. Commands contributed to the
`keyRow` menu are appended. Visibility stays with Pillar 5 `terminal.accessoryBar`.

## 11. Safe mode

`SafeModeState(persisted, sessionReason).active: StateFlow<SafeModeReason?>`, reasons
`SETTING | LAUNCHER_SHORTCUT | AUTO_CRASH`. Entry: `extensions.safeMode` (G, `appLevel`, persisted); launcher long-press "Start in safe
mode" (a static app shortcut launching `MainActivity` with `EXTRA_SAFE_MODE`, session only);
automatic from the crash journal verdict (extension-runtime.md 5.3, session only).
`SafeModeState` is built in `AppContainer` before `ContributionRegistry` registers anything.

Effects: every non-built-in extension is not enabled (so no contributions, WASM or
extension servers); `lsp.servers` entries defined without an extension are not started;
project-layer `execBearing` values are ignored; all other settings, keybindings and colour
customizations still apply (validated data). `isSafeMode` is true; a persistent banner offers
"Exit safe mode" (clears the setting if that was the reason, then recreates the activity).
The Extensions screen works, so the culprit can be disabled or uninstalled. Safe mode never
rewrites stored enablement.

## 12. Language servers from settings, and project trust

`lsp.servers` (P, `OBJECT_2`) resolves per `(env, project)`:

`ServerConfigResolver(contributions, settings, trust).resolve(q): Map<ServerKey, ServerConfig>`
(keys `<extId>/<id>` or free names) feeds `ServerSupervisor` (lsp-client.md):

1. Start from `easyide.languageServers` of enabled extensions, keyed `<extId>/<id>`.
2. Each resolved `lsp.servers` entry: an existing key gets field overrides (validated against
   the `languageServers` field schema plus `enabled`); a new key needs `languages` and
   `command`; `enabled: false` removes the server; an override for a disabled extension's server is ignored.
3. No capability prompt: the user wrote it (sdk-reference). A missing binary is the server's failed state.

**Project trust** (answers arch.md open question 2). The project file arrives with `git
clone` and is writable from the sandbox, and a language server is spawned without any user
action, so exec-bearing values from the PROJECT layer need consent:

- Exec-bearing: `lsp.servers.*.command`, `.env`, `.initializationOptions`, and any built-in
  key marked `execBearing` (e.g. Pillar 5 `terminal.shell`, `terminal.env`).
- Fingerprint = sha256 of the canonical JSON (RFC 8785, as the registry uses) of those
  project-layer values only.
- `ProjectTrust.state(projectId, fp)` compares with DataStore `trust:lsp:<projectId>`. On
  mismatch the sheet lists every affected server key with its exact argv and env: "Allow for
  this project" (store fp), "Not now" (session), "Never" (store `denied:<fp>`).
- Untrusted: those project values are dropped (lower layers apply; project-only servers do
  not start) and a status item "Project servers blocked" reopens the sheet. Any change to
  the values changes the fingerprint and re-prompts.
- USER and ENVIRONMENT layers are not gated: written in-app; the env file is outside the rootfs.

## 13. Per-language feature toggles

L-scope keys in `[lang]` blocks of any layer, resolved with the document's language id.
One declarative `FeatureToggles` table maps LSP feature ids to the keys that gate them
client-side (requests not sent, UI hidden):

| Feature ids | Keys |
|---|---|
| `diagnostics` | `editor.diagnostics.minSeverity`, `showInGutter`, `showSquiggles`, `ignoreSources` |
| `completion` | `editor.quickSuggestions`, `suggestOnTriggerCharacters`, `wordBasedSuggestions`, `suggest.showSnippets` |
| `hover`, `signatureHelp` | `editor.hover.enabled`, `editor.parameterHints.enabled` |
| `inlayHints`, `semanticTokens`, `codeLens` | `editor.inlayHints.enabled`, `editor.semanticHighlighting.enabled`, `editor.codeLens` |
| `documentHighlight`, `documentLink`, `codeAction` | `editor.occurrencesHighlight`, `editor.links`, `editor.lightbulb.enabled` |
| `formatting`, `rangeFormatting`, `onTypeFormatting` | `editor.formatOnSave/Type/Paste`, `editor.defaultFormatter` |
| `foldingRange` | `editor.folding`, `editor.foldingStrategy` |

`lsp.enabled: false` in `[lang]` starts no server for that language. Toggles are client-side
because capabilities are negotiated once per server at `initialize` and one server may serve
several languages; server-wide pruning is `lsp.servers.<k>.features`.

## 14. Profiles

```kotlin
data class Profile(val name: String,                            // ^[A-Za-z0-9 _-]{1,40}$, "default" reserved
                   val settings: LayerDoc,                     // the USER layer incl. [lang]
                   val keybindings: List<KeybindingEntry>,
                   val enabledExtensions: List<ExtensionId>?,  // null = no allowlist
                   val keyRows: List<KeyRowLayout>?)           // wins over settings' keyRows.layouts (validate warns if both)
class ProfileManager { val active: StateFlow<Profile>; suspend fun switchTo(name: String): Result<Unit> }
// plus create(name, copyFrom?), rename(from, to), delete(name) - never the active or default profile
```

File: `<files>/user/profiles/<name>.json` = `{settings, keybindings, enabledExtensions,
keyRows}` (sdk-reference). `default` is the DataStore user layer plus
`<files>/user/keybindings.json`. `appLevel` keys (`profiles.active`, `extensions.registries`,
`extensions.safeMode`, `extensions.developerMode`, `extensions.limits.*`, `extensions.wasm.*`,
`lsp.globalMemoryBudgetMb`, secrets) always live in the default store.

Switch: (1) parse and validate the target, refuse on errors; (2) write `profiles.active`;
(3) swap the USER `LayerSource` and keymap user layer in one emission; (4)
`ExtensionRegistry` recomputes (allowlist) -> contribution diff, `LspConfigBridge` pushes
config, servers of now-disabled extensions stop. Buffers, terminals, layout untouched; no restart.

## 15. Export and import

One zip via SAF (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`), per sdk-reference:

```
easyide-settings-<date>.zip
  manifest.json      {"format":"easyide-settings","formatVersion":1,"appVersion","exportedAt",
                      "profiles":[...], "extensions":[{"id","version","source","scope"}]}
  settings.json      default profile user layer ([lang] blocks included)
  keybindings.json   snippets/<lang>.json   profiles/<name>.json
```

Never exported: `Setting.Secret` values, anything in `GitCredentials` or the Claude key
store (0012), trust records, `state.json` approvals/pins (capabilities are re-approved on the
importing device), `appLevel` limit keys, environment and project layers (those travel with
the environment or the repo).

Import: paths checked with the package rules (no `..`, no absolute, no duplicates),
total size <= `SettingsPolicy.IMPORT_MAX_BYTES`; every file validated; preview shows counts,
invalid entries and profile name clashes; "Merge" (imported keys win, others kept) or
"Replace"; applied as one DataStore `edit` plus temp-and-rename per file. Unknown keys are
kept (they may belong to extensions not installed yet). Listed extensions that are missing
are offered through the normal install flow with capability prompts - never installed automatically.

## 16. Settings JSON editor

"Edit as JSON" opens a virtual document of the active profile's non-default values (USER;
order: category, then key) or the real file in an editor tab (ENVIRONMENT/PROJECT).
`SettingsJsonDiagnostics` (in-app, no server), debounced by
`SettingsPolicy.JSON_VALIDATE_DEBOUNCE_MS`, feeds the LSP diagnostic decorations (ADR-B):

| Check | Severity |
|---|---|
| JSONC parse error | error |
| schema/type violation (built-in `decode`, contributed `SchemaValidator`) | error (value will be skipped) |
| unknown key; key of a disabled extension | warning; info naming the extension |
| key not allowed in this layer, or non-L key inside `[lang]` | warning "ignored here" |
| `deprecationMessage`; unknown or non-hideable `ContributionRef` | warning |
| exec-bearing key in a project file | info "requires project trust" |
| `keybindings.json`: bad chord, `when` parse error, unknown command | error / error / warning |

Saving the virtual USER document: parse errors refuse the save (buffer kept); otherwise the
diff is one DataStore `edit`. Schema-invalid values are saved as written (user intent) and
skipped at resolution. Key/enum completion from the schema is an M4 provider.

## 17. UI generated from the schema

`SettingsScreen` renders from `SettingsSchema`: Pillar 5 categories plus one per contributed
section under "Extensions"; tablet = rail + detail, phone = list.

| Type | Row |
|---|---|
| Bool | switch |
| Enum (+ `enumDescriptions`) | dropdown |
| IntRange / number with min/max | stepper or slider |
| Str (`pattern`) | text field, inline validation |
| StrList | chip list editor |
| Obj, arrays of objects, anything else | "Edit in settings.json" link to the key |
| Secret | masked field in the Keystore-backed store beside `GitCredentials` |

- Layer tabs User / Environment (active env) / Project (open project), plus a language chip
  (`@lang:python`) that writes into `[python]`; rows whose scope excludes the layer are read-only.
- Each row shows: modified dot (set in this layer), "Overridden by <layer>" with a link to the
  file (arch.md risk "why is this value used?"), per-row reset (removes the key from this
  layer), invalid-value warning.
- Search over title, description, keywords and key; filters `@modified`, `@ext:<id>`, `@lang:<id>`.
- `SettingsViewModel(schema, resolver, writer, environmentManager, externalFolderSync, projectManager)`
  exposes `StateFlow<SettingsScreenState>` from `schema.entries` + `resolver.snapshot(q)`;
  `onThemeSelected`/`onDefaultEnvironmentSelected` become `onValueChanged(key, layer, language, value)`;
  environment deletion and folder picking stay as they are.
- Titles: string resources for built-ins, manifest strings (NLS applied) for contributions.

## 18. Threading and policy constants

DataStore/file I/O on `Dispatchers.IO`; resolution, theme, keymap and conflict computation
on `Dispatchers.Default`; key dispatch reads immutable snapshots on Main. Only `LayerSource`
writers lock (a `Mutex` per file, so concurrent `setConfig` calls serialise). Keys used: the
sdk-reference keys cited above plus Pillar 5 `appearance.themeMode`, `terminal.*`,
`keyboard.hardwareShortcutsEnabled`.

```kotlin
object SettingsPolicy {                                   // starting values, to profile
    const val FILE_RELOAD_DEBOUNCE_MS = 200L;  const val JSON_VALIDATE_DEBOUNCE_MS = 300L
    const val CHORD_TIMEOUT_MS = 2_000L;       const val ICON_CACHE_ENTRIES = 256
    const val IMPORT_MAX_BYTES = 10L * 1024 * 1024;    const val MAX_FILE_BYTES = 1L * 1024 * 1024
    const val MAX_JSON_DEPTH = 64
    val EXTENSION_UNWRITABLE: List<String> = listOf("extensions.*", "lsp.servers", "profiles.active")
    val NON_HIDEABLE: Set<String> = setOf("command:workbench.action.showCommands", "view:builtin.settings", "statusBar:builtin.safeMode")
    val RESERVED_CHORDS: Set<String> = setOf("meta+tab", "alt+tab", "meta+space")   // to verify per Android version
}
```

## 19. Testing

JVM tests for pure pieces; Robolectric where DataStore or `FileObserver` is involved.

| Area | Tests |
|---|---|
| Resolution | table-driven: 5 layers x plain/`[lang]` x scopes G/E/P/L; invalid value falls through; `OBJECT`/`OBJECT_2` merges (`lsp.servers` user field + project disable); arrays replace; provenance and shadowed lists |
| Schema | every built-in default decodes; contributed scope mapping; collision rules of 5.2; `configurationDefaults` order |
| Storage | DataStore migration of the three legacy keys; one DataStore instance shared with `UiPreferences`; `JsoncEditor` preserves comments (golden files); bad JSON keeps last good parse; self-write not reloaded |
| Propagation | batch edit = one snapshot emission; `observe` distinct; profile switch = one emission |
| Overrides | hide/order algorithm incl. unknown ids and `NON_HIDEABLE`; full-list writes (3.3) |
| Keybindings | parse/normalise chords; removal by command, by command+key, by command+when; last-wins dispatch; disabled command consumes key; chord pending/timeout (fake clock); conflict, prefix and reserved detection |
| Themes | `ThemeColorMap` coverage; `tokenColors` collapse via `ScopeRules.prefixesFor` (longest selector, later tie); full merge-order table of 8.3; colour parsing |
| Icon themes | lookup precedence incl. multi-part extensions and light variant |
| Key rows | `auto` selection order, replace-by-id, hidden rows, built-in fallback; `TerminalKeyboard.KEYS` round-trips into `builtin.terminal` |
| Safe mode | each entry path; effects list of sec 11; exit clears only the setting reason |
| LSP servers + trust | contributed + override merge; custom server needs `languages`+`command`; fingerprint stable under key reordering, changes on any exec-bearing edit; untrusted drops only project exec values |
| Export/import | round trip equals source minus excluded classes; secrets never present (scan zip bytes for a planted secret); zip path traversal rejected; merge vs replace |
| JSON editor | one fixture per diagnostic row of sec 16; save refused on parse error |
| UI | screenshot tests of one row per type; search filters |

## 20. Open issues

1. SVG for icon themes and command icons needs a renderer; any library (e.g. AndroidSVG)
   must have its license and maintenance verified from its repository before adoption.
   PNG-only until then.
2. Per-project profile association (arch.md sec 5.5 "switch per project") has no settings key;
   v1 supports only the global `profiles.active`.
3. `ColorToken` set depends on ADR-A (`EasyIdeColors`); `ThemeColorMap` is written against
   `EditorColors` first.
4. `RESERVED_CHORDS` contents must be verified on device per Android version.
5. Pillar 5 lists `terminal.accessoryKeys`; this LLD makes `keyRows.layouts` the single
   source for row contents. The ux-overhaul settings list should drop `accessoryKeys`.

## Deviations

- Resolved in arch.md sec 5.5: `workbench.contributions.*`, `keyRows.layouts`/`active`, one
  zip export, `editor.diagnostics.*` in `[lang]`, no `lsp.servers.<id>.args` (argv `command`).
- Pillar 5's two scopes (`GLOBAL | PROJECT_OVERRIDABLE`) become sdk-reference's four (G/E/P/L).
- Implementation (2026-09-24): `keyRows.layouts` is a USER-layer setting, so it follows the
  active profile; the profile file's `keyRows` field only round-trips. `NON_HIDEABLE` lives in
  `:extensions` as `ContributionOverrides.NON_HIDEABLE`. `workbench.stages.placement` is not
  declared until contributed stages are rendered. The Keyboard Shortcuts screen reports
  same-chord conflicts only (not prefix or reserved), and removals still match `when` by
  focus condition (the resolver's existing rule).
