# VS Code extension compatibility: out-of-scope tracks roadmap

Companion to [roadmap.md](roadmap.md). D8 makes each out-of-scope (OOS) track a separate roadmap with its own estimate.
None of this work is in M1-M5. What M1 does ship is the **stub slice** of each track (WP-OOS-DAP/NB/CHAT/PROP/MS/WEB
rows in roadmap.md §2, 2 agent-days each). Those stubs make extensions that register OOS providers at activation still
activate. Per-track scores `S_track` (test-program.md §2.3.1) are reported on their own and never rolled into `S_in`.

Sources: shape dispositions from [design.md](design.md) §6 (method count, disposition: N = accept/no-op, R = typed
`-32010` reject, N+R = register accepted, use rejected). Weights come from [corpus.md](corpus.md) §3-4 and
`data/corpus.json`, as `downloadCount` share of corpus C (1,091,356,814 downloads, 156 extensions). A "touches" share
counts an extension once if it has any surface in the track. It is not the weight the track would add to `S_in`: most
of these extensions stay in E_in and only their track items are excluded (test-program.md §2.4). Effort uses the
planning numbers in roadmap.md (S=2, M=5, L=15, XL=30 agent-days) and is estimated here unless a source is named.

## 0. Summary and recommended order

| Order | Track | Weight touched (of C) | Fully excluded from E_in | Main-side shapes (design.md §6) | Effort (agent-days) | Hard prerequisites | Recommendation |
|---|---|---|---|---|---|---|---|
| 1 (runs alongside M5) | OOS-PROP (allow-list growth) | 26.9% (14 ext declare any proposal) | 0 | 8 proposal-only shapes, 30 methods + proposal members inside in-scope shapes | ~37 | the in-scope WP that owns each API family | grow the allow-list proposal by proposal, starting with `terminalDataWriteEvent` (9.3%) and the ms-python / GitHub PR candidates from design-protocol.md P7 |
| 2 | OOS-DAP | 29.5% (22 ext contribute `debuggers`) | 4 ext, 8.02% | DebugService (21, N+R) | ~70 | M3 trees/menus, M4 terminals, M5 tasks; PE1 only for inline values | **first real OOS track after M5**: largest weight, and the whole DAP runs in the host (the adapter only relays) |
| 3 | OOS-NB | 3.2% by contributions (10 ext); 10.1% counting `notebooks.*` API use (13 ext) | 1 ext, 0.89% | 6 shapes, 30 methods | ~72 | M4 webviews (renderers), PE1 (cell editors), Python kernel in guest | after DAP; the value is mostly Jupyter-for-Python |
| 4 | OOS-CHAT | 28.6% (31 ext) | 0 (no chat-only extension) | 18 shapes, 105 methods | ~80 | product decision on an in-app assistant and model provider; security review; ADR 0034 | defer; the stubs suffice because the touched extensions register tools opportunistically and keep working |
| 5 (S, any time) | OOS-FORK (added by roadmap) | ~1.0% (4 ext) | 0 | none (fork namespaces are absent in the vendored host) | 2 | WP-TEST-4 corpus runner | check that the extensions guard their fork-API calls, then report; never implement fork APIs |
| - | OOS-MS | Pylance: not on Open VSX; remote: 0.08% | 0 | 5 shapes, 19 methods | not estimated | Microsoft service terms | do not pursue |
| - | OOS-WEB | 0% (0 ext) | 0 | none (needs a worker host) | 15-30 if ever | a web-worker host | do not pursue; watch in the monthly corpus refresh |

Interaction with the ADR 0031 fallback: if the device gates miss by less than 2x, the host bundle is rebuilt **without**
chat/mcp/debug/notebook (ADR 0031 "Device go/no-go"). DAP, NB and CHAT would then first need a gate re-run with those
parts restored (WP-PERF-1 method, S). Count that as a prerequisite of each of those tracks.

Accounting rule: adding a proposal to the allow-list or implementing a track moves its items from `S_track` into the
g2/g3 denominators of `S_in` (test-program.md §2.4). Each step must ship with passing corpus scenarios, or `S_in` drops.

## 1. OOS-DAP: debug adapters and the debug API

| Item | Content |
|---|---|
| Corpus weight | 22 extensions contribute `debuggers`, 29.5% of C (corpus.md §4). Four are debug-adapter-only and excluded from E_in: `ms-python.debugpy` 5.50%, `vscjava.vscode-java-debug` 2.38%, `ms-vscode.node-debug2` 0.07%, `xdebug.php-debug` 0.07% (corpus.md §3 exclusions, 8.02% total). In-scope extensions with a debugger surface, by weight: ms-python.python 5.4, Shopify.ruby-lsp 4.0, golang.Go 3.7, devsense.phptools-vscode 3.7, ms-azuretools.vscode-containers 1.3, ms-toolsai.jupyter 0.9, Dart-Code.dart-code 0.5, GitHub.vscode-github-actions 0.4 (% of C, `data/corpus.json`) |
| Today (M1 stub) | `DebugService` 21 methods N+R. `$registerDebugTypes`, `$registerDebugConfigurationProvider` and `$registerDebugAdapterDescriptorFactory` are accepted, because 8/8 spike runs call them at activation. `$startDebugging` resolves `false` and shows a banner (design.md §6 row 25) |
| Main side to build | Adapter: all 21 `MainThreadDebugService` methods. VS Code's host already creates and talks to the debug adapters (executable, server, inline) and relays DAP messages through the main side. The adapter keeps the session and breakpoint state and forwards a UI-level model to Kotlin. Kotlin: session list, breakpoint store (source, function, data, logpoints), stack/variables/watch models, debug context keys (`inDebugMode`, `debugType`, `debugState`) |
| UI | Run and Debug view (variables, watch, call stack, breakpoints as trees, reusing WP-UI-5), floating debug toolbar (phone: bottom bar), debug console REPL (reusing the output panel from WP-UI-4), breakpoints in the gutter (pre-PE1 gutter layer from WP-UI-7), `debug/*` and `debug/callstack/context` menus (WP-UI-3), `launch.json` editing and configuration pick (WP-UI-4 quick pick, WP-UI-12). Inline values need PE1 |
| Other dependencies | `preLaunchTask` needs tasks (WP-API-7). `runInTerminal` needs terminals (WP-API-8). Adapters spawned in the guest need the native-binary scan (WP-HOST-11): the linux-arm64 `ms-python.debugpy` package still ships x86-64 attach `.so` files (corpus.md §4), so attach-to-process is expected to fail. Launch mode is UNVERIFIED (to check: a debugpy launch scenario on the Pad 6). Built-ins `debug-auto-launch` and `debug-server-ready` are "must not" today (design.md §7.2) and would need re-evaluation |
| Effort | Adapter DebugService L 15; Kotlin session/breakpoint model L 15; debug UI XL 30; launch.json, variable resolution and configuration providers M 5; scenarios (Go/dlv, Python/debugpy launch, Node js-debug, Ruby) M 5. **Total ~70** |
| Prerequisites | roadmap.md M3 (trees, menus, context keys), M4 (terminals, gutter decorations), M5 (tasks); gate re-run if debug was stripped from the bundle |
| Recommendation | First OOS track after M5. It has the largest weight (29.5% touched; the only track whose exclusions reach 8% of C), and it reuses M3/M4 surfaces. Deliver it in phases: (1) launch + breakpoints + stack/variables for Go and Python (Go 3.7%, Python 5.4% + debugpy 5.5%); (2) debug console, watch, logpoints; (3) inline values after PE1 |

## 2. OOS-NB: notebooks

| Item | Content |
|---|---|
| Corpus weight | 3.2% of C for 10 extensions with notebook contributions (corpus.md §4). 10.1% for 13 extensions if `notebooks.*` API use is counted (this roadmap's query over `data/corpus.json` `apiUsage`; the jump is ms-python.python 5.4%). Excluded: `ms-toolsai.jupyter-renderers` 0.89% (renderer-only). `ms-toolsai.vscode-jupyter-cell-tags` and `-slideshow` (0.74% each) stay in E_in and gate-fail until this track exists (corpus.md §3 note) |
| Today (M1 stub) | Notebook (5, N), NotebookDocuments (3, R), NotebookEditors (3, R), NotebookKernels (18, N+R: controllers can be created, execution rejected), NotebookRenderers (1, N), Interactive (0, N) |
| Main side to build | Adapter: notebook document/editor sync (the NotebookDocuments and NotebookEditors deltas), kernel selection and execution relay, renderer messaging. Kotlin: notebook model (cells, outputs, metadata), serializer round trip through the host (`ipynb` built-in to vendor, which design.md §7.2 marks "must not" today) |
| UI | Notebook editor (cell list, code and markdown cells, run buttons, execution order, output area), kernel picker (quick pick), outputs drawn by notebook renderers in WebViews (WP-UI-8 model, one WebView per output region or one per notebook, to be measured against the `maxLive` budget in WP-PERF-8), `notebook/*` menus |
| Other dependencies | A Jupyter kernel in the guest (python + ipykernel). How ms-toolsai.jupyter launches kernels in proot, and whether it needs native addons, is UNVERIFIED (to check: activate ms-toolsai.jupyter in the corpus harness and trace its spawns) |
| Effort | Adapter shapes L 15; Kotlin notebook model and sync L 15; notebook editor UI XL 30; renderer host on WebViews M 5; `ipynb` built-in S 2; scenarios M 5. **Total ~72** |
| Prerequisites | M4 (WebViews), PE1 (a cell editor per cell needs the virtualised editor for acceptable memory and scrolling), a Python toolchain in the guest (WP-TEST-13 fixture) |
| Recommendation | After DAP. Direct weight is small (3.2%), the UI cost is the highest per weight point, and the benefit is concentrated in the Python + Jupyter workflow |

## 3. OOS-CHAT: chat, language models, LM tools, MCP

| Item | Content |
|---|---|
| Corpus weight | 31 extensions, 28.6% of C (corpus.md §4). No extension is chat-only, so none is excluded (corpus.md §3 rule 4). By weight: ms-python.python 5.4, Shopify.ruby-lsp 4.0, devsense.phptools-vscode 3.7, vscjava.vscode-java-dependency 3.6, vscjava.vscode-java-debug 2.4, eamodio.gitlens 1.5 (uses `lm.*`/`chat.*` and contributes `mcpServerDefinitionProviders`, corpus.md §4), ms-azuretools.vscode-containers 1.3, openai.chatgpt 1.1, ms-toolsai.jupyter 0.9, saoudrizwan.claude-dev 0.6 |
| Today (M1 stub) | 18 shapes, 105 methods. `$getTools` returns `[]` (the host calls it at start, 8/8 runs), `lm.registerTool` is accepted (0.161 weight at activation), `$selectChatModels` returns `[]`, and `chat.createChatParticipant` rejects with the typed error (design.md §6 rows 9-18, 35, 44, 45, 50, 68) |
| Main side to build | LanguageModels (a model provider: none exists; Copilot and the `copilot` built-in are Microsoft services, "must not" in design.md §7.2), LanguageModelTools (invoke with consent), ChatAgents2 / ChatSessions / ChatContext (participants, streaming responses), Mcp (server definitions, then spawning MCP servers in the guest) |
| UI | Chat view (streamed markdown, references, follow-ups, participant picker), per-tool consent sheet (a new WP-SEC-4-style disclosure), model picker, MCP server list |
| Effort | Phase 1: tool registry + MCP definitions exposed to an in-app agent, L 15. Phase 2: model-provider bridge to a model the user configures, L 15. Phase 3: chat view XL 30 + participants in the adapter L 15. Security review of tool invocation and prompt injection M 5. **Total ~80** |
| Prerequisites | A product decision that easyIDE has an assistant surface and a model provider (not decided anywhere in the repo docs read for this audit). Threat-model extension (security-licensing.md §1). Play stance (ADR 0034: more third-party code execution) |
| Recommendation | Defer. The weight looks large, but it is carried by extensions whose main features are in scope and that register tools opportunistically. Their `S_in` gates ignore the chat items. Revisit only if a product decision creates an assistant surface. Phase 1 (MCP + tools) is then the cheapest useful step |

## 4. OOS-PROP: proposed APIs beyond the allow-list

| Item | Content |
|---|---|
| Rule (D8, design-protocol.md P7) | allow-list = VS Code stable product.json `extensionEnabledApiProposals` for that extension id ∩ proposals the adapter implements. It starts empty except for must-work needs, is kept as a data file owned by WP-HOST-9, and each entry needs a corpus scenario |
| Corpus weight | 14 extensions declare any proposal, 26.9% of C. Top by weight: `terminalDataWriteEvent` 9.3%, `editorHoverVerbosityLevel` 7.9% (meta.pyrefly, but it has no product.json entry, so it stays disabled exactly as in VS Code stable: no work), `portsAttributes` 6.7%, `quickPickSortByLabel` 6.4%, `notebookReplDocument` / `notebookVariableProvider` / `quickPickItemTooltip` 6.4% (ms-python) (corpus.md §4). GitHub PR declares the most (32 or 34, depending on source; corpus.md §4, design-protocol.md P7), several of them OOS-CHAT (`chatSessionsProvider`, `remoteCodingAgents`) |
| Proposal-only shapes (stubbed) | Browsers (5, R), DataChannels (5, R), DocumentDiff (1, R), EditorInsets (5, R), Power (7, R), Share (2, N), Timeline (3, N; the git built-in registers it), UriOpeners (2, N) |
| Batch 1 (ms-python, 5.4%) | `quickPickSortByLabel`, `quickPickItemTooltip` (WP-API-18), `contribEditorContentMenu` (WP-UI-3), `terminalDataWriteEvent`, `terminalExecuteCommandEvent`, `terminalShellEnv` (WP-API-8/9), `testObserver` (WP-API-23), `portsAttributes` (N; OOS-MS-adjacent), `codeActionAI` (WP-API-13). The `notebook*` ones stay OOS-NB |
| Batch 2 (GitHub PR, 0.6%) | `treeItemMarkdownLabel`, `treeViewMarkdownMessage` (WP-UI-5), `codiconDecoration` (WP-UI-7), `diffCommand`, `quickDiffProvider`, `tabInputMultiDiff` (WP-API-19/22), `contribCommentThreadAdditionalMenu` and the other `comment*` (WP-API-24) |
| Batch 3 | Timeline view (Timeline shape + view UI, M 5), because the git built-in and GitLens produce timeline items |
| Effort | ~16 proposals × S 2 = 32, plus Timeline M 5. **Total ~37**. Each proposal is done by the agent that owns the in-scope family |
| Prerequisites | the owning in-scope WP merged (M1-M5), the proposal present in product.json at the pinned tag, a scenario |
| Recommendation | Run it continuously from M5, starting with batch 1 (largest weight per day). Never enable a proposal that is absent from product.json for that id |

## 5. OOS-MS: services that depend on Microsoft

| Item | Content |
|---|---|
| Scope (D8) | Settings Sync, Microsoft authentication, Live Share, Remote-*, Pylance |
| Corpus | `ms-python.vscode-pylance`: Open VSX returns 404 (corpus.md §1); licence "released under a Microsoft proprietary license" (research/policy-licence.md:147). Remote: `jeanp413.open-remote-ssh` 0.08% (an open-source Remote-SSH alternative, 2 proposals). Live Share: no corpus id matches `liveshare` (this roadmap's query). The `microsoft` auth provider fails with the typed error (WP-API-11 acceptance) |
| Shapes (stubbed) | BrowserTunnelProxy (1, N), ManagedSockets (5, R), MeteredConnection (0, N), ProfileContentHandlers (2, N; Settings Sync profiles), TunnelService (11, N+R) |
| Open question | licensing-policy.md L-7: does the Marketplace ToU "In-Scope Products" restriction attach to Microsoft-published extensions taken from Open VSX? That question is for counsel (WP-SEC-18), not engineering |
| Effort | Not estimated. Remote resolvers + managed sockets alone would be L-XL, and our model is one local sandbox per environment (D3) |
| Recommendation | Do not pursue. Python type checking is covered by the must-work Pyrefly (7.9%). Settings Sync and Microsoft sign-in are out of product scope |

## 6. OOS-WEB: browser-only extensions

| Item | Content |
|---|---|
| Corpus | 0 extensions, 0% (corpus.md §4 "web-only (`browser`, no `main`)"). backend-2.md §20: Not-planned |
| What it would take | VS Code's worker extension host (a `browser` entry in a web worker) inside a hidden WebView or in a Node worker with browser globals, plus a second adapter transport. L 15 - XL 30 |
| Stub | WP-OOS-WEB (M1): a `browser`-only manifest installs as "not supported on easyIDE (OOS-WEB)" and never starts a host |
| Recommendation | Do not pursue. WP-TEST-12's monthly refresh should flag any web-only extension that enters the top 150 with >= 0.5% weight. That is the trigger to revisit |

## 7. OOS-FORK: APIs from VS Code forks (added by roadmap)

| Item | Content |
|---|---|
| Why it is added | No D8 track covers APIs that only exist in forks. research/corpus-notes.md:457 found fork namespaces in bundles: `vscode.antigravityAuth` / `antigravityExtensibility` (jlcodes.antigravity-cockpit 0.5%, googlecloudtools.datacloud 0.3%) and `vscode.cursor` (highagency.pencildev 0.1%, nrwl.angular-console 0.1%): about 1.0% of C, all in E_in |
| Behaviour today | The vendored host is unmodified (D1). It is stable VS Code at the pinned tag (D9), so these namespaces are `undefined`. Guarded code paths degrade; unguarded ones throw `TypeError` at call time and show up as g1/g4 failures in `S_in` |
| Work | WP-OOS-FORK (S 2): static check for guards (`vscode.cursor?`, `typeof`) in the four bundles, plus g1/g4 runs in the corpus harness; record results in corpus.md. Whether they are guarded is UNVERIFIED (research/corpus-notes.md:457) |
| Recommendation | Never implement fork APIs: they are not stable VS Code API, and emulating one fork's identity conflicts with D9. Keep these four as normal E_in members and let the score show what fails |

## 8. Totals

| Track | Effort (agent-days) | Status |
|---|---|---|
| OOS-PROP | ~37 | recommended alongside M5 |
| OOS-DAP | ~70 | recommended first after M5 |
| OOS-NB | ~72 | after DAP |
| OOS-CHAT | ~80 | deferred (product decision) |
| OOS-FORK | 2 | any time after WP-TEST-4 |
| OOS-MS | - | not pursued |
| OOS-WEB | (15-30) | not pursued |
| **Recommended set (PROP + DAP + NB + FORK)** | **~181** | plus a gate re-run if the ADR 0031 fallback stripped debug/notebook |
