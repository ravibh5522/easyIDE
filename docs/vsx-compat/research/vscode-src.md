# VS Code source notes (agent VSCODE-SRC)

All `src/...` paths are relative to the microsoft/vscode clone (`$VS`, MIT). Line numbers are for the commit below.

| item | value |
|---|---|
| vscode commit | `0b16cb97868058754d545e5f9ced99211ccc290b` (committer date 2026-09-25T16:19:08+02:00) |
| package.json version | `1.140.0` (main branch, pre-release of 1.140) |
| Microsoft release used for product.json | stable `1.139.1`, commit `04c0d99f4fb0d8afe6ce4f0c58e31e183ac3e4b1`, date 2026-09-25T03:40:57Z (from `https://update.code.visualstudio.com/latest/server-linux-x64/stable` -> vscode-server-linux-x64.tar.gz, cached under `/root/.cache/easyide-corpus/vscode-stable/`) |
| extractor | `tools/vsx-audit/extract-vscode.mjs` + `tools/vsx-audit/lib/vscode-*.mjs` (TypeScript 5.9.3 compiler API, `ts.createSourceFile` per file, no type checker) |
| docs pages | fetched as raw markdown from `https://code.visualstudio.com/raw/api/references/<page>.md` (the "View as Markdown" link on each HTML page); sha256 in each JSON `meta.source` |

Regenerate:
```
cd tools/vsx-audit && npm install
node tools/vsx-audit/extract-vscode.mjs --vscode $VS --out docs/vsx-compat/data \
  --product /root/.cache/easyide-corpus/vscode-stable/vscode-server-linux-x64/product.json --docs-cache $SP/vscode-docs
```
Two consecutive runs produce byte-identical output (md5 checked). Runtime ~20 s (6,860 non-test `.ts` files under src/vs).

## 1. Generated data (docs/vsx-compat/data/)

| file | items | key numbers |
|---|---|---|
| vscode-api.json | 743 | class 122, enum 64, interface 302, type 20, variable 1 (`version`), namespace 16; namespace members 218 unique names = 121 functions + 55 events + 36 consts + 6 lets; **251 counting each overload** |
| vscode-proposed.json | 180 | 180 `vscode.proposed.*.d.ts` (13,161 lines), all 180 present in `src/vs/platform/extensions/common/extensionsApiProposals.ts` (no `version:` fields at this commit) |
| vscode-contributes.json | 60 | 59 registered extension points; 38 documented; 22 registered-but-undocumented; 1 documented-not-registered (`typescriptServerPlugins`, read by the built-in TS extension) |
| vscode-activation-events.json | 42 | 29 documented, 33 in manifest schema, 19 implicit (generated from contributes), 13 undocumented |
| vscode-when-context.json | 1,281 keys + 16 operators | 1,138 RawContextKey names (1,140 declarations), 110 documented keys, 109 keys only seen in `ContextKeyExpr.*()`, 6 only via `createKey()` |
| vscode-menus.json | 96 | 54 stable + 42 proposal-gated `contributes.menus` keys; 262 `MenuId` statics in actions.ts |
| vscode-exthost-shapes.json | 169 | 87 MainThread*Shape (522 methods), 82 ExtHost*Shape (467 methods); 87 MainContext + 81 ExtHostContext proxy ids |
| vscode-builtin-commands.json | 87 | 87 documented rows (86 unique; `notebook.selectKernel` is listed twice in the docs), 43 implemented as `ApiCommand` in extHostApiCommands.ts, all 87 have a string-literal reference in src |

### Claimed vs actual counts
| claim | actual | explanation |
|---|---|---|
| ~250 namespace members | 218 unique / **251 with overloads** | the claim matches when each overload of a namespace function counts separately (e.g. `window.showInformationMessage` has 4 overloads). 16 namespaces: authentication 4, chat 1, commands 4, comments 1, debug 18, env 21, extensions 3, l10n 3, languages 40, lm 7, notebooks 3, scm 2, tasks 8, tests 1, window 57, workspace 45 |
| 122 classes | 122 | exact |
| 64 enums | 64 | exact |
| 302 interfaces | 302 | exact |
| 87 MainThread shapes | 87 | exact. A regex `\w*Shape$` finds only 86 because `MainThreadChatAgentsShape2` ends in `2` |
| 82 ExtHost shapes | 82 | exact (same `ExtHostChatAgentsShape2` caveat). ExtHostContext has 81 ids: `ExtHostHeapServiceShape` (no id and no implementation, dead) and `ExtHostNotebookDocumentsAndEditorsShape` (no id of its own; it is the base of `ExtHostNotebookShape`) |

Deprecated in stable API (all declarations @deprecated): `AuthenticationForceNewSessionOptions`, `MarkedString`, `scm.inputBox`, `window.withScmProgress`, `workspace.registerTaskProvider`, `workspace.rootPath`.

### Registered contribution points (* = not on the contribution-points docs page)
authentication, breakpoints, chatAgents, chatContext*, chatInstructions, chatOutputRenderers*, chatParticipants*, chatPlugins*, chatPromptFiles, chatSessions*, chatSkills, chatViewsWelcome*, colors, commands, configuration, configurationDefaults, continueEditSession*, css*, customEditors, debugVisualizers*, debuggers, grammars, iconThemes, icons, jsonValidation, jsonValidationRegistry*, keybindings, languageModelChatProviders, languageModelToolSets*, languageModelTools, languages, linkPresentationProviders*, localizations*, mcpServerDefinitionProviders*, menus, notebookPreload*, notebookRenderer*, notebooks*, problemMatchers, problemPatterns, productIconThemes, remoteCodingAgents*, remoteHelp*, resourceLabelFormatters, semanticTokenModifiers, semanticTokenScopes, semanticTokenTypes, snippets, speechProviders*, statusBarItems*, submenus, taskDefinitions, terminal, terminalQuickFixes*, themes, views, viewsContainers, viewsWelcome, walkthroughs.
Implicit activation generators (`activationEventsGenerator`) on 20 points: authentication, chatContext, chatOutputRenderers, chatParticipants, chatSessions, commands, customEditors, debugVisualizers, languageModelChatProviders, languageModelTools, languages, linkPresentationProviders, mcpServerDefinitionProviders, notebookRenderer, notebooks, taskDefinitions, terminal, terminalQuickFixes, views, walkthroughs.
Registration mechanism: `ExtensionsRegistryImpl.registerExtensionPoint` `src/vs/workbench/services/extensions/common/extensionsRegistry.ts:675`: rejects duplicates, registers the generator with `ImplicitActivationEvents.register` (:681-682), and adds the jsonSchema under `contributes.<point>` in the package.json schema (:685). No other mechanism was found. `typescriptServerPlugins` is read directly by `extensions/typescript-language-features/src/tsServer/plugins.ts:73`. `proposalChecksInFile` in the JSON lists the `isProposedApiEnabled`/`checkProposedApiEnabled` names found in the handler's file (a per-file heuristic; e.g. views: `contribViewsRemote`).

### Activation events (event, * = undocumented, (i) = implicit)
`*`, onAuthenticationRequest:(i), onChatContextProvider:*(i), onChatOutputRenderer:*(i), onChatParticipant:(i), onChatSession:*(i), onCommand:(i), onCustomEditor:(i), onDebug, onDebugAdapterProtocolTracker:, onDebugDynamicConfigurations, onDebugInitialConfigurations, onDebugResolve:, onDebugVisualizer:*(i), onEditSession:, onFileSystem:, onIssueReporterOpened, onLanguage:(i), onLanguageModelChatProvider:*(i), onLanguageModelTool:(i), onLinkPresentation:*(i), onMcpCollection:*, onNotebook:, onNotebookSerializer:*(i), onOpenExternalUri:, onProfile:*, onRenderer:(i), onResolveRemoteAuthority:*, onSearch:, onSlash:*, onSpeech*, onStartupFinished, onTaskType:(i), onTerminal:, onTerminalProfile:(i), onTerminalQuickFixRequest:*(i), onTerminalShellIntegration:, onUri:, onView:(i), onWalkthrough:(i), onWebviewPanel:, workspaceContains:.
`onUri` is rewritten to `onUri:<extensionKey>` in `src/vs/platform/extensionManagement/common/implicitActivationEvents.ts:58-59`.

### when-clause operators (src/vs/platform/contextkey/common/scanner.ts, parser `contextkey.ts:174` `class Parser`)
`!` `==`/`===` `!=`/`!==` `<` `<=` `>` `>=` `=~ /re/flags` `in` `not in` `&&` `||` `(` `)` `true` `false`. The scanner switch is at scanner.ts:205-256; `===`/`!==` are accepted (`isTripleEq`, :210/:222); precedence is or (:242) < and (:253) < not (:262). There are 16 `ContextKeyExprType` node kinds. File:line for each is in `vscode-when-context.json#operators`.

### product.json `extensionEnabledApiProposals` (Microsoft stable 1.139.1; not in the OSS repo product.json)
65 entries in total. For the requested ids (case-insensitive):
| id | entry |
|---|---|
| github.vscode-pull-request-github (key `GitHub.vscode-pull-request-github`) | 34 proposals (activeComment, chatParticipantAdditions, chatSessionsProvider, contribCommentThreadAdditionalMenu, shareProvider, quickDiffProvider, tabInputMultiDiff, treeItemMarkdownLabel, ...; full list in vscode-proposed.json) |
| ms-python.python | 11: codeActionAI, contribEditorContentMenu, notebookReplDocument, notebookVariableProvider, portsAttributes, quickPickItemTooltip, quickPickSortByLabel, terminalDataWriteEvent, terminalExecuteCommandEvent, terminalShellEnv, testObserver |
| dbaeumer.vscode-eslint | present, `[]` (empty) |
| eamodio.gitlens, anthropic.claude-code, redhat.java, golang.go, rust-lang.rust-analyzer, vue.volar, ms-azuretools.vscode-docker | **absent** (these extensions cannot use proposed API in stable) |
`extensionAllowedProposedApi` exists in neither product.json. `extensionsEnabledWithApiProposalVersion` has 4 entries.

## 2. Extension host file sizes (src/vs/workbench/api/)
| file | lines | bytes |
|---|---|---|
| src/vscode-dts/vscode.d.ts | 21,239 | 742,274 |
| common/extHost.protocol.ts | 4,218 | 195,843 |
| common/extHost.api.impl.ts (`createApiFactoryAndRegisterActors` :150; 153 `checkProposedApiEnabled` calls) | 2,343 | 133,785 |
| common/extHostTypes.ts (+ common/extHostTypes/ 14 files, 1,617 lines) | 4,317 | 110,481 |
| common/extHostTypeConverters.ts | 4,336 | 155,453 |
| common/extHostLanguageFeatures.ts | 3,056 | 134,115 |
| common/extHostExtensionService.ts | 1,261 | 55,420 |
| common/extHostExtensionActivator.ts | 454 | 15,488 |
| common/extHostWorkspace.ts | 1,218 | 51,488 |
| common/extHostTerminalService.ts | 1,353 | 55,406 |
| common/extHostWebview.ts / WebviewPanels / WebviewView / WebviewMessaging | 314 / 330 / 224 / 122 | 11,575 / 10,561 / 6,576 / 6,389 |
| common/extHostTreeViews.ts | 1,121 | 44,014 |
| common/extHostDocuments.ts / DocumentsAndEditors.ts | 229 / 197 | 8,576 / 7,183 |
| common/extHostTextEditors.ts / extHostTextEditor.ts (the brief's "extHostEditors.ts" does not exist) | 230 / 690 | 10,325 / 21,407 |
| common/extHostDecorations.ts | 121 | 4,874 |
| common/extHostDiagnostics.ts | 371 | 12,762 |
| common/extHostSCM.ts | 1,280 | 45,646 |
| common/extHostAuthentication.ts (+ node/ 339) | 1,047 | 45,464 |
| common/extHostStorage.ts | 75 | 2,832 |
| common/extHostSecrets.ts / extHostSecretState.ts | 51 / 42 | 1,758 / 1,831 |
| common/extHostTask.ts (+ node/ 195) | 849 | 33,136 |
| common/extHostFileSystem.ts / FileSystemConsumer / FileSystemEventService / FileSystemInfo | 350 / 260 / 410 / 57 | 12,958 / 10,939 / 17,585 / 2,083 |
| common/extHostEditorTabs.ts (`ExtHostEditorTabs` :248, `tabGroups` :266) | 444 | 16,635 |
| common/extHostApiCommands.ts | 641 | 33,771 |
| common/extHostCommands.ts / extHostConfiguration.ts | 508 / 347 | 19,582 / 15,414 |
| common/extHost*.ts (114 files, total) | 54,309 | 2,127,997 |
| browser/mainThread*.ts (97 files, total) | 22,467 | 934,195 |
| shape implementation files (MainThread impl files / ExtHost impl files, deduped, from JSON) | 20,713 / 35,733 | 863,533 / 1,403,763 |

api/node/*: extensionHostProcess.ts 475, proxyResolver.ts 501, extHostTunnelService.ts 419, loopbackServer.ts 339, extHostAuthentication.ts 339, extHostDebugService.ts 289, extHostStoragePaths.ts 288, extHostExtensionService.ts 240, extHostBrowserTunnelProxy.ts 232, extHostMcpNode.ts 217, extHostCLIServer.ts 200, extHostTask.ts 195, extHostSearch.ts 167, extHostDiskFileSystemProvider.ts 80, extHostConsoleForwarder.ts 65, extHost.node.services.ts 58, extHostLoggerService.ts 33, extHostDownloadService.ts 31, extHostTerminalService.ts 31, extHostVariableResolverService.ts 13. api/worker/: extensionHostWorker.ts, extensionHostWorkerMain.ts, extHost.worker.services.ts, extHostExtensionService.ts, extHostConsoleForwarder.ts (web worker host).

## 3. Extension host bootstrap (src/vs/workbench/api/node/extensionHostProcess.ts)
| step | line(s) | what happens |
|---|---|---|
| early patches | :56 removeInspectPort, :81-93 `Module._load` hook, :97-157 `patchProcess` (overrides `process.exit`/`process.crash`/`process.on('uncaughtException')`) | extensions cannot exit the host |
| pick the transport | :181 `readExtHostConnection(process.env)`; env names in `src/vs/workbench/services/extensions/common/extensionHostEnv.ts:8-50` | `ExtHostConnectionType` IPC=1 (`VSCODE_EXTHOST_IPC_HOOK` = named pipe/UDS path, :18), Socket=2 (`VSCODE_EXTHOST_WILL_SEND_SOCKET`, :35), MessagePort=3 (`VSCODE_WILL_SEND_MESSAGE_PORT`, :48) |
| MessagePort (Electron utility process, local desktop) | :183-203 | `process.parentPort.on('message')` receives the MessagePortMain; messages are wrapped in `BufferedEmitter<VSBuffer>` |
| Socket (remote server / reconnecting) | :205-270 | sends `{type:'VSCODE_EXTHOST_IPC_READY'}` (:268) over node IPC, gets `VSCODE_EXTHOST_IPC_SOCKET` with a `net.Socket` handle (:220-221), wraps it in `NodeSocket` or `WebSocketNodeSocket` (:228-232, `skipWebSocketFrames`/`permessageDeflate`), then `new PersistentProtocol` (:243) with reconnection (`VSCODE_RECONNECTION_GRACE_TIME` :215; `VSCODE_EXTHOST_IPC_REDUCE_GRACE_TIME` :255) |
| IPC pipe | :272-290 | `net.createConnection(pipeName)` -> `PersistentProtocol(new NodeSocket(...))` |
| handshake | `connectToRenderer` :332-395 | host sends `MessageType.Ready` (:393), receives JSON `IExtensionHostInitData` (:338), exits with `VersionMismatch` if the commit differs (:347), watches parent pid every 1 s + `@vscode/native-watchdog` (:354-385), then sends `MessageType.Initialized` (:390). Message kinds `Initialized, Ready, Terminate` are defined at `src/vs/workbench/services/extensions/common/extensionHostProtocol.ts:123-127` (1-byte messages) |
| start | `startExtensionHostProcess` :397-473 | :439 protocol, :441 renderer, :464 `new ExtensionHostMain(protocol, initData, hostUtils, uriTransformer)` -> `src/vs/workbench/api/common/extensionHostMain.ts:176` `new RPCProtocol(protocol, null, uriTransformer)` |

Wire framing (PersistentProtocol, `src/vs/base/parts/ipc/common/ipc.net.ts`): 13-byte header `HeaderLength = 13` (:290): u8 type, u32BE id, u32BE ack, u32BE payload length (:474-478). `ProtocolMessageType` None/Regular/Control/Ack/Disconnect/ReplayRequest/Pause/Resume/KeepAlive (:263-273). Ack after 2 s, timeout 20 s, keep-alive 5 s, reconnection grace 3 h (:289-313).
RPC layer (`src/vs/workbench/services/extensions/common/rpcProtocol.ts`, 968 lines): each message is u8 `MessageType` + u32 req id (`MessageBuffer.alloc` :518-522), then per type: Request = u8 rpcId (proxy index) + short-string method + long-string JSON args (:776-780). `MessageType` (:940-953): RequestJSONArgs=1, RequestJSONArgsWithCancellation=2, RequestMixedArgs=3, RequestMixedArgsWithCancellation=4, Acknowledged=5, Cancel=6, ReplyOKEmpty=7, ReplyOKVSBuffer=8, ReplyOKJSON=9, ReplyOKJSONWithBuffers=10, ReplyErrError=11, ReplyErrEmpty=12. `ArgType` (:955-960): String, VSBuffer, SerializedObjectWithBuffers, Undefined. Dispatch in `_receiveOneMessage` :280, `_remoteCall` :461, unresponsive threshold 3 s (:119). Proxy ids: `createProxyIdentifier` in `src/vs/workbench/services/extensions/common/proxyIdentifier.ts`; MainContext at extHost.protocol.ts:4046, ExtHostContext at :4136.
Module loading: `vscode` is served by `VSCodeNodeModuleFactory` (`src/vs/workbench/api/common/extHostRequireInterceptor.ts:148-149`), registered at :62. The node host uses `module._load` for CJS and `module.registerHooks` for ESM (`src/vs/workbench/api/node/extHostExtensionService.ts:170-172`), with loaders at :218 (CJS) and :222 (ESM).

## 4. Activation pipeline (src/vs/workbench/services/extensions/)
| concern | ref |
|---|---|
| manifest schema incl. `activationEvents` snippets | common/extensionsRegistry.ts:261-420; `registerExtensionPoint` :675 |
| implicit activation events | `src/vs/platform/extensionManagement/common/implicitActivationEvents.ts:14` (`ImplicitActivationEventsImpl`, `readActivationEvents` :27, `createActivationEventsMap` :38) |
| init: scan, check enablement and proposals, handle extension points | common/abstractExtensionService.ts `_initialize` :464, `_resolveAndProcessExtensions` :516, `checkEnabledAndProposedAPI` :1437, `_doHandleExtensionPoints` :1149, `_handleExtensionPoint` :1227 |
| lazy host start | `_startExtensionHostsIfNecessary` :820; `ExtensionHostStartup.LazyAutoStart` -> `LazyCreateExtensionHostManager` :866-867 (common/lazyCreateExtensionHostManager.ts, 212 lines) |
| activateByEvent (renderer) | :989 public (records `_allRequestedActivateEvents`, returns no-op if `!_registry.containsActivationEvent`), `ActivationKind.Immediate` path :1008/:1025, `_activateByEvent` :1023, `activateById` :1051 |
| extension added at runtime | `_activateAddedExtensionIfNeeded` :404 (`*`, `onStartupFinished`, `workspaceContains` :415-440) |
| registry and cycle detection | common/extensionDescriptionRegistry.ts (423 lines): `deltaExtensions` :103, `_findLoopingExtensions` :122 (dependency cycles are rejected), `containsActivationEvent` :205, lock :262-371 |
| ext host side activation | api/common/extHostExtensionService.ts `_activateByEvent` :298, eager `*` + workspaceContains `_handleEagerExtensions` :671, `onStartupFinished` delayed activation :614-661, `$activate` :1037, `_startExtensionHost` :803 |
| dependency ordering | api/common/extHostExtensionActivator.ts `ExtensionsActivator` :166, `activateByEvent` :217, `_activateExtensions` :239, dependency handling :247-330 (a missing dependency produces `MissingExtensionDependency` :267/:318), `ActivationOperation` :346 |
| workspaceContains scanning | common/workspaceContains.ts (139 lines) :43-109 |
| extension kind / host placement | common/extensionManifestPropertiesService.ts `getExtensionKind` :150, `deduceExtensionKind` :262, per-point kinds :298 |
| proposed API gate | common/extensionsProposedApi.ts (148 lines); product schema for `extensionEnabledApiProposals` extensionsRegistry.ts:707 |

src/vs/platform/extensions/common: extensions.ts (609 lines: `IExtensionContributions` :251, `IRelaxedExtensionManifest` :344, `IExtensionManifest` :376, `ExtensionType` :378, `TargetPlatform` :383-402, `ExtensionIdentifier` :435, `IExtensionDescription` :579); extensionValidator.ts (391 lines: `isValidVersion` :124, `validateExtensionManifest` :242, `isValidExtensionVersion` :333, `isEngineValid` :343); extensionsApiProposals.ts (551 lines, generated, 180 proposals).

## 5. product.json keys relevant to extensions
| key | OSS repo product.json | MS stable 1.139.1 |
|---|---|---|
| extensionsGallery | absent (no Marketplace in OSS builds) | object: serviceUrl `https://marketplace.visualstudio.com/_apis/public/gallery`, itemUrl, publisherUrl, resourceUrlTemplate, extensionUrlTemplate, controlUrl, mcpUrl, nlsBaseUrl, accessSKUs, accessScopes |
| extensionEnabledApiProposals | absent | 65 extension ids |
| extensionsEnabledWithApiProposalVersion | absent | 4 |
| extensionAllowedProposedApi | absent | absent |
| extensionKind | absent | 11 overrides |
| extensionPointExtensionKind | absent | `{"typescriptServerPlugins":["workspace"]}` |
| trustedExtensionAuthAccess | 3 | 5 |
| trustedExtensionProtocolHandlers / trustedExtensionPublishers / extensionPublisherOrgs | absent | 4 / 3 / 1 |
| builtInExtensions | 3 | 4 |
| builtInExtensionsEnabledWithAutoUpdates | 1 | 1 |
| extensionSyncedKeys / extensionVirtualWorkspacesSupport / extensionProperties / extensionsForceVersionByQuality / extensionConfigurationPolicy | absent | 1 / 42 / 2 / 2 / 4 |
| extensionRecommendations, *ExtensionTips, extensionKeywords, extensionAllowedBadgeProviders(+Regex) | absent | 66, tips 10/15/16/43/5/1/7, 79, 33(+1) |
The product schema is declared in `src/vs/base/common/product.ts` (`extensionEnabledApiProposals` :250).

## 6. Extension management (src/vs/platform/extensionManagement/)
| concern | ref / behaviour |
|---|---|
| gallery client | common/extensionGalleryService.ts (2,033 lines): `CURRENT_TARGET_PLATFORM` :35, `getExtensions` :643 (resource API via `extensionUrlTemplate` :626, falls back to the query API :777-831), `query` :1096, `queryRawGalleryExtensions` :1376 (POST JSON :1426), VSIX asset URL `.../Microsoft.VisualStudio.Services.VSIXPackage?redirect=true&targetPlatform=` :326-328 |
| TargetPlatform values | `src/vs/platform/extensions/common/extensions.ts:383-402`: win32-x64, win32-arm64, linux-x64, linux-arm64, linux-armhf, alpine-x64, alpine-arm64, darwin-x64, darwin-arm64, web, universal, unknown, undefined |
| platform detection | common/extensionManagement.ts `getTargetPlatform` :88-131 (linux + `arm64` -> linux-arm64; alpine is a separate platform) |
| compatibility | `isTargetPlatformCompatible` common/extensionManagement.ts:138-167, in order: (1) not compatible if the product is `web` and the extension has no `web` target (:133-136); (2) `undefined` compatible; (3) `universal` compatible; (4) `unknown` not compatible; (5) otherwise only an **exact** match is compatible. There is **no cross-platform fallback** (linux-arm64 never takes a linux-x64 or alpine build) |
| version choice | `getAllTargetPlatforms` :407 (`web` is added or removed based on the `__web_extension` tag), `sortExtensionVersions` :430 (among equal versions, the one for the preferred platform goes first), `filterLatestExtensionVersionsForTargetPlatform` :468 (one latest release + one latest pre-release among compatible versions; a platform-specific build replaces a universal build of the same version) |
| VSIX install | node/extensionManagementService.ts `install(vsix)` :145, `installFromLocation` :176, extraction via `extensionsScanner.extractUserExtension` :313/:388 (`src/vs/base/node/zip.ts` `extract`) |
| download + signature | node/extensionDownloader.ts `download` :64-80 (downloads the `.sigzip` archive and calls the verification service); node/extensionManagementService.ts `downloadExtension` :340-362: the setting `extensions.verifySignature` defaults to true; the install fails unless the status is Success, or the status is NotSigned and the gallery manifest does not require signing (`shouldRequireRepositorySignatureFor` common/extensionManagement.ts:803-808); the check is skipped when `!environmentService.isBuilt` or on linux-armhf |
| signature verification | node/extensionSignatureVerificationService.ts (136 lines) `ExtensionSignatureVerificationService` :51 loads `import('@vscode/vsce-sign')` dynamically (:69-71). If the module is missing, `verify` logs "Could not load vsce-sign module" and returns `undefined` (:77-83), which is then treated as a verification failure when a signature is required (:362) |
| vsce-sign packaging and licence | not in the repo's root package.json. It is a dependency of `@vscode/vsce` in `build/package-lock.json:1932` (`"@vscode/vsce-sign": "^2.0.0"`), allow-listed in `build/package.json:84`, and its native binary is kept by `build/gulpfile.vscode.ts:288` (`**/@vscode/vsce-sign/bin/*`). The MS server tarball ships `node_modules/@vscode/vsce-sign/bin/vsce-sign`. Per registry.npmjs.org, `@vscode/vsce-sign@2.1.0` and `@vscode/vsce-sign-linux-arm64@2.0.6` have `"license": "SEE LICENSE IN LICENSE.txt"`. LICENSE.txt (tarball `vsce-sign-2.1.0.tgz`) is "MICROSOFT SOFTWARE LICENSE TERMS / MICROSOFT VSCE-SIGN": "You may install and use any number of copies of the software only with Microsoft Visual Studio, Visual Studio for Mac, Visual Studio Code, Azure DevOps, Team Foundation Server, Code Spaces and successor Microsoft products and services". Conclusion: **proprietary; not usable by a third-party IDE**. Open VSX signatures need a different verifier |
| dependencies and packs | common/abstractExtensionManagementService.ts `getAllDepsAndPackExtensions` :648-698 (recursive over `extensionDependencies` + `extensionPack`, skips installed ones :658-664), used at install :407-424; dependents check on uninstall :991-1004 |
| scanner | common/extensionsScannerService.ts (1,111 lines) |

## 7. Workbench UI implementations (for UI-surface mapping)
| surface id | implementation (lines) |
|---|---|
| ui.view-container / ui.tree-view | api/browser/viewsExtensionPoint.ts (760; points :252/:258), browser/parts/views/treeView.ts (2,175), viewPane.ts (881), viewPaneContainer.ts (1,326), common/views.ts (913), api/browser/mainThreadTreeViews.ts (407), api/common/extHostTreeViews.ts (1,121) |
| ui.views-welcome | contrib/welcomeViews/common/viewsWelcomeExtensionPoint.ts (81), viewsWelcomeContribution.ts (88) |
| ui.menus / ui.submenus | services/actions/common/menusExtensionPoint.ts (1,297; apiMenus :37, commands :911, submenus :989, menus :1047), platform/actions/common/actions.ts (782; 262 MenuIds), platform/actions/browser/menuEntryActionViewItem.ts (672) |
| ui.keybindings | services/keybinding/browser/keybindingService.ts (1,018; extension point :130, handler :228), platform/keybinding/common/keybindingsRegistry.ts (274), keybindingResolver.ts (415) |
| ui.walkthroughs | contrib/welcomeGettingStarted/browser/gettingStartedExtensionPoint.ts (228), gettingStartedService.ts (772), gettingStarted.ts (1,799) |
| ui.theme-colors | services/themes/common/colorExtensionPoint.ts (210), platform/theme/common/colorRegistry.ts (18, re-exports) + colors/*.ts (10 files, 1,647 lines with colorUtils.ts) |
| ui.icons / ui.product-icon-theme / ui.icon-theme / ui.color-theme | services/themes/common/iconExtensionPoint.ts (144), platform/theme/common/iconRegistry.ts (345), services/themes/browser/productIconThemeData.ts (289), fileIconThemeData.ts (506), common/themeExtensionPoints.ts (306), browser/workbenchThemeService.ts (934) |
| ui.custom-editor | contrib/customEditor/common/extensionPoint.ts (193), browser/customEditors.ts (464), api/browser/mainThreadCustomEditors.ts (935) |
| ui.webview-panel / ui.webview-view / ui.webview-bridge | contrib/webview/browser/webviewElement.ts (1,047), pre/index.html (1,335; CSP meta :7, CSP rewrite :889), pre/service-worker.js (717; fetch handler :349, `vscode-resource-base-authority` :27), resourceLoading.ts (165), common/webview.ts (78: `webviewResourceBaseHost = 'vscode-cdn.net'` :21, `webviewGenericCspSource` :25, `asWebviewUri` :40), contrib/webviewPanel/browser/webviewEditor.ts (210), contrib/webviewView/browser/webviewViewPane.ts (285), mainThreadWebviews/Panels/Views (160/390/143), extHostWebview.asWebviewUri :76 |
| webview theme variables | contrib/webview/browser/themeing.ts (123): exports every registered color (:64); name = `--vscode-` + id with `.` replaced by `-` (`asCssVariableName` platform/theme/common/colorUtils.ts:35-37) |
| ui.scm-view | contrib/scm/browser/scmViewPane.ts (2,410), scm.contribution.ts (708), api/browser/mainThreadSCM.ts (863) |
| ui.comments-ui | contrib/comments/browser/commentsController.ts (1,468), commentThreadWidget.ts (385), commentsView.ts (590), api/browser/mainThreadComments.ts (797) |
| ui.terminal-ui / terminal.shell-integration | contrib/terminal/browser/terminalInstance.ts (2,996), terminalService.ts (1,453), api/browser/mainThreadTerminalService.ts (539); scripts in `src/vs/workbench/contrib/terminal/common/scripts/` (shellIntegration-bash.sh, -env/-login/-profile/-rc.zsh, shellIntegration.fish, shellIntegration.ps1); injection `getShellIntegrationInjection` src/vs/platform/terminal/node/terminalEnvironment.ts:53 |
| ui.status-bar | api/browser/statusBarExtensionPoint.ts (303), browser/parts/statusbar/statusbarPart.ts (1,073), api/browser/mainThreadStatusBar.ts (79) |
| ui.notifications / ui.progress | browser/parts/notifications/notificationsCenter.ts (463), notificationsToasts.ts (721), api/browser/mainThreadMessageService.ts (152), mainThreadProgress.ts (89) |
| ui.quick-pick / ui.input-box | platform/quickinput/browser/quickInput.ts (1,394), quickInputController.ts (1,356), api/browser/mainThreadQuickOpen.ts (307) |
| ui.output | contrib/output/browser/outputView.ts (560), services/output/common/output.ts (323), api/browser/mainThreadOutputService.ts (133) |
| ui.diagnostics-ui (problems) | contrib/markers/browser/markersView.ts (1,118), markers.contribution.ts (724) |
| ui.file-decorations | services/decorations/browser/decorationsService.ts (420), api/browser/mainThreadDecorations.ts (127) |
| ui.timeline | contrib/timeline/browser/timelinePane.ts (1,377), api/browser/mainThreadTimeline.ts (69) |
| ui.tabs | api/common/extHostEditorTabs.ts (444), api/browser/mainThreadEditorTabs.ts (699), browser/parts/editor/editorGroupView.ts (2,299) |
(Paths in section 7 are relative to src/vs/workbench/ unless they start with platform/ or src/.)

## 8. Caveats / UNVERIFIED
- `proposalChecksInFile` (contributes) is a per-file heuristic. It does not prove that a particular point is gated. To verify one, read the handler in the cited file.
- 15 context-key names built at runtime (e.g. `view.${id}.visible`, `config.*`, keys passed as variables) cannot be resolved statically. They are listed in `meta.summary.unresolvedSymbolicKeyNameSamples`. Documented keys not found in source: `extension`, `scmResourceGroup`, `webviewId`, `webviewSection` (probably set dynamically; UNVERIFIED, grep the setContext/createKey call sites to confirm), plus the doc pattern `view.${viewId}.visible`.
- `definitionRefs` for built-in commands mixes true registrations with `id:` object properties. `literalRefs` shows every place the id appears.
- Docs content changes over time. The sha256 of each page is recorded, and `--refresh-docs` re-fetches.
