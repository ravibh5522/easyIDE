# Extension SDK - Test plan

How the language core, extension runtime, WASM host, registry and CLI are tested, what
gates CI, and the acceptance tests that close each milestone.
Status: PROPOSED (2026-09-23). Design: [arch.md](arch.md). Contract: [sdk-reference.md](sdk-reference.md).
Feature ids: [features.md](features.md). Rules referenced as R-xxx-nn: [rules.md](rules.md).

## Baseline

- **There are no test source sets today.** No `src/test` or `src/androidTest` exists under
  `services/mobile/*`; `.github/workflows/canary.yml` and `release.yml` only run
  `./gradlew :app:assembleCanary` / `:app:assembleRelease`. Every gate below is new work,
  tracked in [tracker.md](tracker.md).
- **No Android emulator is available in the current development environment.** Instrumented
  tests, macrobenchmarks and the device matrix need a physical device (reference: Xiaomi
  Pad 6, 8 GB) or a CI device farm. Until one exists, results from those layers are
  reported as "not run" - never inferred from JVM tests (R-DOC-08).
- Consequence for design: as much logic as possible lives in JVM-only modules (`:lsp`,
  `:extensions`, `:ext-wasm`, `tools/easyide-ext`), so most of this plan runs on a laptop
  and in CI without a device (R-ENG-01, R-ENG-03).
- Where arch.md and sdk-reference.md disagree on a default (global LSP budget, WASM
  memory, WASM timeout), tests take the **sdk-reference.md** value, read from the schema.

## Test layers

| Layer | Runs on | Tooling | Scope | Gate |
|---|---|---|---|---|
| U - Unit | JVM, every PR | JUnit 5 + kotlinx-coroutines-test | pure logic: when-clauses, settings resolution, path mapping, diffing, framing, state machines, schema | required |
| I - JVM integration | JVM, every PR | JUnit 5 + `FakeLspServer` + temp dirs | `:lsp` sessions end to end, install/rollback on a temp tree, registry verify with test keys | required |
| W - WASM conformance | JVM, every PR | JUnit 5 + Chicory + prebuilt guest fixtures | ABI v1, host functions, capability checks, limits | required from M7 |
| C - CLI | JVM, every PR | JUnit 5 + golden files | `easyide-ext` subcommands, exit codes, `--json` | required from M5 |
| A - Instrumented | device, nightly / pre-release | AndroidX Test, Compose UI test | ART, real proot, `ServerProcessFactory`, decorations, touch targets, StrictMode | release gate; "not run" only with a label |
| P - Macrobenchmark | device, nightly | Jetpack Macrobenchmark + `/proc` sampler | startup, typing latency, LSP main-thread time, eviction | release gate |
| S - Security | JVM + device | fixtures, fault injection | tampering, capability denial, secret leakage, zip-slip | required (JVM part every PR) |
| M - Manual matrix | devices, per release | checklist | real servers, OEM killers, keyboards, stylus | release sign-off |

## Platform (M0)

Covers PLT-01..13 foundations shared with ux-overhaul Pillar 4/5 and ADR-B.

| Area | Layer | What is asserted |
|---|---|---|
| Command registry | U | a command registered once is reachable from palette, a menu and a keybinding; `enablement` follows its when-clause |
| Keymap | U | a keymap JSON entry binds a chord; `"-cmd"` removes it; key row generated from the same table contains the same commands (R-UX-04) |
| Settings schema + project file | U, I | layering order and `[lang]` precedence; project `.easyide/settings.json` wins; invalid value falls to the next layer and logs once |
| Decoration layers | A | underline, background range, inline text, gutter icon, between-line block and caret popup each render from a test provider (screenshot) |
| Server process spawn | A, S | `ServerProcessFactory` (beside `ShellRunner`) runs a real process in proot with a clean env plus declared `env`; stdio survives 4 MB messages |
| `MemoryPolicy` + RSS sampler | U, A | policy table drives kill order; sampler reads `/proc/<pid>/status` for the process tree |
| Shared schema | U, C | app validator and CLI validator return identical verdicts on every fixture (R-ENG-05) |
| Environment probes | I | `envHasCommand:<name>` refreshes after an install; `envDistro/Arch/Backend` from `LinuxEnvironment` |
| API version + deprecation | U | `engines.easyide` matching; deprecated key warns and still works inside the window (R-API-03/04) |
| Per-extension secret store | U, S | namespaced by extension id; cannot address git token (`GitCredentials`) names (R-SEC-03) |

## L0 language core

No-server features (NS-*, arch 5.1). Fixture projects: Python, TS, Go, C, Rust, Markdown.

| Area | Layer | What is asserted |
|---|---|---|
| language-configuration | U | brackets, auto-close with `autoCloseBefore`, auto-surround, line/block comment toggle, `indentationRules`, `onEnterRules`, `wordPattern` - table-driven cases per language |
| Generated configs | C | `tools/build-grammars.py` emits a config for each bundled grammar or records the generic fallback (arch.md sec 14 q6) |
| Folding | U | marker and indentation folding ranges; LSP `foldingRange` replaces L0 when present |
| Snippet engine | U, A | tab stops, placeholders, choices, nested placeholders, `$TM_*` variables, escaping; navigation by key-row Tab (touch) and keyboard |
| Word completion | U | buffer + open tabs, respects `editor.wordBasedSuggestions`, merge order with snippets per `editor.snippetSuggestions` |
| Highlighting unchanged | U | TextMate output from `TextMateHighlighter` / `DocumentHighlighter` is byte-identical before and after L0 work on fixture files (regression guard) |
| Extension grammars | I | a grammar from a package loads through the same path as bundled ones; `tokenColors` collapse onto `ScopeRules` roles |

## LSP client

Headless JVM first (LSP-01..12), then features (LSP-20..38) on device.

### Unit

| Component | What is asserted |
|---|---|
| `JsonRpcConnection` | `Content-Length` with split reads, several messages per buffer, non-ASCII bodies (byte vs char length), malformed header -> connection error, not crash; id correlation; `$/cancelRequest` on coroutine cancel; late responses dropped |
| `DocumentSync` | prefix/suffix diff gives one correct range for insert, delete, replace, whole-doc replace, empty doc, CRLF, surrogate pairs; UTF-16 and negotiated UTF-8 positions; versions strictly increasing |
| `PathMapper` | project and environment paths both ways; spaces, `%`, unicode; `..` escapes refused; environment files flagged read-only |
| State machine | every transition of arch.md sec 7.6; illegal transitions unrepresentable (R-ENG-10); backoff from `lsp.restart.backoffMs` doubling and capped |
| `MemoryPolicy` kill order | synthetic sessions (IDLE/RUNNING, visible or not, LRU times, idle WASM) -> victim list per sec 7.7; terminals and buffers never listed (R-PERF-03) |

### JVM integration with a fake LSP server

`FakeLspServer` (test fixture in `:lsp` test sources) is driven by a script of expected
requests and canned responses and records every message it receives. Modes: `normal`,
`slow`, `crashAfter(n)`, `hangOnInitialize`, `garbage`, `fullSyncOnly`, `pullDiagnostics`,
`utf8Positions`, `dynamicRegistration`, `serverRequests` (`workspace/configuration`,
`workspace/applyEdit`, `window/workDoneProgress/create`). An `rssProvider` hook injects RSS
samples so budget tests need no device.

Scenarios: initialize + capability negotiation (client advertises only rendered features);
flush-before-request ordering; stale response dropped by version; same-kind request
cancels previous; `lsp.requestTimeoutMs` timeout is silent (R-UX-06); crash -> BACKOFF ->
RUNNING; `lsp.restart.maxRetries` crashes -> FAILED with stderr tail; idle shutdown after
`lsp.idleShutdownSec` (virtual time); per-server and `lsp.globalMemoryBudgetMb` breach ->
eviction order; `lsp.maxServers` LRU; two projects in one environment -> two sessions
(key `(env, project, server)`); pyright + ruff split via `features.only`;
`workspace/configuration` answered from resolved settings.

Recorded traces from basedpyright/jedi, ruff, gopls, clangd, typescript-language-server
(captured on device in M2/M4, stored with server version and date) are replayed against
the client to catch protocol regressions without the servers.

### On device

Squiggles, gutter icons, Problems panel, completion popup, hover card (long-press and
mouse hover), signature help, rename preview, lightbulb, Outline; each with a touch path
and a keyboard path (R-UX-05). Timing gates are in [On-device performance](#on-device-performance).

## Extension runtime

EXT-01..09, 20..34, 40 and in-app authoring ECO-30..32.

| Area | Layer | What is asserted |
|---|---|---|
| Manifest parser | U | every sdk-reference example parses; naming regex, SemVer, scope computation, unknown keys warn not fail (R-API-08) |
| Capability audit | U, S | action needing an undeclared capability -> load error (R-SEC-08) |
| When-clause evaluator | U | full grammar: precedence, `=~ /re/flags`, `in`/`not in` with context arrays and quoted lists, unknown keys falsy, parse errors report a column |
| Action engine | U | each action type against a fake host: variable substitution, `${result:}` binding, `${env:}` from the environment only (R-SEC-04), shell quoting (R-SEC-15), sequence abort vs `continueOnError`, cancel silent |
| Contribution registry | I | commands, menus, keybindings, configuration, themes, key rows, stages, status items register into Pillar 4/5 registries - no parallel registry (R-ENG-15) |
| Activation | I | `onLanguage`, `onCommand`, `onView`, `onStage`, `workspaceContains`, `onStartupFinished`; servers become eligible, not started; 2 consecutive activation crashes -> safe mode |
| Cached manifest index | I | startup reads the cache, no zip access; cache rebuilt after corruption |
| Install lifecycle | I | install from folder -> disable -> enable -> update -> rollback -> uninstall; fault injection at every step leaves the previous `current` live (R-ENG-16) |
| Extension Log / inspector | A | errors from actions and activation appear with extension id; inspector names the contributor and any user override hiding it |

## WASM host

EXT-50..54, PLT-13. Guest fixtures are built from `tools/easyide-ext/fixtures/wasm/`
(Rust and AssemblyScript sources) and the `.wasm` binaries are checked in, so CI needs no
guest toolchain.

| Fixture | Asserts |
|---|---|
| `abi-ok` | `ext_abi_version` = 1; activate message fields; command round trip; `free` called on every returned buffer |
| `abi-wrong-version`, `abi-missing-export`, `imports-wasi` | load refused with a specific reason (R-SEC-10) |
| `hostcall-matrix` | every host function with and without its capability -> ok vs `E_CAPABILITY` (R-SEC-09) |
| `spin-forever` | `extensions.wasm.fuelPerCall` exhausted -> `E_LIMIT`, instance discarded, main thread never blocked |
| `sleep-hostcalls` | `maxHostCallsPerCall` and `callTimeoutMs` -> `E_TIMEOUT` |
| `grow-memory` | `maxMemoryMb` cap -> trap |
| `huge-message` | `maxMessageKb` -> `E_LIMIT` |
| `crash-loop` | `maxCrashes` traps within `crashWindowSec` -> disabled until re-enabled |
| `net-fetch` | declared host ok; undeclared host, http, suffix mismatch refused (R-SEC-11) |
| `bad-json`, `bad-length-prefix` | host answers `E_ARGS`; host survives |
| `deactivate-slow` | no answer within `deactivateTimeoutMs` -> instance dropped anyway |

The same suite runs on ART (layer A) and records interpreter throughput. That run is the
spike that moves ADR [0014](../decision/0014-wasm-logic-layer-chicory.md) out of Proposed.

## Registry and install

ECO-01..12, EXT-27. Test keys, index and packages in the fixtures below.

| Area | Layer | What is asserted |
|---|---|---|
| Canonical JSON | U | RFC 8785 test vectors: key order, number formatting, unicode escapes |
| ed25519 | U | RFC 8032 vectors; keyId = first 16 hex of sha256(raw public key) |
| Verification order | I, S | index sig, revocations sig, entry sig, key active/not revoked, TOFU pin/rotation, size + sha256, then `engines` and capability prompt - each failure is a hard stop with its own reason (R-SEC-05) |
| Offline | I | last verified index + cached package install with no network; index age shown |
| Update | I | never applied without a tap (R-SEC-06); capability delta shown; adding a capability needs re-approval, old version keeps running meanwhile (R-API-05) |
| Revocation | I | installed revoked version disabled on refresh, user notified, not uninstalled |
| Rotation | I | valid `rotation` record accepted; forged or missing -> hard fail |
| `sandbox.install` | I, A | steps run in order in a visible terminal tab; failing `verify` deletes the new version dir; `when` evaluated with `envDistro/Arch/Backend`; uninstall offered as best effort |
| Sideload | I | `.easyext.sig` verified when present; otherwise "unsigned, local", never updated by id match (R-SEC-14) |
| Open VSX | I | `.vsix` declarative subset installs, compatibility report, "partial" and "not signed by an easyIDE-registry publisher" labels; off unless `extensions.openVsx.enabled` |
| Zip safety | S | `../`, absolute, symlink, nested archive, case-duplicate entries rejected; `extensions.limits.*` enforced before unpack completes (R-SEC-12) |

## CLI

ECO-20..25. Golden-file tests per subcommand of [sdk-reference.md#cli](sdk-reference.md#cli):

- `init`: output tree per template (`theme`, `snippets`, `language-pack`, `lsp-pack`,
  `toolbar-command`, `wasm-rust`, `wasm-assemblyscript`); each passes `validate --strict`
  and `test` out of the box.
- `validate`: every invalid fixture yields its expected diagnostic and exit code 1; I/O
  failures exit 2; `--strict` turns warnings into failures; `--engine` changes the verdict
  for an `engines` mismatch; capability audit reports undeclared and unused.
- `package`: same input -> byte-identical zip and sha256 across two runs and two OSes.
- `keygen` / `sign` / `publish`: against a local bare git repo as the index; the produced
  entry verifies in the app's `RegistryClient` (shared fixture).
- `test`: replays `test/*.json` scenarios; WASM runs with real limits.
- `dev`: `--local` registers a folder; adb path covered by AT-M5-03 on device.
- `--json` output validated against a checked-in JSON Schema.

## Customization

CUS-01..18 (arch 5.5).

| Area | Layer | What is asserted |
|---|---|---|
| Settings layering | U | built-in < extension defaults < user < `[lang]` < environment < project; `[lang]` inside any layer; winning layer reported for the Settings row |
| Contributed settings | I | `configuration` properties render as native rows with type/enum/range; reset and "modified" dot work |
| Keybinding overrides | U | user and project `keybindings.json` beat contributions; `-command` removes; `when` honoured |
| UI overrides | I, A | `workbench.contributions.hidden` / `order` for buttons, menu items, views, stages, status items, key rows (R-UX-07) |
| Themes | I | `workbench.colorCustomizations`, `editor.tokenColorCustomizations`, `editor.semanticTokenColorCustomizations` including `[Theme]` blocks |
| Server overrides | I | `lsp.servers.<extId>/<id>` overrides fields and `enabled:false`; a server defined only in settings starts; project-file servers prompt once (R-SEC-13) |
| Enablement | I | per extension globally, per environment, per project; `extensionEnabled:<id>` context follows |
| Profiles | I | switching profile swaps settings layer, enabled extensions, keybindings, key rows |
| Safe mode | A | from Settings, launcher shortcut, and automatically after 2 activation crashes (R-UX-08) |
| Export / import | I | round trip restores settings, keybindings, snippets, profiles, extension list; no secrets in the bundle |

## Security tests

Fixtures under `services/shared/extension-schema/fixtures/security/` (Apache-2.0 so the
CLI can use them):

- Tampering and signature cases - see [Registry and install](#registry-and-install).
- Capability mismatches and denial - [Extension runtime](#extension-runtime), [WASM host](#wasm-host).
- Secret leakage: spawn a server and a `sandboxExec` action, dump `env`; assert no
  `EASYIDE_GIT_TOKEN` and no Android host variables; `secrets.get` of another extension's
  or git names -> `E_NOT_FOUND`; Extension Log and LSP trace redact secrets (R-SEC-03, R-SEC-17).
- Injection: filenames with `$(touch /tmp/x)`, backticks, quotes, newlines passed through
  `${file}` into `runInTerminal` and `install[].run` (R-SEC-15).
- Copy lint: string resources and docs grepped for isolation claims (R-SEC-01).

These tests prove the **checks** work. They cannot prove that code running in the sandbox
is contained - it is not ([0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md)).

## On-device performance

A new `:benchmark` module on the reference device. Thresholds live in one
`perf-gates.json` keyed by metric (mirroring arch.md sec 2), and tests read settings
defaults from the schema rather than inlining them (R-ENG-07).

| Scenario | Metric | Gate |
|---|---|---|
| Cold start, 0 vs 20 declarative extensions | startup delta | < +50 ms (R-PERF-09) |
| Manifest index load, 20 extensions | trace section | < 20 ms |
| Type 200 chars into a 400 KB `.py`, LSP off vs on | frame time p95 | no regression beyond noise band (R-PERF-08) |
| Completion response -> popup frame | trace | < 150 ms p95 |
| LSP response handling on main | trace | < 4 ms p95 (R-PERF-06) |
| `didChange` flush, 400 KB | trace | < 2 ms (R-PERF-07) |
| Open `.py`, warm / cold environment | first diagnostic rendered | < 3 s / < 10 s |
| Budget breach (fake heavy server) | breach -> kill | < 2 s (R-PERF-01) |
| `onTrimMemory(RUNNING_LOW)` via `am send-trim-memory` | eviction starts | kill order followed (R-PERF-05) |
| Install from offline cache | tap -> contributions live | < 10 s excl. `sandbox.install` |

Gates compare against a stored baseline per device model; the noise band is measured over
10 runs and stored with the baseline.

### Manual device matrix

| Device class | Why |
|---|---|
| 8 GB tablet (reference, Xiaomi Pad 6) | budgets, success metrics |
| 4-6 GB tablet | lower budget scaling, eviction pressure |
| Aggressive OEM killer (HyperOS, One UI) | `SandboxForegroundService` survival with servers running |
| Rooted device | chroot backend path (0002) |
| Foldable | `devicePosture`, `windowSizeClass` when-clauses |
| Hardware keyboard + mouse | hover, Ctrl+click, keybindings |
| Stylus | `inputMode == stylus`, long-press vs hover |

Per-release checklist: offline install of the Python pack; first-diagnostics times;
completion by touch and keyboard; rename preview; `kill -9` the server from the terminal
-> backoff; open 4 projects -> LRU eviction shown in status bar; safe mode from launcher;
theme install + colour customization; contributed key row reordered; uninstall with
sandbox undo offered. Results go in the release PR with device and build.

## Fixtures

| Set | Location | Contents |
|---|---|---|
| Manifests | `services/shared/extension-schema/fixtures/manifests/` | every sdk-reference example; one invalid variant per schema rule |
| Packages | `.../fixtures/packages/{valid,invalid,security}/` | theme, snippets, language pack, lsp pack, toolbar command, wasm; malicious zips |
| Keys | `.../fixtures/keys/` | `TEST-ONLY-*` root and publisher ed25519 keys; the app's real pinned root differs, so they can never verify against the real registry |
| Index | `.../fixtures/index/` | signed index, publishers, revocations: fresh, stale, revoked, rotated |
| VS Code corpus | `.../fixtures/vscode/` | MIT/Apache themes, grammars, snippets, language configs with licenses kept (R-API-10) |
| LSP traces | `:lsp/src/test/resources/traces/` | per server, with version and capture date |
| WASM guests | `tools/easyide-ext/fixtures/wasm/` | sources + checked-in `.wasm` |
| Projects | `.../fixtures/projects/` | small Python, TS, Go, C, Rust, Markdown projects |
| When contexts | `.../fixtures/contexts/*.json` | context snapshots for `easyide-ext test` and unit tests |

No real credentials in any fixture; keys are regenerated by a script; binary fixtures carry
a README naming the source that produced them.

## CI gates

| Stage | Trigger | Runs | Blocks merge |
|---|---|---|---|
| lint | every PR | ktlint/Detekt (`MagicNumber`, `GlobalCoroutineUsage` in SDK modules), Android Lint `HardcodedText`, 600-line + ASCII check on touched files, module-boundary check, isolation-claim grep | yes |
| jvm-test | every PR | U, I, S (JVM), C, W (from M7), schema conformance | yes |
| assemble | every PR | existing `:app:assembleCanary` | yes |
| device-nightly | nightly on `main` | A + P on the reference device | no; a red nightly blocks the next release |
| release | tag | all green + manual matrix signed off in the release PR | yes |
| registry | PR to index repo | R-REG-02 checks | yes (index repo) |

Until a device runner exists, `device-nightly` does not exist; release PRs state "A/P
layers not run - no device in CI" (R-DOC-08).

## Milestone acceptance

Ids `AT-Mn-nn`, mapped to arch.md sec 12 exit criteria. A milestone closes only when
every AT is green or explicitly waived with a reason in tracker.md.

### M0

- AT-M0-01 (A): underline, gutter icon and caret popup render in `EditorPane` from a test provider.
- AT-M0-02 (U): a command bound only in keymap JSON fires from a hardware chord and from the key row built from the same table.
- AT-M0-03 (U, I): a project `.easyide/settings.json` value wins; the Settings row shows "project".

### M1

- AT-M1-01 (U): bracket, auto-close, comment, indent/onEnter and folding correct for Python, TS, Go, C, Rust, Markdown fixtures.
- AT-M1-02 (A): snippet tab stops and choices navigable by touch and keyboard.
- AT-M1-03 (U): word-based completion merges buffer + open tabs and honours its setting.
- AT-M1-04 (U): TextMate output unchanged on fixture files (regression guard).

### M2

- AT-M2-01 (I): every fake-server scenario in [LSP client](#lsp-client) passes.
- AT-M2-02 (A, P): reference device: first diagnostics < 3 s warm / < 10 s cold; completion < 150 ms p95.
- AT-M2-03 (A): diagnostics, completion, hover, definition, references, rename, code actions, formatting, symbols on the Python fixture.
- AT-M2-04 (A): `kill -9` -> BACKOFF -> RUNNING; repeated crashes -> FAILED with restart and stderr tail.
- AT-M2-05 (A, P): over-budget fake server evicted < 2 s; terminal and buffers untouched.
- AT-M2-06 (S): server env has no git token or host variables.
- AT-M2-07 (P): no typing-latency regression with LSP on.

### M3

- AT-M3-01 (I, A): Python moved out of app code into `easyide.python-*.easyext` passes AT-M2-03 unchanged.
- AT-M3-02 (I): folder install -> disable -> update -> rollback -> uninstall with per-step fault injection.
- AT-M3-03 (S): prompt shows install commands verbatim and non-containment copy; undeclared capability refused at load.
- AT-M3-04 (U): every action type runs against the fake host.
- AT-M3-05 (I): `sandbox.install` streams to a terminal tab; failing `verify` removes the new version.

### M4

- AT-M4-01 (A, M): TS/JS, Go, C/C++, Bash, YAML, JSON/HTML/CSS, Markdown smoke lists pass; Rust opt-in shows its memory warning.
- AT-M4-02 (A): inlay hints, semantic tokens over TextMate, code lens render.
- AT-M4-03 (I): every 5.5 knob settable from UI and JSON, round-tripping.
- AT-M4-04 (I): a settings-only server starts and serves diagnostics.
- AT-M4-05 (A): hide/reorder a button, key row, status item and stage.
- AT-M4-06 (A): automatic safe mode after 2 activation crashes.
- AT-M4-07 (I): export -> wipe -> import restores everything except secrets.

### M5

- AT-M5-01 (C): every `init` template passes `validate --strict` and `test`.
- AT-M5-02 (C): `validate` catches each seeded error in the invalid fixtures.
- AT-M5-03 (A): `dev --watch --device` reloads a theme edit on device in one save cycle; `--local` works in an environment.
- AT-M5-04 (M): three new authors each ship a theme and a snippet pack in < 15 min using [author-guide.md](author-guide.md) tutorials 1 and 2.
- AT-M5-05 (C): `package` is byte-identical across runs.

### M6

- AT-M6-01 (A, P): signed install from offline cache < 10 s.
- AT-M6-02 (S): every tampering fixture hard-fails with its specific reason.
- AT-M6-03 (I): revoked installed version disabled and notified, not uninstalled.
- AT-M6-04 (I): capability-adding update needs re-approval; no update without a tap.
- AT-M6-05 (I): rotation with valid record accepted; without, hard fail.
- AT-M6-06 (I): Open VSX subset install with compatibility report and labels.
- AT-M6-07 (C, I): `publish` to a local bare index repo yields an entry the client verifies.

### M7

- AT-M7-01 (W): conformance suite passes on JVM.
- AT-M7-02 (A): same suite on ART; throughput recorded in ADR 0014.
- AT-M7-03 (A): author-guide tutorial 4 package (Rust word count) runs on device.
- AT-M7-04 (P): a spinning guest causes no main-thread jank.

### M8

- AT-M8-00: a written gap analysis names requested extensions that L1 + L2 cannot serve.
  Without it M8 does not start and has no further tests.
- If started: AT-M8-01 (P) host memory measured against `lsp.globalMemoryBudgetMb`
  headroom; AT-M8-02 (S) host off by default and its prompt states it is not contained.

## Deviations

- `FakeLspServer`, the `:benchmark` module and `perf-gates.json` are proposed here; arch.md
  sec 6.2 does not name them.
- Budget key names follow sdk-reference.md (`lsp.globalMemoryBudgetMb`, `lsp.maxServers`,
  `lsp.restart.*`); arch.md now matches ([rules.md](rules.md#deviations)).
