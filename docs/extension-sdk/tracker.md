# Feature: Extension SDK & Language Intelligence - Tracker

Status legend: not-started / in-progress / done / blocked

Design: [arch.md](arch.md). API reference: [sdk-reference.md](sdk-reference.md). Everything below is PROPOSED as of 2026-09-23.

| Component | Status | Notes |
|---|---|---|
| **Decisions** | | |
| ADR-E SDK shape, manifest, `.easyext` | in-progress | drafted as [0013](../decision/0013-extension-sdk-shape-manifest-easyext.md), Proposed |
| ADR-F WASM logic layer + runtime | in-progress | drafted as [0014](../decision/0014-wasm-logic-layer-chicory.md); ART spike pending |
| ADR-G SDK licensing (Apache-2.0 proposal) | in-progress | drafted as [0015](../decision/0015-extension-sdk-licensing-apache.md); app stays under 0008 |
| ADR-H Registry + signing model | in-progress | drafted as [0016](../decision/0016-extension-registry-static-index-ed25519.md) |
| ADR-I LSP client + server lifecycle | in-progress | drafted as [0017](../decision/0017-lsp-client-hand-rolled-server-lifecycle.md) |
| Design doc set (arch, sdk-reference, HLD, LLDs, threat model, rules, tests) | done | cross-checked for consistency 2026-09-24; map in [arch.md](arch.md#document-map) |
| ux-overhaul ADR-B editor engine | done | [0018](../decision/0018-editor-engine-and-decorations.md) |
| **M0 prerequisites** | | |
| Command registry + keymap | in-progress | see ux-overhaul tracker |
| Settings schema + layering | in-progress | global layer only |
| Editor decoration layers (squiggles, inlay, popups, gutter) | in-progress | PLT-05: model, painters, geometry, `EditorPopup` + JVM tests landed (`workspace/decor/`); inlay = end-of-line ghost text, code lens = gutter glyph (0018); token overlay stays in the highlighter; instrumented screenshot checks not run (no device) |
| **M1 no-server language features** | | |
| language-configuration.json bundled with grammars | done | 57/229 languages from VS Code 1.139.0 (MIT), generic fallback for the rest |
| Brackets, auto-close, surround, comment toggle, indent/onEnter rules | done | basic: auto-close, overtype, surround, enter/indent rules, comment toggle fn, matching bracket; pair colourization not done |
| Snippets + word completion | not-started | |
| **M2 LSP core** | | |
| JSON-RPC transport + path mapping + doc sync | in-progress | `:lsp` core, typed features and `ServerProcessFactory` done with JVM tests; app ports/bridge and on-device proot pipe test not yet |
| Server lifecycle, memory budget, crash backoff | in-progress | `LanguageServerManager` + state machine done with JVM tests; RSS probe (needs server pid discovery on Android) and `onTrimMemory` wiring are app work |
| Python end to end (diagnostics, completion, hover, definition) | not-started | |
| **M3 declarative extensions** | | |
| Manifest loader + contribution registry | in-progress | :extensions core done (schema+validator, parser, semver, 23 contribution stores, when-clauses, context keys, activation, crash journal/safe mode, enablement; 123 tests). App adapters + descriptor cache next |
| Action vocabulary | in-progress | ActionRunner with full vocabulary over HostPort, variables, SHELL/ARGV/PLAIN quoting, capability checks; app HostPort next |
| First-party packs shipped as extensions (Python, themes) | not-started | dogfood |
| **M4 language packs + customization UI** | | |
| Additional language servers | not-started | licenses per server to verify |
| Customization UI (hide/reorder contributions, server overrides, custom servers, profiles, safe mode) | not-started | |
| **M5 SDK tooling** | | |
| `easyide-ext` CLI | not-started | |
| In-app dev loop (create, install from folder, live reload, log) | not-started | |
| Samples + author docs | not-started | |
| **M6 registry** | | |
| Static signed index repo + browse/install UI | not-started | no backend |
| Open VSX secondary source | not-started | |
| **M7 WASM layer** | | |
| Runtime embed, ABI v1, host API, capability enforcement | not-started | |
| **M8 Node host (conditional)** | | |
| vscode API subset in sandbox | not-started | only if M3+M7 insufficient |
