# ROUTE-SPIKE: how to run Open VSX extensions (route 1 shim vs route 2 vendored ext host vs route 3 Theia/web workbench)

Agent: ROUTE-SPIKE. Date 2026-09-25. VS Code clone `$VS` = microsoft/vscode main `0b16cb97868058754d545e5f9ced99211ccc290b`
(package.json `1.140.0`, 2026-09-25). Host for measurements: x86_64 Intel Xeon @ 2.10GHz, 4 vCPU, 16 GB, Node v22.22.2.
**All timings/RSS below are x86 proxies**, not tablet numbers (method for the tablet in section 8).

Artifacts:
- `tools/vsx-audit/exthost-deps.mjs` (+ `lib/deps-protocol.mjs`, `lib/deps-churn.mjs`) -> `docs/vsx-compat/data/exthost-deps.json`.
  Re-run: `NODE_PATH=<dir with typescript> node tools/vsx-audit/exthost-deps.mjs --vscode $VS --out docs/vsx-compat/data/exthost-deps.json --churn 1.130.0,...,1.139.0 --churn 1.100.0,1.105.0,1.109.0,1.115.0,1.120.0,1.125.0,1.130.0,1.135.0,1.139.0`
  (typescript@5.9.3 from `$SP/node_modules-spike`; `$VS` has no node_modules; tags fetched with `git fetch --depth 1 origin tag <t>`).
- Spike (not committed) in `$SP/spike/`: `bundle.mjs` (esbuild of the ext host), `renderer.ts` (fake main side built from VS Code's
  own `PersistentProtocol` + `RPCProtocol` + `extHost.protocol.ts`), `rss-probe.mjs`, `hello/` (test extension), `logs/calls-*.json`.

## 0. Verdict (short)

**Route 2 works, unmodified, today.** VS Code's own node extension host (`src/vs/workbench/api/node/extensionHostProcess.ts`) bundles
with esbuild in <1 s into one 3.0 MB minified file (0.85 MB gzip) with every npm dependency inlined, runs under Node 22, completes
the real handshake with a fake main side I wrote in ~200 lines, and activates **unmodified** real Open VSX extensions: GitLens,
Claude Code, Go, ESLint, Prettier (ESM), EditorConfig, Code Runner (7 of 8 tried; the 8th failed only because the fake main side
sent no `contributes.configuration` defaults). Hello-world activation ~0.25-0.41 s after process start, RSS 97-113 MB (x86).

Recommendation: **replace ADR 0030's decision 2 (hand-written `vscode` module) with route 2b**: vendor VS Code's ext host
(MIT) as-is + a thin **JS main-thread adapter in the guest** (same bundle toolchain, same commit, reuses `RPCProtocol`) that
implements the in-scope `MainThread*Shape` methods by forwarding to Kotlin over ADR 0030's JSON-RPC/stdio link. Kotlin keeps
owning UI. Keep ADR 0030 decisions 1, 3 (between adapter and Kotlin), 4, 5, 6. Details and go/no-go gates in sections 9-10.

## 1. Dependency entanglement (route 2) - static closure

Tool: `exthost-deps.mjs` (static `import`/`export from`/side-effect imports; `import type` skipped; dynamic `import()` recorded,
not followed; LOC = non-blank lines). Closure is an **upper bound**: esbuild elides type-only-used imports and tree-shakes.

| Entry | Files | LOC | vs/base | vs/platform | vs/editor | vs/workbench | other |
|---|---|---|---|---|---|---|---|
| `api/node/extensionHostProcess` | **890** | **277,516** | 146 / 46,126 | 205 / 55,402 | 128 / 40,968 | 409 / 134,594 | 2 / 426 (`vs/nls`, `vs/amdX`) |
| `api/common/extHost.api.impl` | 812 | 261,226 | 134 / 43,303 | 179 / 50,032 | 128 / 40,968 | 369 / 126,497 | 2 / 426 |
| `api/node/extHost.node.services` | 881 | 275,300 | 145 / 46,078 | 203 / 54,910 | 128 / 40,968 | 403 / 132,918 | 2 / 426 |

Heavy subsystems reachable from `extensionHostProcess` (files / LOC; path-pattern classes in the script, overlapping):

| Subsystem | Files / LOC | Largest members |
|---|---|---|
| chat / lm / mcp / agent host | **173 / 63,357** | `contrib/chat/common/model/chatModel.ts` (3,303), `platform/mcp/common/modelContextProtocol.ts` (3,019), `platform/agentHost/common/state/sessionState.ts` (2,331) |
| editor model (`editor/common/model`, `core`) | 66 / 19,947 | `textModel.ts` (2,316), `pieceTreeBase.ts` (1,607), tree-sitter token store (7 files / 1,887) |
| editor other (`editorOptions.ts` 6,440, `languages.ts` 2,333) | 37 / 12,452 | |
| terminal | 42 / 7,740 | `extHostTerminalService.ts` |
| debug | 14 / 6,221 | `contrib/debug/common/debugModel.ts` (1,849) |
| notebook | 23 / 5,999 | `notebookTextModel.ts` |
| search | 20 / 5,825 | ripgrep text search engine |
| extension mgmt / extensions services | 20 / 5,764 | |
| testing 11 / 4,387; tasks 5 / 4,133 (`problemMatcher.ts` 1,834); remote/tunnels 17 / 4,492; ipc 8 / 4,457; files 10 / 3,790; telemetry 8 / 1,272 | | |

npm packages imported by the closure (from `exthost-deps.json.entries[..].npmDeps`):

| Package | How | Licence (registry.npmjs.org `latest`/pinned) | Native? | Needed on device |
|---|---|---|---|---|
| `minimist` | static | MIT (1.2.8) | no | yes (inlined) |
| `vscode-regexpp` | static | MIT (3.1.0) | no | yes (inlined) |
| `@vscode/proxy-agent` (+ agent-base, debug, ms, http(s)-proxy-agent, socks, socks-proxy-agent, ip-address, smart-buffer, undici, @tootallnate/once) | static + dynamic | all MIT (installed tree checked) | no | yes (inlined) |
| `@vscode/native-watchdog` | static import, used in `try/catch` only when `initData.parentPid` (`extensionHostProcess.ts:351-384`) | MIT | **gyp** | no (send `parentPid: 0`) |
| `@vscode/spdlog` | dynamic (`platform/log/node/spdlogLog`) | MIT | **gyp** | replace with JS file logger (non-fatal if absent; spike logged 2 errors and continued) |
| `@vscode/ripgrep-universal` | dynamic (`base/node/ripgrep`) | MIT | binary | `findTextInFiles` only; use guest `apt install ripgrep` |
| `@vscode/windows-process-tree` | dynamic (debug on Windows) | MIT | gyp | no |
| `kerberos` | dynamic (proxy auth) | **Apache-2.0** (2.1.1 pinned in `package.json:150`) | gyp | no |
| `undici` | dynamic (`extHostMcpNode`) | MIT | no | no (MCP out of scope) |

Vendored third-party JS inside `src/` in the closure: `vs/base/common/marked/marked.js` (marked 14.0.0, MIT, cgmanifest),
`vs/base/common/semver/semver.js` (semver 5.5.0, ISC). `vs/base/common/path.ts:14` carries the Node.js/Joyent MIT notice.
Every other closure file has the `Copyright (c) Microsoft Corporation ... Licensed under the MIT License` header (grep over all 890).

**What esbuild actually keeps** (metafile of the spike bundle): 601 inputs, 543 contribute bytes. Bytes by class (unminified 4.43 MB):
api core 27.3%, vs/base 18.0%, **chat/lm/mcp/agents 17.8%**, vs/platform 11.7%, workbench other 8.3%, vs/editor 7.9% (pieceTree
kept, `textModel.ts` elided), debug 2.6%, notebook 2.1%, testing 1.8%, terminal 1.5%, tasks 1.0%.
=> Out-of-scope tracks (chat+debug+notebook) are ~22.5% of the bundle; stubbing them is optional size work, not a blocker.

Entanglement verdict: large (277k LOC reachable) but **mechanically bundleable with zero source edits** - VS Code ships exactly this
bundle itself (`build/next/index.ts:74-76,110`, options `build/next/bundle.ts:12-37`: esm, `packages: 'external'`, es2024).

## 2. Build spike

| Variant | Raw | Minified | gzip -9 (min) | brotli (min) | Inputs | Build time |
|---|---|---|---|---|---|---|
| A: VS Code options (`packages: 'external'`, needs `node_modules` with minimist, vscode-regexpp, @vscode/proxy-agent) | 4,465,387 | 2,321,230 | 633,871 | 512,456 | 601 | 0.29-0.68 s |
| B: **self-contained** (npm inlined; natives + kerberos + windows-ca-certs external; banner `globalThis.require ??= createRequire(import.meta.url)`) | 5,928,387 | **3,023,387** | **846,812** | 676,579 | 745 | 0.6-0.8 s |
| Fake main-side adapter (`renderer.ts`, reuses VS Code ipc/rpc/protocol) | 395,779 | 214,883 | 67,328 | | | |

Only problems hit while building: (1) `@vscode/windows-ca-certs` unresolved inside proxy-agent -> mark external (Windows-only);
(2) ESM bundle of CJS deps needs a `require` shim (banner). No VS Code source file was edited. Product config is injected by
VS Code's build via a `/*BUILD->INSERT_PRODUCT_CONFIGURATION*/` placeholder (`build/next/index.ts:343-399`,
`platform/product/common/product.ts:69-76`); the spike skipped it (defaults apply), a real build copies that esbuild plugin.

## 3. Run spike (it runs)

Handshake as implemented by the ext host (all `src/vs/...`):
1. Transport chosen from env (`workbench/services/extensions/common/extensionHostEnv.ts:18,35,48`): `VSCODE_EXTHOST_IPC_HOOK=<unix socket>`
   (used by the spike), `VSCODE_EXTHOST_WILL_SEND_SOCKET=1` (socket handed over Node IPC after `{type:'VSCODE_EXTHOST_IPC_READY'}`,
   optional WebSocket framing + permessage-deflate), or `VSCODE_WILL_SEND_MESSAGE_PORT=1` (Electron utility process).
   Code: `workbench/api/node/extensionHostProcess.ts:180-291`.
2. Framing: `PersistentProtocol` (`base/parts/ipc/common/ipc.net.ts`): 13-byte header `type u8 | id u32 | ack u32 | len u32`
   (`:474-478`), types Regular/Control/Ack/Disconnect/ReplayRequest/Pause/Resume/KeepAlive (`:263-273`), acks + replay for reconnection.
3. Host sends 1-byte `Ready` (=2), main replies with `IExtensionHostInitData` as UTF-8 JSON (`extensionHostProcess.ts:336-393`;
   type at `services/extensions/common/extensionHostProtocol.ts:28-60`), host answers `Initialized` (=1), `Terminate` = 3 (`:123-139`).
4. RPC starts (`ExtensionHostMain`, `api/common/extensionHostMain.ts:168-211`). The main side must call
   `ExtHostConfiguration.$initializeConfiguration` and `ExtHostWorkspace.$initializeWorkspace` (barriers:
   `api/common/extHostConfiguration.ts:118-131`, `api/common/extHostExtensionService.ts:212-227`), then `$activateByEvent('*')`/`$activate`.
   With `autoStart: true` the host starts itself; eager `*` extensions activate within max 1 s (`api/common/extHostExtensionService.ts:803-819`).

Fake main side: one logging `Proxy` actor per `MainContext` identifier (87), answering undefined except 8 methods
(`$showMessage` picks first button, `$getInitialState` -> `{isFocused,isActive}`, `$getTools` -> `[]`, `$register` -> id, ...).

Hello-world (`hello/extension.js`: `registerCommand`, `showInformationMessage` with buttons, status bar, output channel,
`getConfiguration`, `executeCommand`) - everything round-tripped, then the main side invoked
`ExtHostCommands.$executeContributedCommand('hello.say')` -> `"said hello to main-side"`. 38 main-thread calls, 20 distinct methods:
`MainThreadLogger.$registerLogger` x3, `MainThreadWindow.$getInitialState`, `MainThreadCommands.$registerCommand` x2 /
`$fireCommandActivationEvent`, `MainThreadTerminalService.$registerProcessSupport`, `MainThreadSearch.$registerTextSearchProvider`,
`MainThreadTask.$registerSupportedExecutions` x2 / `$registerTaskSystem`, `MainThreadLanguageModelTools.$getTools`,
`MainThreadConsole.$logExtensionHostMessage` x8, `MainThreadExtensionService.$onWillActivateExtension` / `$onDidActivateExtension` /
`$setPerformanceMarks`, `MainThreadDebugService.$registerDebugTypes`, `MainThreadTelemetry.$publicLog2` x4,
`MainThreadStorage.$initializeExtensionStorage` x2, `MainThreadFileSystem.$ensureActivation` x2, `MainThreadMessageService.$showMessage` x3,
`MainThreadStatusBar.$setEntry`, `MainThreadOutputService.$register`. 46 msgs in / 80 out, 16 KB in.

Startup and memory, hello-world (x86 proxy; "activated" = ms from `rss-probe.mjs` start, i.e. after Node boot, to end of
`activate()`; RSS read right after the run via `/proc/self/status`):

| Variant (7 runs unless noted) | Activated median (range) | RSS median | VmHWM | heapUsed |
|---|---|---|---|---|
| A unminified | 407 ms (342-696) | 113 MB | 113 | 33 MB |
| A minified | 350 ms (315-568) | 103 MB | 104 | 21-25 MB |
| A minified + `NODE_COMPILE_CACHE` warm (with `module.flushCompileCache()`; cache 2.9 MB) | **248 ms** (234-270) | 97 MB | 98 | 21-26 MB |
| B self-contained minified, isolated dir without node_modules (5 runs) | 310 ms (302-315) | 97 MB | 98 | 23 MB |
| Bare `node -e "require('net')"` (reference) | 32-40 ms total | 42 MB | | 4 MB |

Note: without flushing, compile cache was never written because the spike kills the child (180 KB cache, no gain).

RPC latency (sequential `ExtHostExtensionService.$test_latency`, 2000 calls, 3 runs): **p50 2.58 ms, p90 3.0-3.3 ms, p99 6.4-6.9 ms**.
Floor comes from `ProtocolWriter._writeSoon` deferring writes with `setTimeout` (`ipc.net.ts:500-511`) on both sides - fine for
UI, relevant for chatty providers; pipelined calls are not serialised.

Real Open VSX extensions (unpacked from `/root/.cache/easyide-corpus/x/*/extension`, bundle B, fake main side, single run each;
time from spawning the host; RSS 3 s after activation):

| Extension | Result | Activated at | RSS / HWM | Main calls (distinct) |
|---|---|---|---|---|
| eamodio.gitlens 2026.9.250515 | activated | 644 ms | 132 / 146 MB | 974 (38) |
| Anthropic.claude-code 2.1.282 (linux-arm64 vsix) | activated | 876 ms | 164 / 165 MB | 78 (27) |
| golang.Go 0.56.1 | activated | 547 ms | 120 MB | 146 (36) |
| dbaeumer.vscode-eslint 3.0.34 | activated | 420 ms | 103 MB | 27 (16) |
| esbenp.prettier-vscode 12.4.0 (`"type":"module"`, ESM path via `module.registerHooks`) | activated | 371 ms | 103 MB | 25 (17) |
| EditorConfig 0.18.2 | activated | 425 ms | 100 MB | 28 (18) |
| formulahendry.code-runner 0.12.2 | activated | 710 ms | 100 MB | 29 (17) |
| aaron-bond.better-comments 3.0.2 | **activation error** `items is not iterable` (parser.js:236) - its `better-comments.tags` default comes from `contributes.configuration`, which the main side must put into `$initializeConfiguration.defaults`; fake main sent none | 461 ms | 101 MB | 21 (16) |

Union of main-thread methods these 8 extensions called during activation: **50 distinct methods** (of 524). Beyond the hello set:
`$registerTextContentProvider`, `$registerSerializer`, `$createWebviewPanel`, `$setHtml`, `$setIconPath`, `$registerWebviewViewProvider`,
`$registerTreeViewDataProvider`, `$setTitle`, `$registerTextEditorDecorationType`, `$registerDecorationProvider`, `$registerUriHandler`,
`$registerFileSystemProvider`, `$watch`, `$setEnvironmentVariableCollection`, `$setValue`, `$getPassword`,
`$registerExtensionStorageKeysToSync`, `$registerCompletionsProvider`, `$registerDocumentLinkProvider`, `$registerCodeLensSupport`,
`$registerDocumentFormattingSupport`, `$setLanguageConfiguration`, `$setLanguageStatus`, `$registerTaskProvider`,
`$registerTextEditorProvider`, `$registerDebugConfigurationProvider`, `$registerDebugAdapterDescriptorFactory`, `$executeCommand`.
Caveat: "activated" = `activate()` resolved against a stub main side; it does not prove features work (no documents, no git
extension, no real UI). It does prove the API surface, module loading (CJS + ESM), `require('vscode')` interception and
activation machinery are complete with zero shim work.

Remaining work to make route 2 real (estimate, agent-days): product/NLS injection + JS logger + natives stubs 2; adapter
skeleton + transport to Kotlin 3-5; config defaults/workspace/docs+editors sync 5-8; per in-scope shape handlers see section 4.

## 4. Protocol and the size of the main-side job

Wire format (`workbench/services/extensions/common/rpcProtocol.ts`):
- Message header: `type u8 | req u32` (`MessageBuffer.alloc` `:518-523`); types (`:940-953`): 1 RequestJSONArgs, 2 ...WithCancellation,
  3 RequestMixedArgs, 4 ...WithCancellation, 5 Acknowledged, 6 Cancel, 7 ReplyOKEmpty, 8 ReplyOKVSBuffer, 9 ReplyOKJSON,
  10 ReplyOKJSONWithBuffers, 11 ReplyErrError, 12 ReplyErrEmpty.
- Request: `rpcId u8 | method (u8 len + UTF-8) | args` (`:767-781`). JSON args = one JSON array (u32 len). Mixed args (`:645-671`,
  ArgType `:955`: 1 String(JSON), 2 VSBuffer, 3 SerializedObjectWithBuffers, 4 Undefined) are used when any arg is a VSBuffer,
  `SerializableObjectWithBuffers` or `undefined` (`:716-756`). Buffers inside objects are replaced by `{"$$ref$$": i}` and appended
  (`:32-62`); `undefined` inside objects is `{"$$ref$$": -1}`. Replies analogous (`:837-876`).
- URIs travel as `UriComponents` with `$mid: 1` (`base/common/marshallingIds.ts:7`) and are revived by the receiver (`URI.revive`,
  e.g. `extensionHostMain.ts:222-238`); an optional `IURITransformer` rewrites them for remote authorities (`:64-95`).
- Methods: only `$`-prefixed names are proxied (`_createProxy`, `:249-264`). Every request is acked (`:377-380`); main side marks the
  host unresponsive after 3 s without ack (`:119`). Cancellation: a trailing `CancellationToken` arg is popped, a `Cancel` message is
  sent on cancel (`:461-496`); the receiver appends a fresh token for `...WithCancellation` requests (`:364-368`).
- `SerializableObjectWithBuffers` (`proxyIdentifier.ts:81-85`) marks large payloads (workspace edits, notebook data) for buffer-ref
  encoding. Errors serialise as `{$isError, name, message, stack}` (`:427-438`).
- **rpcId = creation order** of `createProxyIdentifier` (`proxyIdentifier.ts:33-52`); both ends must be built from the same
  `extHost.protocol.ts`. Measured id stability: added identifiers per ~month 1.100->1.130: 6, 2, 10, 2, 2, 4, shifting 70-144
  of the ids each time; 0 changes 1.130->1.139. => A non-JS peer (Kotlin) speaking this protocol must regenerate its id table per
  release; a JS adapter bundled from the same commit gets it for free. Another reason for route 2b over "Kotlin speaks rpcProtocol".

Method counts (from `lib/deps-protocol.mjs`, per proxy identifier; `$` methods incl. inherited; buckets: out = debug, notebook/
interactive, chat/lm/mcp/ai/speech/agents, data channels/browsers; remote = tunnels/managed sockets/share/profile/power/metered):

| Direction | Ids | Methods | In scope | Out of scope | Remote-only |
|---|---|---|---|---|---|
| MainThread (main implements; `extHost.protocol.ts:4046`) | 87 | 524 | **53 ids / 328 methods** | 27 / 168 | 7 / 28 |
| ExtHost (main calls; `:4136`) | 81 | 465 | 48 / 300 | 26 / 141 | 7 / 24 |

(The EXTRACT agent's `vscode-exthost-shapes.json` counts 522 / 467 over shape interfaces; +-2 differences come from inheritance.)
Largest in-scope main shapes: LanguageFeatures 47, Terminal 23, Testing 21, Workspace 20, SCM 17, Authentication 15, TextEditors 13,
FileSystem 12, Task 10, TreeViews 9, Comments 9, ExtensionService 8, Search 8, WebviewPanels 7. Largest ext-side: LanguageFeatures 72,
Terminal 27, Testing 17, SCM 15, FileSystem 14, CustomEditors 13, ExtensionService 12, Task 11, Authentication 10.
DTOs to produce/consume on the main side are VS Code's (e.g. `ISuggestDataDtoField` compact completion encoding `:2666`,
`IWorkspaceEditDto` `:2841`, `IDocumentsAndEditorsDelta` `:2451-2460`, `ISerializedModelContentChangedEvent` `:2402`).

Route 1 comparison: `docs/extension-host/arch.md` specifies ~100 method names + 23 provider kinds, but the host side must then
re-implement what VS Code keeps in `api/common` (49,402 non-blank LOC; ~39,076 excluding out-of-scope files) + `api/node` (3,718).
Corpus demand (`docs/vsx-compat/data/corpus-usage.json`, 156 extensions, 151 use the API): to fully cover extensions summing to
50 / 90 / 99 / 100% of weighted downloads a shim needs **330 / 481 / 532 / 550 distinct API symbols** (median 90 per extension,
p90 187, max 247). "A defined subset" is not small: 99% needs 532 symbols, each with VS Code semantics.

## 5. Upgrade cost (measured churn)

Cadence changed: monthly until 1.110 (2026-03-06); **weekly** since 1.111.0 (2026-03-06 ... 1.139.0 2026-09-22).
`git diff --numstat` between shallow tags (`lib/deps-churn.mjs`; +added/-deleted lines, files):

| Step | Days | vscode.d.ts | proposed d.ts | extHost.protocol.ts | workbench/api/** | api common+node | api/browser (main impls) | Stable API decls +/- |
|---|---|---|---|---|---|---|---|---|
| 1.100->1.105 | 154 | +462/-40 | 28f +1165/-459 | +158/-71 | 114f +9061/-3376 | 70f +6355/-3006 | 31f +1670/-303 | +57/-0 |
| 1.105->1.109 | 119 | +374/-219 | 43f +1832/-422 | +292/-64 | 146f +6947/-1849 | 61f +3573/-1048 | 51f +1592/-640 | +18/-0 |
| 1.109->1.115 | 62 | +26/-1 | 22f +2177/-80 | +408/-29 | 86f +6426/-889 | 54f +3555/-522 | 22f +1549/-230 | +4/-0 |
| 1.115->1.120 | 35 | +3/-1 | 17f +937/-66 | +165/-21 | 41f +2358/-523 | 21f +1156/-297 | 15f +720/-223 | 0 |
| 1.120->1.125 | 34 | +4/-4 | 12f +277/-20 | +66/-3 | 38f +1541/-189 | 20f +932/-132 | 12f +291/-56 | 0 |
| 1.125->1.130 | 37 | 0 | 19f +361/-116 | +64/-3 | 50f +2184/-284 | 26f +911/-211 | 16f +453/-69 | 0 |
| 1.130->1.135 | 34 | +1/-1 | 9f +237/-4 | +17/-4 | 42f +2048/-131 | 19f +481/-71 | 11f +237/-45 | 0 |
| 1.135->1.139 | 28 | +3/-0 | 7f +51/-13 | +3/-2 | 36f +1218/-131 | 14f +85/-90 | 9f +57/-25 | 0 |

Weekly steps 1.130->1.139 (9 releases): vscode.d.ts changed in 2 (1-3 lines), extHost.protocol.ts in 7 (1-14 added lines),
api/** 5-25 files, +30..+1,294 lines per week (median ~+220). Stable declarations: 2,649 (1.100) -> 2,667 (1.109) -> **2,671 since
1.115**, i.e. zero new stable API in the last 24 weekly releases; growth is in proposed API (148 -> 180 `vscode.proposed.*.d.ts`).
Implication: route 1's "new API" cost is ~0/month now, but its fidelity-bug cost is unbounded; route 2's cost is a rebuild per
upstream bump (host is free; the adapter tracks in-scope DTO/protocol edits: ~0-20 protocol lines/month lately) and re-running the
corpus harness. We need not track weekly: pin a release and bump monthly or quarterly.

## 6. Licences (verified)

| Component | Licence text / expression | Source |
|---|---|---|
| VS Code source | "MIT License / Copyright (c) 2015 - present Microsoft Corporation / Permission is hereby granted, free of charge, ..." | `$VS/LICENSE.txt:1-5` |
| ext-host closure | all files Microsoft MIT headers except marked 14.0.0 (MIT), semver 5.5.0 (ISC), path.ts Node/Joyent (MIT); both vendored listed in `ThirdPartyNotices.txt:1829,2235` | section 1 |
| inlined npm deps | MIT (all 14 installed packages); kerberos Apache-2.0 (not bundled) | registry.npmjs.org metadata |
| Theia (all packages) | `"license": "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0"`; NOTICE: "SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0" | `theia/packages/plugin-ext/package.json:76`, `theia/NOTICE.md`, per-file headers e.g. `plugin-ext/src/plugin/plugin-context.ts:14`; `LICENSE-vscode.txt` covers code copied from VS Code |
| openvscode-server | "MIT License / Copyright (c) 2015 - present Microsoft Corporation" | raw.githubusercontent.com/gitpod-io/openvscode-server/main/LICENSE.txt |
| code-server | "The MIT License / Copyright (c) 2019 Coder Technologies Inc." | raw.githubusercontent.com/coder/code-server/main/LICENSE; npm `code-server@4.138.0` license "MIT" |
| OpenSumi | "The MIT License (MIT) / Copyright (c) 2019-present Alibaba Group Holding Limited, Ant Group Co. Ltd." | raw.githubusercontent.com/opensumi/core/main/LICENSE |

easyIDE is PolyForm Noncommercial (ADR 0008). MIT/ISC code can be bundled with notices (we must ship `LICENSE.txt` +
ThirdPartyNotices entries for marked/semver/Node path). EPL-2.0 is file-level copyleft (ADR 0001 license correction). Not a blocker
either way; route 2 is the least encumbered. Microsoft trademarks: do not call the product "VS Code"; `appName` in initData is ours.

## 7. Node runtime on the target

| Fact | Evidence |
|---|---|
| VS Code builds with Node **24.18.0**, remote/server target **24.21.0** | `$VS/.nvmrc`; `$VS/remote/.npmrc` (`target="24.21.0"`) |
| code-server requires Node 24 | npm `code-server@4.138.0` `engines.node: "24"`; `code-server/package.json:106` |
| Ext host needs `module.registerHooks` (Node >= 22.15.0 / 23.5.0) for `import 'vscode'` from ESM extensions; called unconditionally | `api/node/extHostExtensionService.ts:130`; nodejs.org/api/module.html: "registerHooks(options) # Added in: v23.5.0, v22.15.0" |
| Patches `Module._load` (blocks `natives`), `_resolveFilename`, `_resolveLookupPaths` for `require('vscode')` | `extensionHostProcess.ts:81-92`; `extHostExtensionService.ts:43-75` |
| Rewrites `process.execArgv` (drops `--inspect-port=0`), overrides `process.exit`/`process.on('uncaughtException')`, sets `ELECTRON_RUN_AS_NODE=1`, hides global `navigator` | `extensionHostProcess.ts:56-63,97-137,142-149` |
| Uses `createRequire(import.meta.url)`, `Error.prepareStackTrace` hook | `extensionHostProcess.ts:30-31`; `extensionHostMain.ts:94-115` |
| No `worker_threads`, `v8.setFlagsFromString`, `--max-old-space-size` or `enableCompileCache` in the closure (grep over all 890 files) | grep; compile cache is opt-in via env `NODE_COMPILE_CACHE` (`module.enableCompileCache` added v22.8.0, nodejs.org) |
| Spike ran on Node **22.22.2** (below VS Code's 24) with no failures | section 3 |
| Our rootfs is Ubuntu noble; apt `nodejs` there is **18.19.1** (no `registerHooks`) - ADR 0030's "apt-get install nodejs" would break ESM extensions (and the ext host's interceptor install) | `docs/sandbox-runtime/tracker.md:53`; packages.ubuntu.com/noble/nodejs "Package: nodejs (18.19.1+dfsg-6ubuntu5)"; resolute (26.04) has 22.22.1 |
| Official Node 24 linux-arm64: `node-v24.18.0-linux-arm64.tar.xz` 30,473,480 bytes (latest 24.x = v24.21.0, 2026-09-07, LTS "Krypton") | nodejs.org/dist (HEAD content-length, index.json) |

=> Whatever the route, provision Node >= 22.15 (prefer 24 LTS) from nodejs.org tarballs in the guest, not apt on noble.

## 8. Route 3: Theia plugin-ext and "real workbench in a WebView"

Theia (clone `eclipse-theia/theia` master 74ea9a0, 2026-09-25, v1.76.0 released 2026-09-24):
- `packages/plugin-ext`: 372 TS files, 61,751 non-blank LOC non-test (common 6,378; plugin host side `plugin/` 28,106; frontend `main/`
  26,158; `hosted/` 6,212). `plugin-ext-vscode`: 20 files / 2,200 LOC. 48 dependencies including 31 `@theia/*` packages (core,
  monaco, monaco-editor-core, notebook, debug, ai-core, ai-mcp, terminal, scm, task, test, ...); 35 plugin-host files use InversifyJS.
  The plugin host imports `@theia/core` 291 times.
- Own RPC: `plugin-ext/src/common/plugin-api-rpc.ts` (2,934 lines): 50 `*Main` interfaces / 288 `$` methods, 45 `*Ext` / 229.
  Main side is Theia's browser frontend (Inversify DI + Monaco); no headless main side exists for VS Code extensions
  (`plugin-ext-headless` hosts Theia backend plugins only, its README).
- Compatibility claims: `DEFAULT_SUPPORTED_API_VERSION = '1.139.0'` (`dev-packages/application-package/src/api.ts:21`).
  vscode-theia-comparator report (eclipse-theia.github.io/vscode-theia-comparator/status.html, fetched 2026-09-25; repo last commit
  2026-09-09): of 2,655 API entries present in VS Code 1.139.0, Theia v1.76.0 marks **2,400 Supported (90.4%), 231 Stubbed,
  24 Partial**; stubbed are overwhelmingly `Chat*`/`LanguageModel*` members and terminal `shellIntegration`; partial include
  `ExtensionContext`, `Terminal`, `createTerminal`, `createOutputChannel`, `TestRun`, `DocumentDropEditProvider`, `Position/Range.with`.
- Using Theia means either (a) the whole Theia app (Node backend in guest + browser frontend in a WebView) - contradicts ADR 0006's
  native Compose shell ("on a tablet a native explorer and editor will stay faster than a WebView") - or (b) its plugin host with our
  own implementation of Theia's 288-method RPC - the same adapter job as route 2 but against a re-implementation, EPL-licensed,
  with Theia's lag (it tracks VS Code monthly; 90% vs route 2's 100% by construction).

openvscode-server / code-server (full VS Code web workbench served from the guest, shown in a WebView):
- Licences MIT (section 6). openvscode-server: latest tag `openvscode-server-v1.109.5`, main last commit **2026-02-20** (7 months,
  30 weekly upstream releases behind) - maintenance risk. code-server: active (last commit 2026-09-20, v4.138.0), release asset name
  `code-server-$VERSION-linux-arm64.tar.gz` (`install.sh:399`, `ci/build/build-packages.sh:29-41`); tarball size UNVERIFIED
  (github.com releases blocked here; verify with a browser or `curl -I` elsewhere). npm package: 53,513,679 bytes tgz, 208,528,207
  bytes / 1,379 files unpacked (registry `dist.unpackedSize`), plus a `postinstall` that installs native modules (argon2, node-pty...).
  Docs recommend "1 GB of RAM, 2 CPU cores" (`docs/requirements.md:6-9`); Android is documented only via UserLAnd/Nix-on-Droid with
  Node 24 via nvm (`docs/android.md`). Memory on the tablet UNVERIFIED (server + ext host + a Chromium WebView renderer with the
  workbench; expect several hundred MB; measure with `dumpsys meminfo <pkg>` + guest `/proc/*/status`).
- UX: it replaces our Compose shell with Microsoft's desktop-first workbench inside a WebView (ADR 0006 rejects that as the core
  surface; ADR 0001's alternative note: "harder to reskin for touch"). Two editors, two explorers, two settings systems. Fidelity is
  highest (it is VS Code), integration with our native editor/LSP router/terminal is lowest.
- OpenSumi (MIT) is another full IDE framework with its own ext host; same shell-replacement problem; not evaluated further.

## 9. Comparison

| Criterion | Route 1: own `vscode` shim + own JSON-RPC (ADR 0030) | Route 2b: vendored VS Code ext host + JS adapter in guest -> Kotlin | Route 3a: Theia (app in WebView or plugin host + own main) | Route 3b: code-server/openvscode in WebView |
|---|---|---|---|---|
| Licence (verified) | ours | MIT (+ISC semver, MIT deps) | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | MIT |
| API surface in the host | what we write; 99% weighted corpus needs 532 symbols; no code exists yet (`services/mobile/exthost` absent) | **all 2,671 stable decls + proposed**, day 1 (7/8 real extensions activated unmodified) | 90.4% of 1.139 (2,400/2,655) | 100% |
| Time to broad coverage | months: re-implement ~39k LOC of VS Code semantics piecemeal | host: 0; adapter: in-scope 328 main methods, but 50 cover activation of 8 top extensions; features land per shape | (a) weeks to embed but replaces shell; (b) like 2b but 288 methods vs a re-impl | days to embed; replaces shell |
| Fidelity (TextDocument sync, WorkspaceEdit, event order, disposal, cancellation, errors) | re-derived, drift risk everywhere (line model, `version`, `eol`, edit conversion, `onDid*` ordering) | identical to VS Code for everything inside the host (`ExtHostDocumentData`, `extHostTypeConverters.WorkspaceEdit` `:634`, `ExtHostDocumentsAndEditors`); main side must emit correct deltas | Theia's re-implementation | identical |
| Bundle | est. 0.2-0.5 MB (UNVERIFIED, not written) | host 3.0 MB min / 0.85 MB gz self-contained; adapter ~0.2 MB min; -22% possible by stubbing chat/debug/notebook | Theia app: tens of MB (UNVERIFIED) | code-server npm 53.5 MB tgz / 208.5 MB unpacked |
| Startup / memory (x86 proxy) | Node floor 42 MB, ~40 ms + shim; est. 50-70 MB idle (UNVERIFIED) | hello 248-407 ms, 97-113 MB; GitLens 146 MB HWM, Claude Code 165 MB | UNVERIFIED (Node backend + WebView) | UNVERIFIED; docs ask 1 GB RAM |
| Entanglement | none | 890 files / 277,516 LOC reachable; 601 bundled; zero source edits needed | 31 @theia deps, Inversify, Monaco | whole workbench |
| Main-side work | ~100 methods + 23 provider kinds, simpler DTOs, but host work is where the cost is | 328 in-scope methods (53 shapes), VS Code DTOs; JS adapter shields Kotlin from rpcIds and buffers | 288 methods (Theia DTOs) or none (take Theia UI) | none (but no native UI) |
| Upgrade per month (measured) | vscode.d.ts: ~0 stable decls since 1.115; compat bugs unbounded | rebuild + adapter delta: extHost.protocol.ts +3..+17 lines/month lately; api/** +1.2-2k lines (mostly proposed/chat) | follow Theia monthly releases | follow code-server; openvscode stalled since 2026-02 |
| Risk | High: silent semantic gaps; the "table-driven coverage" never reaches the long tail | Med: Node >= 22.15 in guest, memory on 8 GB tablets, adapter correctness; private protocol (pinned per bump) | High: licence + shell duplication + 10% gap | High: UX mismatch with ADR 0006, memory, WebView perf |

## 10. Recommendation and device go/no-go

Decision: **go with route 2b** (vendor `extensionHostProcess` bundle; JS main-thread adapter in the guest built from the same VS Code
tag; Kotlin receives semantic JSON-RPC calls). Supersede ADR 0030 decision 2 and the "Port VS Code's extension host" rejection
(the "coupled to VS Code's service container" concern is disproved: it bundles and runs standalone with no edits). Keep route 1's
arch.md method list as the adapter<->Kotlin protocol where it maps 1:1 onto `MainThread*` calls. Do not pursue route 3.

Device spike (Xiaomi Pad 6 / arm64 / proot / Ubuntu noble), run exactly `$SP/spike` (bundle B + renderer.mjs + hello + the 8 real
extensions) under Node 24.x linux-arm64 from nodejs.org in the guest. Measure: wall time from `spawn` to `$onDidActivateExtension`
(renderer stamps), `/proc/<pid>/status` VmRSS/VmHWM of the host, `$test_latency` p50/p99, 5 cold (drop compile cache) + 5 warm runs;
also `dumpsys meminfo` of the app to see total PSS impact.

| Gate | Threshold (go) | Why this number |
|---|---|---|
| G1 cold start to hello activated (compile cache warm) | <= 1.5 s (no-go > 3 s) | x86 warm = 0.25 s; allow ~4-6x for Snapdragon 870 + proot syscall overhead on ~1 file; 3 s is VS Code's own unresponsive threshold (`rpcProtocol.ts:119`) |
| G2 first-ever cold start (no cache) | <= 4 s | one-time after install; below Android ANR-style patience; compile cache then applies |
| G3 idle host RSS with hello | <= 150 MB (no-go > 200 MB) | ADR 0030 budgets 60-150 MB idle; x86 = 97 MB; arm64 V8 pointer compression is similar |
| G4 RSS with GitLens + Claude Code + ESLint activated in one host | <= 300 MB | x86 single-extension HWMs 146/165 MB; must fit beside 1-2 language servers on an 8 GB tablet (LSP memory budget, ADR 0017 as cited by ADR 0030) |
| G5 RPC round trip p50 / p99 (same socket) | <= 10 ms / <= 40 ms | x86 2.6 / 6.9 ms; completion/hover need < 50 ms end-to-end including the Kotlin hop |
| G6 activation success on the 8 extensions with config defaults fed | 8/8 | 7/8 on x86 with a stub; the 8th needs only config defaults |
| G7 no native module required at startup | 0 `ERR_MODULE_NOT_FOUND`/dlopen failures other than spdlog (to be replaced) | verified on x86 |

If G1-G5 fail by < 2x: stub chat/mcp/debug/notebook out of the bundle (-22% bytes) and retest before reconsidering; if > 2x or G4 fails,
fall back to route 1 **for a declared subset only** and keep route 2 for a "full host" opt-in. Estimated effort after a device go:
host packaging 2 d, adapter core (lifecycle, config, workspace, documents/editors, commands, window messages/quick pick/input,
output, status bar, storage/secrets) 10-15 d, language features 10 d, tree/webview/decorations 10-15 d, SCM/terminal/tasks later;
each shape verified by the corpus harness driving the adapter headlessly (the spike renderer is its seed).

## 11. UNVERIFIED / not done

- All performance numbers are x86 (Xeon 2.1 GHz); arm64/proot numbers need the device run above.
- code-server linux-arm64 release tarball size, openvscode-server release sizes (github.com blocked); memory of route 3 options.
- Route 1 bundle/startup numbers are estimates (no route 1 code exists).
- Extension "activated" means `activate()` resolved against a stub main side, not that features work.
- The Theia comparator figures are Theia's own report, not our measurement.
