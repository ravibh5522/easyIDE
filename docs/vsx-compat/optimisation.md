# VSX-COMPAT: performance, memory and battery budgets (optimisation)

Status: audit output, 2026-09-25. Target device: **Xiaomi Pad 6** (Snapdragon 870, 8 GB RAM, Android 13, arm64, SELinux enforcing,
adb serial `8084d710`; `docs/sandbox-runtime/tracker.md:59`). App id `dev.easyide.app` (`services/mobile/app/build.gradle.kts:24`).
Binding decisions: ADR 0031 (route 2b, vendored ext host + JS adapter), 0032 (Node 24 LTS from nodejs.org, `NODE_COMPILE_CACHE`),
0033 (webviews), D3 (one host per workspace session, started lazily), D11 (WP ids).
Related: [design.md](design.md) (adapter and process model), [backend.md](backend.md) (MemoryPolicy / DocumentStore changes),
[test-program.md](test-program.md) (corpus gates and harness), [ui-webviews.md](ui-webviews.md) (webview lifecycle, T1 plan),
[ui.md](ui.md) (WP-UI-7 decorations frame test), [research/route-spike.md](research/route-spike.md) (x86 evidence),
[research/ourcode-backend.md](research/ourcode-backend.md) (our code), ADR [0017](../decision/0017-lsp-client-hand-rolled-server-lifecycle.md),
[0023](../decision/0023-workspace-session-lifetime.md), [0031](../decision/0031-vendor-vscode-extension-host.md), [0032](../decision/0032-node-runtime-provisioning.md).

**Nothing in this file has been measured on the tablet.** Every number is one of:
`M-x86` = measured on the x86 spike host (Xeon 2.1 GHz, 4 vCPU, Node 22.22.2; route-spike.md:4), `M-corpus` = computed from
`data/corpus.json` (snapshot 2026-09-25T14:49Z, 156 extensions), `D` = derived (formula given), `T` = target (reason given),
`EX` = existing project budget (file:line). Rule R-PERF-13 (`docs/extension-sdk/rules.md:99`) applies: a number becomes a claim only
after the device benchmark in section 3 records it in `docs/vsx-compat/results/perf-<date>.json`.

## 0. Process picture that the budgets refer to

| Process (guest, under proot, app UID) | Role | x86 evidence |
|---|---|---|
| `node exthost.mjs` (vendored bundle, 3.0 MB min / 0.85 MB gz) | runs every extension of the workspace (D3) | idle hello 97 MB RSS, activated 248 ms warm cache (route-spike.md:124-129) |
| adapter (`node adapter.mjs`, ~0.2 MB min) | MainThread side, JSON-RPC over stdio to Kotlin (ADR 0031) | not measured; spike renderer 214,883 B min (route-spike.md:84). Bare Node floor 42 MB RSS (route-spike.md:130) |
| children spawned by extensions | language servers (ESLint server, gopls...), `claude` CLI, git | 126/156 corpus extensions (87.7% of weight) contain `child_process` use (`spawnsProcesses`, M-corpus) |
| built-in LSP sessions | ADR 0017, budgeted by `MemoryPolicy` | `lsp.globalMemoryBudgetMb`=1200, `lsp.maxServers`=3, `lsp.defaultMemoryBudgetMb`=400 (`app/.../LspSettingsSchema.kt:52-65`) |
| Android WebView renderer(s) | one WebView per visible webview (ADR 0033 / D5) | UNVERIFIED (ui-webviews.md §5) |

Two RPC hops: **host <-> adapter** = VS Code `RPCProtocol` over `PersistentProtocol` on a unix socket inside the guest (binary,
VSBuffer-aware); **adapter <-> Kotlin** = JSON-RPC Content-Length over stdio pipes crossing proot (`LSP/jsonrpc/JsonRpcConnection.kt:50-150`).

## 1. Budget table

Columns: **Target** (go), **Fail** (no-go / regression gate), **Source**, **How measured**, **Where**: `CI` = x86 proxy harness with
device factor k (section 3.3), `DEV` = device benchmark on 8084d710, `MB` = Android macrobenchmark on device.
Units: MB = MiB (1,048,576 B) for sizes computed from corpus; RSS as reported by `/proc/<pid>/status` (kB/1024).

### 1.1 Startup and activation

| # | Metric | Target | Fail | Source / justification | How measured | Where |
|---|---|---|---|---|---|---|
| B1 | Node + host cold start, first ever (no compile cache): spawn -> `Initialized` + hello activated | <= 4.0 s | > 6.0 s | T = ADR 0031 G2 (one-time after install). Fail = 1.5x G2: beyond that users perceive the first enable as broken | bench `startup --cold` (drop `$NODE_COMPILE_CACHE`, `echo 3 > drop_caches` impossible without root: instead first run after install + `am force-stop`); adapter stamps `spawn`, `Ready`, `Initialized`, `$onDidActivateExtension` with `performance.timeOrigin`-based ms; 5 runs, median | DEV, CI (x86 407 ms unmin / 310 ms self-contained, M-x86) |
| B2 | Warm start (compile cache warm) to hello activated | <= 1.5 s | > 3.0 s | T = ADR 0031 G1. x86 248 ms (range 234-270, M-x86 route-spike.md:128) x 4-6 allowance for SD870 + proot; 3 s = VS Code's own unresponsive threshold (`rpcProtocol.ts:119`) | same, 5 warm runs after one flushed run | DEV, CI (<= 375 ms at k=4) |
| B3 | Host spawn relative to workspace open | never before the workspace's first editor frame; spawn at first-frame + 1 s idle | host process visible in `/proc` before first frame | T: D3 lazy start. 1 s mirrors VS Code's wait for startup activations "at maximum 1s" (`extHostExtensionService.ts:811-814`) | Perfetto: app slice `firstEditorFrame` vs proot fork of `node` (sched trace `task_newtask`) | DEV |
| B4 | Per-extension activation (`activate()` only, host already up), p50 across corpus | <= 600 ms | > 1.2 s | D: x86 deltas over the hello baseline 310 ms for the 8 spike extensions (371-876 ms from spawn, route-spike.md:141-150): GitLens 334, Claude Code 566, Go 237, ESLint 110, Prettier 61, EditorConfig 115, Code Runner 400, Better Comments 151 ms -> p50 ~133 ms; x k=4 -> ~530, rounded to 600 | host `$onWillActivateExtension`/`$onDidActivateExtension` timestamps (`MainThreadExtensionService`, already called in the spike, route-spike.md:116) recorded by the adapter; corpus harness runs every E_in extension | CI (<= 150 ms), DEV (22 must-work) |
| B5 | Per-extension activation, p95 across corpus | <= 2.5 s | > 5 s or any must-work > 3 s | D: x86 max of the 8 = 566 ms (Claude Code) x k=4 = 2.3 s; must-work cap 3 s = B2 fail | same | CI (<= 625 ms), DEV |
| B6 | Time from `*` / `onStartupFinished` eligibility to activation of all such extensions (GitLens + ESLint + Prettier + Claude Code set) | <= 4 s after first editor frame | > 8 s | D = B3 1 s + B2 1.5 s + sequential activations; 58 corpus extensions (18.0% of weight) use `onStartupFinished`, 6 (0.5%) use `*` (M-corpus) | adapter stamps | DEV |
| B7 | App cold start (launcher -> first frame) with code extensions enabled | no regression: <= +50 ms vs none | > +50 ms | EX: arch.md "App cold start with 20 declarative extensions < +50 ms" (`docs/extension-sdk/arch.md:96`), R-PERF-09. Host is never on this path (B3); only the vsx manifest index is read | macrobenchmark `StartupTimingMetric`, `CompilationMode.Partial` (baseline profile module exists: `services/mobile/baselineprofile`), 10 iterations, 20 installed vsx extensions vs 0 | MB |

### 1.2 Memory

| # | Metric | Target | Fail | Source / justification | How measured | Where |
|---|---|---|---|---|---|---|
| B8 | Idle host RSS (hello only) | <= 150 MB | > 200 MB | EX/T: ADR 0031 G3; ADR 0030 "about 60-150 MB idle" (`0030:62`). x86 97 MB (self-contained min, M-x86). Node official arm64 and x64 builds are both 64-bit; ratio UNVERIFIED (expected 1.0-1.3) | `/proc/<pid>/status` VmRSS + VmHWM 30 s after activation, pid found as in `ProcMemoryProbe` (`app/.../SandboxServerLauncher.kt:79-91`); also `smaps_rollup` Pss | DEV, CI |
| B9 | Idle adapter RSS | <= 70 MB | > 100 MB | D: Node floor 42 MB (M-x86) + bundle heap; not measured (UNVERIFIED). Removing it (adapter in a Worker, technique 2.14) saves ~30 MB | same | DEV, CI |
| B10 | RSS delta per active extension, median across corpus | <= 15 MB | > 30 MB | D: x86 deltas over 97 MB baseline: EditorConfig 3, Code Runner 3, ESLint 6, Prettier 6, Go 23, GitLens 35 (49 HWM), Claude Code 67 -> median 6 MB (n=7); x ~1.3 memory factor, T rounded up for heavier corpus tail | corpus harness: RSS after `activate()` + 3 s, minus pre-activation RSS, one extension per fresh host | CI (median of E_in), DEV (must-work) |
| B11 | RSS delta per active extension, p90 | <= 80 MB | > 120 MB | D: x86 p90 ~ 67 MB (Claude Code) x ~1.2 | same | CI, DEV |
| B12 | Host process RSS, GitLens + Claude Code + ESLint active | <= 300 MB | > 400 MB | EX: ADR 0031 G4. x86 single HWMs 146 / 165 MB | bench `mem --set heavy3` | DEV |
| B13 | Host tree RSS ceiling (host + adapter + extension-spawned children), new key `extensions.host.memoryBudgetMb` | 400 MB default | over for 2 consecutive 5 s samples -> restart host once, then stop with notice | T: = `lsp.defaultMemoryBudgetMb` (400, EX) so a host costs the same as one server slot; B12 300 MB + adapter 70 + margin. Mirrors R-PERF-02 and `LspPolicy.OVER_BUDGET_SAMPLES`=2, `MEMORY_SAMPLE_MS`=5000 (`lsp/LspPolicy.kt:63-66`). `claude` CLI child RSS is UNVERIFIED and may alone exceed this (its ELF is 227 MiB, M-corpus): measure before fixing the default | `ProcTree.treeRssKb` over the host pid (`app/.../ProcTree.kt`) | DEV |
| B14 | Global guest budget (LSP sessions + host tree), `lsp.globalMemoryBudgetMb` | 1600 MB on devices with totalMem >= 7 GB while a host runs; 1200 otherwise (unchanged) | breach not resolved within 2 s (R-PERF-01) | D: today's 1200 = `lsp.maxServers` 3 x `lsp.defaultMemoryBudgetMb` 400 (EX, `LspSettingsSchema.kt:52-65`); the host is one more 400 MB slot -> 1600. D10: an extension's language server replaces our built-in one, so its RSS moves from an LSP slot into the host tree rather than adding. Whether 1600 MB fits an 8 GB tablet beside Android + app + WebViews is UNVERIFIED: read `MemAvailable` (`/proc/meminfo`) and `dumpsys meminfo` "Free RAM" with the heavy set running | sampler + `dumpsys meminfo dev.easyide.app` total PSS | DEV |
| B15 | App process (Java/native, excluding guest) growth with 4 extensions active and their UI (trees, decorations, status) | <= +40 MB PSS vs extensions off | > +80 MB | T: Kotlin-side state is data only (trees paged, decorations per visible window); 40 MB ~ one 10k-node tree + 5k decorations + caches, to be confirmed | `dumpsys meminfo dev.easyide.app` "TOTAL PSS" minus guest pids (guest processes are separate pids in the same UID) | DEV |
| B16 | V8 heap cap | `--max-old-space-size=512` for host | host OOM crash > 1 per 8 h session in the soak | T: above B13 so the budget sampler (restart) acts first; bounds runaway growth between samples; Node docs: "As memory consumption approaches the limit, V8 will spend more time on garbage collection" (nodejs.org/api/cli.html `--max-old-space-size`). Default semi-space at a 512 MiB limit "defaults to 1 MiB" (same page) | crash journal (D3) reason `FATAL ERROR: ... heap out of memory` | DEV soak |

Low-memory shedding order (extends `MemoryPolicy.evictionOrder`, `lsp/manager/MemoryPolicy.kt:58-69`; ADR 0030:62-64 says the host is
"the first thing shed under low-memory pressure after idle language servers"; ui-webviews.md §5 fixes idle LSPs -> hidden webviews -> host):

| Step | Victim | Condition / action | Cost to the user |
|---|---|---|---|
| 1 IDLE | idle LSP sessions, LRU | as today | none (restart on next open) |
| 2 NOT_VISIBLE | running LSP sessions with no visible doc, LRU | as today | a server restart later |
| 3 PRESSURE_HOOKS | WASM idle instances (existing hook), **hidden retained webviews** LRU (destroy, keep `setState` state) | new `MemoryPressureHook` from the webview host | panel re-renders on next show |
| 4 HOST_IDLE (new) | extension host tree **if** no visible webview of it, no open `withProgress`, no in-flight request older than 1 s, and no provider call from the focused editor in the last 60 s | graceful `Terminate` (flushes compile cache), host restarts lazily on the next activation event | re-activation on next use (B2 + B4) |
| 5 FOCUSED | LSP sessions serving the focused editor, largest RSS first | as today | features pause |
| 6 HOST (new, only at `COMPLETE`) | host tree even when busy | `Terminate`, then SIGKILL after `LspPolicy.SHUTDOWN_GRACE_MS` 2000 | extension state lost; crash journal entry "evicted, not crashed" |
| never | editor buffers, terminals, visible webviews below COMPLETE | R-PERF-03 (`rules.md:89`) | - |

Mapping to `MemoryPressure` (`MemoryPolicy.kt:44-49`): RUNNING_LOW = steps 1-2; BACKGROUND = 1-4; COMPLETE = all. Parked workspaces
(ADR 0023 §3) are evicted by the registry before any of this and take their host with them.

### 1.3 IPC, UI thread and payloads

| # | Metric | Target | Fail | Source / justification | How measured | Where |
|---|---|---|---|---|---|---|
| B17 | host <-> adapter round trip p50 / p99 | <= 10 / 40 ms | > 20 / 80 ms | EX: ADR 0031 G5. x86 p50 2.58, p90 3.0-3.3, p99 6.4-6.9 ms (2000 sequential `$test_latency`, M-x86). Floor = `ProtocolWriter._writeSoon` `setTimeout` on both sides (`ipc.net.ts:500-511`) | `ExtHostExtensionService.$test_latency` from the adapter, 2000 calls x 3 | CI (<= 2.5 / 10 at k=4), DEV |
| B18 | adapter <-> Kotlin round trip p50 / p99 (empty request, stdio through proot) | <= 5 / 20 ms | > 10 / 40 ms | T: must leave room so B17 + B18 <= 15 / 60 ms; not measured. LSP uses the same path (`JsonRpcConnection`) but has no latency figure either | new `$/ping` both directions, 2000 calls; Kotlin side uses `SystemClock.elapsedRealtimeNanos` | DEV |
| B19 | Provider call overhead end to end (Kotlin request -> adapter -> host -> trivial provider -> back), p95, excluding provider compute | <= 50 ms | > 100 ms | T: route-spike G5 note "completion/hover need < 50 ms end-to-end including the Kotlin hop"; keeps EX "Completion popup after server response < 150 ms p95" (`arch.md:91`) achievable | hello extension registers a constant completion/hover provider; bench issues 500 requests | DEV |
| B20 | Main-thread (UI) time per inbound message batch, p95 | <= 4 ms | > 8 ms | EX: R-PERF-06 "main-thread time per LSP response < 4 ms (p95)" (`rules.md:92`), applied to extension messages | Perfetto `android.os.Trace` sections `ext:apply:<method>` around the main-thread apply step | MB |
| B21 | Extension-driven main-thread work per frame | <= 2 ms per frame, sum over all ext messages applied in that frame | frame with > 4 ms ext work, or any added janky frame | D: Pad 6 panel is 144 Hz (vendor spec, UNVERIFIED on our unit: `dumpsys display`), frame = 6.9 ms; 2 ms ~ 30% leaves the editor's draw its share. At 60 Hz the same 2 ms is 12% | Perfetto `ext:apply` slices summed per `Choreographer#doFrame` | MB |
| B22 | JSON parse / serialise on the main thread | 0 bytes | any `RpcCodec.decode` on main | EX pattern: LSP decodes on `ioDispatcher` (`JsonRpcConnection.kt:93,159-175`); the ext link reuses it; main thread receives typed objects only | Perfetto: no `RpcCodec` slice on the main thread; StrictMode custom policy in debug | MB, JVM test |
| B23 | Keystroke-to-glyph latency, 400 KB file, ESLint + Prettier + GitLens + Error Lens active | no regression vs extensions off: p50 and p95 `frameDurationCpuMs` within +max(5%, 0.5 ms) | worse than that | EX: arch.md:92 / R-PERF-08 "no regression vs LSP off"; tolerance T = macrobenchmark run-to-run noise floor (to be confirmed in calibration) | macrobenchmark `FrameTimingMetric` typing script (100 chars at 10 chars/s) | MB |
| B24 | `didChange`-style document delta flush to the adapter for a 400 KB document | <= 2 ms (JVM side) | > 4 ms | EX: R-PERF-07 (`rules.md:93`); same prefix/suffix diff (`LSP/text/TextDiff.kt:11-60`) | JVM microbenchmark + Perfetto | CI (JVM), MB |

Payload limits and batching (adapter <-> Kotlin link; the host <-> adapter link is VS Code's and stays unmodified):

| Stream | Limit / batching rule | Source / justification |
|---|---|---|
| Frame size | header <= 8 KB, body <= 16 MB, JSON depth <= 256 | EX framing `LspPolicy.kt:13-24` (64 MB body). 16 MB (T): no legitimate UI message is larger once binary goes by side channel (below); a 64 MB JSON string would be ~128 MB of UTF-16 in the JVM |
| Document open | full text once, only for documents <= 2 MB; larger files never become extension documents (UI says so) | EX `LspPolicy.MAX_FULL_SYNC_BYTES` 2 MB (`:30`), R-PERF-11; editable cap is 256 KB and read-only viewer 8 MB (`SBX/files/FileContent.kt:48-54`). VS Code's own cut-off is 50 MB (`textModel.ts:188,368`) - ours is stricter and listed as a known difference in test-program.md |
| Document changes | one coalesced delta per flush, flushed on the same debounce as LSP `DocumentSync` (`LSP/session/DocumentSync.kt:31-60`), and always before any provider request for that document (versions match) | D1 "coalesced doc deltas"; impact on extensions UNVERIFIED (ourcode-backend.md:357) |
| Selection / visible ranges (`onDidChangeTextEditorSelection`, `...VisibleRanges`) | send only when the value changes at line granularity for visible ranges (VS Code does the same comparison, `mainThreadEditor.ts:122-126`); coalesce latest-wins, at most one per editor per 50 ms while scrolling, and flush immediately on scroll end | VS Code sends on every scroll event (`mainThreadEditor.ts:313-325`); 144 Hz scrolling would be 144 msgs/s. Final state identical, event count lower (documented difference) |
| Decorations `$trySetDecorations` | adapter merges per (editor, type) latest-wins within one 16 ms tick; Kotlin keeps ranges per type, paints only the visible line window; cap 10,000 ranges per type per editor (excess dropped with one log line) | T: 2x the WP-UI-7 test load "5k decorations on a 5k-line file" (ui.md:363); ui.md §5 "decorations limited to the visible line window" |
| Diagnostics `$changeMany` | host already caps 1,000 markers per file (`extHostDiagnostics.ts:134-151,235-236`) and debounces `onDidChangeDiagnostics` 50 ms (`:240`); adapter merges per owner within 100 ms, max 500 URIs per message | T: 100 ms ~ half of `PULL_DIAGNOSTICS_DEBOUNCE_MS` 200 (`LspPolicy.kt:84`); a workspace-wide lint of 5k files becomes 10 messages, not 5k |
| Tree children | adapter pages `getChildren` results to Kotlin 200 items per message; Kotlin requests the next page when the LazyColumn nears the end; `resolveTreeItem` only for rows on screen | T: 200 rows ~ 3.5 screens at 28 dp rows on a 1600 dp tall tablet (ui.md:67 row heights). Host debounces tree refresh 200 ms already (`extHostTreeViews.ts:402-422`) |
| Output / terminal / pseudoterminal streams | coalesce into <= 60 ms batches, max 64 KB per message | EX: sandbox shell streams "~60 ms batches" (`docs/sandbox-runtime/tracker.md:30`) |
| Progress, status bar, `setContext` | latest-wins per id per 16 ms tick | T: one frame at 60 Hz |
| Queue bound (Kotlin inbound per stream kind) | 1,000 pending notifications; on overflow merge-by-key (decorations, status, contexts, visible ranges) or drop-oldest with a counter (logs, output keep newest 64 KB) | T; today notifications are "pumped one at a time in wire order" (`JsonRpcConnection.kt:50-56`, ourcode-backend.md:91), a bottleneck for chatty streams |

Binary and large payloads:

| Payload | Transport | Why |
|---|---|---|
| Files under `file:` / `/workspace` | never over RPC: extensions use Node `fs` directly (ADR 0030 decision 5 "files and processes are not proxied") | zero copy |
| `workspace.fs` on our schemes, `FileSystemProvider` read/write, custom binary editors | host <-> adapter: VSBuffer mixed args (binary, `rpcProtocol.ts:645-671`); adapter <-> Kotlin: <= 64 KB inline base64, > 64 KB side channel = temp file in the guest (`/tmp/easyide-xfer/<id>`) that Kotlin reads from the rootfs host path (`LspRuntime.rootfsDir`, `app/.../LspRuntime.kt:120`) and deletes; message carries path + length + sha256 | base64 costs +33% bytes and a JVM string copy; 64 KB = default Linux pipe capacity (pipe(7), 16 pages of 4 KiB; device page size UNVERIFIED: `getconf PAGESIZE` in guest) |
| Webview resources (`asWebviewUri` of `localResourceRoots`) | `shouldInterceptRequest` streams the file straight from the rootfs host path on a WebView IO thread; no RPC unless the root is a non-file scheme | ui-webviews.md S3; `shouldInterceptRequest` runs "on a different thread than application's main thread" |
| Webview `postMessage` | JSON via `addWebMessageListener` (D5); ArrayBuffers > 64 KB rejected with a logged error until the side channel is extended to webviews | T; VS Code transfers ArrayBuffers to webviews, low use in corpus (UNVERIFIED count) |
| Streaming (Claude Code chat panel, output) | no message > 64 KB; producer-side coalescing at 60 ms as above | same as streams |

### 1.4 Webviews

| # | Metric | Target | Fail | Source / justification | How measured | Where |
|---|---|---|---|---|---|---|
| B25 | PSS per live WebView (renderer + app-side), per page type (empty, GitLens Home, Claude Code panel, Git Graph) | UNVERIFIED - set after T1; provisional <= 120 MB each | provisional > 200 MB | no primary figure (ui-webviews.md:122-126). Android: "Most WebView memory ... is allocated in native memory" (quoted there) | ui-webviews.md T1: `dumpsys meminfo dev.easyide.app` + `dumpsys meminfo` of `*SandboxedProcessService*` renderer(s) for 0/1/2/4 webviews; check whether renderers are shared | DEV |
| B26 | Max concurrent live webviews | `extensions.webview.maxLive` 2 / 3 / 4 by window class | re-derive after B25: `maxLive = floor(webviewShareMb / B25)` with webviewShareMb = 500 on 8 GB (T) | EX proposal ui-webviews.md:117-120 | count in soak | DEV |
| B27 | Hidden retained webview cost | report hidden-vs-destroyed delta | if delta > 50% of live PSS, default `extensions.webview.retainHidden` to false on < 6 GB devices | ui-webviews.md:120 | T1 | DEV |
| B28 | Webview first paint (panel open -> first contentful frame) | <= 800 ms warm | > 2 s | T: VS Code-like "instant" panel; WebView process warm after first use | Perfetto WebView trace categories + adapter stamp at `$setHtml` | DEV |

### 1.5 Install size and storage IO

Corpus sizes (M-corpus, `node` over `data/corpus.json`, all 156 extensions, target chosen = linux-arm64 > universal):

| Quantity | p50 | p90 | p95 | max | Notes |
|---|---|---|---|---|---|
| `vsixBytes` (download) | 2.64 MB | 36.55 MB | 108.87 MB | 231.26 MB (openai.chatgpt) | sum 2,603 MB |
| `unpackedBytes` | 7.53 MB | 133.91 MB | 230.15 MB | 554.89 MB (openai.chatgpt) | sum 6,513 MB |
| `mainBundleBytes` (n=153) | 0.68 MB | 7.38 MB | 16.13 MB | 53.34 MB (Continue.continue) | download-weighted p50 0.9 / p90 2.9 MB; 62 extensions > 1 MB, 21 > 5 MB |
| JS files in package (`jsFilesCount`) | 5 | 314 | | 7,067 | many-file packages cost proot `stat`/`open` at load |

| # | Item | Figure | Budget / rule | Source |
|---|---|---|---|---|
| B29 | Claude Code 2.1.282 linux-arm64 | vsix 106.31 MB, unpacked 237.56 MB, main bundle 2.93 MB, native `claude` ELF 227 MB | must install: vsx limits raised per WP-REG-8 | M-corpus; registry-install.md:520,543 |
| B30 | GitLens 2026.9.250515 | vsix 4.45 MB, unpacked 17.95 MB, main 2.75 MB | compile cache is what matters (2.2) | M-corpus |
| B31 | 22 must-work extensions together | vsix 313.9 MB, unpacked 624.8 MB (redhat.java 126.2 / 183.5, Claude Code above) | show size before download; require free space >= 2 x unpacked + 200 MB (T: unpack staging + rollback copy, registry-install.md §6.2) | M-corpus |
| B32 | Node runtime | ~31 MB download, ~100 MB unpacked (UNVERIFIED) | provisioned once per environment on first code-extension enable (ADR 0032) | ADR 0032:14,41 |
| B33 | Host bundle + adapter in APK | 0.85 + ~0.07 MB gz (3.0 + 0.2 MB unpacked) | APK growth <= 1.5 MB (T); stubbing chat/debug/notebook saves 22.5% (route-spike.md:73) | M-x86 |
| B34 | Compile cache directory | x86 2.9 MB for the host bundle (route-spike.md:128) | cap 64 MB per environment, LRU-pruned by Node version dir (cache is per Node version, nodejs.org module docs "Limitations of the compile cache") | M-x86 + T |
| B35 | Storage IO under proot | proot syscall overhead "typically 10-30%" is **UNVERIFIED**: it is asserted without measurement in `docs/sandbox-runtime/arch.md:162` and ADR 0002:16; ADR 0002 Addendum 2 measured only functionality (x86 Waydroid) and the device check (tracker.md:59) no timings | gate: activation of a many-file extension (jsFilesCount >= 300, e.g. an unbundled one) <= 2x a bundled one of similar bytes; fs share of activation <= 30% | bench `io` (3.4) |

### 1.6 Battery and background

| # | Metric | Target | Fail | Source / justification | How measured | Where |
|---|---|---|---|---|---|---|
| B36 | Idle host tree CPU, app foreground, hello + GitLens + ESLint, 10 min no input | <= 1% of one core average (<= 6 CPU-s / 10 min) | > 3% | T. Known floor: `PersistentProtocol` KeepAlive every 5 s on each side (`ipc.net.ts:312,892-895`) and ack timers (`:294`); `parentPid` 1 s poll is off because we send `parentPid: 0` (`extensionHostProcess.ts:351-371`, route-spike.md:59) | `utime+stime` from `/proc/<pid>/stat` of every pid in the host tree, start/end; `voluntary_ctxt_switches` delta as wakeup proxy | DEV |
| B37 | Idle wakeups of the host tree | <= 2 /s | > 10 /s | D: 2 KeepAlive timers = 0.4/s + ack/timers; 2/s leaves room for extension timers | ctxt switch deltas as above | DEV |
| B38 | App backgrounded, workspace live (FGS running per ADR 0023 §8) | policy below; host tree CPU after suspend = 0 | any CPU after suspend | T | same + `dumpsys batterystats --charged dev.easyide.app` | DEV |
| B39 | Battery drain, 30 min screen-on editing script with the heavy set vs extensions off | <= +10% of the delta mAh | > +20% | T; Snapdragon 870 thermal behaviour unknown for this load (UNVERIFIED) | `dumpsys batterystats --reset`, script, `dumpsys batterystats` estimated power per UID; `dumpsys thermalservice` status sampled | DEV |

Background options evaluated (the FGS keeps the app at foreground priority while a workspace is live, ADR 0023:61-65, so Android's
cached-apps freezer does not apply: it "stops execution for cached processes", source.android.com/docs/core/perf/cached-apps-freezer):

| Option | For | Against | Decision |
|---|---|---|---|
| A. Keep running under the FGS | extensions finish work (Claude Code turn, GitLens fetch); zero resume cost | KeepAlive and extension timers burn CPU for hours; `dataSync` FGS time is capped at "a total of 6 hours in a 24-hour period" on Android 15 (developer.android.com fgs/timeout), after which ADR 0023 stops the service anyway | default for the first N minutes |
| B. SIGSTOP the host tree (host + adapter + children, one process group) after N min backgrounded and idle; SIGCONT on foreground before the first message | zero CPU; state kept; resume in ms | a stopped peer must not trip timeouts: stop host and adapter together (host<->adapter `TimeoutTime` 20 s, `ipc.net.ts:300`); Kotlin must not count the stopped time against request timeouts; network sessions of extensions may drop | **chosen**: N = `extensions.host.backgroundSuspendMin`, default 10 (T: equals `lsp.idleShutdownSec` 600 default, `LspSettingsSchema.kt:67-70`); 0 = never. "Idle" = no open `withProgress`, no in-flight request, host tree CPU < 1% over the last 60 s, no task/terminal owned by an extension running |
| C. Terminate the host after N min backgrounded | frees RAM | re-activation cost (B2+B4) on return and state loss (Claude Code session) | only via memory pressure steps 4/6 |
| D. Lower priority always (nice 10 for the host tree) | foreground editor wins CPU contention | slower activation under load | **chosen** (2.17), measured in B23 |

Thermal: at `PowerManager` thermal status >= SEVERE (API 29+), apply option B immediately when backgrounded and raise provider debounces
x2 when foreground (2.18). Measured with `dumpsys thermalservice` during B39.

## 2. Techniques

| # | Technique | Decision | Evidence / reason | Budget served |
|---|---|---|---|---|
| 2.1 | Lazy activation; `*` semantics | Never start the host at app start. A workspace session with >= 1 enabled code extension schedules spawn at first editor frame + 1 s idle (B3). Inside the host, activation stays VS Code's: `*` extensions are eager and activate first, then `onStartupFinished` ("after all `*` activated extensions have finished activating", `extensionsRegistry.ts:314`). We do **not** rewrite `*` into `onStartupFinished + delay`: that would reorder activation against VS Code semantics and only 6 corpus extensions (0.5% weight) use `*`. The delay is applied to the host spawn instead, which the extension cannot observe | D3; M-corpus | B3, B6, B7 |
| 2.2 | V8 compile cache | `NODE_COMPILE_CACHE=<guest cache>/node-compile-cache` (ADR 0032) for host, adapter and every extension module. The cache is written at exit or by `module.flushCompileCache()`; the spike saw no gain when the child was killed (route-spike.md:132). Therefore: a tiny preload `--require /opt/easyide/exthost/preload.cjs` (ours, host source untouched) calls `module.flushCompileCache()` 10 s after the last `$onDidActivateExtension` and on `Terminate`; shutdown is always graceful first. Gain x86: 350 -> 248 ms (-29%); large single-file bundles (GitLens 2.75 MB, Claude Code 2.93 MB main) benefit most | nodejs.org/api/module.html "Module compile cache", `flushCompileCache` Added v22.10.0; M-x86 | B2, B4 |
| 2.3 | Startup snapshot (`--snapshot-blob`) | Feasibility **UNVERIFIED**; not planned before M5. Node: "The snapshot currently only supports loading a single entrypoint ... which can load built-in modules, but not additional user-land modules" (nodejs.org/api/cli.html `--build-snapshot`). The host bundle is one file (fits) but it is ESM and connects a socket at load (must be deferred via `v8.startupSnapshot.setDeserializeMainFunction`); extensions are loaded later and never snapshotted. Upper bound of the win: warm start minus bare Node = 248 - 40 = ~200 ms on x86 | nodejs.org; M-x86 | B2 |
| 2.4 | Pre-warm | After install/update of an extension, no speculative `require` (top-level code needs `vscode` and may have side effects). Pre-warm = host spawn at workspace idle (2.1) + compile cache (2.2) | | B2, B4 |
| 2.5 | Delta document sync | Reuse `DocumentStore` snapshots + single-range `TextDiff` -> coalesced `IModelChangedEvent` per flush; full text only on open (<= 2 MB) | ourcode-backend.md:61-64,121-122; R-PERF-07 | B24 |
| 2.6 | Debounce provider calls | Reuse the LSP feature debounces (completion trigger, hover delay, `PULL_DIAGNOSTICS_DEBOUNCE_MS` 200) for extension providers through the FeatureSource abstraction (D10) so an extension source and an LSP source are called on the same schedule | `LspPolicy.kt:84`; D10 | B19, B23 |
| 2.7 | Cancellation | Every Kotlin request that is superseded (new keystroke, caret move, popup closed) sends `$/cancelRequest` (existing, `JsonRpcConnection.kt:118,260,292`); the adapter maps it to the host's `Cancel` message (`rpcProtocol.ts:461-496`) so providers see `token.isCancellationRequested` | D1 amendment | B19 |
| 2.8 | Backpressure | Bounded inbound queues per stream kind with merge-by-key / drop-oldest (1.3 table); the notification pump becomes per-kind so a decoration storm cannot delay a `showMessage` | `JsonRpcConnection.kt:50-56` | B20, B21 |
| 2.9 | JSON off the main thread | decode on `ioDispatcher`, map to immutable UI models on `Dispatchers.Default`, post a single state swap to main per frame | `JsonRpcConnection.kt:93,159-175` | B20-B22 |
| 2.10 | Tree virtualisation | Lazy `LazyColumn` keyed by handle, paged children (200), `resolveTreeItem` for visible rows only | ui.md:67 | B15, B21 |
| 2.11 | Decorations diffing | Per (editor, type) keep the last range set; apply only the diff to the painter; paint visible line window only; one painter pass per layer per frame (ui.md:241) | ADR 0018 layers | B21, B23 |
| 2.12 | Webview pooling | No pre-created WebView pool (a warm unused WebView costs renderer memory, B25 unknown). Reuse = keep one detached WebView for `retainContextWhenHidden` panels within `maxLive`; destroy/restore otherwise (ui-webviews.md §5). Revisit after T1 | ui-webviews.md:112-120 | B25-B27 |
| 2.13 | `--max-old-space-size` | host 512, adapter 128 (T: adapter holds only protocol state) | nodejs.org cli | B13, B16 |
| 2.14 | Adapter placement | Measure adapter as a separate process (baseline) vs a `worker_threads` Worker started by a preload in the host process (saves one Node floor, ~30-40 MB, UNVERIFIED); the ext host itself does not use `worker_threads` (route-spike.md:258), so no conflict expected. Decide in WP-PERF-6 | M-x86 floor 42 MB | B9, B14 |
| 2.15 | GC tuning | Default GC. Evaluate `--optimize-for-size` and `--max-semi-space-size` (default <= 16 MiB up to 2 GiB limits, nodejs.org) only if B10-B12 fail; no `--jitless` (slower and disables WebAssembly) | nodejs.org | B10-B12 |
| 2.16 | Bundle trimming | If G1-G5 miss by < 2x: stub chat/mcp/debug/notebook out of the host bundle (-22.5% bytes, route-spike.md:73; ADR 0031) | M-x86 | B1, B2, B33 |
| 2.17 | Priority | spawn the host tree via `nice -n 10` inside the guest (coreutils); Android cgroup/cpuset inheritance of guest children across app foreground/background transitions is UNVERIFIED (`cat /proc/<pid>/cgroup` and `/proc/<pid>/cpuset` in both states) | Linux `setpriority` for same UID lowering priority | B23, B36 |
| 2.18 | Thermal | Listen to `PowerManager.addThermalStatusListener`; at >= SEVERE double provider debounces and suspend in background (1.6) | API 29+ | B39 |
| 2.19 | Suspend in background | SIGSTOP/SIGCONT the host process group (1.6 option B) | ipc.net.ts timeouts | B38 |
| 2.20 | Native addon loading | `--no-addons` is **not** used (extensions legitimately ship `.node` files for linux-arm64); G7 only forbids them for the host itself | route-spike.md:59-65 | B1 |

## 3. Measurement tooling

### 3.1 Device benchmark: `tools/vsx-audit/bench/` (design only, not implemented in this audit)

| File | Purpose |
|---|---|
| `bench.mjs` | host-side driver (runs on the dev machine): pushes the harness, runs scenarios over `adb -s 8084d710`, pulls JSON, writes `docs/vsx-compat/results/perf-<date>.json` |
| `guest/renderer.mjs` | the spike fake main side (`tools/vsx-audit/spike/renderer.ts`) extended with stamps, `$test_latency`, per-extension activation loop, RSS sampling of its child |
| `guest/rss.sh` | loop: every 1 s print `pid VmRSS VmHWM utime stime voluntary_ctxt_switches` for the host tree (children from `/proc/*/stat` ppid walk, like `ProcTree`) |
| `guest/io.mjs` | proot IO micro-bench: 10,000 x `fs.statSync`, `openSync/closeSync`, `readFileSync` of a 1 KB file and a 3 MB file; `require` of 300 tiny modules vs 1 bundled module of equal bytes |
| `scenarios/*.json` | `startup-cold`, `startup-warm`, `activate-corpus`, `mem-heavy3`, `rpc`, `idle-10min`, `background-suspend`, `io` |

Guest commands run through the app's own environment (the same proot, rootfs and env as production). Two entry routes: (a) debug
build intent `dev.easyide.app/.debug.BenchReceiver` that runs a scenario via `SandboxShell` (preferred: exact production spawn path);
(b) fallback `adb shell run-as dev.easyide.app` + the proot command line from `ProotLauncher.kt:20-57` (UNVERIFIED that `run-as` can exec
`libproot.so` from the native lib dir under enforcing SELinux).

Device-side commands (all with `adb -s 8084d710`):

| What | Command |
|---|---|
| guest pids | guest processes are ordinary Linux pids of the app UID (proot has no pid namespace): `adb shell pidof dev.easyide.app`, then `adb shell ps -A -o PID,PPID,RSS,NAME --ppid <proot pid>`; reading other UIDs' `/proc` from `shell` may be restricted (hidepid, UNVERIFIED) -> use route (a) |
| RSS / HWM | `cat /proc/<pid>/status` (VmRSS, VmHWM), `cat /proc/<pid>/smaps_rollup` (Pss) |
| app PSS | `adb shell dumpsys meminfo dev.easyide.app`; renderers: `adb shell dumpsys meminfo | grep -i sandboxed` |
| free memory | `adb shell cat /proc/meminfo` (MemAvailable), `dumpsys meminfo` summary "Free RAM" |
| CPU / wakeups | `/proc/<pid>/stat` fields 14-15, `/proc/<pid>/status` ctxt switches; `adb shell dumpsys batterystats --reset` / `--charged dev.easyide.app` |
| UI thread | Perfetto: `adb shell perfetto -o /data/misc/perfetto-traces/ext.pftrace -t 20s sched freq gfx view am wm dalvik` with app `Trace` sections `ext:apply:*`, `ext:decode:*`; open in ui.perfetto.dev |
| host CPU profile | `node --cpu-prof --cpu-prof-dir=/tmp/prof exthost.mjs` (guest), pull the `.cpuprofile` |
| native profile (optional) | `simpleperf record -p <node pid> -g --duration 10` (Android simpleperf on a debuggable build; ptrace conflicts with proot's tracer UNVERIFIED) |
| thermal | `adb shell dumpsys thermalservice` |
| display rate | `adb shell dumpsys display | grep -i refresh` (confirms the 144 Hz frame budget of B21) |

Macrobenchmark module (new, next to `services/mobile/baselineprofile`): `StartupTimingMetric` (B7), `FrameTimingMetric` typing and
scrolling scripts (B21, B23), custom `TraceSectionMetric("ext:apply%")` (B20). Run on 8084d710 with `CompilationMode.Partial` baseline.

### 3.2 CI x86 proxy harness

`tools/vsx-audit/spike/` (bundle + fake main side) promoted to a CI job: bundle host from the pinned tag, run `startup-cold`/`-warm`
(B1, B2), `rpc` (B17), `activate-corpus` over E_in (B4, B5, B10, B11, and g1 of test-program.md), all under the Node version of ADR 0032
(linux-x64 build of the same version). No proot on CI (IO numbers come only from the device).

### 3.3 Device factor k (x86 -> Pad 6)

- Until calibrated: `k_cpu = 4` (lower end of the "4-6x for Snapdragon 870 + proot" allowance behind G1, route-spike.md:328),
  `k_mem = 1.0`, `k_rpc = 4`. CI threshold = device target / k (shown as "CI (...)" in section 1).
- Calibration (WP-PERF-2): run the same scenarios with the same Node version on the CI runner type and on 8084d710, 5 cold + 10 warm;
  `k_cpu` = median over {B2, B4 per extension for the 8 spike extensions} of device/x86; `k_rpc` = device/x86 B17 p50; `k_mem` =
  device/x86 B8 and B10 medians. Store k with its inputs in the results file. Re-calibrate on: Node major change, VS Code tag bump,
  CI runner type change, or when a CI pass / device fail disagreement occurs twice.

### 3.4 Results storage and regression gates

`docs/vsx-compat/results/perf-<YYYY-MM-DD>.json`: `{ device: {model, serial, android, kernel, refreshHz}, node, vscodeTag, bundleSha256,
k: {...}, runs: [{scenario, metric, unit, samples[], p50, p95, p99}], budgets: {B1: {target, fail, pass}} }`. One file per run; never edited.
Gates: CI fails a PR that changes `tools/vsx-audit/**`, the vendored host, the adapter or `:lsp` / ext Kotlin when any CI-scaled metric is
past its fail threshold or regresses > 10% vs the last green `results` file. Device runs are required for each milestone (M1..M5) and
before a VS Code tag bump; a device fail blocks the milestone. PR checklist item "Memory policy / budgets untouched, or macrobenchmark
results attached" (`rules.md:194`) applies.

## 4. Work packages

| WP | Scope | Deps | Acceptance | Effort | Risk |
|---|---|---|---|---|---|
| **WP-PERF-1** Device go/no-go run | run `$SP/spike` equivalent (bundle B, renderer, hello + 8 extensions) on 8084d710 under Node 24 arm64 in the guest; record G1-G7 and B1, B2, B8, B12, B17 | ADR 0032 Node provisioning (manual install acceptable for the spike) | `results/perf-<date>.json` with G1-G7 verdicts; ADR 0031 status updated | 2 d | High (decides route) |
| **WP-PERF-2** Bench harness + k calibration | `tools/vsx-audit/bench/` per 3.1, CI job per 3.2, k per 3.3 | WP-PERF-1 | one command reproduces all DEV rows except MB rows; k stored; CI job green on main | 4 d | Med |
| **WP-PERF-3** Memory policy integration | host tree as a `MemoryPolicy` participant: steps HOST_IDLE and HOST, `extensions.host.memoryBudgetMb`, global budget rule B14, hidden-webview pressure hook | backend.md C9 (ourcode-backend.md:337), WP-HOST supervision | JVM tests: eviction order table 1.2 exact; fake RSS injection triggers restart after 2 samples; device: breach resolved < 2 s | 3 d | Med |
| **WP-PERF-4** Startup path | lazy spawn (B3), preload with compile-cache flush (2.2), graceful terminate, cache dir cap (B34), `nice` (2.17) | WP-HOST spawn | B1-B3, B6 met on device; B7 macrobenchmark shows no regression | 3 d | Low |
| **WP-PERF-5** UI-link throughput | per-kind queues and merge policies, decorations/diagnostics/tree/stream batching (1.3 tables), side channel for > 64 KB | design.md UI protocol | B20-B22 met with a synthetic storm (10k decorations/s, 5k-file diagnostics, 50k-node tree); JVM tests per merge rule | 5 d | Med |
| **WP-PERF-6** Adapter placement + heap flags | measure separate process vs Worker (2.14), set `--max-old-space-size` (2.13), GC flags only if needed (2.15) | WP-PERF-2 | B9, B13, B16 recorded; decision written into design.md | 2 d | Low |
| **WP-PERF-7** Background and battery | suspend policy (option B), thermal hooks, idle measurement | WP-PERF-3, ADR 0023 FGS | B36-B39 on device; no request timeout fired across a 30 min suspend/resume cycle | 3 d | Med (FGS/Play policy interplay) |
| **WP-PERF-8** Webview memory (T1) | ui-webviews.md T1 measurement; set B25-B27; re-derive `maxLive` | WP-UI-9 minimal panel | numbers in results file; `maxLive` defaults justified by formula | 2 d | Med |
| **WP-PERF-9** Macrobenchmarks | new macrobenchmark module: startup, typing, scrolling, `ext:apply` trace sections | WP-UI-7, WP-API language features | B7, B20, B21, B23 green on 8084d710 with the heavy set | 4 d | Med |
| **WP-PERF-10** proot IO profile | `io` scenario; quantify syscall overhead that sandbox-runtime/arch.md:162 asserts; decide whether many-file extensions need a pre-bundling or a cached `stat` layer (none planned) | WP-PERF-2 | B35 measured; "10-30%" either confirmed with numbers or corrected in arch.md | 1 d | Low |
| **WP-PERF-11** Corpus perf gate | B4/B5/B10/B11 per extension in the corpus harness; report worst 10 | test-program.md harness (WP-TEST) | per-extension rows in results; gate in CI | 2 d | Low |

## 5. UNVERIFIED

| Item | How to verify |
|---|---|
| All device numbers (B1-B39) | WP-PERF-1/2 on 8084d710 |
| proot syscall overhead "10-30%" (`sandbox-runtime/arch.md:162`, ADR 0002:16): stated, never measured in the repo | WP-PERF-10 `io` scenario vs the same loop in `adb shell` (toybox) |
| arm64 vs x86 RSS ratio for the same Node version | B8 on both, same bundle |
| Adapter RSS (spike renderer never sampled) | B9 |
| `claude` CLI child RSS when the Claude Code panel is used | B13 measurement with a real session (needs account; else record CLI idle RSS) |
| Whether 1600 MB global budget fits beside Android + app + WebViews on 8 GB | B14 `MemAvailable` under the heavy set |
| Per-WebView PSS, renderer sharing, retained-hidden cost | ui-webviews.md T1 / WP-PERF-8 |
| Pad 6 display refresh 144 Hz (frame budget 6.9 ms) | `dumpsys display` |
| `node --snapshot-blob` for an ESM single-file host that opens a socket at load | WP-PERF-6 spike: `--build-snapshot` with a CJS wrapper deferring main via `setDeserializeMainFunction` |
| Node official linux-arm64 unpacked size ~100 MB (ADR 0032:41) | `du -sh /opt/easyide/node/<v>` on device |
| Default V8 heap limit on the device without flags | guest: `node -p "v8.getHeapStatistics().heap_size_limit/2**20"` |
| Android cgroup/cpuset of guest children after app goes background; effect of `nice` | `/proc/<pid>/cgroup`, `/proc/<pid>/cpuset`, `/proc/<pid>/stat` field 19 in both states |
| `adb shell` access to app-UID `/proc/<pid>` (hidepid) and `run-as` exec of proot | try on device; fall back to the debug receiver |
| simpleperf on a proot-traced process | try `simpleperf record -p` on the node pid |
| Coalesced document deltas vs VS Code per-edit `contentChanges` impact | corpus g4 scenarios (test-program.md) |
| Use of ArrayBuffer transfer in webview `postMessage` across the corpus | static scan of webview bundles (WP-TEST) |
| Linux pipe capacity 64 KB on the device kernel | guest: `python3 -c "import fcntl,os;r,w=os.pipe();print(fcntl.fcntl(w,1032))"` (F_GETPIPE_SZ) |
