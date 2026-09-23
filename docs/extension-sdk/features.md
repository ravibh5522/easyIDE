# Extension SDK - Features

Master feature list: every feature of the Extension SDK and language intelligence, with ID,
priority, milestone, layer, knobs, acceptance criteria and owning LLD.

Status: PROPOSED (2026-09-23). Design: [arch.md](arch.md). Contract: [sdk-reference.md](sdk-reference.md).
Terms: [glossary.md](glossary.md).

## How to read this file

- **ID** scheme: `NS` no-server (arch 5.1), `LSP` language intelligence (arch 5.2 + sec 7),
  `EXT` extension capabilities and runtime (arch 5.3), `ECO` ecosystem (arch 5.4), `CUS`
  customization (arch 5.5), `PLT` platform prerequisites and shared infrastructure (arch M0,
  sec 6.2). IDs are stable: never renumber, retire with "(retired)".
- **L** = layer that implements the feature (L0 built-in host code, L1 declarative, L2 WASM,
  L3 Node host, L4 webviews). Host code that serves contributions of a higher layer is L0.
- **P** = priority. P0 = milestone exit criterion depends on it; P1 = in scope of the
  milestone but not an exit blocker; P2 = later, optional or conditional.
- **M** = milestone from arch sec 12 (`done` = exists today, `later` = unscheduled).
- **Srv** = needs a running language server.
- **Knobs** = setting keys from [sdk-reference.md#settings-keys](sdk-reference.md#settings-keys).
  `(fixed)` = a policy constant from sdk-reference "Fixed limits", not a setting.
- **Acceptance** = testable criteria; the test lives in the test-plan section in the
  traceability table at the end.
- **LLD** = owning design file under `lld/`, or `hld.md` where no LLD exists.

## 1. Platform prerequisites (PLT)

Shared with ux-overhaul ([arch.md](../ux-overhaul/arch.md) Pillar 4, Pillar 5, ADR-B).
Contributions plug into these; there is never a parallel registry.

| ID | Feature | Description | L | P | M | Srv | Knobs | Acceptance | LLD |
|---|---|---|---|---|---|---|---|---|---|
| PLT-01 | Command registry | One `Command(id, title, icon, enabledWhen, run)` registry; palette, menus, key rows, buttons are views over it | L0 | P0 | M0 | no | - | a command registered once is reachable from palette, a menu and a keybinding; disabled state follows `enabledWhen` | lld/extension-runtime.md |
| PLT-02 | Keymap | Default chord per command + user overrides JSON, dispatched from top-level `onPreviewKeyEvent` | L0 | P0 | M0 | no | `keybindings.json` | a command bound only in keymap JSON fires on its chord; removing the entry unbinds it without restart | lld/customization.md |
| PLT-03 | Settings schema | Declarative `Setting<T>` schema drives storage, `SettingRow` UI, search, defaults, validation; replaces `UiPreferences` triples | L0 | P0 | M0 | no | all | every schema key renders a row, round-trips through `SettingsStore`, invalid stored value falls back to default | lld/customization.md |
| PLT-04 | Project settings file | `<project>/.easyide/settings.json` layer read and watched | L0 | P0 | M0 | no | all P/L keys | a project value wins over user global; editing the file updates the resolved value live | lld/customization.md |
| PLT-05 | Editor decoration layers | Underline, background range, inline text, gutter icon, between-line block, caret popup, token overlay in `EditorPane` (ADR-B) | L0 | P0 | M0 | no | - | a test underline, gutter icon and caret popup render at the right offsets and survive scrolling and edits | lld/lsp-client.md |
| PLT-06 | Palette + quick open | Command palette and quick open as views over PLT-01, with `#` prefix hook for workspace symbols | L0 | P0 | M0 | no | keybindings | Ctrl+Shift+P lists every enabled command; `when`-hidden commands are absent | lld/extension-runtime.md |
| PLT-07 | Server process spawn | `ServerProcessFactory`: non-pty stdio process in an environment, cwd `/workspace`, clean env + declared `env`, beside `ShellRunner` | L0 | P0 | M2 | no | `lsp.servers.<id>.env` | spawned process env contains no git token, no Claude key, only declared vars; stdin/stdout/stderr pipes work under proot and chroot | lld/lsp-client.md |
| PLT-08 | MemoryPolicy + RSS sampler | One declarative threshold table; RSS of process tree from `/proc/<pid>/status`; `onTrimMemory` hook | L0 | P0 | M2 | no | `lsp.globalMemoryBudgetMb`, `lsp.defaultMemoryBudgetMb`, `LspPolicy.MEMORY_SAMPLE_MS` (fixed) | no inline thresholds (lint/grep check); sampler reports tree RSS within 5% of `/proc` sum | lld/lsp-lifecycle.md |
| PLT-09 | Shared extension schema | One JSON Schema in `services/shared/extension-schema/` used by app loader and CLI | L0 | P0 | M3 | no | - | app and CLI produce identical verdicts on the schema fixture suite | lld/extension-runtime.md |
| PLT-10 | Environment context probes | `envId/envState/envBackend/envDistro/envArch`, cached `envHasCommand:<name>` refreshed after installs | L0 | P1 | M3 | no | - | probe result cached; refreshed after `sandbox.install`; stopped env -> `envState == stopped` | lld/extension-runtime.md |
| PLT-11 | API version + deprecation | App exposes one `engines.easyide` API version; deprecated keys/actions/host fns work for 2 minors and warn | L0 | P0 | M3 | no | - | out-of-range manifest refused with message; deprecated key logs one warning in Extension Log and in `validate` | lld/extension-runtime.md |
| PLT-12 | Problems panel, Outline stage, status items host | UI surfaces consumed by LSP and contributions (Problems list, right-stage Outline, status bar slots) | L0 | P0 | M2 | no | `editor.diagnostics.*` | Problems lists diagnostics grouped by file with counts; tap navigates; Outline renders a symbol tree | lld/lsp-client.md |
| PLT-13 | Per-extension secret store | Keystore-backed namespace per extension beside `GitCredentials`; git token/Claude key unreachable | L0 | P2 | M7 | no | - | `secrets.get` returns only this extension's entries; no API path returns a git credential (0012) | lld/wasm-host.md |

## 2. No-server language features (NS, arch 5.1)

Anchored on existing code: `TextMateHighlighter`, `DocumentHighlighter`, `ScopeRules`
(`services/mobile/app/src/main/java/dev/easyide/app/ui/screens/workspace/syntax/`).

| ID | Feature | Description | L | P | M | Srv | Knobs | Acceptance | LLD |
|---|---|---|---|---|---|---|---|---|---|
| NS-01 | TextMate highlighting | 229 bundled grammars, 21 `ScopeRules` roles (exists, 0010) | L0 | P0 | done | no | `editor.syntaxHighlighting`, `editor.tokenColorCustomizations` | existing highlighter tests pass; no regression in 400 KB keystroke timing | hld.md |
| NS-02 | Language configuration for bundled grammars | `tools/build-grammars.py` emits license-filtered `language-configuration.json` per grammar | L0 | P0 | M1 | no | - | every bundled language has a config or documented generic fallback (open question 6); build fails on unlicensed source | hld.md |
| NS-03 | Bracket matching + pair colourization | Match bracket at caret; colour nested pairs from `colorizedBracketPairs` | L0 | P1 | M1 | no | `editor.bracketPairColorization`, `editor.matchBrackets` | caret next to `(` highlights its partner; 3 nesting levels get 3 distinct theme tokens | hld.md |
| NS-04 | Auto-close + auto-surround | `autoClosingPairs`, `surroundingPairs`, `autoCloseBefore`; works from key row too | L0 | P0 | M1 | no | `editor.autoClosingBrackets`, `editor.autoSurround` | typing `(` inserts `()` with caret inside; selection + `"` wraps; typing `)` over auto-inserted `)` overtypes | hld.md |
| NS-05 | Toggle line/block comment | Commands using `comments.lineComment/blockComment` | L0 | P0 | M1 | no | keybindings | toggle adds/removes line comment on multi-line selection for Python, TS, Go, C, Rust, Markdown; no-op for languages without comment config | hld.md |
| NS-06 | Indentation + onEnter rules | `indentationRules`, `onEnterRules` applied on Enter and paste | L0 | P0 | M1 | no | `editor.autoIndent` | Enter after `def f():` indents one level; after `}` dedents; fixture suite per M1 language passes | hld.md |
| NS-07 | Folding by markers + indentation | Fold regions from `folding.markers` or indentation; gutter toggles | L0 | P0 | M1 | no | `editor.folding`, `editor.foldingStrategy` | region markers and indent blocks fold/unfold; fold state survives edits outside the region | hld.md |
| NS-08 | Word pattern | Per-language `wordPattern` for double-tap select and word navigation | L0 | P1 | M1 | no | per-language config | double-tap on `snake_case_id` selects whole identifier in Python; `$var` selects with `$` in Bash | hld.md |
| NS-09 | Snippet engine | VS Code snippet JSON: tab stops, placeholders, choices, `$TM_*` variables, nested | L0 | P0 | M1 | no | `editor.snippetSuggestions`, `editor.suggest.showSnippets` | `${1:x}`/`${1\|a,b\|}`/`$0` navigate via Tab and touch next/prev; `inSnippetMode` true until exit | hld.md |
| NS-10 | Word-based completion | Candidates from current buffer + open tabs, merged with snippets | L0 | P1 | M1 | no | `editor.wordBasedSuggestions`, `editor.quickSuggestions`, `editor.quickSuggestionsDelay` | typing 2 chars of a word present in another open tab offers it; disabled when setting is false | hld.md |
| NS-11 | Save-time hygiene | Trim trailing whitespace, insert final newline, detect indentation on open | L0 | P1 | M1 | no | Pillar 5 editor keys (`trimTrailingWhitespace`, `insertFinalNewline`, `detectIndentation`) | save of a file with trailing spaces strips them only when enabled; tab-indented file opens with tabs | hld.md |
| NS-12 | Optional tree-sitter | Instant parse errors, structural selection | L0 | P2 | later | no | `editor.treeSitter.enabled` | off by default; enabling it adds no dependency to the base APK until an ADR accepts it | hld.md |

## 3. LSP language intelligence (LSP, arch 5.2 + sec 7)

Client in the app process (`:lsp`, JVM-testable), servers in the sandbox.

### 3.1 Client core

| ID | Feature | Description | L | P | M | Srv | Knobs | Acceptance | LLD |
|---|---|---|---|---|---|---|---|---|---|
| LSP-01 | JSON-RPC transport | stdio, `Content-Length` framing, id correlation, notifications; stderr to Extension Log ring | L0 | P0 | M2 | yes | `lsp.trace`, `LspPolicy.LOG_RING_LINES` (fixed) | framing fuzz suite (split/merged headers, UTF-8 multibyte bodies) passes headless | lld/lsp-client.md |
| LSP-02 | Path mapping | Host path <-> `file:///workspace/...`; env files `file:///usr/...` opened read-only; `positionEncoding` utf-8 when offered, else UTF-16 | L0 | P0 | M2 | yes | - | round-trip for project, env and non-ASCII paths; surrogate-pair columns map correctly in UTF-16 mode | lld/lsp-client.md |
| LSP-03 | Document sync | Versioned docs, prefix/suffix diff -> one incremental `didChange`, flush before any request, Full-sync size cap | L0 | P0 | M2 | yes | `lsp.didChangeDebounceMs`, `LspPolicy.MAX_FULL_SYNC_BYTES` (fixed) | flush < 2 ms on 400 KB doc; server text equals buffer after random edit fuzz; oversize doc shows "not synced" | lld/lsp-client.md |
| LSP-04 | Cancellation + staleness | `$/cancelRequest` for superseded requests; stale-version responses dropped; per-method timeout silent | L0 | P0 | M2 | yes | `lsp.requestTimeoutMs` | second completion cancels first; response for old version never renders; timeout shows no dialog | lld/lsp-client.md |
| LSP-05 | Capability negotiation | Advertise only rendered features; UI hides features server lacks; dynamic registration for `didChangeWatchedFiles` (via `ProjectFileWatcher`) and configuration | L0 | P0 | M2 | yes | - | `lspSupports:<lang>:<feature>` matches server capabilities; unsupported feature has no menu entry | lld/lsp-features.md |
| LSP-06 | Server lifecycle | State machine NOT_INSTALLED..FAILED (arch 7.6), lazy start, idle shutdown, crash backoff | L0 | P0 | M2 | yes | `lsp.idleShutdownSec`, `lsp.startupTimeoutSec`, `lsp.restart.maxRetries`, `lsp.restart.backoffMs` | fault injection: kill -> BACKOFF -> RUNNING; N crashes -> FAILED with stderr tail + restart action | lld/lsp-lifecycle.md |
| LSP-07 | Memory budget + kill order | Per-server and global budgets; evict IDLE LRU, then non-visible, then idle WASM, then focused; never terminal or buffers | L0 | P0 | M2 | yes | `lsp.globalMemoryBudgetMb`, `lsp.defaultMemoryBudgetMb`, `lsp.maxServers`, `lsp.servers.<id>.memoryBudgetMb` | eviction < 2 s after breach; order verified with 4 fake servers; status shows "paused (memory)" | lld/lsp-lifecycle.md |
| LSP-08 | Session keying + roots | One session per (environment, project, server); `rootMarkers` pick `rootUri` | L0 | P0 | M2 | yes | `lsp.servers.<id>.rootMarkers` | two projects in one env get two sessions; nested `pyproject.toml` becomes root | lld/lsp-lifecycle.md |
| LSP-09 | Multi-server feature routing | `features.only/exclude` and `priority` pick which server answers each feature (pyright + ruff) | L0 | P1 | M2 | yes | `lsp.servers.<id>.features`, `priority`, `editor.defaultFormatter` | with pyright+ruff, formatting goes only to ruff; diagnostics from both merge with source labels | lld/lsp-features.md |
| LSP-10 | Configuration bridge | `workspace/configuration` and `didChangeConfiguration` answered from settings resolution of `settingsSection` | L0 | P0 | M2 | yes | `lsp.servers.<id>.settingsSection` | changing `python.*` in project settings pushes one `didChangeConfiguration` | lld/lsp-client.md |
| LSP-11 | Server status, restart, log | Status bar item per server state, restart command, per-server stderr/trace in Extension Log | L0 | P0 | M2 | yes | `lsp.trace` | status reflects every state in 7.6; restart from status item returns to RUNNING | lld/lsp-client.md |
| LSP-12 | Multi-root readiness | Advertise `workspaceFolders` with one folder; future extra binds map to `/workspace/<name>` | L0 | P2 | M2 | yes | - | initialize params contain exactly one folder equal to `rootUri` | lld/lsp-client.md |

### 3.2 Language features

| ID | Feature | Description | L | P | M | Srv | Knobs | Acceptance | LLD |
|---|---|---|---|---|---|---|---|---|---|
| LSP-20 | Diagnostics | push + pull diagnostics -> squiggles, gutter, Problems, status count | L0 | P0 | M2 | yes | `editor.diagnostics.minSeverity`, `showInGutter`, `showSquiggles`, `ignoreSources` | first diagnostics < 3 s warm, < 10 s cold on reference device; minSeverity hides lower severities | lld/lsp-features.md |
| LSP-21 | Completion | `completion` + `resolve`, trigger chars, commit chars, snippets, merged with NS-09/NS-10, local refilter unless `isIncomplete` | L0 | P0 | M2 | yes | `editor.quickSuggestions`, `quickSuggestionsDelay`, `suggestOnTriggerCharacters`, `acceptSuggestionOnEnter` | popup < 150 ms p95 after response; accept applies `textEdit`+`additionalTextEdits` as one undo unit; works by tap, Tab, Enter | lld/lsp-features.md |
| LSP-22 | Signature help | Popup above caret on trigger chars | L0 | P1 | M2 | yes | `editor.parameterHints.enabled` | typing `(` after a function shows its signature; active parameter follows `,` | lld/lsp-features.md |
| LSP-23 | Hover | Long-press / mouse hover / shortcut card | L0 | P0 | M2 | yes | `editor.hover.enabled`, `editor.hover.delay` | long-press on symbol shows markdown card; mouse hover obeys delay; `hoverVisible` true while shown | lld/lsp-features.md |
| LSP-24 | Go to definition family | definition, declaration, typeDefinition, implementation | L0 | P0 | M2 | yes | keybindings | F12 / Ctrl+click / long-press menu opens target; env targets open read-only | lld/lsp-features.md |
| LSP-25 | Find references | Peek sheet and side panel | L0 | P0 | M2 | yes | - | lists all references with file/line; tap navigates; `peekVisible` true while open | lld/lsp-features.md |
| LSP-26 | Document highlight | Background ranges for symbol occurrences | L0 | P1 | M2 | yes | `editor.occurrencesHighlight` | caret on a variable highlights all occurrences in file; cleared on move off | lld/lsp-features.md |
| LSP-27 | Document symbols | Outline in right stage, breadcrumbs | L0 | P0 | M2 | yes | `breadcrumbs.enabled` | Outline tree matches `documentSymbol` hierarchy; tap reveals range | lld/lsp-features.md |
| LSP-28 | Rename | `prepareRename` + `rename` with edit preview | L0 | P0 | M2 | yes | - | inline field pre-filled; preview lists every file edit; apply is one undo per file | lld/lsp-features.md |
| LSP-29 | Code actions / quick fixes | Lightbulb in gutter, long-press menu, `resolve`, on-save actions | L0 | P0 | M2 | yes | `editor.lightbulb.enabled`, `editor.codeActionsOnSave` | diagnostic with fix shows lightbulb; applying fix clears diagnostic | lld/lsp-features.md |
| LSP-30 | Formatting | document, range, on-type, on-save (`willSaveWaitUntil`), on-paste | L0 | P0 | M2 | yes | `editor.formatOnSave`, `formatOnType`, `formatOnPaste`, `editor.defaultFormatter` | format-on-save applies edits before write; disabled server -> save still succeeds | lld/lsp-features.md |
| LSP-31 | Workspace symbols | Palette `#` prefix | L0 | P1 | M4 | yes | - | `#Foo` lists matching symbols across project; tap opens | lld/lsp-features.md |
| LSP-32 | Inlay hints | Inline ghost text | L0 | P1 | M4 | yes | `editor.inlayHints.enabled` | hints render inline, never shift caret offsets; `onUnlessPressed` hides while pressed | lld/lsp-features.md |
| LSP-33 | Semantic tokens | full/delta/range layered on TextMate roles | L0 | P1 | M4 | yes | `editor.semanticHighlighting.enabled`, `editor.semanticTokenColorCustomizations` | delta applied correctly under edit fuzz; theme with `semanticHighlighting:false` shows TextMate only | lld/lsp-features.md |
| LSP-34 | Folding ranges | Server ranges replace NS-07 when available | L0 | P1 | M4 | yes | `editor.foldingStrategy` | `auto` uses server ranges; `indentation` ignores them | lld/lsp-features.md |
| LSP-35 | Selection ranges | Expand/shrink selection commands | L0 | P2 | M4 | yes | keybindings | expand walks string -> call -> statement -> block | lld/lsp-features.md |
| LSP-36 | Code lens | Between-line blocks with resolve | L0 | P1 | M4 | yes | `editor.codeLens` | lens appears above symbol; tap runs its command | lld/lsp-features.md |
| LSP-37 | Document links | Underline, tap to open | L0 | P2 | M4 | yes | `editor.links` | link in comment opens via `openFile` or `openUrl` confirm | lld/lsp-features.md |
| LSP-38 | Call / type hierarchy | Side panel tree | L0 | P2 | later | yes | - | incoming/outgoing calls expand lazily | lld/lsp-features.md |

### 3.3 First-party language packs

| ID | Feature | Description | L | P | M | Srv | Knobs | Acceptance | LLD |
|---|---|---|---|---|---|---|---|---|---|
| LSP-50 | Python end to end | basedpyright or jedi (open question 1) + ruff; built in-app for M2, repackaged in M3 (EXT-40) | L1 | P0 | M2 | yes | `lsp.servers.easyide.python/*`, `python.*` | M2 exit criteria in arch sec 12 met on Xiaomi Pad 6 | lld/lsp-client.md |
| LSP-51 | M4 language packs | TS/JS, Go, C/C++, Bash, YAML, JSON/HTML/CSS, Markdown, Rust (opt-in warning) per arch sec 8 | L1 | P1 | M4 | yes | `lsp.servers.<extId>/<id>` | each pack installs, verifies and shows diagnostics + completion on a fixture project | lld/lsp-client.md |
| LSP-52 | Kotlin pack | JetBrains kotlin-lsp, opt-in with Alpha + memory warning | L1 | P2 | later | yes | `lsp.servers.<extId>/kotlin` | never blocks a milestone; install sheet shows warning and budget | lld/lsp-client.md |

## 4. Extension capabilities and runtime (EXT, arch 5.3)

### 4.1 Runtime

| ID | Feature | Description | L | P | M | Srv | Knobs | Acceptance | LLD |
|---|---|---|---|---|---|---|---|---|---|
| EXT-01 | Manifest parser + validation | `package.json` + `easyide` block, JSON Schema, `engines.easyide`, naming rules, unknown keys warn | L0 | P0 | M3 | no | - | schema fixture suite: every invalid fixture rejected with a path-precise message | lld/extension-runtime.md |
| EXT-02 | Package format | `.easyext` zip rules (relative paths, no `..`/symlinks/nested archives), size limits | L0 | P0 | M3 | no | `extensions.limits.packageMb`, `unpackedMb`, `fileMb` | zip-slip, symlink, duplicate-case and oversize fixtures all refused | lld/extension-runtime.md |
| EXT-03 | Contribution registry | Registers static contributions into PLT-01/02/03, themes, stages from a cached manifest index | L0 | P0 | M3 | no | `extensions.enabled`, `extensions.disabled` | index load < 20 ms for 20 extensions; cold start < +50 ms vs none; no zip reads at start | lld/extension-runtime.md |
| EXT-04 | Activation manager | `onLanguage`, `onCommand`, `onView`, `onStage`, `workspaceContains`, `onStartupFinished`; crash counting | L0 | P0 | M3 | no | `safeMode` | servers become eligible on first matching event only; 2 consecutive activation crashes -> safe mode (100% in fault injection) | lld/extension-runtime.md |
| EXT-05 | When-clause evaluator | VS Code grammar + easyIDE context keys; unknown key = falsy | L0 | P0 | M3 | no | - | grammar test table (all operators, `in`/`not in`, regex) passes; unknown key never throws | lld/extension-runtime.md |
| EXT-06 | Action runner | Executes the 16 action types, `sequence`, `as`/`${result:}`, error behaviour per sdk-reference | L0 | P0 | M3 | no | `extensions.actions.execTimeoutSec`, `captureKb` | each action type has a fake-host test; failing step aborts sequence with snackbar + log; cancel is silent | lld/extension-runtime.md |
| EXT-07 | Variables + inputs | `${file}`..`${result:}` substitution, guest paths only, shell-quoting into shell strings, `easyide.inputs` | L0 | P0 | M3 | no | - | filename with spaces/quotes survives `runInTerminal`; unresolvable variable = step error; `${env:}` never reads Android env | lld/extension-runtime.md |
| EXT-08 | Capability consistency check | Load-time refusal when an action/host fn needs an undeclared capability; computed `scope` | L0 | P0 | M3 | no | - | `sandboxExec` without `sandbox.exec` refused at load, not at run; `scope: global` with sandbox use refused | lld/extension-runtime.md |
| EXT-09 | Localization | `%key%` substitution from `l10n/package.nls[.<locale>].json` | L0 | P2 | M3 | no | - | manifest strings resolve to device locale, fall back to default nls | lld/extension-runtime.md |

### 4.2 Contribution points

| ID | Feature | Description | L | P | M | Srv | Knobs | Acceptance | LLD |
|---|---|---|---|---|---|---|---|---|---|
| EXT-20 | Languages + grammars + language-configuration | Contributed data grammars (JSON/plist, no native code) and configs | L1 | P0 | M3 | no | `files.associations`, `editor.syntaxHighlighting` | contributed language detected by extension, filename and `firstLine`; grammar highlights via `TextMateHighlighter` | lld/extension-runtime.md |
| EXT-21 | Snippets | Pack snippet files per language | L1 | P0 | M3 | no | `editor.snippetSuggestions` | pack snippet appears in completion; user snippet with same prefix wins | lld/extension-runtime.md |
| EXT-22 | Themes + icon themes | VS Code color/icon theme JSON; `tokenColors` collapse onto 21 roles | L1 | P0 | M3 | no | `workbench.colorTheme`, `workbench.iconTheme` | installed theme selectable; unknown `colors` keys listed by validate; icon theme maps extensions/names | lld/extension-runtime.md |
| EXT-23 | Commands + menus | Commands with `enablement`, icons as tokens/SVG; all sdk-reference menu ids | L1 | P0 | M3 | no | `workbench.contributions.hidden`, `order` | contributed command appears in each declared menu, honours `when` and `group` sort | lld/extension-runtime.md |
| EXT-24 | Keybindings | Contributed chords with `when`, `args`; `cmd` -> Meta | L1 | P0 | M3 | no | `keybindings.json` | contributed chord fires only when its `when` holds; user `-command` removes it | lld/extension-runtime.md |
| EXT-25 | Configuration | Contributed properties + `configurationDefaults` incl. `[lang]` | L1 | P0 | M3 | no | any layer | contributed key renders as native row in extension category; default beats built-in, loses to user | lld/extension-runtime.md |
| EXT-26 | Language servers | `easyide.languageServers` definitions, key `<extId>/<id>` | L1 | P0 | M3 | yes | `lsp.servers.<extId>/<id>` | contributed server starts via LSP-06 on `onLanguage`; override `enabled:false` stops it | lld/extension-runtime.md |
| EXT-27 | Sandbox toolchain | `easyide.sandbox` requires/install/verify/uninstall, streamed to terminal | L1 | P0 | M3 | no | `extensions.sandbox.confirmEachStep` | install steps run visibly in order; non-zero `verify` deletes version dir and keeps previous `current` | lld/registry-and-install.md |
| EXT-28 | Views + containers + viewData | Data-backed tree/list views, `viewsContainers`, `viewsWelcome`, `easyide.viewData` | L1/L2 | P1 | M4 | no | `workbench.contributions.hidden` | `viewData` from captured JSON renders a tree; `refreshOn` events refresh it | lld/extension-runtime.md |
| EXT-29 | Status bar items | `easyide.statusBarItems` with variables and `when` | L1/L2 | P1 | M4 | no | `workbench.contributions.hidden`, `order` | text updates when `${config:...}` changes; tap runs command | lld/extension-runtime.md |
| EXT-30 | Tasks + problem matchers | `taskDefinitions`, `problemMatchers` feeding Run menu and Problems | L1 | P1 | M4 | no | `.easyide/tasks.json` | matcher parses fixture compiler output into Problems entries with correct file/line | lld/extension-runtime.md |
| EXT-31 | Key rows | `easyide.keyRows` per language (insert/snippet/key/command, longPress) | L1 | P1 | M4 | no | `keyRows.layouts`, `keyRows.active` | `auto` shows first row whose `when` matches; exactly-one-of rule enforced by validate | lld/extension-runtime.md |
| EXT-32 | Stages | `easyide.stages`: named areas (left/main/right/bottom), not pixels | L1 | P1 | M4 | no | `workbench.stages.placement` | contributed stage appears in its default area; placement override moves it | lld/extension-runtime.md |
| EXT-33 | Tablet menus | `editor/touchToolbar`, `editor/selectionToolbar`, `editor/gutter`, `keyRow` menus | L1 | P1 | M4 | no | `workbench.contributions.hidden`, `order` | touch toolbar shows only with touch `inputMode`; selection toolbar only with selection | lld/extension-runtime.md |
| EXT-34 | Walkthroughs | `walkthroughs` with image/markdown/svg media | L1 | P2 | M5 | no | `workbench.welcome.enabled` | steps render; `completionEvents` tick steps; video media ignored with validate warning | lld/extension-runtime.md |
| EXT-40 | First-party packs as extensions | Python pack and themes removed from app code and shipped as `.easyext` | L1 | P0 | M3 | yes | - | M3 exit: identical behaviour after reinstall; disable/uninstall/rollback work | lld/extension-runtime.md |

### 4.3 Logic layers

| ID | Feature | Description | L | P | M | Srv | Knobs | Acceptance | LLD |
|---|---|---|---|---|---|---|---|---|---|
| EXT-50 | WASM host + ABI v1 | Chicory (ADR-F) in `:ext-wasm`, one instance + worker per extension, `alloc/free/ext_*`, `[u32 len][json]` buffers | L2 | P0 | M7 | no | `extensions.wasm.enabled`, `maxModuleMb`, `maxMessageKb` | `ext_abi_version != 1` refused; sample command round-trips; no WASI imports resolvable | lld/wasm-host.md |
| EXT-51 | Host functions + capability enforcement | `host_call` dispatch, capability table (declared AND approved), `ActionRunner` reuse, async handles | L2 | P0 | M7 | no | `extensions.storage.quotaKb`, `extensions.wasm.netMaxResponseKb` | undeclared or unapproved fn -> `E_CAPABILITY` and log; `net.fetch` to undeclared host refused | lld/wasm-host.md |
| EXT-52 | WASM limits + lifecycle | Fuel, memory cap, per-call/activate/deactivate timeouts, host-call cap, trap -> discard, crash window -> disable | L2 | P0 | M7 | no | `extensions.wasm.maxMemoryMb`, `fuelPerCall`, `maxHostCallsPerCall`, `callTimeoutMs`, `activateTimeoutMs`, `deactivateTimeoutMs`, `maxCrashes`, `crashWindowSec` | fuel exhaustion, memory cap, timeout and denied capability each trap cleanly; editor stays responsive | lld/wasm-host.md |
| EXT-53 | WASM providers + view data | WASM supplies commands, view data, decorations, status items | L2 | P1 | M7 | no | - | sample view provider fills a view via `ui.setViewData`; `editor.decorate` renders diagnostics | lld/wasm-host.md |
| EXT-54 | Guest SDKs | `easyide-guest` Rust crate and `@easyide/guest-as`; Rust and AssemblyScript templates | L2 | P1 | M7 | no | - | both templates build, pass `easyide-ext test`, run on device | lld/wasm-host.md |
| EXT-60 | Node host (vscode API subset) | L3 extension host in the sandbox, off by default | L3 | P2 | M8 | no | - | only started after a written gap analysis (arch M8); no acceptance until then | hld.md |
| EXT-61 | Webview panels | L4 | L4 | P2 | later | no | - | deferred; no work scheduled | hld.md |

## 5. Ecosystem (ECO, arch 5.4)

| ID | Feature | Description | L | P | M | Srv | Knobs | Acceptance | LLD |
|---|---|---|---|---|---|---|---|---|---|
| ECO-01 | Extension store | Versioned dirs, atomic `current` symlink flip, content-addressed cache, `state.json` | L0 | P0 | M3 | no | - | crash mid-install leaves previous `current` intact; global vs per-environment scope per rule | lld/registry-and-install.md |
| ECO-02 | Install from folder / file | SAF picker or project folder; labelled "unsigned, local"; never auto-updated | L0 | P0 | M3 | no | - | local install shows capability sheet and unsigned label | lld/registry-and-install.md |
| ECO-03 | Capability prompt + approval | "What will run" sheet (install commands verbatim, server commands, action types); plain-words non-containment note; approval stored with exact set | L0 | P0 | M3 | no | - | sheet lists every declared capability with sdk-reference prompt text; declining installs nothing | lld/registry-and-install.md |
| ECO-04 | Uninstall | Remove dirs; offer best-effort undo of recorded `sandbox.install` | L0 | P0 | M3 | no | - | contributions disappear without restart; `uninstall[]` runs only on confirm, labelled best-effort | lld/registry-and-install.md |
| ECO-05 | Registry browse/search | Static index, cached, filtered by `engines` and capabilities | L0 | P1 | M6 | no | `extensions.registries` | incompatible entries hidden or marked; search by name/category | lld/registry-and-install.md |
| ECO-06 | Registry install | download -> sha256 -> ed25519 -> prompt -> unpack -> install -> verify -> activate | L0 | P0 | M6 | no | `extensions.registries` | signed install from cache offline < 10 s (excl. `sandbox.install`) | lld/registry-and-install.md |
| ECO-07 | Signature chain + TOFU | Root key pinned in APK, publisher keys, JCS canonical bytes, TOFU pin per publisher, rotation records | L0 | P0 | M6 | no | `extensions.registries[].rootKey` | tampered package, wrong key, pin mismatch without rotation all hard-fail with no "install anyway" | lld/registry-and-install.md |
| ECO-08 | Revocation | `revocations.json` disables revoked versions/keys at refresh, notifies, never auto-uninstalls | L0 | P0 | M6 | no | - | revoked key hard-fails install; installed revoked version disabled with notice | lld/registry-and-install.md |
| ECO-09 | Update + rollback | Notify with version, changelog, capability delta; tap to apply; one-tap rollback to retained version | L0 | P0 | M6 | no | `extensions.autoCheckUpdates` | no update ever applies without a tap; added capability requires new approval; rollback restores previous in one tap | lld/registry-and-install.md |
| ECO-10 | Offline | Last verified index, sigs, cached packages usable with no network; index age shown | L0 | P1 | M6 | no | - | airplane-mode install from cache succeeds; staleness label visible | lld/registry-and-install.md |
| ECO-11 | Open VSX subset | Read `.vsix` `package.json` declarative subset, compatibility report, "partial" | L0 | P1 | M6 | no | `extensions.openVsx.enabled` | `main`/`browser` ignored; report counts unsupported features; label "not signed by an easyIDE-registry publisher" | lld/registry-and-install.md |
| ECO-12 | Sideload signatures | Verify `<file>.easyext.sig` and TOFU pin when present | L0 | P2 | M6 | no | - | valid sig removes unsigned label; bad sig hard-fails | lld/registry-and-install.md |
| ECO-20 | CLI init + templates | `theme`, `snippets`, `language-pack`, `lsp-pack`, `toolbar-command`, `wasm-rust`, `wasm-assemblyscript` | - | P0 | M5 | no | - | every template passes `validate --strict` and `test` unmodified | lld/cli.md |
| ECO-21 | CLI validate | Schema, paths, grammar/theme parse, when-clauses, unknown keys, capability audit, limits | - | P0 | M5 | no | - | catches schema + capability errors in samples test suite (M5 exit) | lld/cli.md |
| ECO-22 | CLI package, keygen, sign | Deterministic zip + sha256; ed25519 keypair; detached `.sig` | - | P0 | M5 | no | - | two `package` runs produce byte-identical output | lld/cli.md |
| ECO-23 | CLI test harness | Fixture contexts, fake host, real WASM limits, `test/*.json` scenarios, no device | - | P1 | M5 | no | - | sample scenarios pass in CI on a plain JVM | lld/cli.md |
| ECO-24 | CLI dev + live reload | adb push to dev inbox or `--local` registration; reload on change | - | P1 | M5 | no | `extensions.developerMode` | saved change visible on device < 5 s without reinstall; refused when developer mode off | lld/cli.md |
| ECO-25 | CLI publish | Compute + sign index entry, commit to branch, print PR URL; user's own git credentials | - | P1 | M6 | no | - | produces an entry that the client verification (ECO-07) accepts | lld/cli.md |
| ECO-30 | In-app Create extension | Same templates scaffolded into a project | L0 | P1 | M5 | no | - | new author ships theme + snippet pack in < 15 min, zero code (M5 exit) | lld/extension-runtime.md |
| ECO-31 | Extension Log panel | Ring of activation errors, action failures, server stderr, WASM `log.write` | L0 | P0 | M5 | no | `extensions.developerMode`, `lsp.trace` | each failure path in EXT-06/EXT-52/LSP-01 writes a log entry with extension id | lld/extension-runtime.md |
| ECO-32 | Contribution inspector | Which extension contributed each button/row/key/setting and which override hides it | L0 | P1 | M5 | no | `extensions.developerMode` | inspector names contributor and hiding setting for every contribution kind | lld/extension-runtime.md |
| ECO-33 | Samples + author docs + SDK license | Samples, [author-guide.md](author-guide.md), Apache-2.0 SDK pieces (ADR-G) | - | P0 | M5 | no | - | samples build in CI; license files present in SDK artifacts | author-guide.md |

## 6. Customization (CUS, arch 5.5)

| ID | Feature | Description | L | P | M | Srv | Knobs | Acceptance | LLD |
|---|---|---|---|---|---|---|---|---|---|
| CUS-01 | Settings layering + resolution | built-in < extension default < user < `[lang]` < environment < project; per-layer cache; invalid -> next layer, log once | L0 | P0 | M0 | no | all | resolution table test covers every layer x `[lang]` combination; invalid value logs once | lld/customization.md |
| CUS-02 | Winning-layer display | Settings row shows winning layer and link to overriding file | L0 | P1 | M4 | no | - | row for a project-overridden key shows "project" and opens the file | lld/customization.md |
| CUS-03 | Keybinding overrides | `keybindings.json` overrides defaults and contributions; `-cmd` removes; `when` | L0 | P0 | M3 | no | `keybindings.json` | user entry beats contributed chord; `-python.runFile` unbinds it; project file beats user | lld/customization.md |
| CUS-04 | Key row customization | Reorder/replace contributed rows, user layouts | L0 | P1 | M4 | no | `keyRows.layouts`, `keyRows.active` | user layout with same id replaces contributed row; reorder persists | lld/customization.md |
| CUS-05 | Theme customizations | Colour, token and semantic overrides with `[themeName]` blocks | L0 | P1 | M4 | no | `workbench.colorCustomizations`, `editor.tokenColorCustomizations`, `editor.semanticTokenColorCustomizations` | `[Theme]` block applies only under that theme; role override recolours tokens | lld/customization.md |
| CUS-06 | Hide + reorder contributions | Refs `<kind>:<location>:<id>` for menus, views, status, stages, key rows | L0 | P0 | M4 | no | `workbench.contributions.hidden`, `workbench.contributions.order` | every contribution kind hideable and orderable from UI and JSON (M4 exit) | lld/customization.md |
| CUS-07 | Stage placement | Move contributed/built-in stages between named areas | L0 | P1 | M4 | no | `workbench.stages.placement` | placement survives restart; invalid area falls back to default | lld/customization.md |
| CUS-08 | Extension enablement | Enable/disable globally, per environment, per project | L0 | P0 | M3 | no | `extensions.disabled`, `extensions.enabled` | disabled in project P -> `extensionEnabled:<id>` false in P only | lld/customization.md |
| CUS-09 | Language server overrides | Override `command,args,env,initializationOptions,memoryBudgetMb,enabled` of a contributed server | L0 | P0 | M4 | no | `lsp.servers.<extId>/<id>` | override `env` reaches the process; `enabled:false` stops a running server | lld/customization.md |
| CUS-10 | Custom server without extension | `lsp.servers.<newId>` with `languages` + `command` | L0 | P0 | M4 | yes | `lsp.servers.<newId>` | M4 exit: server defined only in settings gives diagnostics | lld/customization.md |
| CUS-11 | Project server trust prompt | Prompt once per project before honouring `lsp.servers` from a project file (open question 2, proposed) | L0 | P1 | M4 | no | `lsp.servers` (P scope) | cloned repo's project `lsp.servers` does not spawn before the user confirms | lld/customization.md |
| CUS-12 | Per-language feature toggles | Any L-scope key inside `[lang]` blocks in user, environment, project | L0 | P0 | M4 | no | `lsp.enabled`, `editor.inlayHints.enabled`, `editor.diagnostics.minSeverity`, ... | `[python]` `inlayHints: off` hides hints only in Python | lld/customization.md |
| CUS-13 | User snippets | `snippets/<lang>.json` + global; override pack snippets by prefix | L0 | P1 | M4 | no | `editor.snippetSuggestions` | user snippet shadows pack snippet with same prefix | lld/customization.md |
| CUS-14 | File associations | Glob -> language id; chooses grammar and server | L0 | P1 | M3 | no | `files.associations` | `*.conf` mapped to `ini` highlights and routes as `ini` | lld/customization.md |
| CUS-15 | User tasks | `.easyide/tasks.json` (VS Code tasks shape, `sandboxExec` backing, may define matchers) | L0 | P1 | M4 | no | `.easyide/tasks.json` | user task with same label beats contributed task | lld/customization.md |
| CUS-16 | Profiles | Named set: user settings + enabled extensions + keybindings + key rows; switch per project | L0 | P1 | M4 | no | `profiles.active`, `profiles/<name>.json` | switching profile swaps all four parts; project remembers its profile | lld/customization.md |
| CUS-17 | Safe mode | All non-built-in extensions off; from Settings, launcher long-press, or auto after 2 activation crashes | L0 | P0 | M4 | no | `safeMode` | reachable 100% after injected bad extension; `isSafeMode` true; exit restores state | lld/customization.md |
| CUS-18 | Export / import | One bundle (settings, keybindings, snippets, profiles, extension list + versions) via SAF; secrets excluded | L0 | P1 | M4 | no | - | export -> wipe -> import restores identical resolved settings; bundle contains no secret | lld/customization.md |

## 7. Milestone rollup

| M | PLT | NS | LSP | EXT | ECO | CUS | Total |
|---|---|---|---|---|---|---|---|
| done | 0 | 1 | 0 | 0 | 0 | 0 | 1 |
| M0 | 6 | 0 | 0 | 0 | 0 | 1 | 7 |
| M1 | 0 | 10 | 0 | 0 | 0 | 0 | 10 |
| M2 | 3 | 0 | 24 | 0 | 0 | 0 | 27 |
| M3 | 3 | 0 | 0 | 18 | 4 | 3 | 28 |
| M4 | 0 | 0 | 8 | 6 | 0 | 14 | 28 |
| M5 | 0 | 0 | 0 | 1 | 9 | 0 | 10 |
| M6 | 0 | 0 | 0 | 0 | 9 | 0 | 9 |
| M7 | 1 | 0 | 0 | 5 | 0 | 0 | 6 |
| M8 | 0 | 0 | 0 | 1 | 0 | 0 | 1 |
| later | 0 | 1 | 2 | 1 | 0 | 0 | 4 |
| **Total** | 13 | 12 | 34 | 32 | 22 | 18 | **131** |

## 8. Traceability

| Area | IDs | Owning LLD | Test-plan section |
|---|---|---|---|
| Platform prerequisites | PLT-01, 02, 06, 09, 10, 11 | lld/extension-runtime.md | [Platform (M0)](test-plan.md#platform-m0) + [Extension runtime](test-plan.md#extension-runtime) |
| Platform prerequisites | PLT-03, 04 | lld/customization.md | [Platform (M0)](test-plan.md#platform-m0) + [Customization](test-plan.md#customization) |
| Platform prerequisites | PLT-05, 07, 08, 12 | lld/lsp-client.md (08: lld/lsp-lifecycle.md) | [Platform (M0)](test-plan.md#platform-m0) + [LSP client](test-plan.md#lsp-client) |
| Platform prerequisites | PLT-13 | lld/wasm-host.md | [WASM host](test-plan.md#wasm-host) |
| No-server (5.1) | NS-01..12 | hld.md (L0 language core) | [L0 language core](test-plan.md#l0-language-core) |
| LSP client core (sec 7) | LSP-01..12 | lld/lsp-client.md, lld/lsp-lifecycle.md (06-08), lld/lsp-features.md (05, 09) | [LSP client](test-plan.md#lsp-client) (Unit, JVM integration) |
| LSP features (5.2) | LSP-20..38 | lld/lsp-features.md | [LSP client](test-plan.md#lsp-client) + [On-device performance](test-plan.md#on-device-performance) |
| Language packs (sec 8) | LSP-50..52 | lld/lsp-client.md | [Milestone acceptance](test-plan.md#milestone-acceptance) M2, M4 |
| Extension runtime (5.3) | EXT-01..09, 20..34, 40 | lld/extension-runtime.md | [Extension runtime](test-plan.md#extension-runtime) |
| Sandbox toolchain | EXT-27 | lld/registry-and-install.md | [Registry and install](test-plan.md#registry-and-install) |
| WASM (5.3, L2) | EXT-50..54 | lld/wasm-host.md | [WASM host](test-plan.md#wasm-host) |
| L3/L4 | EXT-60, 61 | hld.md | none (not scheduled) |
| Install + registry (5.4) | ECO-01..12 | lld/registry-and-install.md | [Registry and install](test-plan.md#registry-and-install) |
| CLI (5.4) | ECO-20..25 | lld/cli.md | [CLI](test-plan.md#cli) |
| In-app authoring (5.4) | ECO-30..32 | lld/extension-runtime.md | [Extension runtime](test-plan.md#extension-runtime) + [Milestone acceptance](test-plan.md#milestone-acceptance) M5 |
| Author docs (5.4) | ECO-33 | author-guide.md | [Milestone acceptance](test-plan.md#milestone-acceptance) M5 |
| Customization (5.5) | CUS-01..18 | lld/customization.md | [Customization](test-plan.md#customization) |

## Deviations

1. Resolved (2026-09-24): arch.md now uses the sdk-reference key names and defaults; the former
   `*` keys are in sdk-reference Settings keys, or are `LspPolicy` constants marked `(fixed)`.
2. No LLD owns L0 no-server features (NS-*) in the brief's layout; owner is hld.md until an
   `lld/language-core.md` is warranted. `walkthroughs` (EXT-34) and `l10n` (EXT-09) exist
   only in sdk-reference; milestones M5/M3 assigned here.
