# VS Code webviews on Android: design detail (audit, 2026-09-25)

Companion to [ui.md](ui.md) §7. Covers `ui.webview-panel`, `ui.webview-view`, `ui.webview-bridge` and the webview half of
`ui.custom-editor`. The model follows the lead's binding decision D5, recorded as **ADR 0033 webview security model**; route 2b
(ADR 0031: VS Code's extension host vendored unmodified plus a guest-side main-thread adapter) decides what the page sees. Other docs: [README.md](README.md), [design.md](design.md), [backend.md](backend.md),
[gap-analysis.md](gap-analysis.md), [optimisation.md](optimisation.md), [security-licensing.md](security-licensing.md), [research/route-spike.md](research/route-spike.md).

Aliases: `A/` = `services/mobile/app/src/main/java/dev/easyide/app/`. `VS:` = microsoft/vscode at `0b16cb97` (MIT), paths under
`src/vs/workbench/contrib/webview/` unless given in full. `WV` = `VS:src/vs/workbench/contrib/webview/browser/`.
Android facts are quoted from [research/policy-licence.md](research/policy-licence.md) §4, which cites developer.android.com.

## 1. Why a WebView, and who needs it

VS Code draws these surfaces with HTML that the extension supplies. No native renderer can stand in for that HTML, so this is the only
place where easyIDE uses a WebView for extension UI ([ui.md](ui.md) §1). Corpus usage comes from
[data/corpus-usage.json](data/corpus-usage.json) (156 extensions; weightShare = download-weighted share).

| API / point | weightShare | must-work users (22-list) |
|---|---|---|
| `window.createWebviewPanel` | 0.395 (58 ext) | Claude Code, Ruby LSP, Java, Go, GitLens, Volar, rust-analyzer, GitHub PR, Dart, Code Spell Checker, Git Graph |
| `contributes.views` of `type: webview` (corpus.json `views.webviewViews`) | 0.148 (38 ext) | Claude Code, GitLens, GitHub PR, Dart, Code Spell Checker |
| `window.registerWebviewViewProvider` | 0.137 | same as above |
| `window.registerWebviewPanelSerializer` | 0.150 | Claude Code, Go, GitLens, GitHub PR |
| `window.registerCustomEditorProvider` / `contributes.customEditors` | 0.142 / 0.146 | Java, GitLens |
| menu `webview/context` | 0.088 | - |
| when keys `webviewId` / `activeWebviewPanelId` | 0.106 / 0.095 | - |

Today the app has one WebView: `MermaidView` (`A/ui/screens/workspace/MermaidView.kt:36-58`). It uses `allowFileAccess = true`,
`blockNetworkLoads = true` and `addJavascriptInterface`. None of that carries over; ux-overhaul PS6 already wants
`WebViewAssetLoader` there (docs/ux-overhaul/arch.md:61).

## 2. How VS Code does it (the model we copy)

| Concern | VS Code | Evidence |
|---|---|---|
| Container | sandboxed `<iframe>` (`allow-scripts allow-same-origin allow-forms allow-pointer-lock allow-downloads`) hosting `pre/index.html`, which writes the extension HTML into an inner frame | `WV/webviewElement.ts:424`, `WV/pre/index.html:1041` |
| Origin | random UUID persisted per (viewType, extension id) in `WebviewOriginStore`, so the same view always gets the same origin | `WV/webview.ts:360-410`; panels `VS:src/vs/workbench/api/browser/mainThreadWebviewPanels.ts:167`; views `VS:.../webviewView/browser/webviewViewPane.ts:168` |
| Resource URIs | `asWebviewUri` gives `https://{scheme}+{authority}.vscode-resource.vscode-cdn.net/{path}`, which a service worker intercepts (`fetch` handler) and loads through the host | `common/webview.ts:21-50`; `WV/pre/service-worker.js:349-360` |
| `cspSource` | `'self' https://*.vscode-cdn.net` | `common/webview.ts:25` |
| CSP | taken from the extension's `<meta>`; `vscode-resource:` tokens are rewritten to `cspSource`; posts `no-csp-found` when there is none (a warning, not enforced) | `WV/pre/index.html:889-897` |
| Outer CSP | `default-src 'none'; script-src 'sha256-…' 'self'; frame-src 'self'; style-src 'unsafe-inline'` on the host page | `WV/pre/index.html:7-8` |
| Page API | `acquireVsCodeApi()` returns a frozen `{postMessage, setState, getState}`; a second acquire throws; `setState` posts `do-update-state`; the initial state is injected | `WV/pre/index.html:205-245` |
| Transport | `MessageChannel` port between host and frame; origin checked on receive | `WV/pre/index.html:299-328`; `WV/webviewElement.ts:519-520` |
| Theme | every registered colour becomes `--vscode-<id with the FIRST '.' replaced by '-'>` (`replace('.', '-')`); plus 36 sizes and the font variables `--vscode-font-family/-weight/-size`, `--vscode-editor-font-family/-weight/-size/-font-feature-settings` | `WV/themeing.ts:64-91`; sizes `VS:src/vs/platform/theme/common/sizes/baseSizes.ts` (36 `registerSize`) |
| Body classes | `vscode-light`, `vscode-dark`, `vscode-high-contrast`, `vscode-high-contrast-light`, `vscode-reduce-motion`, `vscode-using-screen-reader` | `WV/pre/index.html:488-503`; `WV/themeing.ts:108-123` |
| Context menu | the `contextmenu` event collects `data-vscode-context` JSON from ancestors and posts `did-context-menu` to the host, which shows `webview/context` | `WV/pre/index.html:1179-1216`; `WV/webviewElement.ts:263` |
| Keys / find | `did-keydown` and `did-keyup` go to the host for keybindings; `enableFindWidget` uses CSS highlights and `did-find` | `WV/pre/index.html:621,639,193-196,1295`; `WV/webviewElement.ts:243,338` |
| Lifecycle | the context is destroyed when hidden unless `retainContextWhenHidden`, which "has a high memory overhead"; a hidden webview cannot receive messages | `VS:src/vscode-dts/vscode.d.ts:10066-10080` |
| Options | `enableScripts`, `enableForms` (default = enableScripts), `enableCommandUris` (bool or allow-list, default false), `localResourceRoots` (default: workspace folders + extension dir), `portMapping` | `vscode.d.ts:9905-9950` |

Colour-variable subtlety: `WV/themeing.ts:67` replaces only the first `.`, while `asCssVariableName`
(`VS:src/vs/platform/theme/common/colorUtils.ts:35-37`) replaces all of them. Webviews therefore see the first-dot form. Almost all core ids
have one dot. Contributed ids with two dots differ, e.g. `gitlens.decorations.x` becomes `--vscode-gitlens-decorations.x`. We emit
exactly what `themeing.ts` emits (parity), and a golden test pins it.

## 3. Security model (ADR 0033, decision D5)

Binding decision (lead, D5 → ADR 0033): one Android WebView per visible webview (panel, view, custom editor); a custom https origin
**per instance**; resources served by `shouldInterceptRequest` and limited to `localResourceRoots`; VS Code's `pre/` scripts vendored
(MIT); bridge through `addWebMessageListener` with an origin allow-list; no `addJavascriptInterface`, no `file://`; the extension CSP is
kept; we add no network; `retainContextWhenHidden` is honoured within a live-WebView budget, otherwise serialize and restore. This section
turns D5 into mechanisms. Route 2b (ADR 0031, D1) runs VS Code's extension host **unmodified**, so `asWebviewUri`, `cspSource` and the
`webview.*` protocol are VS Code's own (`VS:src/vs/workbench/api/common/extHostWebview.ts:76-93`). The adapter in the guest implements
`MainThreadWebviews/Panels/Views` and forwards to Kotlin over the UI protocol.

**Threat model.** Extension HTML and JS are untrusted content from a code-running extension. They must not be able to:
(a) read files outside `localResourceRoots`; (b) reach another webview's state, storage or messages; (c) call app code except
through the bridge of their own instance; (d) run commands unless `enableCommandUris` allows it; (e) gain Android permissions (camera,
mic, location, file chooser) without a prompt; (f) use easyIDE as a network proxy, or leak a request to a real host because our code
missed it.

| # | Rule | Mechanism (Android) |
|---|---|---|
| S1 | One `android.webkit.WebView` per live webview instance. It loads the vendored host page `index.html`, which creates the inner same-origin frame (`fake.html`) and writes the extension HTML into it, as in VS Code (`WV/pre/index.html:1020-1050`) | Compose `AndroidView` in the panel section or stage document |
| S2 | Page origin `https://<H>.webview.easyide.invalid`, where `H` = VS Code's `parentOriginHash(parentOrigin, salt)` (base32 SHA-256, 52 chars) with `parentOrigin` = `https://workbench.easyide.invalid` and `salt` = a random UUID **per webview instance**. The vendored page checks `hostname === H` or `startsWith(H + '.')` and refuses to start otherwise (`WV/pre/index.html:335-360`). The salt is persisted with the serialized panel (and per (extension, viewId) for views), so a restore keeps its origin and its web storage | origin store (DataStore). VS Code keys the salt per (viewType, extension) (`WV/webview.ts:360-410`); D5 asks for per-instance, which is stricter: two panels of the same type do not share storage. `.invalid` never resolves (RFC 6761), so a missed request fails closed. UNVERIFIED: secure context + `crypto.subtle` on `.invalid` in WebView (T3); the page throws without `crypto.subtle` (`index.html:335-337`) |
| S3 | `WebViewClient.shouldInterceptRequest` answers **every** request for the page host and for `*.vscode-resource.vscode-cdn.net`. It never returns `null` for these hosts, because `null` means "WebView will continue to load the resource as usual", which would reach a real host (vscode-cdn.net belongs to Microsoft). Unknown paths get 404 | `shouldInterceptRequest(WebView, WebResourceRequest)` runs "on a different thread than application's main thread" (WebViewAssetLoader doc) |
| S4 | Host page assets (`index.html`, `fake.html`, vendored, served from the APK) come from the page host. Extension resources keep VS Code's URI form, since the unmodified host's `asWebviewUri` gives `https://{scheme}+{authority}.vscode-resource.vscode-cdn.net/{path}` (`VS:src/vs/workbench/contrib/webview/common/webview.ts:21-50`). The interceptor decodes scheme, authority and path and serves the file only if its canonical path (after `..` removal and symlink resolution) is under one of **that WebView instance's** `localResourceRoots` (default: workspace folders + extension dir, `vscode.d.ts:9932`). `file` resolves in the guest rootfs or binds. Other schemes are read through the adapter's `fs` with a timeout. Responses carry `Access-Control-Allow-Origin: <page origin>` (the resource host is cross-origin to the page) | one `WebviewResourceServer`. Isolation holds even though the resource host name is shared, because the interceptor is per WebView object |
| S5 | `cspSource` stays VS Code's `'self' https://*.vscode-cdn.net` (`common/webview.ts:25`), so extension CSPs written for VS Code work unchanged. `https://*.vscode-cdn.net` can only reach our interceptor (S3) | none: unmodified host (D1) |
| S6 | The extension CSP is kept. The vendored page rewrites `vscode-resource:` tokens to `cspSource` and reports `no-csp-found` (`index.html:889-897`); we log that as a warning (parity, no block). Header CSP on the host page as VS Code ships it (`index.html:7-8`); extension frame documents get an extra header `object-src 'none'; base-uri 'self'` | `WebResourceResponse` headers. UNVERIFIED: Android docs never mention CSP; verify header + meta intersection on device (T4) |
| S7 | Service worker off: the host page is loaded with `?disableServiceWorker=true`, a flag the vendored page supports (`index.html:37,247-249`; VS Code sets it in `webviewElement.ts:454-456`). Resource fetches then reach S3/S4 directly (§6) | `ServiceWorkerControllerCompat` client answers any SW fetch with 403 as a backstop |
| S8 | Bridge. The vendored page posts `webview-ready` with a `MessagePort` to `window.parent` (`index.html:328`). In a top-level WebView `window.parent === window`. Our **outer-host adapter script** (easyIDE code, injected with `addDocumentStartJavaScript` for the exact page origin; the inner frame has the same origin, so the script returns at once when `window !== window.top`) takes that port and relays each port message to `__easyide.postMessage(...)` and each host reply back into the port. So the vendored page talks the same `WebviewHostMessaging` channels as in VS Code (`load-resource` unused, `onmessage`, `do-update-state`, `did-context-menu`, `did-keydown`, `did-click-link`, `did-focus`, ...) | `WebViewCompat.addWebMessageListener(webView, "__easyide", setOf(exactPageOrigin), listener)`, exact origin, no wildcard (the doc says with a wildcard "the app must treat received messages as untrustworthy"). The listener accepts only `isMainFrame == true` from `sourceOrigin == exactPageOrigin` and attributes every message to this instance's handle. Host → page uses `JavaScriptReplyProxy`. Needs features `WEB_MESSAGE_LISTENER`, `DOCUMENT_START_SCRIPT` (T9) |
| S9 | No `addJavascriptInterface`. `allowFileAccess=false`, `allowContentAccess=false`, JS on (the host page needs it; extension scripts are gated by the frame `sandbox` built from `enableScripts`, `index.html:1024-1030`), `setSupportMultipleWindows(false)`, `mixedContentMode = NEVER_ALLOW`, geolocation off, `mediaPlaybackRequiresUserGesture = true` | `WebSettings` |
| S10 | Navigation: the top frame never leaves its origin. `did-click-link` → http(s) goes to `env.openExternal` (existing confirm dialog, `A/extensions/host/AppHostPort.kt:140-147`), `command:` runs only if `enableCommandUris` allows the id (checked in the adapter, as VS Code's main thread does). `shouldOverrideUrlLoading` drops anything else | `WebViewClient.shouldOverrideUrlLoading` |
| S11 | `WebChromeClient.onPermissionRequest` → deny; `onShowFileChooser` → our picker scoped to the workspace; downloads → save dialog | no Android runtime permission is requested for webviews |
| S12 | Storage isolation beyond origins: one WebView `Profile` per extension (`WebViewCompat.setProfile(webView, "ext:<id>")`). A Profile "holds its own set of data". Deleting it wipes cookies and storage on uninstall or kill switch. Without `MULTI_PROFILE`, per-instance origins (S2) + `WebStorage.deleteOrigin` | androidx.webkit `Profile`/`ProfileStore`. `setDataDirectorySuffix` is per **process**, so it cannot isolate extensions |
| S13 | Network: no proxy, tunnel or port forward is added by us. The page loads remote URLs only as its own CSP allows (parity: GitHub PR loads avatars). `portMapping` is not implemented in v1: localhost inside a WebView is the device loopback. UNVERIFIED: whether guest-bound ports are reachable there (T7). Per-extension setting `extensions.webview.blockNetwork` → `blockNetworkLoads` | `WebSettings.setBlockNetworkLoads` |
| S14 | Renderer crash/OOM: `onRenderProcessGone` → drop that WebView, mark the instance "crashed", offer Reload; state comes from the last `setState` | Android: renderer is "A separate sandboxed process"; "native memory can silently grow to gigabytes" |

What the model does **not** protect: an extension's webview can phish inside its own panel (same as VS Code); extension code in the
inner frame is same-origin with its host page (as in VS Code, `allow-same-origin`) and so can drive its own bridge; it can only reach its
own extension, which already runs arbitrary code in the sandbox (ADR 0030 Security, ADR 0031).

## 4. Vendored page and our adapter script

| Piece | Source | Notes |
|---|---|---|
| `index.html` (1,335 lines), `fake.html` (10) | vendored from `WV/pre/` at the pinned stable tag of ADR 0031 (D5) | MIT. `index.html` carries **no** per-file header (only `service-worker.js` has one), so we add the MIT notice as a comment on top and a NOTICE.md row (component, path, MIT, tag/commit), same pattern as `language-configuration.json` ([research/policy-licence.md](research/policy-licence.md) §2.5). Its inline script is pinned by a `sha256-…` hash in its own CSP (`index.html:7-8`): **any edit breaks it**, so we do not edit; parameters go in the query string |
| `service-worker.js` (717) | not shipped (S7) | revisit only if a device test shows `disableServiceWorker` misbehaves |
| `acquireVsCodeApi`, `getState/setState`, theme vars, body classes, `data-vscode-context`, key forwarding, find | inside the vendored page, unchanged (`index.html:205-245, 488-503, 621-639, 1179-1216, 1272-1298`) | state is stored by Kotlin per instance, capped by `extensions.webview.maxStateBytes` (proposal 1 MiB; over the cap the write is refused and logged) |
| outer-host adapter (~150 lines JS, ours) | easyIDE, injected at document start in the top frame | port ↔ `__easyide` relay (S8); supplies what VS Code's `webviewElement.ts` sends: `content` (HTML + options), `styles` (theme vars), `focus`, `message`, `find`, `set-title`, `initial-scroll-position`; serializes `ArrayBuffer`s as binary WebMessages. UNVERIFIED: payloads extensions post as `Uint8Array` round-trip correctly (Git Graph, GitLens graph) |
| `styles` payload | Kotlin | every id of the raw colour registry ([ui.md](ui.md) §4.2) as `vscode-<id with the first '.' → '-'>` (parity with `WV/themeing.ts:67`), the 36 sizes, fonts: `--vscode-font-family` = app UI font stack (Geist Sans, ADR 0026) + system fallback, `--vscode-editor-font-*` from `editor.*` settings; `activeTheme` = `vscode-light/dark/high-contrast/high-contrast-light`; `vscode-reduce-motion` from the system animator scale; `vscode-using-screen-reader` when TalkBack is on. Re-sent on theme/font change |
| context menu | `did-context-menu` (with merged `data-vscode-context`) → native KitMenu for `webview/context`, keys `webviewId`, `webviewSection` + the merged context | touch: long-press. UNVERIFIED that `contextmenu` fires on long-press in an Android WebView (T5); fallback: the view's `...` menu |
| keys | `did-keydown` → our `Keymap` (palette, quick open, save) unless the page called `preventDefault` | same as VS Code |
| find | `enableFindWidget` → Kit find bar sending `find` / `find-stop` into the page (the vendored page highlights with CSS highlights, `index.html:193-196`) | no `WebView.findAllAsync` needed |
| clipboard | native WebView selection and copy/paste; the frame gets `clipboard-read/write` allow rules when scripts are on (`index.html:1034-1036`) | UNVERIFIED permission behaviour in WebView (T6) |
| focus | `did-focus`/`did-blur` → context keys `webviewFocus`, `activeWebviewPanelId` | |

## 5. Lifecycle, memory, eviction

| Event | Webview panel | Webview view |
|---|---|---|
| create | `webview/create` → stage document `webview:<handle>` in group `viewColumn` (1-4 → group, `Beside` → split on tablet, full screen with Back on phone) | first time the view is shown: `onView:<id>` activation → `webview/resolve` → WebView in the panel section |
| hidden (tab switch / panel collapsed) | default: destroy the WebView, keep `state` (VS Code parity). `retainContextWhenHidden`: keep it detached, `onPause()` + `pauseTimers` equivalent; messages to it are queued or dropped as in VS Code ("You cannot send messages to a hidden webview") | same |
| shown again | re-create, re-post HTML + state; the extension sees `onDidChangeViewState` | same |
| app restart / process death | panels with a registered `WebviewPanelSerializer` come back: tab restored lazily, `onWebviewPanel:<viewType>` activation, `webview/resolve {state}`. Panels without a serializer are dropped from the restored tab set (VS Code parity) | re-resolved when visible |
| memory pressure (`onTrimMemory`) | evict hidden retained webviews in LRU order: destroy, keep state; next show = restore. Visible ones stay. Order in the shared kill list (backend C9): idle LSPs → hidden webviews → host | same |

**Budget (proposal, settings keys, not constants).** `extensions.webview.maxLive` = 2 on COMPACT, 3 on MEDIUM, 4 on EXPANDED (matches
the 1-4 editor groups). Only visible webviews count, plus retained hidden ones. Opening one more evicts the LRU hidden retained webview.
If all are visible, the oldest non-focused webview is shown as a snapshot image with a "Tap to reload" overlay.
`extensions.webview.retainHidden` (default true) lets a user turn `retainContextWhenHidden` into plain destroy/restore on low-RAM devices.

**Memory per WebView: UNVERIFIED.** No primary figure exists. Android says "Most WebView memory ... is allocated in native memory" and
that on low-RAM devices WebView "might fall back to a single process". Measurement plan (T1): on a 4 GB phone and an 8 GB tablet, for
0/1/2/4 webviews (GitLens Home, Claude Code panel, Git Graph, an empty `<html>`), record `dumpsys meminfo <pkg>` plus
`dumpsys meminfo` of the `SandboxedProcessService` renderer(s) (PSS, private dirty), whether renderers are shared across WebViews, and
the cost of `retainContextWhenHidden` (hidden vs destroyed). Put the numbers into [optimisation.md](optimisation.md) and re-derive `maxLive`.

## 6. Service workers

VS Code needs its service worker only to turn `vscode-cdn.net` fetches into host resource loads (`WV/pre/service-worker.js:349-360`).
We load the vendored page with `disableServiceWorker`, and `shouldInterceptRequest` sees those fetches directly (S3/S4). **We ship no
service worker.** Extension pages that register their own SW are not supported in v1: the `ServiceWorkerControllerCompat` client answers
every SW fetch with 403, so a SW can never become a second, unfiltered resource path. UNVERIFIED: whether SW script fetches pass through
`WebViewClient.shouldInterceptRequest` or only the SW client, and whether registration on `.invalid` fails anyway (T8).

## 7. Custom editors

`contributes.customEditors` (weightShare 0.146) becomes `documentOpeners` entries (glob → document type, priority `default`/`option`,
`A/ui/shell/ext/DocumentOpeners.kt`), plus "Open With..." in the explorer (tablet: context menu; phone: long-press sheet).
- `CustomTextEditorProvider`: webview + our document model. Edits come back as `WorkspaceEdit` through the normal path, and save/dirty
  follow the text document.
- `CustomEditorProvider` (binary / custom documents): `CustomDocument` with `openCustomDocument`, `onDidChangeCustomDocument`, save/
  revert/backup through host RPC. Undo stack entries are proxied to the host.
- Users in the must-work list: Java (a class-file / project webview) and GitLens (rebase editor, `git-rebase-todo`). The GitLens rebase
  editor needs a `TextDocument` for `git-rebase-todo`, which is in scope.

## 8. Device tests (all UNVERIFIED items above)

| T | Check | Pass condition |
|---|---|---|
| T1 | memory per WebView (§5) | numbers recorded for both devices; `maxLive` re-derived |
| T2 | `addWebMessageListener` exact origin on `https://<H>.webview.easyide.invalid` | a message from a second WebView with another origin is not delivered |
| T3 | secure context, `crypto.subtle` and `localStorage` on `.invalid`; vendored page passes its hostname check | `window.isSecureContext === true`; page reaches `webview-ready`; storage survives destroy/recreate |
| T4 | header CSP + meta CSP intersection | inline script blocked by either one is blocked |
| T5 | `contextmenu` on long-press | event fires with `data-vscode-context` |
| T6 | `navigator.clipboard.writeText` from a user gesture | works or fails cleanly; document the result |
| T7 | guest dev server on 127.0.0.1:<port> reachable from a WebView | fetch succeeds when the page's CSP allows it |
| T8 | `disableServiceWorker` path loads GitLens Home resources; extension page calling `navigator.serviceWorker.register` | resources served by S4; registration fails; no request reaches the network for `*.vscode-cdn.net` |
| T9 | `MULTI_PROFILE` availability on minSdk devices + Profile wipe | per-extension cookies and storage cleared on uninstall |
| T10 | GitLens Home, Claude Code panel, Git Graph, GitHub PR description, Code Spell Checker info panel render with theme vars | golden screenshots light/dark; no console CSP errors except the extension's own |
