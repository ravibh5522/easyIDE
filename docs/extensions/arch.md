# Feature: Extensions — Architecture

## Overview

The ask: real syntax highlighting, code hints, completion and shortcuts; language packs
that install their own toolchain into the sandbox and contribute their own UI; extensions
that behave "exactly like VS Code extensions"; and eventually users authoring their own.

This document is the audit of how to get there. Its central claim is that **this is not one
system but four**, with different costs, different risks, and different answers to "which
framework does an extension author use". Treating them as one project is how it fails.

The trigger was the editor's current highlighter
([`SyntaxHighlighter.kt`](../../services/mobile/app/src/main/java/dev/easyide/app/ui/screens/workspace/SyntaxHighlighter.kt)):
101 lines, four regexes, and a single flat keyword set unioned across Kotlin, Python, JS
and C, applied to every file regardless of extension. It colours `#include` as a comment in
C, greys the rest of the line after Python's `//` operator, and has no notion of types,
functions or tags. It was always a placeholder. Tier 0 below replaces it.

## Decisions this depends on

| ADR | Bearing on this design |
|---|---|
| [0001](../decision/0001-ide-foundation-theia.md) | Theia is the eventual language/extension layer; EPL-2.0 file-level copyleft only binds modified Theia files |
| [0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md) | proot is not a security boundary — this constrains the whole extension trust model |
| [0003](../decision/0003-multi-stage-panels-theia-widgets.md) | Stages were to be Theia dock areas hosting `WidgetFactory` widgets |
| [0006](../decision/0006-native-ide-shell-before-theia.md) | Superseded 0003 in practice: the shell is native Compose now, so "stages" are ours to define |
| [0008](../decision/0008-noncommercial-source-available-licensing.md) | easyIDE is sold commercially — this rules out some dependencies outright |
| **0009** (to write) | Which extension host we build, and in what order |

0006 is the load-bearing one. Once the shell stopped being Theia, "extension" stopped
meaning "Theia widget" and became an open question. This doc answers it.

---

## 1. What "100% like a VS Code extension" actually requires

A VS Code extension is a Node.js program. It never touches the UI directly. It runs in an
**extension host** process and talks to the editor over RPC; the editor implements the
*main side* of that protocol and does all rendering. Extensions declare
[contribution points](https://code.visualstudio.com/api/references/contribution-points) in
`package.json` and activate lazily on activation events.

The consequence that decides this design: **whoever renders the UI must implement the main
side of that RPC.** VS Code implements it in TypeScript against the DOM. Theia reimplements
it in `@theia/plugin-ext`. If easyIDE renders natively in Compose, easyIDE implements it in
Kotlin — there is no third option and no library to borrow.

That surface is not uniform. Extensions contribute UI three different ways, and they cost
wildly different amounts to support:

| How an extension contributes UI | Examples | What it takes to support natively | Can it look identical? |
|---|---|---|---|
| **Declarative JSON** in `contributes` | `commands`, `menus`, `keybindings`, `languages`, `grammars`, `snippets`, `themes`, `configuration`, `views`, `viewsContainers`, `icons`, `problemMatchers` | Parse JSON, render with our own Compose components. No JS runs at all. | **Better than identical** — it is native, so it gets tablet touch targets and Material 3 for free |
| **Programmatic API**, data-only | `TreeDataProvider`, `StatusBarItem`, `QuickPick`, `InputBox`, `Diagnostic`, `CodeLens`, `DecorationType` | Implement those namespaces' main side in Kotlin. Data crosses RPC; we draw it. | Yes, functionally. Visually native. |
| **Webview** | `createWebviewPanel`, `WebviewViewProvider`, custom editors, most "rich" extensions | The extension ships its own HTML/CSS/JS. Nothing to reimplement — you host it. | **Only** in a WebView. Pixel-identical by construction, impossible otherwise. |

So "100% like VS Code" resolves cleanly once split: declarative and data-only contributions
render **natively and should**; webview extensions render **in a WebView and must**. Any
attempt to natively re-render an extension's own HTML is a project with no end.

**Theia's own coverage is the realistic ceiling for anyone reimplementing this.** Theia has
been at it since 2018 with a funded team, targets VS Code API 1.108 as of the 2026-02
release, and its
[daily comparator](https://eclipse-theia.github.io/vscode-theia-comparator/status.html)
still lists partial and stubbed APIs. A Kotlin main-side written from scratch will not
reach parity, and the design below never promises it.

---

## 2. Constraints that are not negotiable

These were verified from primary sources during this audit. Each one kills at least one
otherwise-obvious design.

### 2.1 The Microsoft Marketplace is legally unavailable

The Visual Studio Marketplace Terms of Use restrict Marketplace Offerings to **In-Scope
Products and Services** — Visual Studio Code, GitHub Codespaces, Azure DevOps, Azure DevOps
Server and successors — and explicitly forbid installing, importing or using them in
anything else. easyIDE is not in scope. Pointing easyIDE at
`marketplace.visualstudio.com` would be a licence violation, not a grey area.

**Therefore: [Open VSX](https://open-vsx.org) is the registry.** Eclipse Foundation,
EPL-2.0, ~10,000 extensions, the default for Theia and every other VS Code-API editor.
API surface we need:

```
search / metadata   GET https://open-vsx.org/vscode/gallery
detail page         GET https://open-vsx.org/vscode/item
file download       GET https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}
```

Second-order consequence: **Microsoft's own extensions are not on Open VSX and cannot be
used.** C/C++, C#, Pylance, the Remote-\* pack and the Python debugger are licensed for use
only in Microsoft products. Every language pack easyIDE ships must be built on an
open-source server — `clangd`, `OmniSharp`, `jedi-language-server` or `basedpyright`,
`gopls`, `rust-analyzer`, `typescript-language-server`. This is a product constraint, not
just a legal one: users will ask why Python "isn't as good as VS Code", and the answer is
Pylance.

### 2.2 Open VSX has been supply-chain attacked, twice

A 2025 vulnerability exposed publisher repositories to takeover. In 2026 the **GlassWorm**
campaign distributed malware through 72 extensions on the registry. Eclipse has since added
publisher verification and scanning.

This matters more for easyIDE than for VS Code, because of the next constraint.

### 2.3 There is no per-extension isolation available to us

proot is a `ptrace` path rewriter, not a sandbox
([0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md)). Everything inside
a sandbox runs as the same Android UID with the same permissions. An extension host running
in the sandbox can read every file in every project bound into that environment, reach the
network, and talk to the credential-helper socket.

**An installed extension is therefore as trusted as code the user runs in the terminal.**
That is the honest framing and it must appear in the UI, not just here. Section 8 covers
what mitigation is actually possible.

### 2.4 Memory is the binding constraint, not API compatibility

This is the finding that most changes the plan. Real-world language server footprints:

| Server | Typical RSS |
|---|---|
| rust-analyzer | 1–4 GB on a normal workspace |
| Pylance | up to ~4 GB (and unavailable to us anyway) |
| typescript-language-server | 1–2 GB on a large project |
| gopls | notably heavier than the Go toolchain itself |
| clangd, jedi-language-server, lua-ls | 100–500 MB |

Against that: an 8 GB tablet, Android's low-memory killer, a foreground Service that OEM
battery managers already fight, a Chromium WebView process that can be reclaimed
independently, and proot's 10–30% syscall overhead on top. Community reports of running
code-server under Termux converge on "8 GB RAM minimum, and most extensions can't be
installed anyway."

Design consequences, all of them non-optional:

- **A language server is a heavyweight, explicitly-managed resource**, like a debugger
  session — not something started silently on file open.
- **One server per language per environment, ref-counted across projects**, idle-timed out,
  killed first under memory pressure.
- **Highlighting must never depend on a server.** It has to work with the server dead,
  starting, or never installed. That is what makes tree-sitter Tier 0 rather than an
  optimisation.
- The workspace must degrade in a defined order under pressure: servers → extension host →
  webviews → editor tabs. The eviction thresholds belong in one declarative table, profiled
  on device, not guessed.

### 2.5 Dependency licensing

Per [0008](../decision/0008-noncommercial-source-available-licensing.md), easyIDE is sold
commercially, so a copyleft UI dependency is a real problem.

| Candidate | License | Verdict |
|---|---|---|
| [KTreeSitter](https://tree-sitter.github.io/kotlin-tree-sitter/) 0.25.1 (official `tree-sitter` org, KMP, Android target) | **MIT** | Safe. Preferred parser binding. |
| tree-sitter core + grammars | MIT (grammars vary — check per grammar) | Safe, but verify each grammar individually |
| [sora-editor](https://github.com/Rosemoe/sora-editor) (Android code-editor view, TextMate + tree-sitter + `editor-lsp`) | **LGPL-2.1** | **Avoid.** LGPL relinking obligations in an APK are awkward and conflict with selling a commercial licence. Excellent prior art to read; not something to link. |
| [LSP4J](https://github.com/eclipse-lsp4j/lsp4j) | EPL-2.0 **OR** EDL-1.0 (BSD-3-Clause) | Usable under EDL-1.0. Heavy for Android; a small hand-rolled JSON-RPC client may be the better call. |
| Open VSX registry software | EPL-2.0 | Only relevant if we self-host a mirror |

Every one of these must be re-verified from the upstream repo before adoption, per the
standing rule in [.claude/CLAUDE.md](../../.claude/CLAUDE.md), and recorded in
[NOTICE.md](../../NOTICE.md).

---

## 3. Architecture: four tiers

Each tier is independently shippable and useful on its own. Each one is a strictly larger
commitment than the last. **Tier 0 and Tier 1 together deliver almost everything the
request actually described**, without running a single line of extension JavaScript.

```
Tier 3  Webview host + Theia         "the long tail, pixel-identical"
        ---------------------------------------------------------
Tier 2  Node extension host in the   "real .vsix from Open VSX"
        sandbox + Kotlin RPC main
        ---------------------------------------------------------
Tier 1  Declarative extension        "custom buttons, options, views,
        manifest, rendered natively   stages, keybindings, snippets,
        (no JS at all)                themes, grammars"
        ---------------------------------------------------------
Tier 0  tree-sitter + LSP client     "real highlighting, hints,
        native in Kotlin              completion, go-to-definition"
```

### Tier 0 — Native language core

Replaces the regex highlighter and delivers the four things named in the request
(highlighting, hints, completion, shortcuts) with no extension system at all.

**Highlighting: tree-sitter, in-process.** KTreeSitter (MIT) parses incrementally on every
keystroke and yields a real syntax tree; a `highlights.scm` query per grammar maps nodes to
theme token roles. This is what Neovim, Zed and GitHub use. It is correct by construction —
`#include` is a preprocessor directive because the C grammar says so, not because a regex
guessed.

Cost to be honest about: grammars are compiled native code, roughly 0.5–1.5 MB per language
per ABI, and easyIDE ships two ABIs. Eight languages is a realistic APK increase of several
MB. Grammars beyond a small built-in set should therefore be **downloaded on demand into
the environment**, not bundled — which is exactly the Tier 1 language-pack mechanism.

The theme needs more token roles than the four it has today
([`EditorColors.kt`](../../services/mobile/app/src/main/java/dev/easyide/app/ui/theme/EditorColors.kt)
currently defines `keyword`, `string`, `comment`, `number`). Add at minimum: type,
function, variable, parameter, property, operator, punctuation, tag, attribute, constant,
namespace. These are theme tokens in the design system, never inlined — see
[design-system/arch.md](../design-system/arch.md).

**Hints, completion, navigation: an LSP client in Kotlin.** Servers are ordinary processes
and easyIDE already has the one hard prerequisite — a Linux sandbox that can run them
([sandbox-runtime](../sandbox-runtime/arch.md)). Reuse `TerminalProcess`'s streaming
stdin/stdout plumbing; frame JSON-RPC over it.

```
Compose editor
   |  buffer edits, cursor
   v
LspClient (Kotlin)  --- JSON-RPC over stdio --->  SandboxShell
   ^                                                  |
   |  diagnostics, completion, hover, definition       v
   +-------------------------------------------  language server
                                                  (inside proot)
```

Scope Tier 0 to the requests that pay for themselves on a tablet: `initialize`,
`didOpen`/`didChange`/`didSave`, `publishDiagnostics`, `completion`, `hover`,
`definition`, `documentSymbol`, `formatting`. Defer rename, call hierarchy, code actions.

**Shortcuts** are already an open item in [ui-shell](../ui-shell/arch.md) — a single
declarative keybinding table feeding both the accessory bar and physical-keyboard
bindings. Tier 1 lets extensions add rows to that table; the table has to exist first.

Tier 0 ships with **zero** extension infrastructure. It should not wait for any of it.

### Tier 1 — Declarative extension manifest, rendered natively

An easyIDE extension at this tier is a **zip with a JSON manifest and data files. No
executable code.** The manifest is deliberately VS Code-shaped, so authors already know it
and so a real `.vsix` can later be consumed by reading the subset it uses.

```jsonc
{
  "name": "python",
  "displayName": "Python",
  "version": "1.2.0",
  "publisher": "easyide",
  "engines": { "easyide": "^0.2.0" },

  "contributes": {
    "languages":   [{ "id": "python", "extensions": [".py"], "comments": { "line": "#" } }],
    "grammars":    [{ "language": "python", "treeSitter": "python.so",
                      "queries": "highlights.scm" }],
    "snippets":    [{ "language": "python", "path": "snippets.json" }],
    "themes":      [{ "label": "Python Dark", "path": "theme.json" }],
    "keybindings": [{ "key": "ctrl+shift+r", "command": "python.runFile",
                      "when": "editorLangId == python" }],
    "commands":    [{ "command": "python.runFile", "title": "Run File",
                      "icon": "play_arrow" }],
    "menus":       { "editor/title": [{ "command": "python.runFile", "group": "navigation" }] },
    "configuration": [{ "title": "Python",
                        "properties": { "python.formatter": { "type": "string",
                                        "enum": ["black", "ruff"], "default": "ruff" } } }],
    "views":       { "explorer": [{ "id": "python.venvs", "name": "Environments" }] },
    "viewsContainers": { "activitybar": [{ "id": "python", "title": "Python",
                                           "icon": "code" }] }
  },

  // easyIDE-specific. This is the "custom sandbox installation" piece.
  "languageServer": {
    "command": ["jedi-language-server"],
    "languages": ["python"],
    "memoryBudgetMb": 400
  },
  "sandbox": {
    "requires": ["python3"],
    "install": ["apt-get install -y python3-pip",
                "pip install jedi-language-server ruff"],
    "verify": "jedi-language-server --version"
  }
}
```

Everything in `contributes` is data. easyIDE renders it with its own Compose components:
a contributed command becomes a real Material 3 button in the editor title bar, a
contributed view becomes a native list in a stage, contributed configuration becomes real
rows in Settings, a contributed keybinding becomes a row in the keybinding table and a key
on the accessory bar. **On a tablet this is better than VS Code's own rendering**, because
the touch targets and theming are native rather than a desktop layout scaled down.

`sandbox.install` reuses the machinery that already exists — `SandboxImage.setupCommands`
already runs install-time commands with output streamed to the terminal
([0007](../decision/0007-sandbox-image-catalog-and-custom-rootfs.md)). A language pack is
the same idea addressed to an existing environment instead of a fresh one.

**Command execution at Tier 1**: a contributed command that has no JS behind it must still
do something. Two honest options — either commands map to a small fixed vocabulary of
built-in actions (`runInTerminal`, `openFile`, `applyEdit`, `setConfig`, `lspRequest`), or
Tier 1 commands are declarative-only and anything else waits for Tier 2. The first keeps
the tier genuinely useful; it is the recommended path and it is an open question below.

### Tier 2 — Real extension host

Only at this tier does JavaScript run. Node.js goes into the sandbox (the Node preset
already exists), the standard VS Code extension host protocol is spoken, and a **Kotlin
main-side** implements a defined subset of the `vscode` API namespaces.

```
Android app process                 |   proot sandbox
                                    |
Compose UI                          |
   ^                                |
   |  render                        |
ExtensionHostBridge (Kotlin)        |
   |  JSON-RPC over a unix socket   |
   +--------------------------------+---> node extension-host.js
                                    |        |
                                    |        +-- extension A (activated lazily)
                                    |        +-- extension B
```

Supported subset, in priority order: `commands`, `window` (messages, status bar, quick
pick, input box), `workspace` (fs, config, events), `languages` (diagnostics, providers),
`TreeDataProvider`, `Uri`, `EventEmitter`, `Disposable`. Explicitly out of scope until
Tier 3: `WebviewPanel`, custom editors, notebooks, debug adapters, the chat/LM APIs.

An extension declaring an unsupported API must **fail loudly at install time** by manifest
inspection, not mysteriously at runtime. A compatibility report per extension —
"uses 3 APIs easyIDE does not implement" — is the difference between a usable platform and
a bug tracker full of "extension X doesn't work".

The extension host is also the second-largest memory consumer after language servers, and
it is a full trust hole per §2.3. Both argue for it being off by default and per-environment.

### Tier 3 — Webviews and Theia

`WebviewPanel` is a WebView showing extension-authored HTML. The pattern is already proven
in-app by `MermaidView` (bundled JS asset, `blockNetworkLoads`, height reported back over a
bridge). A webview panel is that, hosted in a stage, with the VS Code webview messaging API
bridged.

Beyond that lies full Theia in a WebView — the original [0001](../decision/0001-ide-foundation-theia.md)
plan, and still the only route to genuine API parity. It remains the documented long-term
language layer. It should be evaluated **as an alternative main stage**, not as a
replacement for the native shell, because [0006](../decision/0006-native-ide-shell-before-theia.md)
established that native is also the offline and low-memory path.

---

## 4. Stages and UI contributions

[0006](../decision/0006-native-ide-shell-before-theia.md) means stages are no longer Theia
dock areas — they are ours, and they need a registry with the same property 0003 wanted:
no special-casing per content type.

```kotlin
// One declarative registry. A contribution is data; the renderer is ours.
data class StageContribution(
    val id: String,
    val title: String,
    val icon: IconToken,
    val defaultStage: Stage,          // Left / Main / Right / Bottom
    val source: ContributionSource,   // BuiltIn | Extension(id)
    val content: StageContent,        // TreeView | ListView | Editor | Webview | Custom
)
```

- **Built-in panes** (explorer, editor, terminal) register through the same path as
  extension-contributed ones. If they do not, the extension path will be second-class and
  will stay broken.
- A `viewsContainers` contribution adds an activity-rail entry; `views` adds panes inside
  it. Both are pure data.
- **Custom stages** — a fifth area, or a user-defined split — are a layout concern, not an
  extension concern. Extensions should target *named* areas and let layout be user
  preference, otherwise every extension fights for screen space on a 10-inch display.
- Contributed UI must be **cancellable and reclaimable**: a stage backed by a dead
  extension shows an inline error and a retry, never a blank pane.

Rendering rule, stated once: contributions describe *what*, easyIDE decides *how*. An
extension cannot ship a Compose layout, cannot set pixel values, and cannot use a colour
outside the theme tokens. That is what keeps a tablet IDE coherent when a hundred
desktop-authored extensions are installed, and it is the direct application of the
no-hardcoding rule in [.claude/CLAUDE.md](../../.claude/CLAUDE.md).

---

## 5. Install, update and patch

```
 browse (Open VSX /vscode/gallery, filtered to what easyIDE can run)
   -> resolve version against engines.easyide + declared API subset
   -> download .vsix over TLS  (currently no checksum verification anywhere in the
                                app — see sandbox-runtime tracker; fix this first)
   -> verify: publisher pin + SHA-256 against registry metadata
   -> unpack to <env>/extensions/<publisher>.<name>-<version>/
   -> static manifest audit: unsupported APIs? sandbox.install commands? -> show the user
   -> run sandbox.install with output streamed to the terminal (never silently)
   -> verify command must exit 0, else roll back the whole directory
   -> activate; register contributions
```

- **Versioned directories, never in-place overwrite.** Update = install alongside, flip a
  symlink, keep the previous version until the new one activates cleanly. This is the only
  affordable rollback on a device where an interrupted write is routine.
- **Extensions are per-environment, not global.** Environments are shareable and projects
  bind into them ([0005](../decision/0005-sandbox-environment-sharing-model.md)); a Python
  pack belongs to the environment that has Python in it. A global install would break the
  moment two projects need different toolchains.
- **No silent auto-update.** Given §2.2, an update is code execution the user did not ask
  for. Notify, show a diff of what changed, require a tap.
- **Offline is a first-class state.** The whole product premise is on-device and offline;
  a cached `.vsix` must install with no network, and the UI must say what is stale rather
  than failing.
- Uninstall must remove the extension directory *and* offer to undo `sandbox.install`,
  which it cannot do reliably — so record what was installed and be honest that removing an
  apt package is best-effort.

---

## 6. What framework does an extension author use

| Tier | Author writes | Runs where | Skills needed |
|---|---|---|---|
| 1 | JSON + tree-sitter queries + snippet/theme files | Nothing executes | None beyond JSON |
| 2 | TypeScript/JavaScript against the `vscode` API subset | Node, in the sandbox | Standard VS Code extension development |
| 3 | HTML/CSS/JS for webview panels | WebView | Web front-end |

For user-authored extensions the answer is therefore **Tier 1 for most people, Tier 2 for
developers**. A user adding syntax support for a niche language, a snippet set, a theme or
a keybinding pack never writes code. That is the case worth optimising for, and it is
reachable long before Tier 2 exists.

An in-app authoring path — scaffold a manifest, edit it in easyIDE, install it from a local
directory — is worth more to adoption than registry publishing, and costs almost nothing
once Tier 1 exists.

---

## 7. Compatibility: what we will and will not claim

| Extension category | Share of the ecosystem | Tier | Works? |
|---|---|---|---|
| Themes, icon themes | large | 1 | Yes |
| Snippets | large | 1 | Yes |
| Grammars / basic language support | large | 1 | Yes, via tree-sitter rather than TextMate |
| Language servers (LSP client wrappers) | large | 0/1 | Yes — the server is the real work, and it is a process |
| Formatters, linters | large | 1/2 | Yes, mostly as sandbox tools |
| Tree-view / status-bar / command extensions | medium | 2 | Yes, within the subset |
| Webview-based (preview, charts, GUI panels) | medium | 3 | Yes, in a WebView |
| Debuggers (DAP) | medium | later | Not planned |
| Notebooks | medium | later | Not planned |
| Microsoft-published (C/C++, C#, Pylance, Remote-\*) | large | **never** | Licence-blocked, §2.1 |

Marketing language must match this table. "Compatible with VS Code extensions" without
qualification will be read as "all of them" and will be wrong.

---

## 8. Security model

Stated plainly because §2.3 leaves no room for a comfortable version: **installing an
extension is equivalent to running its code in your terminal.** There is no per-extension
sandbox and proot cannot provide one.

What is actually achievable:

- **Manifest-declared capabilities**, shown before install: does it run sandbox commands,
  reach the network, read outside the project, spawn a server? Not enforced by a boundary —
  enforced by *disclosure*, which is a real mitigation for the accidental case and no
  mitigation at all for the malicious one. Say which.
- **Publisher pinning and SHA-256 verification** against registry metadata on every
  download, and a hard failure on mismatch.
- **No auto-update, no auto-activation of newly installed extensions.**
- **A visible extension inventory per environment**, with an obvious kill switch.
- **The credential-helper socket must not be reachable from an extension host.** Today it
  is a unix socket inside the rootfs, reachable by anything in the sandbox
  ([sandbox-runtime arch §2](../sandbox-runtime/arch.md#credential-helper-protocol)). If
  Tier 2 ships, this needs revisiting — it is the one place where an extension turns into
  stolen git credentials.

Do not describe any of this as isolation. The standing rule in
[.claude/CLAUDE.md](../../.claude/CLAUDE.md) applies to extension UI copy exactly as it
applies to sandbox copy.

---

## 9. Recommended sequencing

1. **Tier 0 highlighting** — tree-sitter, ~6 grammars, expanded theme tokens. Fixes the
   complaint that started this. No extension system required.
2. **Keybinding table** — the ui-shell open item. Tier 1 needs something to contribute to.
3. **Tier 0 LSP client** — one language end to end (Python via `jedi-language-server` is
   the cheapest real test; it is small, pure Python, and the Python preset already exists).
4. **Tier 1 manifest + native contribution rendering**, with easyIDE's own first-party
   language packs as the first consumers. Dogfood the format before opening it.
5. **Open VSX browse/install** for the subset Tier 1 can honestly run.
6. **Tier 2** only if Tier 1 demonstrably is not enough. It is the largest, riskiest and
   least reversible piece, and it is the one that reopens the credential question.

Steps 1–3 are the ones that answer "syntax view is not good". Steps 4–5 answer "language
pack extensions". Step 6 answers "exactly like VS Code" and should be deferred until
someone has hit a wall that only it can remove.

---

## Open questions

1. **Do Tier 1 commands execute anything?** A fixed vocabulary of built-in actions
   (`runInTerminal`, `openFile`, `applyEdit`, `lspRequest`) makes Tier 1 genuinely useful
   and delays Tier 2 substantially. It is also a small API that we will be stuck with.
   Needs deciding before the manifest schema is frozen.
2. **tree-sitter grammars: bundled or downloaded?** Bundling is simple and offline; each
   grammar is ~1 MB × 2 ABIs. Downloading means loading a `.so` at runtime into the app
   process — which is arbitrary native code with no verification story, and materially
   worse than running it in the sandbox. Leaning bundled for a built-in set, sandbox-side
   parsing for the rest. Unresolved.
3. **TextMate grammars as a fallback?** There are far more TextMate grammars than
   tree-sitter ones, and `.vsix` language packs ship TextMate. Supporting both doubles the
   highlighting code. Probably worth it for coverage; needs a spike.
4. **Where does the LSP client live** — app process or sandbox? In-app means simpler
   lifecycle and no extra process; in-sandbox means it dies with the sandbox and survives
   app restarts differently. Interacts with the rebuild flow in
   [sandbox-runtime §4](../sandbox-runtime/arch.md#4-session-management).
5. **Memory-pressure eviction thresholds** must be profiled on the Xiaomi Pad 6, not
   guessed, and must live in one declarative table.
6. **Does Theia actually run acceptably in an Android WebView?** Assumed since
   [0001](../decision/0001-ide-foundation-theia.md); never tested. Tier 3 depends on it and
   nobody has spiked it.
7. **Rootfs and `.vsix` downloads still have no checksum verification** anywhere in the
   app. That is a gap today, and it becomes a supply-chain hole the moment extensions exist.
