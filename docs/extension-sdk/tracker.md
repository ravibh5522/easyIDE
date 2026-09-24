# Feature: Extension SDK & Language Intelligence - Tracker

Status legend: not-started / in-progress / done / blocked

Design: [arch.md](arch.md). API reference: [sdk-reference.md](sdk-reference.md). Everything below is PROPOSED as of 2026-09-23.

| Component | Status | Notes |
|---|---|---|
| **Decisions** | | |
| ADR-E SDK shape, manifest, `.easyext` | in-progress | drafted as [0013](../decision/0013-extension-sdk-shape-manifest-easyext.md), Proposed |
| ADR-F WASM logic layer + runtime | done | [0014](../decision/0014-wasm-logic-layer-chicory.md) Accepted 2026-09-24 after the on-device spike |
| ADR-G SDK licensing (Apache-2.0 proposal) | done | [0015](../decision/0015-extension-sdk-licensing-apache.md) Accepted; 2026-09-24 amendment moves the validation core (parser, schema validator, layout, JCS, signatures) to Apache-2.0 `services/shared/extension-schema` (`:extension-schema`); the runtime stays under 0008 |
| ADR-H Registry + signing model | in-progress | drafted as [0016](../decision/0016-extension-registry-static-index-ed25519.md) |
| ADR-I LSP client + server lifecycle | in-progress | drafted as [0017](../decision/0017-lsp-client-hand-rolled-server-lifecycle.md) |
| Design doc set (arch, sdk-reference, HLD, LLDs, threat model, rules, tests) | done | cross-checked for consistency 2026-09-24; map in [arch.md](arch.md#document-map) |
| ux-overhaul ADR-B editor engine | done | [0018](../decision/0018-editor-engine-and-decorations.md) |
| **M0 prerequisites** | | |
| Command registry + keymap | in-progress | see ux-overhaul tracker; one `KeyBinding` model with two-press chords (`prefix`), `when` expressions and `args`, layered built-in < extension < keybindings.json, `ChordDispatcher` honouring `when` at both steps |
| Settings schema + layering | in-progress | global layer only |
| Editor decoration layers (squiggles, inlay, popups, gutter) | in-progress | PLT-05: model, painters, geometry, `EditorPopup` + JVM tests landed (`workspace/decor/`); inlay = end-of-line ghost text, code lens = gutter glyph (0018); token overlay stays in the highlighter; instrumented screenshot checks not run (no device) |
| **M1 no-server language features** | | |
| language-configuration.json bundled with grammars | done | 57/229 languages from VS Code 1.139.0 (MIT), generic fallback for the rest |
| Brackets, auto-close, surround, comment toggle, indent/onEnter rules | done | basic: auto-close, overtype, surround, enter/indent rules, comment toggle (`editor.action.commentLine`, Ctrl+/), matching bracket; pair colourization not done |
| Snippets + word completion | not-started | |
| **M2 LSP core** | | |
| JSON-RPC transport + path mapping + doc sync | in-progress | `:lsp` core done; app wiring done (`app/lsp/`: every port, `ServerRegistry` for `lsp.servers` + contributed `languageServers` of enabled packs (`ContributedServers.Provider`: own environment only, `lsp.spawn` granted, `${extensionPath}`/`${config:}` expanded, `sandbox.install` steps whose `when` holds + `verify` as the install recipe); `workspace/lsp/`: tab lifecycle, per-language settings). On-device proot pipe test with a real server not run yet |
| Server lifecycle, memory budget, crash backoff | in-progress | done in code: `ProcMemoryProbe` finds the proot child of the app in `/proc` by argv + project bind and sums the tree's VmRSS; `onTrimMemory` mapped to `MemoryPressure`; status item with restart/stop/start/log; install notice runs the pack recipe in a terminal. Not yet measured on a device |
| Python end to end (diagnostics, completion, hover, definition) | in-progress | M2/M4 presenters built on decision 0018 layers: diagnostics + Problems, completion (snippets, resolve, commit chars), hover, signature help, definition family, references, rename preview, code actions + lightbulb, formatting (document/selection/on-type/on-save + codeActionsOnSave), highlights, inlay hints, `@`/`#` symbols, Outline. `lspReady:`/`lspState:`/`lspSupports:<lang>:<feature>` context keys published to extensions. Needs a device run with a server installed |
| **M3 declarative extensions** | | |
| Manifest loader + contribution registry | in-progress | :extensions core done. Wired into :app (`app/extensions/`): inventory over SandboxPaths + APK built-ins, SettingsPort over layered settings, live adapters for commands/palette, keybindings (keymap extension layer under keybindings.json), menus (editor/title, editor/context, editor/touchToolbar, explorer/context, commandPalette, keyRow), status bar items, key rows, snippets, languages/grammars/language-configuration, `configuration` -> SettingsRegistry, themes seam (catalog only). Descriptor cache (`.descriptor.json`) not done |
| Action vocabulary | in-progress | App HostPort done: terminals (named + command PTY), sandboxExec capture with separate stderr, editor edits/snippets/openFile, prompts, stages, built-in commands, tasks.json. `lspRequest` routes through `LspRequestGateway` (`textDocument/*`, `workspace/symbol`, `workspace/executeCommand` only; best ready server offering the feature; caret position params by default; `then` shows locations, applies `TextEdit[]`/`WorkspaceEdit`, or shows the result); WorkspaceEdit resource operations refused for `applyEdit` |
| Extensions screen + install from folder/.easyext | in-progress | list/state/toggle/crash re-enable/safe mode exit/inspector/capabilities/log, local install with capability sheet and atomic `current` flip; packs with `sandbox.install` steps refused (need the registry installer) |
| First-party packs shipped as extensions (Python, themes) | in-progress | built-ins load through the package reader: `easyide.core-snippets`, `easyide.git-commands`, and the declarative productivity packs `easyide.tablet-toolbar`, `easyide.key-rows`, `easyide.toggles`, `easyide.git-extras`, `easyide.project-tasks` (JVM-checked: every command/menu/key/status reference resolves, when-clauses parse, written settings accept their values, shell lines pass `sh -n`); Python pack and themes not yet |
| **M4 language packs + customization UI** | | |
| Additional language servers | not-started | licenses per server to verify |
| Customization UI (hide/reorder contributions, server overrides, custom servers, profiles, safe mode) | not-started | |
| **M5 SDK tooling** | | |
| `easyide-ext` CLI | in-progress | `tools/easyide-ext` (own Gradle build, compiles the shared SDK core): `init` (theme, snippets, language-pack, toolbar-command), `validate` (app pipeline + WASM header + `--strict` readiness), deterministic `package`, `keygen` (PBES2-encrypted Ed25519, `--rotate`), `sign`, `verify`; `--json`, exit codes 0/1/2; 17 JVM tests incl. golden templates, determinism, app/CLI agreement on the built-in packs. Not done: `test`, `dev`, `publish`, `registry build`, lsp-pack/wasm templates; full WASM static check blocked on 0015 split (see its amendment) |
| In-app dev loop (create, install from folder, live reload, log) | not-started | |
| Samples + author docs | not-started | |
| **M6 registry** | | |
| Static signed index repo + browse/install UI | in-progress | building blocks only: RFC 8785 `Jcs` (strict parse: duplicate keys and non-integers refused), `Sig`, `KeyIds`, `SignedBytes`, `SignatureVerifier` behind an `Ed25519` port (`JdkEd25519` for JVM/CLI; Android provider still to verify, registry-and-install.md sec 4.2) in `dev.easyide.extensions.registry`; no index fetch, trust store or UI yet |
| Open VSX secondary source | not-started | |
| **M7 WASM layer** | | |
| Runtime embed, ABI v1, host API, capability enforcement | in-progress | `:ext-wasm` host on Chicory landed in b322368 after the on-device spike passed (ABI v1, capability-gated host functions, metering and limits); this row was not updated then |
| **M8 Node host (conditional)** | | |
| vscode API subset in sandbox | not-started | only if M3+M7 insufficient |
