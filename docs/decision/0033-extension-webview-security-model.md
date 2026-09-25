# 0033 - Extension webviews: one Android WebView per instance, per-instance origin, VS Code's host page vendored

Status: Accepted (details and device checks T1-T10: [../vsx-compat/ui-webviews.md](../vsx-compat/ui-webviews.md))

## Context

Route 2b ([0031](0031-vendor-vscode-extension-host.md)) runs VS Code's extension host unmodified, so
`asWebviewUri`, `cspSource` and the webview protocol are VS Code's own: resource URIs are
`https://{scheme}+{authority}.vscode-resource.vscode-cdn.net/{path}` and `cspSource` is
`'self' https://*.vscode-cdn.net` (`src/vs/workbench/contrib/webview/common/webview.ts:21-50`).
Webview content is untrusted HTML/JS from a code-running extension. It is the main UI of Claude Code,
GitLens Home/Graph, Git Graph and GitHub Pull Requests ([../vsx-compat/ui.md](../vsx-compat/ui.md)).
The draft protocol gave every webview one shared origin, `easyide-webview.local`
([../extension-host/arch.md](../extension-host/arch.md)), so webviews could read each other's storage.
The one existing WebView in the app (`MermaidView`) uses `addJavascriptInterface` and file access,
which Play's Device and Network Abuse policy names for untrusted content
([../vsx-compat/licensing-policy.md](../vsx-compat/licensing-policy.md) section 4.1).

## Decision

1. One `android.webkit.WebView` per live webview instance (panel, view, custom editor), hosting VS Code's
   `pre/index.html` and `fake.html` **vendored unmodified** from the pinned tag (MIT, NOTICE row). The page's
   inline script is pinned by a hash in its own CSP, so parameters go in the query string, never edits.
2. Each instance gets its own origin `https://<H>.webview.easyide.invalid`, with `H` the vendored page's
   `parentOriginHash` of a per-instance salt; the salt is persisted with the serialized panel so a restore
   keeps its origin. `.invalid` never resolves, so a missed request fails closed.
3. `shouldInterceptRequest` answers **every** request for the page host and for
   `*.vscode-resource.vscode-cdn.net` (never `null`, which would reach Microsoft's real domain), serving
   files only under that instance's `localResourceRoots` after canonicalisation; unknown paths get 404.
4. No service worker (`disableServiceWorker`, which the vendored page supports); a service-worker client
   answers any service-worker fetch with 403.
5. Bridge: `WebViewCompat.addWebMessageListener` with the exact page origin, main frame only, plus a small
   document-start script of ours relaying the page's `MessagePort`. Never `addJavascriptInterface`;
   `allowFileAccess` and `allowContentAccess` off; no multiple windows; permission requests denied.
6. The extension's CSP is kept (parity, including VS Code's warning-only `no-csp-found`); we add no proxy,
   tunnel or port mapping. Navigation leaves the page only through `env.openExternal` (confirmed) or
   allow-listed `command:` URIs.
7. Storage: one androidx.webkit `Profile` per extension where `MULTI_PROFILE` is available, deleted on
   uninstall or kill switch; otherwise per-instance origins plus `WebStorage.deleteOrigin`.
8. Lifecycle as VS Code: hidden webviews are destroyed unless `retainContextWhenHidden`, within a budget
   `extensions.webview.maxLive` (2/3/4 by window class), with LRU eviction and serializer restore.

## Alternatives considered

- **One shared origin for all webviews (draft arch.md).** Cross-webview storage and message confusion.
- **Our own host page and `acquireVsCodeApi` shim.** Diverges from what extension bundles were tested
  against (state, theme variables, context menus, key forwarding).
- **Keep VS Code's service worker for resource loading.** An extra, harder-to-audit resource path;
  interception already sees the fetches.
- **Render webviews in-process through a single WebView with iframes (VS Code's own layout).** Fewer
  WebViews, but one renderer crash or leak takes every extension UI down, and the Android bridge
  cannot tell frames apart as reliably as separate WebView objects.

## Consequences

- Memory per WebView is unmeasured (T1); `maxLive` is a setting until it is measured
  ([../vsx-compat/optimisation.md](../vsx-compat/optimisation.md)).
- Secure-context behaviour of `.invalid` origins (`crypto.subtle`, which the vendored page needs), CSP
  header plus meta enforcement, and long-press `contextmenu` are device checks T3-T5 before M4.
- `MermaidView`'s pattern must not be reused for extension content.
- This is containment of web content from the app, not isolation of the extension: the extension already
  runs arbitrary code in the guest ([0002](0002-sandbox-backend-proot-default-chroot-optin.md), 0031).
