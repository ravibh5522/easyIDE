# VS Code extension compatibility - live tracker

Status per work package and per capability (matrix row group). Implementation agents update their row in the same PR
as the work (Not started / In progress / Done, with a link to the PR or commit). Plan: [roadmap.md](roadmap.md).
Generated once by `tools/vsx-audit/gen-tracker-briefs.mjs`; edited by hand since. Score history: `results/` (see
[test-program.md](test-program.md)); current usage-weighted score S_in: not measured (no host exists yet).

## Work packages

| # | WP | Title | M | Deps | Effort | Owner | Brief | Status | Evidence |
|---|---|---|---|---|---|---|---|---|---|
| 1 | WP-HOST-1 | Reproducible host + adapter bundle build from pinned tag | M1 | - | M / 5 | exthost-js | [brief](agent-briefs/WP-HOST-1.md) | Not started | |
| 2 | WP-HOST-2 | Node 24 provisioning (ADR 0032) | M1 | - | M / 5 | kotlin-exthost | [brief](agent-briefs/WP-HOST-2.md) | Not started | |
| 3 | WP-PERF-1 | Device go/no-go measurement run (G1-G7, B1/B2/B8/B12/B17) | M1 | HOST-1, HOST-2 | S / 2 | perf | [brief](agent-briefs/WP-PERF-1.md) | Not started | |
| 4 | WP-HOST-3 | GO/NO-GO decision: ADR 0031 gates G1-G7 recorded | M1 | PERF-1 | S / 2 | perf | [brief](agent-briefs/WP-HOST-3.md) | Not started | |
| 5 | WP-SEC-5 | Host launch hygiene (env allow-list, no token, kill-on-exit) | M1 | - | S / 2 | security | [brief](agent-briefs/WP-SEC-5.md) | Not started | |
| 6 | WP-HOST-4 | `ExtHostSupervisor` (per workspace, lazy start, backoff, park, kill mechanism) | M1 | HOST-1, HOST-2, HOST-3, SEC-5 | L / 15 | kotlin-exthost | [brief](agent-briefs/WP-HOST-4.md) | Not started | |
| 7 | WP-HOST-5 | `:exthost` module + UI protocol v1 transport | M1 | HOST-1 | M / 5 | kotlin-exthost | [brief](agent-briefs/WP-HOST-5.md) | Not started | |
| 8 | WP-HOST-12 | Adapter core (initData, registry, activation fwd, lifecycle, errors, logs) | M1 | HOST-1, HOST-5 | L / 15 | exthost-js | [brief](agent-briefs/WP-HOST-12.md) | Not started | |
| 9 | WP-OOS-DAP | Stub slice: DebugService N+R | M1 | HOST-12 | S / 2 | exthost-js | [brief](agent-briefs/WP-OOS-DAP.md) | Not started | |
| 10 | WP-OOS-NB | Stub slice: Notebook* N/R | M1 | HOST-12 | S / 2 | exthost-js | [brief](agent-briefs/WP-OOS-NB.md) | Not started | |
| 11 | WP-OOS-CHAT | Stub slice: chat/lm/mcp/ai N+R (18 shapes) | M1 | HOST-12 | S / 2 | exthost-js | [brief](agent-briefs/WP-OOS-CHAT.md) | Not started | |
| 12 | WP-OOS-PROP | Stub slice: proposal-only shapes N/R | M1 | HOST-12 | S / 2 | exthost-js | [brief](agent-briefs/WP-OOS-PROP.md) | Not started | |
| 13 | WP-OOS-MS | Stub slice: remote/tunnel/profile shapes N/R | M1 | HOST-12 | S / 2 | exthost-js | [brief](agent-briefs/WP-OOS-MS.md) | Not started | |
| 14 | WP-OOS-WEB | Stub slice: browser-only manifests labelled | M1 | HOST-12 | S / 2 | exthost-js | [brief](agent-briefs/WP-OOS-WEB.md) | Not started | |
| 15 | WP-HOST-7 | Crash journal, attribution, safe mode | M1 | HOST-4 | S / 2 | kotlin-exthost | [brief](agent-briefs/WP-HOST-7.md) | Not started | |
| 16 | WP-HOST-8 | Guest storage homes, Memento, quota, wipe | M1 | HOST-4 | S / 2 | exthost-js | [brief](agent-briefs/WP-HOST-8.md) | Not started | |
| 18 | WP-REG-8 | vsx-specific package limits | M1 | - | S / 2 | registry | [brief](agent-briefs/WP-REG-8.md) | Not started | |
| 20 | WP-HOST-10 | Extension set: start/delta, restart-to-apply, deps, engines check | M1 (enable) / M5 (rest) | HOST-9 | M / 5 | kotlin-exthost | [brief](agent-briefs/WP-HOST-10.md) | Not started | |
| 21 | WP-API-1 | Activation bridge (raw + implicit events, `$activateByEvent`) | M1 | HOST-4, HOST-9, HOST-12 | M / 5 | kotlin-exthost | [brief](agent-briefs/WP-API-1.md) | Not started | |
| 22 | WP-API-2 | Configuration models + update mapping | M1 | HOST-5 | M / 5 | kotlin-exthost | [brief](agent-briefs/WP-API-2.md) | Not started | |
| 23 | WP-API-15 | Identity/env (D9), telemetry no-op, l10n, URI handler fwd | M1 | HOST-4 | S / 2 | exthost-js | [brief](agent-briefs/WP-API-15.md) | Not started | |
| 24 | WP-API-17 | Commands + context keys (`setContext`) | M1 | HOST-12 | M / 5 | exthost-js | [brief](agent-briefs/WP-API-17.md) | Not started | |
| 25 | WP-API-18 | Window: messages, quick input, progress, status, output, dialogs, clipboard | M1 | HOST-12 | L / 15 | exthost-js | [brief](agent-briefs/WP-API-18.md) | Not started | |
| 26 | WP-SEC-12 | Built-in command allow-list + protected settings | M1 | API-17 | S / 2 | security | [brief](agent-briefs/WP-SEC-12.md) | Not started | |
| 27 | WP-SEC-15 | Distribution flag `extensions.code.enabled` per flavour (ADR 0034) | M1 | - | M / 5 | security | [brief](agent-briefs/WP-SEC-15.md) | Not started | |
| 28 | WP-SEC-17 | Telemetry-off enforcement + disclosure | M1 | API-15 | S / 2 | security | [brief](agent-briefs/WP-SEC-17.md) | Not started | |
| 29 | WP-SEC-18 | Legal review package for ADR 0034 (external lead time) | M1 (start) | - | S / 2 | security | [brief](agent-briefs/WP-SEC-18.md) | Not started | |
| 30 | WP-REG-9 | Registry doc corrections (ADR 0016, LLD §9, arch.md) | M1 | - | S / 2 | registry | [brief](agent-briefs/WP-REG-9.md) | Not started | |
| 31 | WP-HOST-14 | Doc drift fixes (added by roadmap) | M1 | - | S / 2 | docs (lead) | [brief](agent-briefs/WP-HOST-14.md) | Not started | |
| 32 | WP-TEST-1 | fake-main harness from spike renderer | M1 | - | M / 5 | test-harness | [brief](agent-briefs/WP-TEST-1.md) | Not started | |
| 33 | WP-TEST-2 | Adapter contract tests + UI-protocol JSON Schema | M1 | TEST-1 | M / 5 | test-harness | [brief](agent-briefs/WP-TEST-2.md) | Not started | |
| 34 | WP-TEST-3 | Kotlin `FakeAdapter` fixture + unit tests | M2 | HOST-5 | L / 15 | test-harness | [brief](agent-briefs/WP-TEST-3.md) | Not started | |
| 35 | WP-HOST-6 | `Victim.Host` in MemoryPolicy, RSS, shedding, onTrimMemory | M2 | HOST-4 | M / 5 | kotlin-exthost | [brief](agent-briefs/WP-HOST-6.md) | Not started | |
| 36 | WP-SEC-14 | Native-code scan at install + spawn log | M2 | REG-4 | M / 5 | security | [brief](agent-briefs/WP-SEC-14.md) | Not started | |
| 37 | WP-HOST-11 | Guest prerequisites, native-binary usability scan, PATH policy | M2 | HOST-4, SEC-14 | S / 2 | kotlin-exthost | [brief](agent-briefs/WP-HOST-11.md) | Not started | |
| 38 | WP-UI-6 | Editor model projection (tabs, visible editors, compare diff) (moved M3->M2 by roadmap) | M2 | HOST-5 | M / 5 | ui-kit | [brief](agent-briefs/WP-UI-6.md) | Not started | |
| 39 | WP-API-3 | Documents/editors sync at scale, will-save, applyEdit | M2 | HOST-5, UI-6 | L / 15 | kotlin-exthost | [brief](agent-briefs/WP-API-3.md) | Not started | |
| 40 | WP-API-4 | FS providers, content providers, findFiles | M2 | HOST-5 | M / 5 | exthost-js | [brief](agent-briefs/WP-API-4.md) | Not started | |
| 41 | WP-API-5 | Guest watchers + file-operation events | M2 | HOST-5 | M / 5 | exthost-js | [brief](agent-briefs/WP-API-5.md) | Not started | |
| 42 | WP-SEC-7 | `ExtensionSecrets` (Keystore AES-GCM) | M2 | - | S / 2 | security | [brief](agent-briefs/WP-SEC-7.md) | Not started | |
| 43 | WP-API-6 | Secrets forwarding + change events | M2 | SEC-7 | S / 2 | kotlin-exthost | [brief](agent-briefs/WP-API-6.md) | Not started | |
| 44 | WP-API-12 | `FeatureSource` + `DocumentSelector` router refactor | M2 | - | M / 5 | lsp-router | [brief](agent-briefs/WP-API-12.md) | Not started | |
| 46 | WP-UI-10 | Language-feature UI deltas | M2 | UI-1, API-12 | M / 5 | ui-kit | [brief](agent-briefs/WP-UI-10.md) | Not started | |
| 47 | WP-API-13 | Provider bridge (~25 kinds) + D10 yield rule | M2 | API-12, API-3, UI-10 | L / 15 | lsp-router | [brief](agent-briefs/WP-API-13.md) | Not started | |
| 48 | WP-UI-4 | Workbench feedback surfaces (notifications, progress, status, quick input, output) | M2 | UI-1, API-18 | L / 15 | ui-kit | [brief](agent-briefs/WP-UI-4.md) | Not started | |
| 49 | WP-SEC-10 | UI anti-spoofing (attribution, modal rate limit, command links) | M2 | UI-4 | M / 5 | security | [brief](agent-briefs/WP-SEC-10.md) | Not started | |
| 50 | WP-REG-1 | Open VSX HTTP client (throttle, 429, paging, cache) | M2 | - | M / 5 | registry | [brief](agent-briefs/WP-REG-1.md) | Not started | |
| 51 | WP-REG-2 | Target-platform resolution + `engines.vscode` grammar | M2 | REG-1 | M / 5 | registry | [brief](agent-briefs/WP-REG-2.md) | Not started | |
| 52 | WP-REG-3 | sha256 + `.sigzip` Ed25519 + kill list | M2 | REG-1 | M / 5 | registry | [brief](agent-briefs/WP-REG-3.md) | Not started | |
| 53 | WP-SEC-1 | Integrity acceptance on REG-3 (ST-01, ST-VSX-11) | M2 | REG-3 | S / 2 | security | [brief](agent-briefs/WP-SEC-1.md) | Not started | |
| 54 | WP-SEC-2 | Kill list, reviewStatus, deprecation handling | M2 | REG-3 | S / 2 | security | [brief](agent-briefs/WP-SEC-2.md) | Not started | |
| 55 | WP-REG-5 | Dependency/pack closure resolver | M2 | REG-2, REG-4 | M / 5 | registry | [brief](agent-briefs/WP-REG-5.md) | Not started | |
| 56 | WP-REG-6 | Browse/search/detail UI | M2 | REG-1, REG-2 | M / 5 | registry | [brief](agent-briefs/WP-REG-6.md) | Not started | |
| 57 | WP-SEC-3 | Publisher display, pin, typosquat warning | M2 | REG-6 | S / 2 | security | [brief](agent-briefs/WP-SEC-3.md) | Not started | |
| 58 | WP-SEC-16 | Licence capture/display, NOTICE rules | M2 | REG-6 | S / 2 | security | [brief](agent-briefs/WP-SEC-16.md) | Not started | |
| 59 | WP-SEC-4 | `host.run` capability + install disclosure sheet | M2 | REG-5, SEC-14 | M / 5 | security | [brief](agent-briefs/WP-SEC-4.md) | Not started | |
| 60 | WP-SEC-6 | Per-environment kill switch + state wipe | M2 | HOST-4, HOST-8 | M / 5 | security | [brief](agent-briefs/WP-SEC-6.md) | Not started | |
| 61 | WP-SEC-13 | Host resource policy acceptance (ST-VSX-10) | M2 | HOST-6 | M / 5 | security | [brief](agent-briefs/WP-SEC-13.md) | Not started | |
| 62 | WP-PERF-2 | Bench harness + k calibration | M2 | PERF-1 | M / 5 | perf | [brief](agent-briefs/WP-PERF-2.md) | Not started | |
| 63 | WP-PERF-3 | Memory policy budgets/tests (on HOST-6) | M2 | HOST-6 | S / 2 | perf | [brief](agent-briefs/WP-PERF-3.md) | Not started | |
| 64 | WP-PERF-4 | Startup path (lazy spawn, compile cache, nice) | M2 | HOST-4 | S / 2 | perf | [brief](agent-briefs/WP-PERF-4.md) | Not started | |
| 65 | WP-PERF-5 | UI-link throughput (queues, coalescing, side channel) | M2 | HOST-5 | M / 5 | perf | [brief](agent-briefs/WP-PERF-5.md) | Not started | |
| 66 | WP-PERF-6 | Adapter placement + heap flags | M2 | PERF-2 | S / 2 | perf | [brief](agent-briefs/WP-PERF-6.md) | Not started | |
| 67 | WP-PERF-10 | proot IO profile | M2 | PERF-2 | S / 2 | perf | [brief](agent-briefs/WP-PERF-10.md) | Not started | |
| 68 | WP-TEST-4 | Corpus runner CLI + results JSON | M2 | TEST-1 | M / 5 | test-harness | [brief](agent-briefs/WP-TEST-4.md) | Not started | |
| 69 | WP-TEST-5 | Runtime API call tracer + gap-analysis join | M2 | TEST-4, HOST-12 | S / 2 | test-harness | [brief](agent-briefs/WP-TEST-5.md) | Not started | |
| 70 | WP-TEST-6 | `gen-compat-table.mjs` | M2 | TEST-4 | S / 2 | test-harness | [brief](agent-briefs/WP-TEST-6.md) | Not started | |
| 71 | WP-TEST-11 | CI tiers, regression rule, flake policy | M2 | TEST-4 | S / 2 | test-harness | [brief](agent-briefs/WP-TEST-11.md) | Not started | |
| 72 | WP-TEST-13 | Guest toolchain fixtures for scenarios (added by roadmap) | M2 | - | L / 15 | test-harness | [brief](agent-briefs/WP-TEST-13.md) | Not started | |
| 73 | WP-TEST-8 | 22 scenario scripts (M2: 8 language, M3: 2, M4: 5, M5: rest) | M2-M5 | TEST-1, TEST-4, TEST-13 | XL / 30 | test-harness | [brief](agent-briefs/WP-TEST-8.md) | Not started | |
| 74 | WP-TEST-9 | Device smoke script + reviewed `tap.py` | M2 | TEST-4, HOST-4 | M / 5 | test-harness | [brief](agent-briefs/WP-TEST-9.md) | Not started | |
| 75 | WP-API-16 | Workspace trust | M3 | HOST-4 | S / 2 | exthost-js | [brief](agent-briefs/WP-API-16.md) | Not started | |
| 76 | WP-API-19 | Text editor, decorations, file decorations, tabs (main side) | M3 | HOST-12, API-3 | M / 5 | exthost-js | [brief](agent-briefs/WP-API-19.md) | Not started | |
| 77 | WP-API-20 | Tree views (main side) | M3 | HOST-12 | M / 5 | exthost-js | [brief](agent-briefs/WP-API-20.md) | Not started | |
| 78 | WP-UI-2 | Context keys (`===`/`!==`, key table, overlays) | M3 | API-17 | M / 5 | ui-kit | [brief](agent-briefs/WP-UI-2.md) | Not started | |
| 81 | WP-UI-12 | Settings & walkthroughs | M3 | UI-1 | M / 5 | ui-kit | [brief](agent-briefs/WP-UI-12.md) | Not started | |
| 82 | WP-TEST-7 | 9 golden test classes | M3 | UI-4, UI-5 | M / 5 | test-harness | [brief](agent-briefs/WP-TEST-7.md) | Not started | |
| 83 | WP-PERF-7 | Background and battery | M3 | PERF-3 | S / 2 | perf | [brief](agent-briefs/WP-PERF-7.md) | Not started | |
| 84 | WP-API-21 | Webviews + custom editors (main side) | M4 | HOST-12 | L / 15 | exthost-js | [brief](agent-briefs/WP-API-21.md) | Not started | |
| 85 | WP-UI-7 | Extension decorations layer (pre-PE1) | M4 | UI-1, API-19 | L / 15 | ui-kit | [brief](agent-briefs/WP-UI-7.md) | Not started | |
| 86 | WP-UI-8 | Webview foundation (ADR 0033) | M4 | UI-1, API-21 | L / 15 | ui-webview | [brief](agent-briefs/WP-UI-8.md) | Not started | |
| 87 | WP-SEC-9 | Webview security baseline acceptance | M4 | UI-8 | L / 15 | security | [brief](agent-briefs/WP-SEC-9.md) | Not started | |
| 88 | WP-UI-9 | Webview panels, views, custom editors | M4 | UI-8, UI-3, UI-5 | L / 15 | ui-webview | [brief](agent-briefs/WP-UI-9.md) | Not started | |
| 89 | WP-API-22 | SCM + quick diff (main side) | M4 | HOST-12 | M / 5 | exthost-js | [brief](agent-briefs/WP-API-22.md) | Not started | |
| 90 | WP-UI-11 | SCM UI (moved M5->M4 by roadmap) | M4 | UI-3, UI-7, API-22 | L / 15 | ui-kit | [brief](agent-briefs/WP-UI-11.md) | Not started | |
| 91 | WP-API-10 | Git API via vendored `vscode.git`/`git-base` | M4 | HOST-10, API-4, API-22, UI-11 | L / 15 | exthost-js | [brief](agent-briefs/WP-API-10.md) | Not started | |
| 94 | WP-API-14 | Proxy + CA forwarding | M4 | HOST-5 | M / 5 | exthost-js | [brief](agent-briefs/WP-API-14.md) | Not started | |
| 95 | WP-SEC-11 | Deep-link router + openExternal allow-list | M4 | API-15 | M / 5 | security | [brief](agent-briefs/WP-SEC-11.md) | Not started | |
| 96 | WP-UI-14 | Accounts & URI handler UI | M4 | SEC-11, API-15 | M / 5 | ui-kit | [brief](agent-briefs/WP-UI-14.md) | Not started | |
| 97 | WP-PERF-8 | Webview memory (T1), `maxLive` | M4 | UI-9 | S / 2 | perf | [brief](agent-briefs/WP-PERF-8.md) | Not started | |
| 98 | WP-PERF-9 | Macrobenchmarks | M4 | UI-7, API-13 | M / 5 | perf | [brief](agent-briefs/WP-PERF-9.md) | Not started | |
| 99 | WP-TEST-10 | Extension-driven macrobenchmark scenarios | M4 | TEST-8, PERF-2 | M / 5 | test-harness | [brief](agent-briefs/WP-TEST-10.md) | Not started | |
| 100 | WP-SEC-8 | GitHub auth provider (consent, Accounts, OAuth) | M5 | SEC-11 | L / 15 | security | [brief](agent-briefs/WP-SEC-8.md) | Not started | |
| 101 | WP-API-11 | Authentication (adapter + Kotlin GitHub provider) | M5 | SEC-8, UI-14 | L / 15 | kotlin-exthost | [brief](agent-briefs/WP-API-11.md) | Not started | |
| 103 | WP-API-9 | Shell integration (OSC 633) | M5 | API-8 | M / 5 | exthost-js | [brief](agent-briefs/WP-API-9.md) | Not started | |
| 105 | WP-API-23 | Testing (TestController main side) | M5 | HOST-12 | M / 5 | exthost-js | [brief](agent-briefs/WP-API-23.md) | Not started | |
| 106 | WP-API-24 | Comments (stub from M1, full in M5) | M5 | HOST-12 | M / 5 | exthost-js | [brief](agent-briefs/WP-API-24.md) | Not started | |
| 107 | WP-API-25 | Labels, theming, misc | M5 | HOST-12 | S / 2 | exthost-js | [brief](agent-briefs/WP-API-25.md) | Not started | |
| 108 | WP-UI-15 | Comments UI | M5 | UI-3, UI-7, API-24 | L / 15 | ui-kit | [brief](agent-briefs/WP-UI-15.md) | Not started | |
| 109 | WP-UI-16 | Accessibility pass | M5 | UI-15, UI-13, UI-11, UI-9, UI-12 | M / 5 | ui-kit | [brief](agent-briefs/WP-UI-16.md) | Not started | |
| 110 | WP-REG-7 | Updates, pre-release opt-in, rollback re-validation | M5 | REG-3, REG-4 | M / 5 | registry | [brief](agent-briefs/WP-REG-7.md) | Not started | |
| 111 | WP-PERF-11 | Corpus perf gate | M5 | TEST-4 | S / 2 | perf | [brief](agent-briefs/WP-PERF-11.md) | Not started | |
| 112 | WP-TEST-12 | Monthly corpus refresh automation | M5 | - | S / 2 | test-harness | [brief](agent-briefs/WP-TEST-12.md) | Not started | |

## Capabilities (matrix rows grouped by capability id; row detail in [matrix/](matrix/))

| Capability | Gap today | M | WP | Status |
|---|---|---|---|---|
| `auth.api` | Missing-both | M5 | WP-API-11, WP-SEC-8, WP-UI-14 | Not started |
| `auth.github` | Missing-both | M5 | WP-API-11, WP-SEC-8, WP-HOST-13 | Not started |
| `chat.lm` | Not-planned | OOS | WP-OOS-CHAT | Not started |
| `commands.builtin` | Missing-both | M3 | WP-API-17, WP-UI-2, WP-UI-6, WP-SEC-12 | Not started |
| `commands.registry` | Missing-backend | M1 | WP-API-17 | Not started |
| `comments.api` | Missing-both | M5 | WP-API-24, WP-UI-15 | Not started |
| `config.defaults` | Missing-backend | M1 | WP-API-2, WP-HOST-9 | Not started |
| `config.inspect` | Missing-backend | M1 | WP-API-2 | Not started |
| `config.langOverride` | Missing-backend | M1 | WP-API-2 | Not started |
| `config.model` | Missing-backend | M1 | WP-API-2 | Not started |
| `debug.dap` | Not-planned | OOS | WP-OOS-DAP | Not started |
| `diagnostics` | Missing-backend | M2 | WP-API-13, WP-UI-10 | Not started |
| `doc.content-provider` | Missing-both | M2 | WP-API-4, WP-API-13 | Not started |
| `doc.encoding` | Missing-backend | M2 | WP-API-3 | Not started |
| `doc.model` | Missing-backend | M2 | WP-API-3 | Not started |
| `doc.sync` | Missing-backend | M2 | WP-API-3 | Not started |
| `env.clipboard` | Missing-backend | M1 | WP-API-18 | Not started |
| `env.info` | Missing-backend | M1 | WP-API-15 | Not started |
| `env.openExternal` | Missing-backend | M1 | WP-API-18, WP-SEC-11 | Not started |
| `env.shell` | Missing-backend | M1 | WP-API-15 | Not started |
| `env.uri-handler` | Missing-both | M4 | WP-API-15, WP-API-18, WP-SEC-11, WP-UI-14 | Not started |
| `ext.api` | Missing-backend | M1 | WP-HOST-9, WP-HOST-12 | Not started |
| `ext.builtins` | Missing-backend | M5 | WP-API-10, WP-HOST-13 | Not started |
| `ext.context` | Missing-backend | M1 | WP-HOST-8, WP-HOST-12 | Not started |
| `ext.deps` | Missing-both | M4 | WP-HOST-10, WP-REG-5, WP-API-10 | Not started |
| `ext.enable` | Missing-backend | M1 | WP-HOST-10 | Not started |
| `ext.killswitch` | Missing-both | M1 | WP-SEC-6, WP-HOST-4 | Not started |
| `ext.kind` | Missing-backend | M1 | WP-HOST-10 | Not started |
| `ext.manifest` | Missing-backend | M1 | WP-HOST-9, WP-REG-4, WP-REG-8 | Not started |
| `ext.pack` | Missing-both | M5 | WP-HOST-10, WP-REG-5 | Not started |
| `ext.safemode` | Partial | M1 | WP-HOST-7 | Not started |
| `ext.update` | Partial | M5 | WP-HOST-10, WP-REG-7 | Not started |
| `ext.web` | Not-planned | OOS | WP-OOS-WEB | Not started |
| `fork.api` | Not-planned | OOS |  | Not started |
| `fs.api` | Missing-backend | M2 | WP-HOST-1, WP-API-4 | Not started |
| `fs.provider` | Missing-both | M2 | WP-API-4 | Not started |
| `fs.watch` | Missing-backend | M2 | WP-API-5 | Not started |
| `git.api` | Missing-backend | M4 | WP-API-10 | Not started |
| `host.activation` | Missing-backend | M1 | WP-API-1 | Not started |
| `host.core-types` | Missing-backend | M1 | WP-HOST-1 | Not started |
| `host.crash` | Missing-backend | M1 | WP-HOST-7 | Not started |
| `host.lifecycle` | Missing-backend | M1 | WP-HOST-4 | Not started |
| `host.loader` | Missing-backend | M1 | WP-HOST-1, WP-HOST-12 | Not started |
| `host.memory` | Missing-backend | M2 | WP-HOST-6, WP-SEC-13, WP-PERF-3 | Not started |
| `host.process` | Missing-backend | M1 | WP-HOST-2, WP-HOST-3, WP-HOST-4 | Not started |
| `host.transport` | Missing-backend | M1 | WP-HOST-5 | Not started |
| `l10n` | Missing-backend | M2 | WP-API-15, WP-HOST-12 | Not started |
| `lang.call-hierarchy` | Missing-both | M5 | WP-API-13, WP-UI-10 | Not started |
| `lang.code-action` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.code-lens` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.color` | Missing-both | M5 | WP-API-13, WP-UI-10 | Not started |
| `lang.completion` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.definition` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.drop-paste` | Missing-both | M5 | WP-API-13, WP-UI-10 | Not started |
| `lang.folding` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.formatting` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.grammar` | Have | M1 | WP-HOST-9 | Not started |
| `lang.highlight` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.hover` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.inlay-hint` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.inline-completion` | Missing-both | M5 | WP-API-13, WP-UI-10 | Not started |
| `lang.inline-values` | Not-planned | OOS | WP-OOS-DAP | Not started |
| `lang.json-validation` | Missing-backend | M5 | WP-HOST-13 | Not started |
| `lang.language-config` | Missing-backend | M2 | WP-API-13 | Not started |
| `lang.languages` | Have | M1 | WP-HOST-9 | Not started |
| `lang.linked-editing` | Missing-both | M5 | WP-API-13, WP-UI-10 | Not started |
| `lang.links` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.references` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.rename` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.router` | Missing-backend | M2 | WP-API-12, WP-API-13 | Not started |
| `lang.selection-range` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.semantic-tokens` | Missing-backend | M5 | WP-API-13, WP-UI-10 | Not started |
| `lang.set-language` | Missing-backend | M2 | WP-API-3 | Not started |
| `lang.signature-help` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.snippets` | Have | M1 | WP-HOST-9 | Not started |
| `lang.symbols` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `lang.type-hierarchy` | Missing-both | M5 | WP-API-13, WP-UI-10 | Not started |
| `lang.workspace-symbols` | Missing-backend | M2 | WP-API-12, WP-API-13, WP-UI-10 | Not started |
| `log.output` | Missing-both | M2 | WP-API-18, WP-UI-4, WP-HOST-12 | Not started |
| `misc.unclassified` | Not-planned | OOS | WP-OOS-PROP | Not started |
| `ms-services` | Not-planned | OOS | WP-OOS-MS | Not started |
| `native.modules` | Missing-backend | M2 | WP-HOST-11, WP-REG-4, WP-SEC-14 | Not started |
| `net.proxy` | Missing-backend | M2 | WP-API-14 | Not started |
| `notebook.api` | Not-planned | OOS | WP-OOS-NB | Not started |
| `process.spawn` | Missing-backend | M2 | WP-HOST-11 | Not started |
| `proposed.api` | Not-planned | OOS | WP-OOS-PROP | Not started |
| `scm.api` | Missing-both | M5 | WP-API-22, WP-UI-11 | Not started |
| `secrets` | Missing-backend | M2 | WP-API-6, WP-SEC-7 | Not started |
| `storage.memento` | Missing-backend | M1 | WP-HOST-8 | Not started |
| `storage.paths` | Missing-backend | M1 | WP-HOST-8 | Not started |
| `tasks.api` | Missing-both | M5 | WP-API-7, WP-UI-13 | Not started |
| `tasks.problem-matchers` | Missing-backend | M5 | WP-API-7 | Not started |
| `telemetry` | Missing-backend | M1 | WP-API-15, WP-SEC-17 | Not started |
| `terminal.api` | Missing-backend | M4 | WP-API-8 | Not started |
| `terminal.env-collection` | Missing-backend | M4 | WP-API-8 | Not started |
| `terminal.links` | Missing-both | M5 | WP-API-8, WP-UI-13 | Not started |
| `terminal.profiles` | Missing-both | M5 | WP-API-8, WP-UI-13 | Not started |
| `terminal.pty` | Missing-both | M5 | WP-API-8, WP-UI-13 | Not started |
| `terminal.shell-integration` | Missing-backend | M5 | WP-API-9 | Not started |
| `tests.api` | Missing-both | M5 | WP-API-23, WP-UI-13 | Not started |
| `ui.accessibility` | Partial | M5 | WP-UI-16 | Not started |
| `ui.activity-nav` | Have | M3 | WP-UI-5 | Not started |
| `ui.auth-ui` | Missing-both | M4 | WP-API-11, WP-UI-14 | Not started |
| `ui.breadcrumbs` | Missing-backend | M2 | WP-UI-10 | Not started |
| `ui.code-action-ui` | Missing-backend | M2 | WP-UI-10 | Not started |
| `ui.code-lens-ui` | Missing-both | M2 | WP-UI-10 | Not started |
| `ui.codicons` | Missing-UI | M2 | WP-UI-1 | Not started |
| `ui.color-theme` | Missing-both | M2 | WP-UI-1 | Not started |
| `ui.command-palette` | Have | M1 | WP-UI-3 | Not started |
| `ui.comments-ui` | Missing-both | M5 | WP-API-24, WP-UI-15 | Not started |
| `ui.completion-ui` | Missing-both | M2 | WP-UI-10 | Not started |
| `ui.context-keys` | Missing-both | M3 | WP-API-17, WP-UI-2 | Not started |
| `ui.custom-editor` | Missing-both | M4 | WP-API-21, WP-UI-9 | Not started |
| `ui.debug` | Not-planned | OOS | WP-OOS-DAP | Not started |
| `ui.diagnostics-ui` | Missing-both | M2 | WP-UI-10 | Not started |
| `ui.dialogs` | Missing-both | M3 | WP-API-18 | Not started |
| `ui.diff-editor` | Missing-both | M3 | WP-UI-6 | Not started |
| `ui.editor-decorations` | Missing-both | M4 | WP-API-19, WP-UI-7 | Not started |
| `ui.extensions-ui` | Partial | M1 | WP-REG-6, WP-SEC-4 | Not started |
| `ui.file-decorations` | Missing-both | M4 | WP-API-19, WP-UI-7 | Not started |
| `ui.folding` | Missing-UI | M5 | PE1 | Not started |
| `ui.gutter` | Missing-both | M4 | WP-UI-7 | Not started |
| `ui.hover-ui` | Missing-both | M2 | WP-UI-10 | Not started |
| `ui.icon-theme` | Have | M1 | WP-HOST-9 | Not started |
| `ui.icons` | Missing-UI | M2 | WP-UI-1 | Not started |
| `ui.inlay-hint-ui` | Missing-both | M2 | WP-UI-10 | Not started |
| `ui.input-box` | Missing-both | M2 | WP-API-18, WP-UI-4 | Not started |
| `ui.keybindings` | Have | M3 | WP-UI-2 | Not started |
| `ui.language-status` | Missing-both | M2 | WP-API-18, WP-UI-4 | Not started |
| `ui.menus` | Partial | M3 | WP-UI-3 | Not started |
| `ui.minimap` | Not-planned | OOS |  | Not started |
| `ui.notebook` | Not-planned | OOS | WP-OOS-NB | Not started |
| `ui.notifications` | Missing-both | M2 | WP-API-18, WP-UI-4, WP-SEC-10 | Not started |
| `ui.outline` | Missing-backend | M2 | WP-UI-10 | Not started |
| `ui.output` | Missing-both | M2 | WP-API-18, WP-UI-4 | Not started |
| `ui.overview-ruler` | Missing-both | M4 | WP-UI-7 | Not started |
| `ui.panel` | Partial | M2 | WP-UI-4 | Not started |
| `ui.peek` | Missing-both | M2 | WP-UI-10 | Not started |
| `ui.product-icon-theme` | Not-planned | OOS |  | Not started |
| `ui.progress` | Missing-both | M2 | WP-API-18, WP-UI-4 | Not started |
| `ui.quick-pick` | Missing-both | M2 | WP-API-18, WP-UI-4 | Not started |
| `ui.rename-ui` | Missing-backend | M2 | WP-UI-10 | Not started |
| `ui.resource-labels` | Not-planned | M1 | WP-API-25 | Not started |
| `ui.scm-view` | Missing-both | M5 | WP-API-22, WP-UI-11 | Not started |
| `ui.semantic-highlighting` | Missing-backend | M2 | WP-UI-10 | Not started |
| `ui.settings-ui` | Partial | M3 | WP-UI-12 | Not started |
| `ui.status-bar` | Missing-both | M2 | WP-API-18, WP-UI-4 | Not started |
| `ui.submenus` | Missing-UI | M3 | WP-UI-3 | Not started |
| `ui.tabs` | Missing-backend | M3 | WP-API-19, WP-UI-6 | Not started |
| `ui.terminal-ui` | Missing-both | M5 | WP-API-8, WP-UI-13 | Not started |
| `ui.testing` | Missing-both | M5 | WP-API-23, WP-UI-13 | Not started |
| `ui.text-editor` | Missing-both | M2 | WP-API-19, WP-UI-6 | Not started |
| `ui.theme-colors` | Missing-UI | M2 | WP-UI-1 | Not started |
| `ui.timeline` | Not-planned | OOS | WP-OOS-PROP | Not started |
| `ui.tree-view` | Missing-both | M3 | WP-API-20, WP-UI-5 | Not started |
| `ui.view-container` | Partial | M3 | WP-UI-5 | Not started |
| `ui.views-welcome` | Missing-UI | M3 | WP-UI-5 | Not started |
| `ui.walkthroughs` | Missing-UI | M3 | WP-UI-12 | Not started |
| `ui.webview-bridge` | Missing-both | M4 | WP-UI-8, WP-SEC-9 | Not started |
| `ui.webview-panel` | Missing-both | M4 | WP-API-21, WP-UI-8, WP-UI-9, WP-SEC-9 | Not started |
| `ui.webview-view` | Missing-both | M4 | WP-API-21, WP-UI-8, WP-UI-9, WP-SEC-9 | Not started |
| `ui.window-misc` | Not-planned | OOS | WP-OOS-PROP | Not started |
| `workspace.edit` | Missing-backend | M2 | WP-API-3 | Not started |
| `workspace.find` | Missing-backend | M2 | WP-API-4 | Not started |
| `workspace.folders` | Missing-backend | M2 | WP-API-2, WP-API-3 | Not started |
| `workspace.misc` | Missing-backend | M2 | WP-API-3 | Not started |
| `workspace.save` | Missing-backend | M2 | WP-API-3 | Not started |
| `workspace.trust` | Missing-backend | M3 | WP-API-16 | Not started |
