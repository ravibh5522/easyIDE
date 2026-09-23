# Decisions

Lightweight ADRs (Architecture Decision Records) for major, hard-to-reverse choices - the kind where a future reader needs to know *why*, not just *what*, before proposing to change it.

**When to add one**: choice of a core dependency/framework, a security or isolation tradeoff, anything that would be expensive to reverse later, or any decision that was seriously contested before landing on an answer.

**When not to**: routine implementation choices, anything already obvious from reading the code, anything easily reversible.

**Naming**: `NNNN-kebab-case-title.md`, sequential, never reused even if a decision is later superseded (mark it `Status: Superseded by 00XX` instead of deleting).

**Template**: [0000-template.md](0000-template.md)

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-ide-foundation-theia.md) | IDE foundation: Theia over native editor / code-server / Spyder | Accepted |
| [0002](0002-sandbox-backend-proot-default-chroot-optin.md) | Sandbox backend: proot default, chroot+BusyBox opt-in for rooted devices | Accepted |
| [0003](0003-multi-stage-panels-theia-widgets.md) | Multi-stage panel system built on Theia's ApplicationShell + WidgetFactory | Accepted |
| [0004](0004-material3-design-system.md) | Material 3 as the native Compose design system foundation | Accepted |
| [0005](0005-sandbox-environment-sharing-model.md) | Sandbox environments are shareable; projects bind into them | Accepted |
| [0006](0006-native-ide-shell-before-theia.md) | Native IDE shell now; Theia becomes the language layer later (partially supersedes 0003) | Accepted |
| [0008](0008-noncommercial-source-available-licensing.md) | Source-available licensing: PolyForm Noncommercial + paid commercial license | Accepted |
| [0009](0009-extension-platform-tiers.md) | Extension platform: native declarative contributions before a VS Code extension host | Accepted |
| [0010](0010-textmate-highlighting-bundled.md) | Syntax highlighting: TextMate grammars, all bundled, tree-sitter deferred | Accepted |
| [0011](0011-jgit-for-object-model-sandbox-git-for-network.md) | Git: JGit for the object model, sandbox `git` for the network | Accepted |
| [0012](0012-git-token-in-process-env-not-credential-socket.md) | Git tokens travel in the git process's environment, not a credential socket | Accepted |
| [0013](0013-extension-sdk-shape-manifest-easyext.md) | Extension SDK shape: VS Code-shaped manifest, `.easyext` zip, `easyide` key, fixed action vocabulary | Proposed |
| [0014](0014-wasm-logic-layer-chicory.md) | WASM logic layer (L2) in-app on Chicory, JSON-over-memory ABI v1 (amends 0009) | Proposed |
| [0015](0015-extension-sdk-licensing-apache.md) | Extension SDK licensing: schema, guest bindings, CLI and samples under Apache-2.0 | Proposed |
| [0016](0016-extension-registry-static-index-ed25519.md) | Extension registry: static signed git index, ed25519 publisher keys, TOFU pins, revocation | Proposed |
| [0017](0017-lsp-client-hand-rolled-server-lifecycle.md) | LSP client: hand-rolled JSON-RPC in the app process; one server per (environment, project, server) | Proposed |
