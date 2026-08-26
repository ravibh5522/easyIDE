# 0009 - Extension platform: native declarative contributions before a VS Code extension host

Status: Accepted

## Context

The product goal is language packs that behave like VS Code extensions — real highlighting,
hints, completion, shortcuts, their own toolchain installed into the sandbox, their own
buttons and panels — with users eventually authoring their own. The obvious reading is
"run VS Code extensions", and [0001](0001-ide-foundation-theia.md) already nominated Theia
as the way to get that.

Auditing it (full analysis in [extensions/arch.md](../extensions/arch.md)) turned up four
facts that make the obvious reading wrong as a *starting point*:

1. **A VS Code extension never draws anything.** It is a Node process talking RPC to an
   editor that implements the *main side* of that protocol and does all rendering. Whoever
   renders natively must write that main side themselves. Theia has been doing exactly this
   since 2018 with a funded team, tracks VS Code API 1.108 as of its 2026-02 release, and
   its daily comparator still lists partial and stubbed APIs. A Kotlin reimplementation
   will not reach parity.
2. **The Microsoft Marketplace is legally unavailable.** Its Terms of Use restrict
   Marketplace Offerings to "In-Scope Products and Services" — VS Code, GitHub Codespaces,
   Azure DevOps and successors — and forbid importing or using them elsewhere. Open VSX is
   the only lawful registry, and Microsoft's own extensions (C/C++, C#, Pylance, Remote-\*)
   are therefore permanently unavailable regardless of what we build.
3. **Memory is the binding constraint, not API compatibility.** rust-analyzer runs 1-4 GB,
   Pylance up to ~4 GB, typescript-language-server 1-2 GB. Against that: an 8 GB tablet,
   Android's low-memory killer, OEM battery managers that already fight our foreground
   Service, a separately-reclaimable WebView process, and proot's 10-30% syscall overhead.
   An extension host is *additional* pressure on a budget that language servers already
   blow.
4. **Most of what was actually asked for needs no JavaScript at all.** Highlighting,
   snippets, themes, keybindings, contributed buttons and panels, settings, and installing
   a toolchain into the sandbox are all *declarative data*. The parts of the VS Code
   ecosystem that genuinely need an extension host are a minority, and the parts that need
   a webview cannot be rendered natively by anyone, ever.

## Decision

Build the extension platform in **four tiers**, each independently shippable, in this order:

- **Tier 0 — native language core.** tree-sitter (KTreeSitter, MIT) for highlighting,
  in-process and incremental; a Kotlin LSP client speaking JSON-RPC to servers running in
  the existing proot sandbox. No extension system involved. This is what actually replaces
  the regex highlighter.
- **Tier 1 — declarative extension manifest, rendered natively.** An extension is a zip
  with a JSON manifest and data files; **no executable code**. The manifest is deliberately
  VS Code-shaped (`contributes`) plus easyIDE-specific `languageServer` and `sandbox`
  blocks. easyIDE renders every contribution with its own Compose components.
- **Tier 2 — real extension host.** Node in the sandbox, standard extension-host protocol,
  a Kotlin main-side implementing a *defined subset* of the `vscode` API. Deferred until
  Tier 1 is demonstrably not enough.
- **Tier 3 — webviews, then Theia.** `WebviewPanel` for extension-authored HTML, and
  eventually Theia as an alternative main stage for genuine parity.

Two rules bind all tiers:

- **Contributions describe *what*; easyIDE decides *how*.** An extension cannot ship a
  layout, set pixel values, or use a colour outside the theme tokens.
- **Compatibility is stated per category, never as "VS Code compatible" unqualified.**

## Alternatives considered

- **Theia in a WebView first, as [0001](0001-ide-foundation-theia.md) planned.** The only
  route to real API parity and it needs no protocol work from us. Rejected as a *starting*
  point, not on merit: it front-loads the heaviest dependency (Node userland, extension
  host, Chromium frontend) onto the tightest constraint (memory), it has **never been
  spiked on an Android WebView**, and it repeats exactly the sequencing mistake
  [0006](0006-native-ide-shell-before-theia.md) was written to correct — nothing works
  until everything works. It stays the Tier 3 target.
- **Build the extension host first (Tier 2 before Tier 1).** Rejected: it is the largest,
  least reversible piece, it is the one that turns the credential-helper socket into a
  credential-theft path, and it delivers nothing the user asked for that Tier 1 does not
  deliver sooner and cheaper.
- **A purely native extension API of our own design, no VS Code shape at all.** Simpler and
  fully under our control. Rejected because the VS Code manifest shape is free
  compatibility: authors already know it, existing `.vsix` files can later be read by
  consuming the subset they use, and inventing a parallel vocabulary throws that away for
  nothing.
- **Adopt [sora-editor](https://github.com/Rosemoe/sora-editor)** — a mature Android code
  editor with TextMate, tree-sitter and an LSP module, which would shortcut most of Tier 0.
  Rejected on licensing: it is **LGPL-2.1**, and LGPL relinking obligations inside an APK
  sit badly with selling a commercial licence under
  [0008](0008-noncommercial-source-available-licensing.md). Worth reading as prior art;
  not worth linking.
- **Keep the regex highlighter and wait for LSP semantic tokens.** Rejected: semantic
  tokens require a running language server, and highlighting must work when the server is
  dead, starting, or was never installed. That is precisely why tree-sitter is Tier 0 and
  not an optimisation.

## Consequences

- **The complaint that started this gets fixed first and alone.** Tier 0 ships real
  highlighting without any extension infrastructure existing.
- **"VS Code compatible" becomes a table, not a slogan.** Themes, snippets, grammars,
  LSP-wrapper and formatter extensions work early. Tree-view/status-bar extensions wait for
  Tier 2. Webview extensions wait for Tier 3. Debuggers and notebooks are not planned.
  Microsoft-published extensions never work. Marketing copy must match this or it is false
  advertising.
- **Users author extensions in JSON, not TypeScript**, for the common cases — a new
  language, a snippet set, a theme, a keybinding pack. That is a much larger addressable
  group than "people who can write a VS Code extension", and it arrives far sooner.
- **The keybinding table becomes a blocker, not a nice-to-have.** Tier 1 contributions need
  something to contribute to.
- **Extensions are per-environment, not per-app**, following
  [0005](0005-sandbox-environment-sharing-model.md). A global install breaks the moment two
  projects need different toolchains.
- **We take on a security position we cannot fully mitigate.** proot gives no per-extension
  isolation ([0002](0002-sandbox-backend-proot-default-chroot-optin.md)), so installing an
  extension is equivalent to running its code in the terminal. Open VSX has been
  supply-chain attacked twice (2025 repository-takeover vulnerability; 2026 GlassWorm,
  72 extensions). What we can offer is publisher pinning, SHA-256 verification, capability
  disclosure before install, no auto-update, and a per-environment kill switch — all of
  which are **disclosure and hygiene, not a boundary**, and must be described as such.
- **The credential-helper socket must be revisited before Tier 2 ships.** It currently sits
  in the rootfs reachable by anything in the sandbox. That is acceptable while the sandbox
  runs only what the user typed; it is not acceptable once it runs downloaded extensions.
- **Checksum verification stops being optional.** The app verifies no download today,
  including the rootfs tarball. That is a pre-existing gap that becomes a supply-chain hole
  the moment extensions exist.
- **Two grammar systems may end up coexisting.** `.vsix` language packs ship TextMate
  grammars; tree-sitter is the better engine. Supporting both doubles the highlighting code
  and is still probably correct for coverage. Left open deliberately.
- **This ADR is built on desk research, not measurement.** No figure in it was produced by
  running anything on the Xiaomi Pad 6. The Theia-in-WebView assumption in particular has
  never been tested, and Tier 3 rests entirely on it.
