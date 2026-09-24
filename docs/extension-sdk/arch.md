# Feature: Extension SDK & Language Intelligence - Architecture

Status: PROPOSED (nothing implemented), 2026-09-23. Design here; the contract (manifest,
contribution points, actions, WASM host API, CLI, settings keys) is [sdk-reference.md](sdk-reference.md).

## Document map

| Doc | Purpose |
|---|---|
| arch.md (this) | Goals, feature catalogue, layers, LSP client design, trust model, budgets, milestones |
| [sdk-reference.md](sdk-reference.md) | The contract: manifest, contributions, actions, WASM ABI, capabilities, settings keys, fixed limits, CLI, index format |
| [hld.md](hld.md) | Modules, processes, data, scenarios, NFRs, failure modes; owns L0 no-server features |
| [features.md](features.md) | Master feature list: id, priority, milestone, knobs, acceptance, owning LLD, tests |
| [threat-model.md](threat-model.md) | Assets, trust boundaries, STRIDE threats, mitigations M-01..M-23, residual risks |
| [rules.md](rules.md) | Enforceable MUST/SHOULD rules for SDK, language core and extensions |
| [test-plan.md](test-plan.md) | Test layers, fakes, CI gates, milestone acceptance tests |
| [author-guide.md](author-guide.md) | Tutorials for authors: theme, snippet + key row, language pack, WASM |
| [glossary.md](glossary.md) / [tracker.md](tracker.md) | Terms with defining doc / status per milestone |
| [lld/lsp-client.md](lld/lsp-client.md) / [lld/lsp-lifecycle.md](lld/lsp-lifecycle.md) | Transport, document sync, `LspSession`, path mapping, `LspPolicy` / state machine, keying, admission, eviction, backoff |
| [lld/lsp-features.md](lld/lsp-features.md) | Client capabilities, multi-server routing, debounce/staleness, each LSP feature |
| [lld/extension-runtime.md](lld/extension-runtime.md) | Manifest loading, enablement, activation, when-clauses, contributions, `ActionRunner` |
| [lld/customization.md](lld/customization.md) | Settings layering and storage, overrides, keybindings, themes, key rows, safe mode, project trust, profiles |
| [lld/wasm-host.md](lld/wasm-host.md) | Chicory embedding, ABI v1, limits, host functions, L2 capability enforcement |
| [lld/registry-and-install.md](lld/registry-and-install.md) | Index fetch and verification, signing trust, install/update/rollback, Open VSX, cache, `state.json` |
| [lld/cli.md](lld/cli.md) | `easyide-ext`: scaffold, validate, package, sign, publish, test scenarios, dev loop |
| ADRs [0013](../decision/0013-extension-sdk-shape-manifest-easyext.md)-[0017](../decision/0017-lsp-client-hand-rolled-server-lifecycle.md) | ADR-E..ADR-I below |

## 1. Overview

easyIDE has lexical highlighting (229 bundled TextMate grammars,
[0010](../decision/0010-textmate-highlighting-bundled.md)) and nothing else: no completion,
diagnostics or navigation, and no way for anyone else to add a language, theme or button.

The ask is two things that turn out to be one system:

1. **VS Code-level language features** on an Android tablet - squiggles, completion,
   hover, rename, quick fixes, formatting, outline - for the languages people actually use.
2. **An ecosystem of our own**: installable, user-authorable extensions (the *easyIDE
   Extension SDK*), where every contribution is also user-overridable.

They are one system: a language pack that installs a server into the sandbox and wires it
to the editor delivers (1) and is the first extension type in (2). So the language core is
built first and dogfooded as extensions before the format opens.

Strategy: **adopt open formats, invent only what a tablet needs.** TextMate grammars, VS Code
`language-configuration.json`, snippet JSON, color theme JSON and `contributes` shape; LSP
3.17 (DAP later). easyIDE-only: sandbox toolchain install, native tablet UI, touch key rows,
stages, memory budgets - all under one `"easyide": {}` manifest key.

This refines [extensions/arch.md](../extensions/arch.md) (the four-tier audit behind
[0009](../decision/0009-extension-platform-tiers.md)); the mapping is in section 4.

## 2. Goals, non-goals, success metrics

### Goals

1. G1 - Every file type colours and edits correctly (brackets, auto-close, comments,
   indentation, folding) with **no language server**, offline.
2. G2 - Python, TypeScript/JavaScript, Go, C/C++, Rust, Bash, YAML, JSON, HTML/CSS and
   Markdown get LSP diagnostics, completion, hover, definition, references, rename,
   formatting and code actions via first-party language packs.
3. G3 - A third party can ship a theme, snippet pack, language pack or toolbar-command pack
   **with zero code**, and custom logic via WASM without touching the sandbox.
4. G4 - Every contribution (setting, keybinding, button, menu item, view, stage, status
   item, key row, server) can be overridden, hidden or disabled by the user, per project.
5. G5 - Language servers never make the app unusable: they run inside a declared memory
   budget and are evicted before the editor or terminal is.
6. G6 - Install, update and rollback work offline from cache, are verified (sha256 +
   ed25519), and never happen silently.

### Non-goals

- Running arbitrary VS Code extensions (Node extension host). Deferred to L3/M8, conditional.
- Webview panels (L4). Deferred.
- Microsoft Marketplace access or Microsoft-published extensions (Pylance, C/C++, C#,
  Remote-*). Legally unavailable; see [extensions/arch.md sec 2.1](../extensions/arch.md).
- Debugging (DAP). Later; not scheduled in M0-M8.
- A backend service: the registry is a static signed git index; `services/backend` stays empty.
- Per-extension isolation for anything in the sandbox: proot and chroot+BusyBox are
  single-tenant, trust-the-code-you-run ([0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md)).
- tree-sitter as a required dependency. Optional later, per 0010.

### Success metrics

All unmeasured today; targets to verify on the reference device (Xiaomi Pad 6, 8 GB).

| Metric | Target | How measured |
|---|---|---|
| First diagnostics after opening a `.py` file, warm environment | < 3 s | open -> first `publishDiagnostics` rendered |
| First diagnostics, cold (server not running) | < 10 s | same, server spawn included |
| Completion popup after server response | < 150 ms p95 | response received -> popup frame |
| Keystroke-to-glyph latency with LSP active | no regression vs LSP off | frame timing, 400 KB file |
| LSP RSS | within `lsp.globalMemoryBudgetMb` 100% of the time; eviction < 2 s after breach | `/proc/<pid>/status` sampler |
| Extension install from offline cache | < 10 s (excluding `sandbox.install`) | tap -> contributions live |
| Author a theme or snippet pack from scaffold to installed | < 15 min, zero code | usability test, 3 authors |
| App cold start with 20 declarative extensions | < +50 ms vs none | startup trace |
| Safe mode reachable after a bad extension | 100% (auto after 2 activation crashes) | fault-injection test |

## 3. Personas

| Persona | Wants | Served by |
|---|---|---|
| **User** (student, hobbyist, on-the-go dev) | Completion and errors "just work" for Python/JS; nothing to configure | first-party packs, lazy server start, sane budgets |
| **Power user** | Change everything: keys, colours, which buttons show, which server and flags | section 5.5, JSON + UI for every layer |
| **Extension author** | Known formats, a CLI, fast device loop, a place to publish | `easyide-ext`, templates, `dev` live reload, registry PR flow |
| **easyIDE team** | Ship languages without app releases; bounded API to support forever | L0/L1 as data, small action vocabulary, semver `engines.easyide` |

## 4. Decisions

### Existing ADRs this builds on

| ADR | Bearing |
|---|---|
| [0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md) | No isolation in the sandbox - shapes the whole trust model (sec 9) |
| [0005](../decision/0005-sandbox-environment-sharing-model.md) | Projects bind at `/workspace`; servers and sandbox-installing packs are per environment |
| [0007](../decision/0007-sandbox-image-catalog-and-custom-rootfs.md) | `setupCommands` streaming-to-terminal is the model for `easyide.sandbox.install` |
| [0008](../decision/0008-noncommercial-source-available-licensing.md) | App stays PolyForm NC; SDK pieces need a separate license (ADR-G) |
| [0009](../decision/0009-extension-platform-tiers.md) | Tiers and order; this doc inserts a WASM layer (ADR-F amends 0009) |
| [0010](../decision/0010-textmate-highlighting-bundled.md) | TextMate is the highlighter; built-in grammars stay bundled. 0010's "no downloads" direction is reopened **only** for extension-supplied data grammars (JSON, no native code) |
| [0012](../decision/0012-git-token-in-process-env-not-credential-socket.md) | Git token is never visible to extensions or servers |

Layer mapping to 0009 tiers: L0 = Tier 0 (TextMate instead of tree-sitter, per 0010);
L1 = Tier 1; **L2 WASM is new**; L3 Node host = Tier 2; L4 webviews = Tier 3.

### Proposed ADRs (drafted as 0013-0017)

| ADR | Question | Needed by |
|---|---|---|
| ADR-E [0013](../decision/0013-extension-sdk-shape-manifest-easyext.md) | SDK shape: VS Code-shaped `package.json`, `.easyext` zip, `easyide` namespace key, fixed action vocabulary | M3 |
| ADR-F [0014](../decision/0014-wasm-logic-layer-chicory.md) | WASM logic layer in-app; runtime choice (Chicory vs alternatives); JSON-over-memory ABI v1 | M7 |
| ADR-G [0015](../decision/0015-extension-sdk-licensing-apache.md) | SDK licensing: schema, guest bindings, CLI, samples under Apache-2.0; app under 0008 | M5 |
| ADR-H [0016](../decision/0016-extension-registry-static-index-ed25519.md) | Registry + signing: static git index, ed25519 publisher keys, root key, TOFU pins, revocation | M6 |
| ADR-I [0017](../decision/0017-lsp-client-hand-rolled-server-lifecycle.md) | LSP client: hand-rolled JSON-RPC vs LSP4J; session per (env, project, serverId); lifecycle | M2 |

**Dependency: ux-overhaul ADR-B (editor engine)** - squiggles, inlay hints, popups, gutter icons
and code lens need decoration layers `EditorPane` lacks. Also Pillar 4 (command registry + keymap)
and Pillar 5 (settings, ADR-C); contributions plug into those, never a parallel registry.

## 5. Feature catalogue

Columns: **Layer** (L0-L4), **Srv** (needs a language server?), **Surface** (where the user
sees it), **Knobs** (settings keys, full list in
[sdk-reference.md#settings-keys](sdk-reference.md#settings-keys)), **M** (milestone).

### 5.1 No-server language features (L0)

| Feature | Layer | Srv | Surface | Knobs | M |
|---|---|---|---|---|---|
| TextMate highlighting (exists) | L0 | no | editor | `editor.syntaxHighlighting`, token colour overrides | done |
| Bracket matching + pair colourization | L0 | no | editor | `editor.bracketPairColorization`, `editor.matchBrackets` | M1 |
| Auto-close + auto-surround (brackets, quotes) | L0 | no | editor, key row | `editor.autoClosingBrackets`, `editor.autoSurround` | M1 |
| Toggle line/block comment | L0 | no | command, key row | keybinding | M1 |
| Indentation rules + onEnter rules | L0 | no | editor | `editor.autoIndent` | M1 |
| Folding by markers + indentation | L0 | no | gutter | `editor.folding`, `editor.foldingStrategy` | M1 |
| Word pattern (double-tap select, word nav) | L0 | no | editor | per-language via language-configuration | M1 |
| Snippets (VS Code JSON, tab stops, choices, variables) | L0 | no | completion popup, palette | `editor.snippetSuggestions`, user snippets | M1 |
| Word-based completion (buffer + open tabs) | L0 | no | completion popup | `editor.wordBasedSuggestions` | M1 |
| Trim whitespace, final newline, detect indentation | L0 | no | on save | ux-overhaul Pillar 5 editor keys | M1 |
| Optional tree-sitter: instant parse errors, structural selection | L0 | no | editor | `editor.treeSitter.enabled` | later |

`language-configuration.json` for the 229 bundled grammars is generated alongside them by
`tools/build-grammars.py` (license-filtered the same way), so M1 covers all bundled languages.

### 5.2 LSP language intelligence (L0 client, servers via L1 packs)

| Feature | LSP method(s) | Surface | Knobs | M |
|---|---|---|---|---|
| Diagnostics | `publishDiagnostics`, pull `textDocument/diagnostic` | squiggles, gutter, Problems panel, status count | `editor.diagnostics.*` (per `[lang]`) | M2 |
| Completion (+resolve, snippets, commit chars) | `completion`, `completionItem/resolve` | caret popup, touch + keyboard | `editor.quickSuggestions`, `editor.suggestOnTriggerCharacters` | M2 |
| Signature help | `signatureHelp` | popup above caret | `editor.parameterHints.enabled` | M2 |
| Hover | `hover` | long-press / mouse hover card | `editor.hover.enabled`, delay | M2 |
| Go to definition / declaration / type definition / implementation | `definition`, `declaration`, `typeDefinition`, `implementation` | long-press menu, Ctrl+click, F12 | keybindings | M2 |
| Find references (peek) | `references` | peek sheet, side panel | - | M2 |
| Document highlight | `documentHighlight` | background decoration | `editor.occurrencesHighlight` | M2 |
| Document symbols | `documentSymbol` | Outline (right stage), breadcrumbs | `breadcrumbs.enabled` | M2 |
| Workspace symbols | `workspace/symbol` | palette `#` prefix | - | M4 |
| Rename | `prepareRename`, `rename` | inline field, preview of edits | - | M2 |
| Code actions / quick fixes | `codeAction`, `codeAction/resolve` | lightbulb in gutter, long-press | `editor.codeActionsOnSave` | M2 |
| Formatting (document, range, on-type, on save) | `formatting`, `rangeFormatting`, `onTypeFormatting` | command, on save | `editor.formatOnSave`, `editor.defaultFormatter` per lang | M2 |
| Inlay hints | `inlayHint` | inline ghost text | `editor.inlayHints.enabled` per lang | M4 |
| Semantic tokens (layered on TextMate) | `semanticTokens/full`, `/delta`, `/range` | editor colours | `editor.semanticHighlighting.enabled`, semantic colour overrides | M4 |
| Folding ranges | `foldingRange` | gutter (replaces L0 when available) | `editor.foldingStrategy` | M4 |
| Selection ranges | `selectionRange` | expand/shrink selection command | keybinding | M4 |
| Code lens | `codeLens`, `codeLens/resolve` | line above symbol | `editor.codeLens` | M4 |
| Document links | `documentLink` | underline, tap to open | `editor.links` | M4 |
| Call / type hierarchy | `callHierarchy/*`, `typeHierarchy/*` | side panel tree | - | later |
| Server status, restart, log | - | status bar item, Extension Log | `lsp.trace` | M2 |

### 5.3 Extension capabilities (what an extension can contribute)

Schema and full list: [sdk-reference.md#contribution-points](sdk-reference.md#contribution-points).

| Contribution | Layer | Surface | User override | M |
|---|---|---|---|---|
| `languages`, `grammars` (TextMate), language-configuration | L1 | editor | disable per language, file association override | M3 |
| `snippets` | L1 | completion, palette | disable per pack, user snippets win | M3 |
| `themes`, `iconThemes` | L1 | theme picker, explorer | colour customizations on top | M3 |
| `commands` + `menus` (editor title, context, explorer, SCM) | L1 | buttons, menus, palette | hide, reorder, rebind | M3 |
| `keybindings` (with `when`, see [when-clause context](sdk-reference.md#when-clause-context)) | L1 | keymap | remove with `-command`, rebind | M3 |
| `configuration` | L1 | native Settings rows | normal setting layering | M3 |
| `views`, `viewsContainers` (data-backed tree/list) | L1/L2 | activity rail, stages | hide, move, reorder | M4 |
| `statusBarItems` | L1/L2 | status bar | hide, reorder | M4 |
| `taskDefinitions`, `problemMatchers` | L1 | Run menu, Problems | user tasks override | M4 |
| `easyide.languageServers` | L1 | status bar, Problems, editor | full override (5.5) | M3 |
| `easyide.sandbox` (requires/install/verify) | L1 | install dialog, terminal output | skip, re-run | M3 |
| `easyide.keyRows` (touch key row per language) | L1 | terminal/editor key row | reorder, replace | M4 |
| `easyide.stages` (named areas, not pixels) | L1 | layout | move, hide | M4 |
| `editor/touchToolbar` menu (touch-first editor actions) | L1 | editor | hide, reorder | M4 |
| Actions: [action vocabulary](sdk-reference.md#action-vocabulary) with [variables](sdk-reference.md#variables) | L1 | behind any command | - | M3 |
| `easyide.wasm` (logic: providers, commands, view data) | L2 | behind commands/views/providers | disable extension | M7 |
| Node `main` (vscode API subset) | L3 | - | - | M8 (conditional) |
| Webview panels | L4 | stage | - | deferred |

### 5.4 Ecosystem features

| Feature | Detail | M |
|---|---|---|
| Registry browse/search | static index fetched from git host, cached; filter by engines + capabilities | M6 |
| Install | download `.easyext` -> sha256 + ed25519 verify -> capability prompt -> unpack versioned dir -> `sandbox.install` streamed -> verify -> activate | M6 (from file: M3) |
| Install from folder / file | SAF picker or project folder; marked "unsigned, local" | M3 |
| Update / rollback | notify + diff (version, changelog, **capability delta**), tap to apply, never silent; previous version kept for one-tap rollback | M6 |
| Uninstall | remove dirs; offer best-effort undo of recorded `sandbox.install` | M3 |
| Open VSX secondary source | read `.vsix` `package.json`, install the declarative subset only, compatibility report | M6 |
| Publish | `easyide-ext publish` opens a PR against the index repo ([CLI](sdk-reference.md#cli), [index format](sdk-reference.md#registry-index-format)) | M6 |
| Dev loop | `easyide-ext dev` push over adb or in-app "Install from folder" + live reload | M5 |
| In-app authoring | "Create extension" scaffold into a project, Extension Log panel, contribution inspector | M5 |
| Offline | cached index + cached packages install with no network; UI shows staleness | M6 |

### 5.5 Customization (everything overridable)

**Settings layering.** Highest wins. `[lang]` blocks are allowed in user, environment and
project layers; inside one layer a `[lang]` value beats the plain value.

```
  (highest)  project        <project>/.easyide/settings.json
             environment    <files>/environments/<envId>/easyide/settings.json
             user [lang]    "[python]": { "editor.tabSize": 4 }
             user global    app settings store (Settings UI or "Edit as JSON")
             extension      contributes.configuration defaults
  (lowest)   built-in       ux-overhaul Pillar 5 schema defaults
```

Contributed `configuration` properties become native `SettingRow`s (type, enum, range,
description from the manifest) in a per-extension category; same search, reset and "modified"
dot as built-in settings. Invalid values fall back to the next lower layer and log once.

| Area | Mechanism | Scope |
|---|---|---|
| Keybindings | `keybindings.json` entries override defaults and contributions; `"-cmd.id"` removes; `when` clauses supported | user, project |
| Key rows | row layouts (`keyRows.layouts`), `keyRows.active` per `[lang]`; reorder/replace contributed rows | user, project |
| Themes | install/pick; `workbench.colorCustomizations`, `editor.tokenColorCustomizations`, `editor.semanticTokenColorCustomizations` (all accept `[themeName]` blocks); icon themes | user |
| UI contributions | `workbench.contributions.hidden` / `.order`, `workbench.stages.placement` for buttons, menu items, views, stages, status items | user, project |
| Extension enablement | `extensions.disabled` (any P layer): globally, per environment, per project; not in `state.json` | all |
| Language servers | `lsp.servers.<extId>/<id>.{command (argv),env,initializationOptions,memoryBudgetMb,enabled}` overrides a contributed server; project-layer exec values need project trust | user, env, project |
| Custom server, no extension | `lsp.servers.<newId>` with `languages` + `command` defines a server from settings alone | user, env, project |
| Per-language feature toggles | `[python]: { "editor.inlayHints.enabled": "off", "editor.diagnostics.minSeverity": "warning" }` | user, env, project |
| Snippets | user snippets per language + global; override pack snippets by prefix | user, project |
| Tasks | `.easyide/tasks.json` (VS Code tasks shape, `sandboxExec` backing) | project |
| Profiles | named set = user settings layer + enabled extensions + keybindings + key rows; switch per project | user |
| Safe mode | start with all non-built-in extensions off; entered from Settings, a launcher shortcut, or automatically after 2 consecutive activation crashes | app |
| Export / import | one zip (settings, keybindings, snippets, profiles, extension list with versions) via SAF; secrets excluded | user |

## 6. Architecture

### 6.1 Layers

```
+---------------------------------------------------------------------+
|  Compose UI: editor (ADR-B decorations), Problems, Outline, palette,|
|  Settings rows, Extensions screen, status bar, key rows, stages     |
+------------------------+--------------------------+-----------------+
|  Command registry +    |  Settings store          |  Contribution   |
|  Keymap (Pillar 4)     |  (Pillar 5, layered)     |  registry       |
+------------------------+--------------------------+-----------------+
|  L0 language core      |  L1 extension runtime    |  L2 WASM host   |
|  TextMate, lang-config,|  manifest loader,        |  Chicory,       |
|  snippets, LSP client  |  ActionRunner, activation|  capability-    |
|                        |                          |  gated host_call|
+------------------------+------------+-------------+-----------------+
|  sandbox-runtime: ServerProcess (stdio), ShellRunner, installs      |
+---------------------------------------------------------------------+
|  proot / chroot sandbox: language servers, toolchains  (NO isolation)|
+---------------------------------------------------------------------+
```

### 6.2 Components and where they live (proposed module names)

| Component | Responsibility | Location |
|---|---|---|
| `JsonRpcConnection` | Content-Length framing, id correlation, cancellation, notifications | new `:lsp` (`services/mobile/lsp`, Kotlin, no Android UI deps; JVM-testable) |
| `LspSession` | one server: initialize, capability negotiation, request API, state machine | `:lsp` |
| `DocumentSync` | versioned docs, prefix/suffix diff -> incremental `didChange`, flush-before-request | `:lsp` |
| `PathMapper` | host path <-> guest `file://` URI | `:lsp` (interface), impl in app from `SandboxPaths` |
| `ServerSupervisor` | keyed sessions, lazy start, idle stop, backoff, memory sampling, kill order | `:lsp` |
| `ServerProcessFactory` | spawn a non-pty stdio process inside an environment with a clean env | `:sandbox-runtime` (beside `ShellRunner`) |
| `ManifestParser` + JSON Schema | parse/validate `package.json`, `easyide` key, `engines` | new `:extensions`; schema in `services/shared/extension-schema/` |
| `ExtensionStore` | versioned dirs, `current` symlink flip, cache, state (approvals, pins, system disables) | `:extensions` |
| `ContributionRegistry` | adapts contributions into command registry, keymap, settings schema, themes, stages | `:extensions` |
| `ActivationManager` | activation events, lazy activation, crash counting -> safe mode | `:extensions` |
| `ActionRunner` | executes the L1 action vocabulary; shared by L2 host functions | `:extensions` |
| `RegistryClient` + `SignatureVerifier` | index fetch, sha256, ed25519, revocation, TOFU pins | `:extensions` |
| `WasmHost` | Chicory instantiate, ABI, fuel/memory/time limits, capability table | new `:ext-wasm` (isolates the runtime dependency) |
| Language UI | decorations, completion popup, hover card, Problems, Outline, lightbulb | `app/.../ui/screens/workspace/lsp/` |
| Extensions UI | browse, detail, capability prompt, Extension Log, contribution inspector | `app/.../ui/screens/extensions/` |
| `easyide-ext` CLI | init/validate/package/sign/publish/test/dev | `tools/easyide-ext/` (Apache-2.0 per ADR-G) |
| Registry index | signed static index | separate repo `easyide-extensions-index` |

On-disk layout (outside every rootfs for organisation - not protection):

```
<files>/extensions/global/<publisher>.<name>/<version>/     pure UI/theme/snippet/wasm
<files>/extensions/global/<publisher>.<name>/current  ->  <version>
<files>/environments/<envId>/extensions/<publisher>.<name>/<version>/   packs with sandbox or servers (+ current)
<files>/extensions/cache/<sha256>.easyext                   offline cache, content-addressed
<files>/extensions/state.json                               approvals, pins, system disables (revoked, crash-loop)
```

**Scope rule (decided here):** with `easyide.sandbox` or `easyide.languageServers` ->
installed **per environment** (0005). Otherwise (themes, snippets, grammars, non-sandbox
commands, WASM-only) -> **global**, narrowable per environment or project via `extensions.disabled`.

### 6.3 Data flows

**Install** (package layout: [sdk-reference.md#package-layout](sdk-reference.md#package-layout))
1. Resolve index entry (or local file); check `engines.easyide` against the app API version.
2. Download to cache (or hit by sha256); verify sha256, ed25519 signature against the pinned
   publisher key, publisher key against registry root key; any failure is hard.
3. Schema-validate manifest; show capability prompt with "what will run" (install commands,
   servers, action types); record approval with the exact capability set.
4. Unpack to `<scopeDir>/<id>/<version>/` (temp dir + rename; layout above).
5. If `easyide.sandbox.install`: run in the target environment, output streamed to a
   terminal tab; run `verify`; non-zero -> delete the version dir, keep previous `current`.
6. Flip `current` symlink (atomic rename); previous version retained.
7. Register static contributions (no activation yet).

**Activation**
1. At startup, `ContributionRegistry` loads a cached index of enabled manifests (no zip
   reads) and registers static contributions (themes, grammars, keybindings, menus).
2. `ActivationManager` subscribes to the manifest `activationEvents` (`onLanguage`, `onCommand`, ...).
3. First matching event -> activate: L1 = mark live; servers become *eligible* (not
   started); L2 = instantiate WASM, call `ext_activate`.
4. Failure -> Extension Log, contributions greyed with retry; 2 consecutive -> safe mode.

**LSP session lifecycle**
1. File opened with language L in project P, environment E -> one session per `(E, P, serverId)`
   for each server serving L (a language may have several, e.g. pyright + ruff).
   Per project (not shared across projects) because every project binds to the same
   `/workspace` guest path, so one server cannot see two projects at once.
2. Resolve server config: contributed default merged with `lsp.servers.<id>` layers.
3. Budget check (sec 7.7); if over, evict per kill order first.
4. `ServerProcessFactory` spawns the `command` argv in E with cwd `/workspace`, clean env +
   `env` from config; stdio pipes.
5. `initialize` (rootUri `file:///workspace`, client capabilities) -> `initialized` ->
   `workspace/didChangeConfiguration` with resolved `settings` section.
6. `didOpen` for all open docs of L. State RUNNING.
7. Last doc of L closed -> `lsp.idleShutdownSec` -> `shutdown`/`exit` -> SIGKILL after grace.

**Completion round trip**
1. Keystroke inserts text; editor updates buffer, `DocumentSync` records dirty version.
2. Trigger (server trigger char, word char when `editor.quickSuggestions` allows, or explicit
   Ctrl+Space / key-row button) -> debounce `editor.quickSuggestionsDelay`.
3. Flush pending `didChange` (one incremental event), cancel previous in-flight completion
   (`$/cancelRequest`).
4. Send `completion` at mapped position; off main thread.
5. Response -> merge with snippets + word completion -> client-side fuzzy filter; while
   typing, refilter locally unless `isIncomplete`.
6. Popup anchored to caret decoration; focused item -> `completionItem/resolve` for docs.
7. Accept (tap, Tab, Enter, or commit char) -> apply `textEdit` + `additionalTextEdits` as
   one undo unit; snippet syntax goes through the L0 snippet engine.

**WASM action call** (ABI: [sdk-reference.md#wasm-host-api](sdk-reference.md#wasm-host-api))
1. User taps a contributed button -> command registry -> command owned by a WASM extension.
2. `WasmHost` writes `{"type":"command","id":...,"args":...,"context":{...}}` into guest
   memory via guest `alloc`, calls `ext_handle(ptr,len)` on a worker thread.
3. Guest calls `host_call(ptr,len)` with e.g. `{"fn":"editor.applyEdits",...}`.
4. Host checks `fn` against the capability table for this extension (declared AND approved);
   denied -> error result, logged.
5. Allowed -> dispatched to `ActionRunner` (same code as L1 actions) or a read query;
   result JSON written back, pointer returned.
6. Fuel, memory cap or timeout -> instance trapped and discarded; `extensions.wasm.maxCrashes`
   within `crashWindowSec` -> extension disabled until the user re-enables it.

**Settings resolution** (`(key, languageId?, envId, projectId)`; exact algorithm in [lld/customization.md sec 3.2](lld/customization.md#32-algorithm)):
layers project -> environment -> user -> extension default -> built-in, `[lang]` before plain in each;
schema-invalid values skip to the next layer; cached per layer; changed server sections -> `didChangeConfiguration`.

## 7. LSP client design

Decided here (ADR-I to record): **the client runs in the app process**, not the sandbox
(resolves extensions/arch.md open question 4): editor, decorations and settings are in-app;
the sandbox holds only the disposable server process.

### 7.1 Transport
- stdio only in v1 (every candidate server supports it). Framing: `Content-Length` headers,
  UTF-8 bodies. JSON via the JSON library already in the app (no new dependency).
- Reader/writer coroutines on IO; stderr to the Extension Log ring (`LspPolicy.LOG_RING_LINES`).
- Hand-rolled over LSP4J (EPL-2.0 OR EDL-1.0, verified 2026-09-23): LSP4J brings Gson and
  reflection-heavy types for ~40 methods we need. ADR-I decides.

### 7.2 Path mapping
- Project files: host `<files>/projects/<projectId>/a/b.py` <-> `file:///workspace/a/b.py`.
- Environment files (stdlib, site-packages, headers): `file:///usr/lib/...` <->
  `<files>/environments/<envId>/rootfs/usr/lib/...`, opened **read-only** in the editor.
- Positions: the client offers only `positionEncoding` utf-16, the LSP default, which is also
  Kotlin `String` indexing, so no conversion ([lld/lsp-client.md](lld/lsp-client.md)).

### 7.3 Document sync
- Versions start at 1 per open, strictly increasing. Edits coalesce: keep last-synced text; on
  flush, common prefix/suffix vs new text -> one range replacement (O(n), no edit history).
- Flush triggers: `lsp.didChangeDebounceMs` elapsed, or any request about to be sent.
- Servers advertising only `Full` sync get full text (size capped by `LspPolicy.MAX_FULL_SYNC_BYTES`;
  beyond it the doc is not synced and features are off for that file, stated in the UI).
- `didSave` with text when requested; `willSave`/`willSaveWaitUntil` only for format-on-save.

### 7.4 Cancellation and staleness
- Requests carry their doc version; stale responses are dropped (except version-agnostic ones).
- A new request of the same kind for the same doc cancels the previous one.
- Per-method timeouts (`lsp.requestTimeoutMs`) cancel silently; no error dialog.

### 7.5 Capability negotiation
- Client advertises only what the UI renders at the current milestone; each UI feature
  hides itself when the server lacks the capability.
- Dynamic registration supported for `didChangeWatchedFiles` (fed from `ProjectFileWatcher`)
  and `workspace/didChangeConfiguration`.
- `workspace/configuration` requests answered from settings resolution (6.3).

### 7.6 Server lifecycle state machine

States `NotInstalled`, `Stopped(reason)`, `Starting`, `Initializing`, `Running`, `Idle`, `Stopping`,
`Backoff`, `Failed(reason)`; transitions: [lld/lsp-lifecycle.md sec 1](lld/lsp-lifecycle.md#1-server-lifecycle-state-machine).

- Backoff: `lsp.restart.backoffMs` doubling; more than `lsp.restart.maxRetries` crashes within
  `LspPolicy.CRASH_WINDOW_MS` (5 min) -> `Failed(CrashLoop)` with "restart" and the stderr tail.
- Evicted servers go to `Stopped(EVICTED)` and restart lazily on next need; status bar: "paused (memory)".

### 7.7 Memory budget and kill order
- Each server has `memoryBudgetMb` (manifest, else `lsp.defaultMemoryBudgetMb`; user-overridable);
  `lsp.globalMemoryBudgetMb` caps the sum. Sampled RSS of the server process tree every
  `LspPolicy.MEMORY_SAMPLE_MS` from `/proc/<pid>/status`; at most `lsp.maxServers` run.
- Over its own budget for 2 samples -> restart once, then stop with a notice.
- Global over budget or `onTrimMemory` >= RUNNING_LOW -> kill in order: (1) IDLE servers, LRU
  first; (2) RUNNING servers with no visible editor; (3) idle WASM instances; (4) the focused
  editor's server. Terminal sessions and buffers are never touched.
- Internal thresholds live in one declarative `LspPolicy` table (its memory part is the
  `MemoryPolicy` named elsewhere), starting values to be profiled on device.

### 7.8 Multi-root
- v1: one folder per project (`/workspace`); `workspaceFolders` is advertised so future
  multi-root maps extra folders to `/workspace/<name>` binds with no protocol change.

### 7.9 UI integration (via ADR-B decoration layers)

ADR-B is [decision 0018](../decision/0018-editor-engine-and-decorations.md). Until the
line-virtualised editor (PE1) lands, inline inserted text renders as end-of-line ghost text
and between-line blocks (code lens) as a gutter glyph with a tap-to-list popup.

| Layer | Used by |
|---|---|
| underline decorations | diagnostics (severity token colours), document links |
| background ranges | document highlight, references peek |
| inline inserted text | inlay hints, ghost completion (later) |
| gutter icons | diagnostics, lightbulb, fold markers, code lens toggle |
| between-line blocks | code lens |
| caret-anchored popups | completion, signature help, hover (touch: long-press; keyboard: shortcut; mouse: hover) |
| token colour overlay | semantic tokens over TextMate roles (mapping table in `ScopeRules` style) |

## 8. Candidate language servers

License verified 2026-09-23 from each repo's LICENSE via the GitHub API/raw file unless
marked. Memory is a desk estimate, unmeasured. Recipes assume the Ubuntu base environment;
install commands are what the first-party pack's `easyide.sandbox.install` runs.

| Language | Server | Est. RSS | Install recipe | License (verified) |
|---|---|---|---|---|
| Python | basedpyright | 300-800 MB | `pip install basedpyright` | MIT (LICENSE.txt), active |
| Python | pyright | 300-800 MB | `npm i -g pyright` | MIT (LICENSE.txt), active |
| Python (light) | jedi-language-server | 100-300 MB | `pip install jedi-language-server` | MIT |
| Python lint/format | ruff (`ruff server`) | 30-80 MB | `pip install ruff` | MIT |
| TS/JS | typescript-language-server | 300 MB-2 GB | `npm i -g typescript typescript-language-server` | Apache-2.0, with MIT for files copied from vscode |
| TS/JS (alt) | vtsls | 300 MB-2 GB | `npm i -g @vtsls/language-server` | MIT |
| HTML/CSS/JSON | vscode-langservers-extracted | 80-200 MB each | `npm i -g vscode-langservers-extracted` | MIT; **last release v4.10.0, 2024-05-08 - maintenance risk** |
| Go | gopls | 200 MB-1 GB+ | `go install golang.org/x/tools/gopls@latest` | BSD-3-Clause (golang/tools) |
| C/C++ | clangd | 100-500 MB (+index) | `apt-get install -y clangd` | Apache-2.0 WITH LLVM-exception (llvm-project) |
| Rust | rust-analyzer | 1-4 GB (opt-in warning) | `rustup component add rust-analyzer` | MIT OR Apache-2.0 (LICENSE-MIT + LICENSE-APACHE) |
| Bash | bash-language-server | 60-150 MB | `npm i -g bash-language-server` | MIT |
| YAML | yaml-language-server | 80-200 MB | `npm i -g yaml-language-server` | MIT |
| Markdown | marksman | 30-100 MB | release binary `marksman-linux-arm64`, sha256-pinned in pack | MIT |
| Kotlin | JetBrains kotlin-lsp | 1-3 GB (JVM) | release zip + JDK in env | Apache-2.0 (repo); project self-labels **Alpha**; terms of release binaries built on IntelliJ - to verify |

**Microsoft extensions are unavailable**: Pylance, C/C++ (cpptools), C# and the Remote-*
pack are licensed only for Microsoft products and the Marketplace ToU forbids use elsewhere.
Packs above are the open alternatives; UI copy must not promise Pylance-equivalent Python.

Packs declare needed runtimes (Node, Python, Go, Rust) in `easyide.sandbox.requires`.

## 9. Security and trust model

Capabilities (`sandbox.exec`, `sandbox.install`, `network(hosts)`, `fs.*`, `lsp.*`, `clipboard`,
`ui.*`, `secrets.read`): list and semantics in [sdk-reference.md#capabilities](sdk-reference.md#capabilities).

| Layer | What runs | Enforcement |
|---|---|---|
| L0 built-in | our code | trusted |
| L1 declarative | no extension code; host executes actions | **consistency only**: loader rejects a manifest whose actions need an undeclared capability (e.g. `sandboxExec` without `sandbox.exec`). Once a command runs in the sandbox, it can do anything the user can - **disclosure, not enforcement** |
| L1 servers / install | processes in the sandbox | **none** beyond disclosure; same trust as typing the command in the terminal |
| L2 WASM | guest code in-app interpreter | **enforced**: no WASI fs/socket imports; only `host_call`, which checks each call against declared+approved capabilities; fuel, memory cap, timeout. The only enforced layer (a capability boundary, not audited isolation) |
| L3 Node host | JS in the sandbox | none; off by default if ever built |

Rules:
- Capability prompt before install lists exactly what will run (install commands verbatim,
  server commands) and, for L1/L3, says in plain words that it is not contained.
- Updates that add a capability require a new approval; capabilities are never silently
  granted.
- `openUrl` always opens the external browser after a confirm showing the full URL.
- `secrets.read` reads only a per-extension namespace in the Keystore-backed store. The git
  token (0012), Claude API key and credential vault are reachable by no in-app layer (L0, L1
  host, L2) and never placed in a server's environment (clean env + declared `env`). Sandbox
  processes are different: Claude Code CLI keeps its credentials in the rootfs home, readable
  by every server, install step and command there ([threat-model.md R3](threat-model.md)).
- Signature failures, sha256 mismatches, pin mismatches and revoked entries are hard
  failures with no "install anyway" for registry packages. Local unsigned installs are
  allowed, labelled, and never auto-updated.
- **Never claimed**: that proot or chroot+BusyBox isolate extensions or servers; that WASM
  isolation is audited (it is a capability boundary implemented by us over Chicory's
  interpreter); "VS Code compatible" without the category table from
  [extensions/arch.md sec 7](../extensions/arch.md).

## 10. Performance and memory budgets

Defaults are the [sdk-reference settings keys](sdk-reference.md#settings-keys); internal limits
are policy constants ([fixed limits](sdk-reference.md#fixed-limits-and-protected-keys)); none inline.

| Budget | Value | Note |
|---|---|---|
| Global LSP memory | 1200 MB | `lsp.globalMemoryBudgetMb`; profile on 4-6 GB devices before M2 exit |
| Per-server default | manifest `memoryBudgetMb`, else 400 MB | `lsp.defaultMemoryBudgetMb`; rust-analyzer/kotlin-lsp packs declare more and warn |
| Concurrent running servers | 3 | `lsp.maxServers`; LRU beyond |
| Main-thread work per LSP response | < 4 ms | parsing and mapping off main; only state apply on main |
| `didChange` flush | < 2 ms for 400 KB doc | prefix/suffix diff, same caps as 0010 |
| WASM module size | 8 MB max | validated at package time |
| WASM memory / per-call timeout | 64 MB / 2000 ms | `extensions.wasm.maxMemoryMb` (manifest `memoryMb` may ask less) / `callTimeoutMs`, plus fuel |
| Manifest index load at start | < 20 ms for 20 extensions | cached parsed index, no zip reads |

## 11. Versioning and compatibility

- `engines.easyide` (semver range, [manifest](sdk-reference.md#manifest)) is required. The app exposes one API version; install and
  load refuse a non-matching range with a clear message.
- Schema, action vocabulary and WASM host API version with the app API: additions minor, removals major.
- Deprecation: a deprecated key/action/host function keeps working for **2 minor releases**,
  warns in the Extension Log and in `easyide-ext validate`, then is removed in the next major.
- `.vsix` subset reading: from a `.vsix` we read `package.json` `contributes` (languages,
  grammars, language-configuration, snippets, themes, iconThemes, configuration,
  keybindings whose commands resolve to built-ins). `main`/`browser` code is ignored; the
  install screen shows a compatibility report ("uses N features easyIDE does not run") and
  marks the install "partial". No `.vsix` from the Microsoft Marketplace, ever.
- WASM ABI v1 is JSON-over-linear-memory; a future Component Model/WIT ABI would be a new
  `easyide.wasm.abi` value, with v1 supported through its deprecation window.

## 12. Milestones

| M | Scope | Exit criteria |
|---|---|---|
| M0 | Prerequisites: command registry + keymap (Pillar 4), settings schema + layering incl. project file (Pillar 5 / ADR-C), editor decorations (ADR-B) | a test decoration (underline, gutter icon, caret popup) renders; a command is bound by keymap JSON; a project `.easyide/settings.json` value wins |
| M1 | L0 no-server features: language-configuration for bundled grammars, snippets engine, word completion, folding, comments | brackets/auto-close/comment/indent/fold correct for Python, TS, Go, C, Rust, Markdown in tests; snippet tab stops work with touch and keyboard |
| M2 | `:lsp` core + Python end-to-end (basedpyright or jedi + ruff): diagnostics, completion, hover, definition, references, rename, code actions, formatting, symbols | success metrics for diagnostics and completion met on device; kill/restart/backoff verified by fault injection; budget eviction observed |
| M3 | `:extensions` L1 loader, action runner, install from folder, capability prompt; first-party Python pack and themes shipped **as extensions** | Python pack removed from app code and reinstalled as `.easyext` with identical behaviour; disable/uninstall/rollback work |
| M4 | More packs (TS/JS, Go, C/C++, Bash, YAML, JSON/HTML/CSS, Markdown, Rust opt-in); inlay hints, semantic tokens, code lens; customization UI (hide/reorder, server overrides, custom servers, key rows, profiles, safe mode, export/import) | every 5.5 knob reachable from UI and JSON; a custom server defined only in settings works |
| M5 | `easyide-ext` CLI, templates, `dev` live reload, in-app Create extension, Extension Log, contribution inspector, samples, docs; ADR-G | new author ships a theme and a snippet pack in < 15 min; `validate` catches schema + capability errors in samples test suite |
| M6 | Registry index repo, signing, browse/install/update/rollback, revocation, offline cache, Open VSX subset; ADR-H | signed install < 10 s from cache offline; tampered package and revoked key both hard-fail in tests |
| M7 | `:ext-wasm` with Chicory (or ADR-F alternative), host API, Rust + AssemblyScript templates; ADR-F | sample WASM command and view provider run; denied capability, fuel exhaustion, memory cap and timeout each trap cleanly in tests |
| M8 | (Conditional) L3 Node host with vscode API subset | only started if a written gap analysis shows L1+L2 cannot serve named, requested extensions |

## 13. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| ADR-B editor engine slips | no squiggles/popups; M2 blocked | M1 does not need decorations beyond folding; sequence M0 first; keep LSP core testable headless |
| Language servers exceed memory on 8 GB tablets | LMK kills app or servers thrash | per-server + global budgets, kill order, 3-server cap, opt-in warning for heavy servers, jedi as light Python option |
| proot syscall overhead makes servers slow | diagnostics targets missed | measure in M2; chroot opt-in (0002) path; prefer native-binary servers (ruff, gopls, clangd, marksman) |
| Chicory on Android ART | L2 unusable | Verified on ART 2026-09-24 (0014 spike): re-entrancy, memory cap and fuel/interrupt via our metering pass all pass; interpreter ~7 M instr/s, so L2 stays UI-latency logic |
| Action vocabulary is an API we support forever | lock-in | keep it small (sdk-reference), version it, deprecation window, WASM for everything else |
| Supply-chain attack via registry | malicious code in sandbox | signatures, pins, revocation, capability delta on update, no auto-update, disclosure copy |
| vscode-langservers-extracted stale since 2024-05 | HTML/CSS/JSON pack rots | pin version; fall back to extracting servers from VS Code MIT sources ourselves |
| kotlin-lsp Alpha + JVM heavy | poor Kotlin experience | ship as opt-in pack with warning; do not block milestones on it |
| 0010's "everything bundled" direction contradicted | grammar downloads reintroduced | only data grammars (JSON/plist) from extensions, never native code; built-ins stay bundled |
| Settings layering confuses users | "why is this value used?" | Settings row shows the winning layer and a link to the overriding file |
| Manifest schema drift between app and CLI | validation mismatch | one JSON Schema in `services/shared/extension-schema/` |

## 14. Open questions

1. basedpyright (heavier, better types, bundles Node via wheel) or jedi-language-server
   (lighter) as the default Python server in M2? Decide on measured RSS and latency.
2. Resolved: project-layer `lsp.servers` exec values need project trust, prompted once per
   project ([lld/customization.md sec 12](lld/customization.md#12-language-servers-from-settings-and-project-trust)).
3. Chicory vs other pure-JVM WASM runtimes: ART performance spike needed before ADR-F.
4. Registry host: which git host serves the index, and who holds the root key offline?
5. Is 2 minor releases a long enough deprecation window given tablet app update lag?
6. Do we generate language-configuration for all 229 grammars, or only where upstream VS
   Code ships one and fall back to generic rules for the rest?
7. Semantic token -> TextMate role mapping: extend the 21 `ScopeRules` roles, or add a
   separate semantic role table?
8. Should L1 `sandboxExec` actions require a per-invocation confirm the first time, or is
   install-time approval sufficient?
