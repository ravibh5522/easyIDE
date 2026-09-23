# Extension SDK - Glossary

Every term used across the Extension SDK docs, with the doc that defines it.

Status: PROPOSED (2026-09-23). Design: [arch.md](arch.md). Contract: [sdk-reference.md](sdk-reference.md).
Feature IDs: [features.md](features.md).

Source column: `A n` = arch.md section n; `R <section>` = sdk-reference.md section;
`ADR nnnn` = docs/decision; `UX` = [ux-overhaul/arch.md](../ux-overhaul/arch.md).

## Layers and tiers

| Term | Definition | Source |
|---|---|---|
| Layer | One of L0-L4: how much code an extension runs and where. Lower layers are built first and are more trusted by construction. | A 6.1 |
| L0 - language core | Built-in app code: TextMate highlighting, language-configuration, snippet engine, word completion, LSP client, and all host code that serves higher layers. Trusted. Maps to Tier 0 of ADR 0009 (TextMate instead of tree-sitter, per 0010). | A 4, A 6.1 |
| L1 - declarative | Extensions with no code: data files plus `easyide.actions` executed by the host. Language servers and install steps they declare run in the sandbox. Tier 1. | A 5.3, A 9 |
| L2 - WASM | Extension logic compiled to core WebAssembly, run in-app on an embedded interpreter; reaches the app only through `host_call`. New layer, amends ADR 0009 (ADR-F). The only layer with enforced capability checks. | A 9, R WASM host API |
| L3 - Node host | A real extension host running a vscode API subset in the sandbox. Tier 2. Conditional, M8. | A 5.3, A 12 |
| L4 - webviews | HTML panels in a stage. Tier 3. Deferred. | A 2 |
| Tier | ADR 0009's name for the four extension platform levels; L-layers refine them (mapping in arch sec 4). | ADR 0009 |
| First-party pack | An extension published by easyIDE (e.g. `easyide.python`), shipped as a normal `.easyext` to dogfood the format (M3). | A 12 |
| Language pack | An extension that adds a language: grammar, language-configuration, snippets, usually a language server and sandbox toolchain. | A 1 |
| Built-in | Shipped inside the APK and not an extension; survives safe mode. | A 5.5 |

## Packages and manifests

| Term | Definition | Source |
|---|---|---|
| Extension | An installable unit identified by `publisher.name` that contributes to the IDE. | R Package layout |
| Extension id | `publisher.name`, compared case-insensitively. | R Package layout |
| Publisher | Account id that owns signing keys in the registry. | R Manifest |
| `.easyext` | Package zip named `<publisher>.<name>-<version>.easyext`. | R Package layout |
| `.vsix` | VS Code extension package; only its declarative `package.json` subset is read (Open VSX). | A 11 |
| Manifest | The package's `package.json`, VS Code-shaped at top level. | R Manifest |
| `easyide` block | The manifest key holding every easyIDE-only field (capabilities, scope, actions, languageServers, sandbox, stages, keyRows, statusBarItems, viewData, wasm, memoryBudgetMb). | R Manifest |
| `engines.easyide` | Required semver range of the app API an extension targets; install and load refuse a mismatch. `engines.vscode` is tolerated and ignored. | R Manifest, A 11 |
| API version | The single extension API version the app exposes; below 1.0.0 minors may break. | R intro, A 11 |
| Deprecation window | A deprecated key, action or host function keeps working for 2 minor releases with warnings, then is removed at the next major. | A 11 |
| Categories | Browse tags (`Programming Languages`, `Themes`, `Snippets`, ...). | R Manifest |
| l10n / nls | `l10n/package.nls[.<locale>].json` files substituted into `%key%` manifest strings. | R Package layout |
| Scope (install) | `global` (themes, snippets, grammars, key rows, pure WASM) or `environment` (anything with sandbox, language servers or sandbox actions). Computed; a stated scope is checked. | A 6.2, R Manifest |
| Deterministic package | Zip with sorted entries and fixed timestamps so the same input gives the same sha256. | R CLI |
| SPDX | License expression format required in `license` for registry publish. | R Manifest |

## Contributions

| Term | Definition | Source |
|---|---|---|
| Contribution | One thing an extension adds (a command, menu item, theme, view, key row, server, ...). | A 5.3 |
| Contribution point | A named manifest key under `contributes` or `easyide` that accepts contributions. | R Contribution points |
| Compat: same / subset / easyIDE | Whether a contribution point accepts the VS Code schema as-is, with fields ignored, or is easyIDE-only. | R Contribution points |
| Contribution ref | `<kind>:<location>:<id>` string naming a contribution for hide/order, e.g. `menu:editor/title:python.runFile`. | R Settings keys |
| Static contribution | A declarative contribution registered at startup from the cached index, without activation (themes, grammars, keybindings, menus). | A 6.3 |
| Command | An id + title (+ category, icon, enablement) in the command registry; the unit that menus, buttons, keys and palette invoke. | R Contribution points, UX Pillar 4 |
| Enablement | A when-clause on a command controlling whether it can run (greyed otherwise). | R Contribution points |
| Menu id | A place commands can appear: `commandPalette`, `editor/title`, `editor/context`, `editor/touchToolbar`, `editor/selectionToolbar`, `editor/gutter`, `explorer/context`, `view/*`, `scm/*`, `terminal/*`, `statusBar`, `keyRow`. | R Contribution points |
| Group | Menu sort key: `navigation` first, then `name@order`. | R Contribution points |
| Language configuration | VS Code `language-configuration.json`: comments, brackets, auto-closing and surrounding pairs, folding markers, indentation and onEnter rules, word pattern. | R Contribution points, A 5.1 |
| TextMate grammar | `.tmLanguage(.json)` lexical grammar; extension grammars are data only, never native code. | ADR 0010, A 4 |
| Token colour role | One of the 21 `ScopeRules` roles that TextMate scopes (and theme `tokenColors`) collapse onto. | ADR 0010, R themes |
| Snippet | VS Code snippet JSON entry (`prefix`, `body`) with tab stops `$1`, placeholders `${1:x}`, choices `${1\|a,b\|}`, `$0`, `$TM_*` variables. | R Contribution points |
| Theme / icon theme | VS Code colour theme JSON (`colors`, `tokenColors`, `semanticTokenColors`) / file icon theme JSON (SVG/PNG only). | R Contribution points |
| View / view container | A data-backed tree or list (`views`) inside a rail entry or panel (`viewsContainers`). | R Contribution points |
| viewData | `easyide.viewData`: fills a view from an action's captured JSON output, refreshed on named events. | R Contribution points |
| Stage | A named layout area (`left`, `main`, `right`, `bottom`, or contributed id) - never pixel coordinates. | R Contribution points, ADR 0003 |
| Key row | Touch key strip above the soft keyboard in editor/terminal; `easyide.keyRows` contributes per-language rows whose keys insert text, a snippet, a key, or run a command. | R Contribution points |
| Touch toolbar | `editor/touchToolbar` menu: floating bar above the key row on touch input. | R Contribution points |
| Status bar item | `easyide.statusBarItems`: text (variables allowed), alignment, command, when. | R Contribution points |
| Task / problem matcher | VS Code task definitions (Run menu) and output regex patterns that feed the Problems panel. | R Contribution points |
| Walkthrough | Onboarding steps with image/markdown media contributed by an extension. | R Contribution points |
| Language server (definition) | `easyide.languageServers[]` entry: id, languages, guest argv, env, initializationOptions, settingsSection, rootMarkers, memoryBudgetMb, features, priority. | R Contribution points |
| Server key | Global id of a contributed server, `<extId>/<id>`; any other key in `lsp.servers` defines a custom server. | R Contribution points |
| Feature id | Name of one LSP feature (`diagnostics`, `completion`, `formatting`, ...) used in `features.only/exclude` and `lspSupports:`. | R Contribution points |
| Sandbox toolchain | `easyide.sandbox`: `requires` probes, ordered visible `install[]` steps, `verify` command, best-effort `uninstall[]`. | R Contribution points |

## Activation, context, actions

| Term | Definition | Source |
|---|---|---|
| Activation event | A trigger that activates an extension's non-declarative parts: `onLanguage:<id>`, `onCommand:<id>`, `onView:<id>`, `onStage:<id>`, `workspaceContains:<glob>`, `onStartupFinished`. Declarative contributions are always live. | R Manifest, A 6.3 |
| Activation | L1: mark live, servers become eligible (not started). L2: instantiate WASM and call `ext_activate`. | A 6.3 |
| Eligible (server) | Allowed to start, but only spawned when a document needs it. | A 6.3 |
| When-clause | VS Code boolean expression over context keys (`!`, `&&`, `\|\|`, `==`, `=~`, `<`, `in`, ...) gating menus, keys, rows, views, install steps. Unknown keys are falsy, never errors. | R When-clause context |
| Context key | A named value a when-clause reads: `editorLangId`, `inputMode`, `envState`, `lspState:<lang>`, `isSafeMode`, `config.<key>`, etc. | R When-clause context |
| inputMode | Last input kind: `touch`, `stylus`, `mouse`, `keyboard`. | R When-clause context |
| windowSizeClass / devicePosture | `compact`/`medium`/`expanded`; foldable `flat`/`book`/`tabletop`. | R When-clause context |
| Action | `{ "type": ..., params }` executed by the host, bound to a command in `easyide.actions`. | R Action vocabulary |
| Action vocabulary | The fixed, versioned set of action types (`runInTerminal`, `runTask`, `sandboxExec`, `openFile`, `openUrl`, `applyEdit`, `insertSnippet`, `setConfig`, `toggleConfig`, `lspRequest`, `executeCommand`, `showQuickPick`, `showInputBox`, `showMessage`, `revealStage`, `sequence`). | R Action vocabulary |
| Sequence | Action running steps in order; a failing step aborts unless `continueOnError`. | R Action vocabulary |
| Result binding | `"as": "<name>"` on a step, read later as `${result:<name>}`. | R Action vocabulary |
| Variable | `${...}` substitution in action strings (`${file}`, `${config:key}`, `${input:id}`, `${env:NAME}`, `${extensionPath}`). Paths are guest paths; shell substitutions are quoted. | R Variables |
| Input | `easyide.inputs` entry (`promptString`, `pickString`, `command`) resolving `${input:id}`. | R Manifest |
| Extension Log | In-app panel/ring collecting activation errors, action failures, server stderr and WASM `log.write`. | A 6.3, R CLI |
| Contribution inspector | Developer view showing which extension contributed each button, row, key and setting, and which override hides it. | R CLI |

## Environments and paths

| Term | Definition | Source |
|---|---|---|
| Sandbox | The on-device Linux userland (proot by default, chroot+BusyBox opt-in) where toolchains and servers run. Single-tenant: it provides no per-project or per-extension isolation. | ADR 0002 |
| proot / chroot+BusyBox | The two sandbox backends. Neither isolates extensions or servers; never claim they do. | ADR 0002 |
| Environment | One rootfs + configuration (distro, arch, backend) that projects bind into; id `envId`. | ADR 0005, ADR 0007 |
| rootfs | An environment's root filesystem under `<files>/environments/<envId>/rootfs`. | A 7.2 |
| Project | A folder bound into the environment at `/workspace`. | ADR 0005 |
| Guest path | A path inside the sandbox, e.g. `/workspace/a.py`; extensions never see Android host paths. | R intro |
| Host path | An Android app-private path, e.g. `<files>/projects/<projectId>/a.py`. | A 7.2 |
| `<files>` | The app's private files directory on the device. | A 6.2 |
| `/workspace` | Guest mount point of the active project. | ADR 0005 |
| `/opt/easyide/extensions/<id>` | Guest bind mount of an extension's active version (`${extensionPath}`); not a read-only boundary under proot. | R Package layout |
| Disclosure | Shown to the user but not prevented; applies to everything that runs in the sandbox. | R intro, A 9 |
| Enforced | The runtime refuses the call; applies only to L2 WASM and in-app actions. | R Capabilities |

## Security, signing, install

| Term | Definition | Source |
|---|---|---|
| Capability | A declared permission id in `easyide.capabilities`: `sandbox.exec`, `sandbox.install`, `network(hosts)`, `fs.project(read\|write)`, `fs.outsideProject`, `lsp.spawn`, `lsp.request`, `clipboard`, `ui.stage`, `ui.settings`, `secrets.read`. Undeclared use is a load-time error. | R Capabilities |
| Capability prompt | Install sheet listing exactly what will run (install commands verbatim, server commands) and each capability's prompt text; says plainly that sandbox code is not contained. | A 9 |
| Approval | The recorded user consent for an extension's exact capability set. | A 6.3 |
| Capability delta | Capabilities an update adds; requires a new approval, never silent. | A 5.4, A 9 |
| Capability table | Per-extension set of declared AND approved capabilities checked on every `host_call`. | A 6.3 |
| Registry | A static signed git index (no backend) of published extensions. | A 5.4, R Registry index format |
| Index | `index.json` listing entries (id, version, url, sha256, capabilities, signature). | R Registry index format |
| Registry root key | ed25519 key pinned in the APK (`extensions.registries[].rootKey`) that signs the index, publishers and revocations. | R Registry index format |
| Publisher key | ed25519 key in `publishers/<publisher>.json` that signs that publisher's entries. | R Registry index format |
| keyId | First 16 hex chars of sha256(raw public key). | R Registry index format |
| Canonical JSON (JCS) | RFC 8785 serialization; the exact bytes that are signed. | R Registry index format |
| TOFU pin | Trust on first use: the first install pins `publisher -> keyId` per device; a different key later is accepted only via a rotation record signed by the old key. | R Registry index format |
| Rotation record | `{from, to, sigByOld}` in a publisher file authorising a key change. | R Registry index format |
| Revocation | `revocations.json` entries for keys or version ranges; installs hard-fail, installed versions are disabled (not uninstalled). | R Registry index format |
| Hard failure | Verification error with no "install anyway" path. | A 9 |
| Sideload | Installing a local `.easyext` or folder; labelled unsigned unless a matching `.easyext.sig` verifies; never auto-updated. | A 5.4, R Registry index format |
| Open VSX | Eclipse-run open extension registry; secondary source for the declarative subset of `.vsix`, marked "partial" with a compatibility report. | A 5.4, A 11 |
| Partial install | A `.vsix` install where code and unsupported contributions were dropped. | A 11 |
| Extension store | Versioned install dirs, `current` symlink, content-addressed cache and `state.json`. | A 6.2 |
| `current` symlink | Points to the active version dir; flipped atomically on install/update/rollback. | A 6.2 |
| Rollback | One-tap switch back to the retained previous version. | A 5.4 |
| Offline cache | `<files>/extensions/cache/<sha256>.easyext`; with the last verified index allows install with no network. | A 6.2 |
| Secrets store | Keystore-backed per-extension namespace readable via `secrets.read`; git token (0012), Claude key and credential vault are never reachable. | A 9, ADR 0012 |

## LSP

| Term | Definition | Source |
|---|---|---|
| LSP | Language Server Protocol 3.17; the client runs in the app, servers in the sandbox. | A 7 |
| Language server | A sandbox process speaking LSP over stdio (basedpyright, gopls, clangd, ...). | A 8 |
| JSON-RPC / Content-Length framing | LSP wire format: headers with body length, UTF-8 JSON bodies, id-correlated requests. | A 7.1 |
| LSP4J | Eclipse Java LSP library (EPL-2.0 OR EDL-1.0) considered and not preferred (ADR-I). | A 7.1 |
| Session | One `LspSession`: a server keyed by (environment, project, server). | A 6.3 |
| rootMarkers / rootUri | Files whose nearest ancestor becomes the server's root. | R Contribution points |
| Path mapping | Host path <-> guest `file://` URI translation by `PathMapper`. | A 7.2 |
| Position encoding | Column unit: UTF-16 by default, utf-8 when the server offers it. | A 7.2 |
| Document sync | Versioned `didOpen/didChange/didSave/didClose`; edits coalesced into one incremental change by prefix/suffix diff, flushed before any request. | A 7.3 |
| Staleness | A response for an older document version; dropped. | A 7.4 |
| Capability negotiation | Client advertises only what the UI renders; features hide when a server lacks them. Not the same as extension capabilities. | A 7.5 |
| Dynamic registration | Server-requested registration after initialize (`didChangeWatchedFiles`, configuration). | A 7.5 |
| settingsSection | Settings prefix answered on `workspace/configuration` and pushed via `didChangeConfiguration`. | R Contribution points |
| Server states | NOT_INSTALLED, STOPPED, STARTING, INITIALIZING, RUNNING, IDLE, STOPPING, BACKOFF, FAILED. | A 7.6 |
| Backoff | Doubling restart delay after crashes, capped; too many crashes -> FAILED. | A 7.6 |
| Idle shutdown | Stopping a server after its last document closes plus `lsp.idleShutdownSec`. | A 6.3 |
| Memory budget | Per-server RSS limit (`memoryBudgetMb`) and a global cap on the sum. | A 7.7 |
| Kill order | Eviction order under pressure: IDLE LRU, RUNNING not visible, idle WASM, focused editor's server. Terminals and buffers never. | A 7.7 |
| Eviction | Stopping a server for memory; it goes to STOPPED ("paused (memory)") and restarts lazily. | A 7.6 |
| MemoryPolicy | The single declarative table holding all memory thresholds; no inline constants. | A 7.7 |
| RSS | Resident set size, sampled from `/proc/<pid>/status` for the server's process tree. | A 7.7 |
| onTrimMemory | Android memory-pressure callback; RUNNING_LOW or worse triggers the kill order. | A 7.7 |
| Decoration layer | ADR-B editor primitive: underline, background range, inline text, gutter icon, between-line block, caret popup, token overlay. | A 7.9, UX |
| Diagnostics / squiggle | Server-reported problems rendered as underlines, gutter icons and Problems entries. | A 5.2 |
| Lightbulb | Gutter icon offering code actions. | A 5.2 |
| Peek | Inline sheet showing references or definitions without leaving the file. | A 5.2 |
| Inlay hint | Inline ghost text (types, parameter names) that does not shift offsets. | A 5.2 |
| Semantic tokens | Server token classification layered over TextMate roles. | A 5.2 |
| Code lens | Actionable text block rendered between lines above a symbol. | A 5.2 |
| isIncomplete | Completion flag forcing a re-request instead of local refiltering. | A 6.3 |
| Commit character | Character that accepts the focused completion item and is then typed. | A 5.2 |

## WASM

| Term | Definition | Source |
|---|---|---|
| WASM host | `WasmHost` in `:ext-wasm`: instantiates modules, enforces limits and the capability table. | A 6.2 |
| Chicory | Pure-JVM WebAssembly runtime (Apache-2.0), candidate for L2; ART performance unverified (ADR-F). | R WASM host API, A 13 |
| ABI v1 | Core wasm, no WASI/Component Model; JSON messages in linear memory with `[u32 LE length][UTF-8 JSON]` buffers. | R WASM host API |
| Guest | The WASM module. Exports `memory`, `alloc`, `free`, `ext_abi_version`, `ext_activate`, `ext_handle`. | R WASM host API |
| host_call | The single host import (`easyide` module); every request names an `fn` host function. | R WASM host API |
| Host function | A named operation reachable via `host_call` (`editor.getText`, `fs.read`, `net.fetch`, ...), each gated by a capability or always available. | R WASM host API |
| Handle | Id returned by a long host call; completion arrives as an event (`sandbox.exit`, `net.response`). | R WASM host API |
| Fuel | Instruction budget per call (`extensions.wasm.fuelPerCall`). | R WASM host API |
| Trap | Guest abort (fault, fuel, memory, timeout); the call fails and the instance is discarded. | R WASM host API |
| Crash window | `maxCrashes` traps within `crashWindowSec` disables the extension until re-enabled. | R WASM host API |
| Error codes | `E_CAPABILITY`, `E_ARGS`, `E_NOT_FOUND`, `E_TIMEOUT`, `E_CANCELLED`, `E_LIMIT`, `E_UNAVAILABLE`, `E_INTERNAL`. | R WASM host API |
| Guest SDK | `easyide-guest` (Rust) and `@easyide/guest-as` (AssemblyScript) wrappers over the ABI. | R WASM host API |

## Settings and customization

| Term | Definition | Source |
|---|---|---|
| Settings schema | Pillar 5 declarative `Setting<T>` list driving storage, UI, search and validation. | UX Pillar 5 |
| SettingRow | Generic settings UI row per type; contributed `configuration` renders as native rows. | UX Pillar 5, A 5.5 |
| Settings layer | built-in < extension default < user global < user `[lang]` < environment < project; highest wins. | A 5.5, R Settings keys |
| `[lang]` block | `"[python]": {...}` language-specific overrides inside a layer; beats the plain key in that layer. | A 5.5 |
| Setting scope G/E/P/L | Which layers may set a key: G user global only; E global + environment; P every layer except `[lang]`; L every layer including `[lang]`. | R Settings keys |
| Winning layer | The layer whose value resolution returned; shown on the settings row. | A 13 |
| Keymap / keybindings.json | Chord -> command table with user overrides; `"-cmd.id"` removes a binding. | UX Pillar 4, R Settings keys |
| Custom server | A server defined only in `lsp.servers.<newId>`, with no extension. | A 5.5 |
| Profile | Named set of user settings, enabled extensions, keybindings and key rows (`profiles/<name>.json`), switchable per project. | A 5.5 |
| Safe mode | Start with all non-built-in extensions off; from Settings, launcher long-press, or automatically after 2 consecutive activation crashes. | A 5.5 |
| Export bundle | One zip via SAF with settings, keybindings, snippets, profiles and extension list; secrets excluded. | A 5.5, R Settings keys |
| SAF | Android Storage Access Framework (system file picker). | A 5.4 |
| Developer mode | `extensions.developerMode`: enables `dev` push, verbose log, contribution inspector. | R Settings keys |

## Tooling and process

| Term | Definition | Source |
|---|---|---|
| `easyide-ext` | JVM CLI: `init`, `validate`, `package`, `keygen`, `sign`, `publish`, `test`, `dev`. | R CLI |
| Template | `init` scaffold: `theme`, `snippets`, `language-pack`, `lsp-pack`, `toolbar-command`, `wasm-rust`, `wasm-assemblyscript`. | R CLI |
| Dev extension | Unpacked extension installed by `dev` or "Install from folder", live-reloaded on change. | R CLI |
| Dev inbox | App directory `dev` pushes packages into over adb. | R CLI |
| Capability audit | `validate` check that every action/host fn used is declared and every declared capability is used. | R CLI |
| Milestone M0-M8 | Delivery stages from prerequisites (M0) to the conditional Node host (M8). | A 12 |
| Pillar 4 / Pillar 5 | ux-overhaul plans for the command registry + keymap and the settings schema. | UX |
| ADR-B / ADR-C | ux-overhaul decisions: editor engine (decorations) and settings schema. | UX |
| ADR-E..ADR-I | Proposed decisions 0013-0017: SDK shape, WASM layer, SDK licensing, registry + signing, LSP client. | A 4 |
