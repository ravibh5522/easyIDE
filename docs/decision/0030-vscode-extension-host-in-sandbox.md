# 0030 - VS Code extension host: Node in the sandbox, our own `vscode` module, Kotlin main side

Status: Accepted, partly superseded: decision 2 by [0031](0031-vendor-vscode-extension-host.md); decision 1 amended by 0031 (one host per workspace) and [0032](0032-node-runtime-provisioning.md) (Node source). Supersedes the deferral of Tier 2 in [0009](0009-extension-platform-tiers.md).

## Context

[0009](0009-extension-platform-tiers.md) put a real extension host at Tier 2 and deferred it
until declarative packs were "demonstrably not enough". The owner now wants extensions such as
GitLens and the Claude Code extension, which are Node programs using the `vscode` API, webviews,
tree views, CodeLens, decorations, the git extension API and child processes. Declarative packs
cannot express any of that, so the deferral condition is met.

The facts in 0009 still hold and shape the design:

- A VS Code extension draws nothing. It is a Node process talking RPC to a main side that renders.
- Open VSX is the only lawful registry. Microsoft's own extensions stay unavailable.
- Memory is the binding constraint. One host process serves every extension, so its cost is
  paid once.
- proot gives no per-extension isolation. Installing an extension equals running its code in
  the terminal ([0002](0002-sandbox-backend-proot-default-chroot-optin.md)).

## Decision

1. **One Node process per environment** (the host), started inside the proot sandbox by the same
   `ServerLauncher` path the language servers use, with stdio as the transport. Node comes from
   the environment (`apt-get install nodejs`), installed by a sandbox step the first time a
   code-running extension is enabled. Nothing is bundled but our own JS.
2. **We write the `vscode` module ourselves** (`services/mobile/exthost/js`), not port VS Code's
   `extHost*` sources and not adopt Theia's `plugin-ext` (EPL-2.0/GPL-2.0 dual, larger than we
   need). The module implements a *defined subset* of the API and is table-driven so coverage is
   a number we can print. Calls it does not implement throw a typed error naming the API, so an
   extension that needs it fails visibly instead of doing nothing.
3. **Wire protocol**: JSON-RPC 2.0, LSP `Content-Length` framing, the existing
   `:lsp` `JsonRpcConnection` on the Kotlin side. Payloads are plain JSON; no shared handles, no
   binary except base64 for file content. Spec: [extension-host/arch.md](../extension-host/arch.md).
4. **Kotlin main side** (new module `:exthost`, pure JVM like `:lsp`, ports implemented in
   `:app`). It owns extension lifecycle, the command bridge, and each `main/*` method; rendering
   is done by existing app surfaces (kit dialogs, status bar, R4 tree views, editor decorations,
   the language-feature router). Webviews render in an Android `WebView` document in the shell
   stage.
5. **Files and processes are not proxied.** Extension code runs in the same guest as the
   project, so `fs`, `child_process` and `git` work directly against `/workspace`. Only editor
   state (open documents, selections, decorations, providers, UI) crosses the wire.
6. **Extensions with code are gated.** A new capability `host.run` is shown at install and must
   be granted; the host is off until one such extension is enabled; a per-environment kill switch
   stops it and clears its state. This is disclosure and hygiene, not isolation.

## Alternatives considered

- **Theia in a WebView** (0001/0009 Tier 3). Full parity but never spiked on Android and heavier
  than one Node process. Still the long-term parity option; this decision does not preclude it.
- **Port VS Code's extension host (MIT).** Correct protocol, but it is coupled to VS Code's
  service container and the wire format is a private, versioned binary-ish RPC. A subset shim is
  smaller and ours to change.
- **Run extensions in QuickJS/WASM ([0014](0014-wasm-logic-layer-chicory.md)).** No Node APIs, so
  GitLens and Claude Code would not load.

## Consequences

- **Compatibility is a published table**, generated from the shim's API table and a corpus of real
  extensions loaded in CI (GitLens, Claude Code, others), never "VS Code compatible" unqualified.
- **Memory**: the host adds a Node process (about 60-150 MB idle plus extension heap). It is
  counted in the LSP memory budget ([0017](0017-lsp-client-hand-rolled-server-lifecycle.md)) and is
  the first thing shed under low-memory pressure after idle language servers.
- **Security**: `host.run` extensions run arbitrary code in the sandbox. The credential paths
  must not be reachable from it: git tokens travel in process env
  ([0012](0012-git-token-in-process-env-not-credential-socket.md)), and the host is launched with
  a clean environment holding no token. Supply-chain measures from 0009 (publisher pinning,
  SHA-256, no auto-update, kill switch) apply to `.vsix` installs.
- **Webviews** become real, which reopens 0009's Tier 3 boundary for `WebviewPanel` and
  `WebviewView`: a WebView per panel, `vscode-resource` served from the extension directory,
  a strict CSP from the extension's own HTML, no network access added by us.
- Not planned: debug adapters, notebooks, custom editors beyond text and webview, remote/SSH,
  extensions that need Microsoft's proprietary services.
