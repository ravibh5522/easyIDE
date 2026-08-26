# Feature: Extensions — Tracker

Status legend: `not-started` / `in-progress` / `done` / `blocked`

Design: [arch.md](arch.md). **Tier 0 highlighting has shipped**; everything else is design-stage.

## Decisions

| # | Decision | Status |
|---|---|---|
| [0009](../decision/0009-extension-platform-tiers.md) | Four-tier extension platform; native declarative contributions before a VS Code extension host | decided |
| [0006](../decision/0006-native-ide-shell-before-theia.md) | Native shell — the reason stages/contributions are ours to define | decided |
| Tier 1 command execution vocabulary | Do declarative commands execute built-in actions, or nothing? | **open — blocks manifest schema** |
| [0010](../decision/0010-textmate-highlighting-bundled.md) | TextMate for highlighting, all grammars bundled, tree-sitter deferred | decided |
| tree-sitter grammars bundled vs downloaded | Moot for now - tree-sitter is deferred entirely by 0010 | resolved |

## Tier 0 — Native language core

| Component | Status | Notes |
|---|---|---|
| Replace regex highlighter | **done** | Shipped as **TextMate**, not tree-sitter - 229 grammars bundled, 1.35 MB in the APK, offline. See [decision 0010](../decision/0010-textmate-highlighting-bundled.md) |
| Expanded editor theme tokens | **done** | `SyntaxColors` defines 21 roles; `ScopeRules` is the single scope->role table. Light and dark sets |
| Grammar asset pipeline | **done** | [tools/build-grammars.py](../../tools/build-grammars.py) - licence-filters tm-grammars, repairs 4 malformed grammars, maps 846 extensions + 239 filenames via Linguist |
| Incremental tokenization | **done** | `DocumentHighlighter` caches per-line end-state + spans. Edits invalidate from the changed line down; opening tokenizes only to the viewport. Same shape as VS Code |
| Move tokenization off the main thread | **done** | `produceState` + `Dispatchers.Default` + 120 ms debounce. Was a **5 s UI freeze** on a 4,200-line file when inline; 0 hang events after |
| tree-sitter for the top ~20 languages | not-started | Deferred. Compiled `.so` per language **per ABI** vs 1.35 MB for all 229 TextMate grammars. Revisit only if lexical highlighting proves insufficient |
| LSP client (JSON-RPC over stdio) | not-started | Reuse `TerminalProcess` streaming plumbing. LSP4J is EPL-2.0 **OR EDL-1.0 (BSD-3)** — usable, but likely heavier than a hand-rolled client |
| LSP lifecycle + memory budget | not-started | One server per language per environment, ref-counted, idle timeout, killed first under pressure. See arch §2.4 |
| Diagnostics / completion / hover / definition UI | not-started | Scope to these plus `documentSymbol` and `formatting`; defer rename, code actions, call hierarchy |
| First end-to-end language | not-started | Python via `jedi-language-server` — small, pure Python, and the Python preset already exists |
| Declarative keybinding table | not-started | Pre-existing ui-shell open item. **Tier 1 has nothing to contribute to until this exists** |

### Defects in the old regex highlighter (all fixed and regression-tested)

| Defect | Location |
|---|---|
| `#` treated as a line comment in every file — breaks `#include`, CSS colours | `SyntaxHighlighter.kt:31` |
| `//` treated as a line comment in every file — breaks Python floor division | `SyntaxHighlighter.kt:31` |
| One keyword set unioned across Kotlin/Python/JS/C applied to all files | `SyntaxHighlighter.kt:22-29` |
| Strings cannot span newlines — Kotlin `"""`, Python `'''` mis-tokenise | `SyntaxHighlighter.kt:32` |
| `'...'` rule breaks Rust lifetimes and char literals | `SyntaxHighlighter.kt:32` |
| Pass 1 `isProtected` resolves to the linear-scan overload, so comments x strings is quadratic — the KDoc claims otherwise | `SyntaxHighlighter.kt:58` -> `:84` |

All six are gone: `SyntaxHighlighter.kt` was deleted. A 7-assertion regression suite
covering the first five runs against the shipped assets - see the chainlog entry for
2026-08-11.

### Known limits of the shipped highlighter

| Limit | Detail |
|---|---|
| 256 KB ceiling | `HIGHLIGHT_MAX_BYTES == TEXT_EDIT_MAX_BYTES` - anything editable is coloured. Never silently partial |
| **Editable surface is not virtualised** | One `BasicTextField` lays out the whole document. **Measured**: an identical 4-word typing burst costs 507 ms at 14 lines and 5,131 ms at 4,203 lines. Cost scales with document length, not edit size, so this - not tokenizing - is now the binding limit. Fixing it is a rewrite of the editing surface |

## Tier 1 — Declarative extension manifest

| Component | Status | Notes |
|---|---|---|
| Manifest schema + parser | not-started | VS Code-shaped `contributes`, plus easyIDE `languageServer` and `sandbox` blocks. Blocked on the command-vocabulary question |
| `StageContribution` registry | not-started | Built-in panes must register through the same path as extension ones, or the extension path stays second-class |
| Native rendering: commands, menus, keybindings | not-started | Contributed command -> real Material 3 control. Extensions supply data only; no pixel values, no non-token colours |
| Native rendering: views + viewsContainers | not-started | Activity-rail entry + panes inside it |
| Native rendering: configuration | not-started | Contributed settings become real Settings rows |
| Snippets, themes, grammars from a pack | not-started | |
| `sandbox.install` runner | not-started | Reuses `SandboxImage.setupCommands` machinery from [0007](../decision/0007-sandbox-image-catalog-and-custom-rootfs.md). Output streamed to the terminal, never silent |
| First-party language packs | not-started | Dogfood the format before opening it to anyone else |
| In-app authoring (scaffold + install from local dir) | not-started | Worth more for adoption than registry publishing |

## Install / update pipeline

| Component | Status | Notes |
|---|---|---|
| Open VSX browse + search | not-started | `https://open-vsx.org/vscode/gallery`. **Microsoft Marketplace is licence-blocked — see arch §2.1** |
| `.vsix` download + SHA-256 + publisher pin | not-started | Registry has been supply-chain attacked twice (2025 takeover vuln, 2026 GlassWorm / 72 extensions) |
| Versioned install dirs + symlink flip + rollback | not-started | Never overwrite in place; interrupted writes are routine on a device |
| Per-environment extension inventory + kill switch | not-started | Extensions belong to an environment, not the app ([0005](../decision/0005-sandbox-environment-sharing-model.md)) |
| Manifest capability disclosure before install | not-started | Disclosure, not enforcement — say which it is |
| Static compatibility audit at install time | not-started | "Uses N APIs easyIDE does not implement", shown before install, not discovered at runtime |
| Offline install from cached `.vsix` | not-started | Offline is the product premise, not a fallback |
| Checksum verification (rootfs tarball **and** `.vsix`) | **blocked/absent** | The app verifies no download today. Pre-existing gap; becomes a supply-chain hole once extensions exist |

## Tier 2 / Tier 3 — deferred

| Component | Status | Notes |
|---|---|---|
| Node extension host in the sandbox | not-started | Deferred until Tier 1 demonstrably is not enough |
| Kotlin main-side RPC for the `vscode` API subset | not-started | `commands`, `window`, `workspace`, `languages`, `TreeDataProvider`, `Uri`, `EventEmitter`, `Disposable` |
| Credential-helper reachability from an extension host | **blocked** | Today the socket is reachable by anything in the sandbox. Must be revisited **before** Tier 2 ships — this is where an extension becomes stolen git credentials |
| `WebviewPanel` host | not-started | `MermaidView` already proves the pattern (bundled asset, `blockNetworkLoads`, height over a bridge) |
| Theia as an alternative main stage | not-started | Only route to genuine API parity. **Never spiked** — see below |

## Verification status

**Tier 0 highlighting is built and verified on the JVM; nothing else is built.**

Verified 2026-08-11 by running the shipped `assets/grammars` through the same tokenizer
the app uses:

| Check | Result |
|---|---|
| All bundled grammars compile | **229 / 229**, 0 failures |
| Regression suite over the old highlighter's defects | **7 / 7 passed** (C `#include`, Python `//` and `#`, CSS `#fff`, C `//`, Kotlin `"""` across lines, Rust lifetimes) |
| Scope families on real snippets | Correct for Python, C, Rust, Kotlin, TypeScript |
| APK cost | 229 grammars, 6.5 MB raw -> **1.35 MB compressed**; APK 21.6 -> 24.2 MB |
| `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL |

**Verified on the Xiaomi Pad 6** (Android 13, arm64, enforcing) on 2026-08-11: Python, C,
Rust, Kotlin, HTML-with-embedded-CSS/JS, and XML all colour correctly; a 4,203-line file
opens with **0 hang events** after the off-thread fix. Device testing caught a 5 s UI freeze
that the JVM tests could not.

Still unmeasured: memory under sustained editing, and how the 21 roles look in each of the
six themes (only the dark set has been seen).

Every memory figure in [arch.md](arch.md) is still upstream reporting, not measurement.

Unverified assumptions that would change the design if wrong:

| Assumption | Risk if wrong |
|---|---|
| Theia runs acceptably in an Android WebView | Assumed since [0001](../decision/0001-ide-foundation-theia.md), never tested. Tier 3 and all "full VS Code parity" claims depend on it |
| A language server fits in the memory budget on a real tablet alongside the app, WebView and sandbox | Tier 0's LSP half becomes unusable; tree-sitter half still stands |
| tree-sitter incremental parse keeps up with typing on a large file in Compose | Highlighting falls back to viewport-only parsing |
| proot's ptrace overhead does not make server startup intolerable | Servers move to chroot-only, or to a much smaller set |

None of these can be settled from a desk. Each needs a spike on the Xiaomi Pad 6.
