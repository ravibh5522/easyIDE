# VS Code extension compatibility - test and compatibility program

How compatibility with the Open VSX corpus is measured, reproduced and gated in CI, and the
scripted scenarios that stand behind the number. Binding: [D7](#2-the-score)/ADR
[0031](../decision/0031-vendor-vscode-extension-host.md) (route, device gates G1-G7).
Reads: [README.md](README.md), [corpus.md](corpus.md), [design.md](design.md),
[optimisation.md](optimisation.md), [gap-analysis.md](gap-analysis.md),
[registry-install.md](registry-install.md), [security-licensing.md](security-licensing.md),
[data/corpus.json](data/corpus.json), [data/corpus-usage.json](data/corpus-usage.json),
[tools/vsx-audit/spike/README.md](../../tools/vsx-audit/spike/README.md). Layer/gate table
style follows [docs/extension-sdk/test-plan.md](../extension-sdk/test-plan.md); read that
doc for the app's non-VSX test layers (U/I/W/C/A/P/S/M), reused here rather than duplicated.
Status: PROPOSED (2026-09-25). Audit-phase document describing work to be built: no harness
code has been merged (`tools/vsx-audit/spike/` is a throwaway proof-of-route, not a harness).

## 1 Goals

1. Turn the D7 formula into something anyone can run and get the same number from: a pinned
   corpus snapshot, a pinned VS Code tag, a deterministic runner, one results JSON schema.
2. Catch a compatibility regression before it reaches a user, at the layer that is cheapest
   to run it at: unit-level protocol shapes on every PR, the full corpus nightly, real
   hardware weekly.
3. Give every "must-work" extension (the 22 in `corpus.json meta.mustWorkList`) a named,
   assertable scenario, not just a pass/fail score, so a regression names the extension and
   the gate, not just a percentage.
4. Keep score computation auditable: every excluded extension is listed with its reason
   (D7), every gate result traces to a log or a golden file.
5. Reuse, don't duplicate: layers (d)-(g) sit on top of the `:extensions`/`:lsp`/`:ext-wasm`
   unit/integration layers in [test-plan.md](../extension-sdk/test-plan.md) (U/I/W) and feed
   the same A/P device gates that plan defines, rather than inventing a second device story.

## 2 The score

### 2.1 Formula (D7, verbatim terms)

```
S = sum_e w_e * (g1_e + g2_e + g3_e + g4_e) / 4   /   sum_e w_e        over e in the scored set
w_e = downloadCount(e) at the corpus snapshot (corpus.json[e].downloadCount)
g1_e, g2_e, g3_e, g4_e in {0, 1}   (gate result for extension e)
```

Reported as two numbers, both required in every results file:

- **S_in** - the target metric, computed over `E_in` (in-scope set, 2.3). Target `S_in >= 0.99`.
- **S_all** - computed over the full corpus `C` (all 150 top-by-downloads code extensions
  plus the 22 must-work ids, minus dedupe; `corpus.json meta.counts.extensions - declarative`
  = 156 - 13 = 143 code-kind entries at the 2026-09-25 snapshot), for visibility only - not
  gated, since it is depressed by extensions D7 defines out of scope by design (OOS tracks,
  platform-unavailable).

There is no partial credit inside a gate: g1..g4 are each exactly 0 or 1, per D7 ("Four
gates per extension... each 0/1"). An extension that cannot be evaluated at all (crashes the
runner, times out past 3x budget, corpus fetch fails) scores 0 on every gate for that run and
is flagged `runError` in the results row, distinct from a gate that ran and failed.

### 2.2 Gates g1-g4 (D7's four per-extension gates; not to be confused with ADR 0031's
device go/no-go gates G1-G7, which gate the *route*, not per-extension score)

**g1 - activation.** Pass iff all of:
- `activate()` (or, for an extension with no top-level `activate`, module load) resolves
  within timeout `T` = 5 s cold / 1.5 s warm-compile-cache (matches ADR 0031 G1/G2, doubled
  for scoring headroom since G1/G2 are route go/no-go, not per-extension budgets).
- No unhandled promise rejection or thrown error is observed on the extension host's global
  handlers (`VSCODE_HANDLES_UNCAUGHT_ERRORS` path) attributable to this extension between
  activation start and `T + 2s` settle window.
- The runtime trace (3.1e) for the activation window contains zero calls to a `MainThread*`
  method the adapter has marked `Stub-reject` (design.md's method table) - i.e. activation
  itself must not depend on an out-of-scope call. (A later call to a stub method during g4's
  scenario does not fail g1; it can fail g3/g4.)
Checked by: harness layer (a) for x86 pre-check, corpus runner (d) for the scored run.
Evidence unit: `calls-<id>.json` (renderer.ts's existing `finish()` summary) plus the
extension-host stderr/console capture.

**g2 - contributions.** Pass iff every contribution point the extension declares in
`package.json contributes` that is in-scope (not on an OOS track, 2.4) is both *accepted*
(no schema-validation rejection logged by the adapter) and *rendered/registered* on the
Kotlin side, checked per contribution kind:

| Contribution kind | How checked |
|---|---|
| `commands` | each command id appears in Kotlin's command registry after activation (adapter-Kotlin trace: a `commands.register` UI-protocol call per id) |
| `menus` (all locations) | each menu item resolves through the shared when-clause evaluator without a parse error; item appears in the target menu's golden (layer f) for at least one `when` truthy case |
| `configuration` | schema loads into the settings model (test-plan.md's "Shared schema" row); default value round-trips |
| `views` / `viewsContainers` | tree/webview-view registers a Kotlin `ViewProvider` binding; golden (f) renders it at least once |
| `keybindings` | chord parses and does not collide silently (test-plan.md Keymap row) |
| `languages` / `grammars` | language id registers; TextMate grammar loads (L0 layer) |
| `debuggers`, `notebooks`, `chatParticipants`, `languageModelTools` | **out of scope by D8** - excluded from g2's denominator for this extension (2.4), scored only in the OOS track table |
| `taskDefinitions` | task type registers; a scripted `tasks.fetchTasks()` call returns it |
| `colors`, `icons`, `iconThemes`, `productIconThemes` | asset loads without a 404/parse error; one golden screenshot uses it |

An extension with contribution points entirely inside one OOS track (e.g. only
`chatParticipants`) is not scored on g2 at all for that reason - see 2.3 exclusion (a).

**g3 - API calls implemented.** Pass iff both:
- *Static*: every `vscode` API member the corpus scan (`data/corpus-usage.json api.*`, from
  `tools/vsx-audit/lib/corpus-js.mjs`) recorded this extension calling maps, in
  `gap-analysis.md`'s capability table (ids per `SURFACES.md`'s canonical list), to a
  capability with gap class **Have** - or the call site is inside a function-body branch
  gated by a feature-detection pattern the static scanner already discounts (documented in
  gap-analysis.md's scan notes), or the symbol belongs to an OOS track excluded for this
  extension per 2.4.
- *Runtime*: the API call tracer (3.1e) for the g4 scenario run shows **zero** calls that
  reach a method the adapter maps to `Stub-reject` (typed "not supported on easyIDE" error).
  A call to a documented no-op stub does not fail g3 (it is in-scope but intentionally inert,
  e.g. a telemetry sink); a call that reaches a `Stub-reject` path does, because it means the
  extension depends on something declared unsupported.
Static and runtime are both required because static scanning over-approximates (dead code,
polyfills, version-gated branches) and runtime under-approximates (the scenario may not
exercise every code path); g3 needs the static list to contain no **Missing** entry *and* the
runtime trace to contain no rejected call.

**g4 - scripted scenario.** Pass iff the extension's scenario script (section 4) completes
with every one of its listed assertions true, on the pinned corpus snapshot version, within
`3x` the scenario's stated timeout. A scenario with a documented "stops at X" expected
boundary (e.g. GitHub PR's auth stop, 4) passes g4 by reaching exactly that boundary with no
error beyond it - reaching further or crashing before it both fail g4.

### 2.3 In-scope set E_in

`E_in = C - (a) - (b)`, both listed by name with reason, never silently dropped (D7):

- **(a) OOS-purpose exclusion**: extension whose *entire* declared purpose is one OOS track
  (D8) - e.g. a debug-adapter-only extension, a notebook-renderer-only extension, a
  chat-participant-only extension. Source list: `corpus.md`'s per-extension track
  classification (built from `contributes` + `activationEvents` + the corpus-usage.json
  `api.*`/`contributes.*` extension lists). An extension with a *mix* of in-scope and OOS
  contributions (e.g. GitLens: SCM + a debug-adapter side feature) stays in `E_in`; only its
  OOS-track g2/g3 items are excluded from its own gate denominator and rolled into that
  track's separate score (2.3.1).
- **(b) platform-unavailable exclusion**: `corpus.json[e].targetsAvailable` has neither
  `linux-arm64` nor a universal/no-target build (`noGlibcArm64` true, no fallback), so the
  extension cannot run on the device. Source: `corpus.json`'s
  `targetChosen`/`targetReason`/`noGlibcArm64`, cross-checked in `corpus.md`.

Both lists are regenerated by the corpus runner (3.1d `--list-excluded`) from `corpus.json`
and `corpus.md`'s manual track calls, and published alongside every score run
(`results/score-<date>.json excluded`).

#### 2.3.1 Per-track scores for OOS tracks

Each OOS track in D8 gets its own weighted score over the extensions that touch it (which
can include `E_in` members for their OOS-labelled contributions, and `E_in`-excluded (a)
members in full):

```
S_track = sum_{e touches track} w_e * (fraction of that extension's track-labelled g2/g3 items satisfied) / sum_{e touches track} w_e
```

`S_track` is informational, tracked per WP-OOS-* roadmap item (D8), never rolled into
`S_in`/`S_all`, and reported with its own weight share so a reader can see how much of the
corpus a track affects (e.g. "OOS-DAP touches 9.1% of weighted downloads via debug-adapter
contributions in otherwise in-scope extensions").

### 2.4 Exclusion lists (per-extension, inside E_in)

For an in-scope extension, its g2 and g3 denominators exclude, item by item:

- Contribution points / API symbols whose declaring feature is entirely one OOS track (D8),
  matched via `SURFACES.md`'s "Out-of-scope tracks" ids (`debug.dap`, `notebook.api`,
  `chat.lm`, `proposed.api`, `ms-services`) and gap-analysis.md's mapping from `vscode`
  symbol to capability id.
- Proposed APIs not on the D8 allow-list (`extensionEnabledApiProposals` intersection).
- MS-service-dependent features named in D8 (Settings Sync, MS auth, Live Share, Remote-*,
  Pylance-specific protocol extensions) - counted toward `OOS-MS` instead.

Every exclusion is logged per extension in the results row (`results/score-<date>.json
extensions[].excludedItems`), never just dropped silently.

### 2.5 Reproducibility

- **Pinned corpus snapshot**: `docs/vsx-compat/data/corpus.json` (`meta.snapshotAt`,
  `meta.scanVersion`) is the scored snapshot; a score run records
  `corpusSnapshotAt`/`corpusScanVersion` in its results file and refuses to run against a
  `corpus.json` whose `scanVersion` differs unless `--allow-snapshot-drift` is passed
  (used only for the monthly refresh dry-run, 5).
- **Pinned VS Code tag**: the same stable tag the adapter and bundled ext host are built from
  (ADR 0031's "always rebuilt from the same tag as the host"); recorded as `vscodeTag` in
  results. The spike's current pin is `0b16cb97` (notes, pre-tag; production pin moves to a
  numbered stable tag per design.md before M1 exit).
- **Deterministic runner**: no network calls during scoring beyond the one-time corpus
  download-and-cache step (3.1d); extension activation order fixed (alphabetical by id);
  wall-clock and RSS numbers are recorded but never affect gate pass/fail (only g1's timeout
  does, and that timeout is generous - see 2.2); PRNG-free scenario scripts (4).
- **Results JSON schema** (`results/score-<date>.json`):

```jsonc
{
  "schemaVersion": 1,
  "runAt": "2026-10-01T00:00:00Z",
  "corpusSnapshotAt": "2026-09-25T14:49:49.567Z",
  "corpusScanVersion": "4913c6e67664",
  "vscodeTag": "1.104.0",
  "runner": { "host": "x86-ci | device:<model>", "nodeVersion": "24.x", "commit": "<sha>" },
  "S_in": 0.9912, "S_all": 0.8460,
  "weightIn": 1041312902, "weightAll": 1091356814,
  "excluded": {
    "oosPurpose": [{ "id": "ms-vscode.js-debug", "weight": 12345678, "reason": "debug adapter only, OOS-DAP" }],
    "platformUnavailable": [{ "id": "vadimcn.vscode-lldb", "weight": 2345678, "reason": "no linux-arm64/universal build" }]
  },
  "tracks": { "OOS-DAP": { "S_track": 0.41, "weightShare": 0.091 }, "...": {} },
  "extensions": [
    { "id": "eamodio.gitlens", "weight": 55000000, "g1": 1, "g2": 1, "g3": 1, "g4": 1,
      "excludedItems": [{ "kind": "api", "symbol": "debug.registerDebugAdapterDescriptorFactory", "track": "OOS-DAP" }],
      "runError": null, "evidence": { "calls": "calls-gitlens.json", "scenario": "scenario-gitlens.json", "goldens": ["gitlens_blame.png"] } }
  ]
}
```

  `results/score-<date>.json` is generated by the corpus runner; `compatibility.md` is
  generated *from* it (never hand-edited) as a human-readable table: one row per extension,
  weight, g1-g4, S contribution, generated by `tools/vsx-audit/gen-compat-table.mjs` (to be
  written, WP-TEST-6).

### 2.6 Worked example (3 extensions)

Illustrative arithmetic only (not the real corpus), showing D7's math and the exclusion
bookkeeping:

| Extension | weight w_e | g1 | g2 | g3 | g4 | (g1+g2+g3+g4)/4 | w_e * avg |
|---|---:|---:|---:|---:|---:|---:|---:|
| `esbenp.prettier-vscode` | 40,000,000 | 1 | 1 | 1 | 1 | 1.00 | 40,000,000 |
| `dbaeumer.vscode-eslint` | 30,000,000 | 1 | 1 | 1 | 0 (quick-fix codeAction assertion fails) | 0.75 | 22,500,000 |
| `GitHub.vscode-pull-request-github` | 10,000,000 | 1 | 1 | 0 (one non-auth API call maps to Missing) | 1 (scenario passes to its defined auth-stop boundary) | 0.75 | 7,500,000 |

`sum(w_e) = 80,000,000`; `sum(w_e*avg) = 70,000,000`; `S_in (this slice) = 70,000,000 /
80,000,000 = 0.875` (87.5%). A fourth extension, `vadimcn.vscode-lldb` (weight 2,345,678),
is excluded under 2.3(b) (no linux-arm64 build) and does not appear in this slice's
denominator at all, only in `results[].excluded.platformUnavailable`.

## 3 Harness layers

Layer letters match section 4's "which gates it covers" column and the CI matrix (5).

### (a) Headless "fake main" harness (Node)

Grows `tools/vsx-audit/spike/renderer.ts` (currently 181 lines, a one-shot spike script)
into `tools/vsx-audit/harness/fake-main.mjs`, a reusable library kept import-compatible with
the spike's proven pieces (VS Code's own `PersistentProtocol`/`RPCProtocol`, the
`MainContext` actor-per-shape `Proxy`, the `calls[]` recorder and `calls-<ext>.json` summary
format - all kept as-is per the spike README). New surface added on top:

- **Response scripting**: `answers` (spike line 34) becomes a per-scenario script file
  (`scenarios/<ext>.answers.mjs`) instead of one hardcoded object, so g4 scenarios can supply
  extension-specific UI answers (which quick-pick item to choose, webview message replies).
- **Scripted user input**: a `driveScenario(steps)` entry point that, after activation
  resolves, plays a list of `{ proxy, method, args }` calls against `ExtHost*` proxies (the
  spike already does one such call, `cmds.$executeContributedCommand`, spike line 147) in
  order, recording results and timings.
- **Trace export**: `calls[]` gains a `direction` field (`main->ext` / `ext->main`) and is
  exported both as the existing JSON and as the tracer format layer (e) consumes.
- Kept from the spike unmodified: pipe-based IPC handshake, `IExtensionHostInitData` shape,
  RSS probe child-process protocol (`rss-probe.mjs`).
Runs on: x86 CI (every PR, subset) and nightly (full corpus, still x86 - this layer never
touches Android). Gate coverage: g1, contributes toward g2/g3 evidence, g4 for extensions
whose scenario needs no Kotlin-side rendering to assert (e.g. Prettier's format-on-save can
be asserted from the fake main side by diffing the `$applyEdit` payload).

### (b) Adapter contract tests (adapter <-> fake Kotlin, UI protocol)

The main-thread adapter (design.md) is not itself Kotlin, but speaks the adapter-to-Kotlin
UI protocol (JSON-RPC/Content-Length, re-scoped from `docs/extension-host/arch.md` per ADR
0031) as a *client*. `tools/vsx-audit/harness/fake-kotlin.mjs` implements that protocol's
*server* side in Node: for every UI-protocol method the adapter can send, asserts the request
shape against the protocol's JSON Schema (design.md publishes one) and returns a scripted
response. This is a contract test, not a Kotlin test: it proves the adapter emits exactly the
messages design.md promises, independent of whether Kotlin's real implementation exists yet.
Runs on: x86 CI, every PR. Gate coverage: g2 (contribution acceptance messages),
part of g3 (adapter maps API calls to the right UI-protocol call, not literally to Kotlin).

### (c) Kotlin `:exthost` unit tests with a fake adapter

Once a Kotlin `:exthost` module exists (design.md's module boundary), its JVM unit tests
(JUnit 5, same tooling as test-plan.md's U layer) run against a `FakeAdapter` fixture - the
mirror of test-plan.md's `FakeLspServer` pattern - that sends canned UI-protocol messages and
asserts Kotlin's registries, view providers and command dispatch update correctly, without a
real Node process. Runs on: JVM, every PR (test-plan.md's U/I gate). Gate coverage: g2
(Kotlin-side "rendered/registered" half of each contribution-kind check in 2.2).

### (d) Corpus runner

`tools/vsx-audit/harness/run-corpus.mjs` - CLI shape:

```
run-corpus --snapshot data/corpus.json --cache /root/.cache/easyide-corpus \
           --set in-scope|all|<id,id,...> --gates g1,g2,g3,g4 \
           --scenario-dir tools/vsx-audit/scenarios --out results/score-<date>.json \
           [--x86 | --device <adb-serial>] [--parallel N] [--list-excluded]
```

Steps per extension: (1) download the pinned `.vsix` from `files.download` (or read from
`$CACHE`, D4-style layout, if already present and sha256-verified per `files.sha256`), (2)
unpack, (3) install into a scratch profile, (4) spawn harness (a) [x86] or drive the on-device
smoke path (3.1g) [`--device`], (5) run g1-g4 per 2.2, (6) write the extension's row plus
raw evidence files (`calls-*.json`, scenario trace, goldens) under
`results/<date>/<id>/`. Runtime budget: x86 full corpus (143 code extensions) budgeted at
<= 45 min wall time at `--parallel 8` (each extension's g1+g4 budget is <= 20 s per 2.2/4,
plus install/download amortized by the shared cache); device full corpus (weekly, 5) budgeted
at <= 4 h given no parallelism (one physical Xiaomi Pad 6) and per-extension device
provisioning (Node/toolchain install, 4's preconditions).

### (e) API call tracer

- **Runtime**: the adapter's main-side proxy (the `makeActor`-equivalent inside the real
  adapter, not the spike's Node stand-in, once it exists - until then, harness (a)'s own
  `calls[]` recorder stands in) wraps every `MainThread*Shape` method to log `{ actor,
  method, argShape }` (argument *shapes*, e.g. `{type, keys}`, not full payloads, to keep
  traces small and avoid retaining secrets) on every call, tagged in-scope/Stub-reject/no-op
  per design.md's method table. Consumed by g1 and g3's runtime half.
- **Static**: the existing corpus scan (`tools/vsx-audit/lib/corpus-js.mjs`, producing
  `data/corpus-usage.json api.*`) - reused unmodified as g3's static half. No new static
  scanner is built; this program only adds the gap-analysis.md join from scanned symbol to
  capability status.

### (f) Golden UI tests (Roborazzi)

Repo already uses Roborazzi 1.75.0 + Robolectric (`services/mobile/app/build.gradle.kts`,
`gradle/libs.versions.toml`), with existing goldens under
`app/src/test/java/.../ui/screens/golden/` and `app/src/testDebug/java/.../ui/kit/gallery/`
(`:recordRoborazziDebug` / `:verifyRoborazziDebug`, run inside `:app:testDebugUnitTest
-Proborazzi.test.verify=true` per that file's comment). This program adds one golden class
per extension-driven UI surface, fed by a fixture extension (the spike's `hello/` pattern,
extended) rather than a real corpus download, so these stay JVM-only (test-plan.md's
`Layer C`-adjacent "runs on JVM every PR" tier):

| Surface | Golden test class (new) | Fixture drives |
|---|---|---|
| Tree view | `ExtTreeViewGoldenTest` | a `TreeDataProvider` fixture with 3 levels, icons, `resourceUri` |
| Status bar | `ExtStatusBarGoldenTest` | left/right alignment, priority order, command-bound click target |
| Quick pick | `ExtQuickPickGoldenTest` | multi-select, `matchOnDescription`, busy state |
| Notifications | `ExtNotificationGoldenTest` | info/warn/error, action buttons, modal vs toast |
| Decorations | `ExtDecorationGoldenTest` | underline, background range, inline text, gutter icon, block, caret popup (mirrors test-plan.md's Platform row) |
| Code lens | `ExtCodeLensGoldenTest` | lens above a symbol, click dispatches command |
| Webview host frame | `ExtWebviewFrameGoldenTest` | chrome only (title, close, retainContextWhenHidden badge) - webview *content* is out of this layer, covered by device layer (g) since it needs a real WebView |
| SCM | `ExtScmGoldenTest` | source-control input box, resource group tree, inline actions |
| Menus | `ExtMenuGoldenTest` | one golden per menu location touched by a must-work extension (4), `when`-filtered |

Naming: `<surface>_<state>.png` under each test's golden dir, matching the existing
`KitGalleryDensityGoldenTest`-style naming. Runs on: JVM, every PR (subset: surfaces touched
by the PR's diff) and nightly (full set).

### (g) On-device smoke tests via adb

Script outline (`tools/vsx-audit/device/smoke.sh`, new): (1) `adb install` the current debug
build; (2) push a Node 24 linux-arm64 tarball into the guest cache and run the same
provisioning path ADR 0032/D2 describes (first-enable trigger, simulated by directly invoking
the provisioning command); (3) for N extensions (the must-work 22, weekly; a rotating subset
of 5, nightly-on-device if that tier is enabled): copy the cached `.vsix` (from the same
`$CACHE` the corpus runner uses, pushed once via `adb push`) into the guest install path
(D4 layout), enable it, run the extension's scenario script (4) by driving the app through
**`tap.py`** (below) plus `adb shell input` for text entry and `adb logcat`/`adb shell cat
/proc/<pid>/status` for assertions and RSS; (4) collect RSS samples (test-plan.md's
`MemoryPolicy` sampler, read from device) and activation/scenario timings into
`results/device-<date>/<id>.json`; (5) uninstall/reset between extensions to avoid
state leakage; (6) `adb pull` the results and screenshots.

`tap.py`: the existing on-device tap/screenshot helper used to drive the app during manual
and scripted device sessions, invoked over `adb` (`adb shell input tap x y`,
`adb exec-out screencap`, wrapped with element-lookup helpers). It lives at the owner's
workstation path `/home/ravi/Desktop/tab-code-wt/tap.py` and **is not in this repository** -
this audit could not read or verify its contents (UNVERIFIED, 7). Proposal: commit a copy to
`tools/vsx-audit/device/tap.py`, licensed like the rest of `tools/vsx-audit`, so `smoke.sh`
and the scenario scripts (4) have a checked-in dependency instead of a workstation path;
tracked as WP-TEST-9 (6). Runs on: device, weekly (must-work 22) - see CI gates (5).

### (h) Performance checks

Reuses test-plan.md's P layer (Jetpack Macrobenchmark + `/proc` sampler) rather than building
a parallel perf harness: extension-driven cases (activation latency, RSS with N extensions
enabled, typing latency with a language-server-backed extension active) are added as
Macrobenchmark scenarios parameterized by extension id, sourced from the scenario scripts
(4). Budgets and the RSS kill-order policy are [optimisation.md](optimisation.md)'s
(WP-PERF-*); this program only supplies extension fixtures and wires their pass/fail into
the g4/RSS assertions those scenarios already make. ADR 0031's G3/G4 (idle/loaded host RSS)
are the route-level version of this same measurement, run once at go/no-go, not per PR.

## 4 Scenario scripts

One per must-work extension (`corpus.json meta.mustWorkList`, 22 ids). Each: preconditions
(guest toolchain needed), steps, assertions, gates covered. Script files live at
`tools/vsx-audit/scenarios/<id>.mjs` (harness (a)/(d) consume them) with a matching
`tools/vsx-audit/scenarios/<id>.device.md` when the on-device layer (g) needs manual/`tap.py`
steps beyond what the headless harness can script.

**GitLens** (`eamodio.gitlens`) - Preconditions: guest repo with >= 5 commits, `git` on
`PATH`. Steps: open a tracked file; wait for blame decorations; open Commit Graph webview;
click a commit to open its details view. Assertions: inline blame decoration text matches
`git blame` for that line; graph webview loads (no CSP violation in its console); details
view shows the same SHA. Gates: g1, g2 (`views`, `webview`, decorations), g3 (`git.api` +
`scm.api` calls all Have), g4.

**Claude Code** (`Anthropic.claude-code`) - Preconditions: `ANTHROPIC_API_KEY` (or the app's
proxy) reachable from the guest per COMMON.md's network allow-list note (this extension's own
network needs are extension-specific, not part of this repo's proxy list - flagged
UNVERIFIED if it cannot reach its backend in the sandbox). Steps: open the panel webview; run
`claude-code.newSession` from the command palette; type into the panel's terminal-integrated
input. Assertions: webview loads and shows a ready state; command executes without a
`Stub-reject`; a terminal is created and receives input (terminal.api). Gates: g1, g2
(webview view + command), g3, g4.

**Prettier** (`esbenp.prettier-vscode`) - Preconditions: none (bundles its own Prettier).
Steps: open a badly-formatted `.ts` fixture; trigger format-on-save (`editor.formatOnSave`
true); save. Assertions: saved content byte-equals the Prettier-formatted fixture; no
`workspace.applyEdit` rejected. Gates: g1, g2 (`formatting` contribution... actually
formatters register via API not `contributes`; check activation event `onLanguage:typescript`
fires), g3 (`lang.formatting`), g4.

**ESLint** (`dbaeumer.vscode-eslint`) - Preconditions: guest has `node_modules/eslint`
installed in the fixture project with a `.eslintrc`. Steps: open a file with one lint
violation; wait for diagnostic; invoke the quick fix. Assertions: one diagnostic appears at
the right range/message; quick fix's `WorkspaceEdit` removes the violation and the diagnostic
clears. Gates: g1, g2 (`commands`, diagnostics), g3 (`lang.code-action`, diagnostics), g4.

**YAML** (`redhat.vscode-yaml`) - Preconditions: fixture `.yaml` + a `$schema` comment
pointing at a bundled local JSON Schema (avoid a live network fetch). Steps: open the file
with one schema violation. Assertions: diagnostic at the violating key; hover shows the
schema's description text. Gates: g1, g2, g3 (`lang.json-validation`, `lang.hover`), g4.

**Python + Pyrefly + Ruff** (`ms-python.python`, `meta.pyrefly`, `charliermarsh.ruff`) -
Preconditions: guest Python 3.11 on `PATH`, a discoverable venv. Steps: open a `.py` fixture
with one type error, one lint violation and a missing-import completion opportunity; trigger
completion; invoke Ruff's quick fix. Graded per-extension though the fixture is shared.
Assertions: Pyrefly diagnostic for the type error; Ruff diagnostic with a working fix;
completion list contains the expected import. Gates: g1, g2, g3 (`lang.completion`,
diagnostics, `lang.code-action`), g4.

**Volar + Tailwind** (`Vue.volar`, `bradlc.vscode-tailwindcss`) - Preconditions: fixture
`.vue` SFC project with `tailwind.config.js`. Steps: open a `.vue` file; place cursor inside a
`class="..."` attribute; trigger completion. Assertions: Volar's completion list includes
component prop names from a sibling `.vue` import; Tailwind's completion list includes a
known utility class with its color swatch. Gates: g1, g2, g3 (`lang.completion`), g4.

**clangd** (`llvm-vs-code-extensions.vscode-clangd`) - Preconditions: `clangd` binary for
arm64 present in the guest (UNVERIFIED whether the guest toolchain provisions one today -
flag and track as a dependency of this scenario, not assumed available). Steps: open a `.cpp`
fixture with one undeclared-symbol error; place cursor on a known function call; trigger go
to definition. Assertions: diagnostic present; go-to-definition navigates to the correct
file:line. Gates: g1, g2, g3 (`lang.definition`, diagnostics), g4 (marked blocked/skip in
results if the `clangd` binary precondition is unmet, not silently passed).

**rust-analyzer** (`rust-lang.rust-analyzer`) - Preconditions: guest Rust toolchain
(`cargo`/`rustc`) or rust-analyzer's own standalone binary fetch path completes once,
offline-cached thereafter. Steps: open a `.rs` fixture function; assert inlay hints for an
inferred type appear; click the "Run" code-lens above a `#[test]` function. Assertions:
inlay-hint text matches the inferred type; run lens triggers a `tasks`/terminal execution.
Gates: g1, g2 (`lang.inlay-hint`, code lens), g3, g4.

**Go** (`golang.Go`) - Preconditions: guest Go toolchain; `gopls` not yet installed (this
scenario specifically exercises the install-prompt path). Steps: open a `.go` fixture;
observe the "Install gopls?" prompt; accept; wait for `gopls` to install and start; observe a
diagnostic. Assertions: prompt appears via `MainThreadMessageService`; `gopls` process starts
under the guest's process model (D3); diagnostic appears post-install. Gates: g1, g2, g3, g4.

**Java** (`redhat.java`) - Preconditions: guest JDK and a Maven/Gradle fixture project. Steps:
open the project root; wait for project import to finish; open a file with one compile error.
Assertions: import completes (status bar idle, no error notification); diagnostic appears.
Gates: g1, g2, g3, g4 - timeout override: 120 s (Java's import is long-running), set per-script.

**GitHub PR** (`GitHub.vscode-pull-request-github`) - Preconditions: no stored GitHub auth in
the guest (clean profile, per COMMON.md's "no git token" env). Steps: open the extension's
view; trigger sign-in. **Expected/defined stop**: g4 passes when the scenario reaches the VS
Code `authentication.getSession` call and the adapter's auth-provider UI surface is invoked
(a Kotlin sign-in sheet stub, per design.md) - it does **not** complete a real OAuth round
trip (no live GitHub account is provisioned). Assertions: `authentication.getSession` call
observed; UI-protocol auth-request message sent; no crash. Gates: g1, g2, g4 (defined-stop
pass); g3 is evaluated only up to the auth boundary, relying on the static half only.

**Docker** (`ms-azuretools.vscode-docker`) - Preconditions: a `Dockerfile`/compose fixture in
the workspace; a live daemon is not assumed (tree can render from file scanning alone).
Steps: open the workspace; open the Docker explorer view. Assertions: tree renders the
fixture's services; no crash if the daemon is absent. Gates: g1, g2 (tree view), g4.

**Ruby LSP** (`Shopify.ruby-lsp`) - Preconditions: guest Ruby + `ruby-lsp` gem installed.
Steps: open a `.rb` fixture with one syntax issue; wait for diagnostic. Assertions:
diagnostic appears; language server process is supervised under D3's process model. Gates:
g1, g2, g3, g4.

**Dart** (`Dart-Code.dart-code`) - Preconditions: guest Dart SDK on `PATH`. Steps: open a
`.dart` fixture; wait for the analysis server to start; trigger completion. Assertions:
completion list is non-empty and includes an expected SDK symbol. Gates: g1, g2, g3, g4.

**Material Icon Theme** (`PKief.material-icon-theme`) - Preconditions: none. Steps: enable
the icon theme; open the file tree with a mixed fixture (folders, `.ts`, `.json`, `.png`, a
dotfile). Assertions: each icon matches the theme's mapping (golden, layer f,
`ExtTreeViewGoldenTest` variant). Gates: g1, g2 (`iconThemes`), g4 (visual golden match).

**Git Graph** (`mhutchie.git-graph`) - Preconditions: guest repo with branches and merges.
Steps: open the Git Graph webview. Assertions: webview renders the expected commit topology
(golden or a structural assertion on the webview's posted graph data, whichever the webview's
message protocol allows to assert without pixel-diffing webview content). Gates: g1, g2
(webview panel), g4.

**Error Lens** (`usernamehw.errorlens`) - Preconditions: a fixture with an existing ESLint (or
TS) diagnostic already producible (depends on ESLint's scenario fixture). Steps: open the
file with a diagnostic present. Assertions: an inline end-of-line decoration renders the
diagnostic message (golden, layer f, decorations surface). Gates: g1, g2 (decorations), g4.

**Code Spell Checker** (`streetsidesoftware.code-spell-checker`) - Preconditions: none.
Steps: open a fixture with one misspelled identifier in a comment. Assertions: a diagnostic
(or decoration, per the extension's configured mode) appears at the misspelled word; quick
fix offers a correction. Gates: g1, g2, g3 (diagnostics, code action), g4.

## 5 CI gates

| Tier | Trigger | Scope | Layers run |
|---|---|---|---|
| Every PR | push to a PR branch | x86 harness only; extensions touched by the diff's changed capability ids (`SURFACES.md`), else a fixed fast subset (the 8 spike extensions: GitLens, Claude Code, Go, ESLint, Prettier, EditorConfig, Code Runner, plus whichever must-work extension maps to the touched capability) | (a) fake-main, (b) adapter contract, (c) Kotlin unit, (e) static half of tracer, (f) goldens for touched surfaces only |
| Nightly | schedule, main branch | full corpus (`E_in`, all 143-ish in-scope extensions), x86 only | (a)-(f) in full, (d) corpus runner `--x86`, full g1-g3 + g4 for scenarios that need no device |
| Weekly, on device | schedule, main branch, Xiaomi Pad 6 (or a device farm once available - none exists today per test-plan.md's baseline) | the 22 must-work extensions | (g) on-device smoke, (d) corpus runner `--device`, (h) perf checks, full g1-g4 incl. device-only steps (GitLens webview, Claude Code terminal) |

**Regression rule**: a run fails CI if either holds, compared against the last accepted
`results/score-*.json` on the same branch lineage:
- `S_in` for the current run is more than **0.1 percentage points** below the baseline
  `S_in` (D7's target is `>= 99%`; the regression budget is separate from and tighter than
  the target itself, so small drift is caught long before the target line is threatened), or
- any must-work extension (4) that passed a gate in the baseline run now fails that gate
  (per-extension, per-gate regression, independent of the aggregate `S_in` delta - a single
  must-work extension losing g4 fails CI even if `S_in` barely moves, because weight alone
  can hide a single high-value regression against 142 unaffected extensions).

**Flake policy**: a gate result that differs between two immediate re-runs (same commit, same
snapshot) on the *nightly x86* tier is retried once automatically by the runner (`--retry 1`,
default); a result that still flips is recorded as `flaky: true` in that extension's row and
does **not** fail the regression rule on its own, but is not silently dropped either - it is
listed in a `results/score-<date>.json flaky[]` array and must be triaged before three
consecutive nightly runs flake the same gate (then it is treated as a real failure, tracked
against the owning WP-TEST item). The weekly on-device tier does not auto-retry (device time
is expensive); a device flake is triaged manually before the next weekly run.

**Corpus refresh cadence**: monthly snapshot PR - re-run the corpus fetch
(`tools/vsx-audit/corpus-fetch.mjs`) and scan (`corpus-manifest.mjs`/`corpus-js.mjs`/
`corpus-native.mjs`), diff the new `corpus.json`/`corpus-usage.json` against the pinned ones,
and open a PR containing only the data files plus the regenerated `results/score-<date>.json`
+ `compatibility.md`. The PR's description must call out: extensions newly entering/leaving
`E_in`, any must-work extension whose `targetsAvailable` changed, and the `S_in` delta versus
the previous pin. The pinned snapshot only advances on merge of that PR - no score run in
between silently drifts (2.5).

## 6 Work packages

| ID | Scope | Deps | Acceptance | Effort | Risk |
|---|---|---|---|---|---|
| WP-TEST-1 | Grow `renderer.ts` into `harness/fake-main.mjs`: scriptable answers, `driveScenario`, trace export (3.1a) | tools/vsx-audit/spike/* | replays all 8 spike extensions' activation with identical `calls-*.json` output to the spike, plus one scripted scenario end to end | M | Med (VS Code internal APIs can shift between the spike's pin and the production pin) |
| WP-TEST-2 | `harness/fake-kotlin.mjs` adapter contract tests + UI-protocol JSON Schema (3.1b) | design.md's protocol table; WP-TEST-1 | every UI-protocol method the adapter can send has a schema and a passing contract test against >= 1 real message from a spike extension's trace | M | Med (depends on design.md landing first) |
| WP-TEST-3 | Kotlin `:exthost` `FakeAdapter` fixture + unit tests (3.1c) | Kotlin `:exthost` module exists (design.md) | mirrors test-plan.md's `FakeLspServer` pattern; covers g2's Kotlin-side half for commands/menus/views/config | L | Med |
| WP-TEST-4 | Corpus runner CLI + results JSON writer (3.1d, 2.5 schema) | WP-TEST-1; data/corpus.json | `run-corpus --set in-scope --x86` produces a schema-valid `results/score-<date>.json` for >= 20 extensions in one CI run | M | Low |
| WP-TEST-5 | Runtime API call tracer wired into the adapter's main-side proxy + gap-analysis.md join (3.1e) | design.md's method table; gap-analysis.md | every call in a g4 run's trace resolves to a capability status; zero unclassified calls | S | Low |
| WP-TEST-6 | `gen-compat-table.mjs`: results JSON -> `compatibility.md` generator (2.5) | WP-TEST-4 | regenerating from a fixed results file is byte-identical across two runs; table matches the worked example's arithmetic (2.6) on a 3-row fixture | S | Low |
| WP-TEST-7 | 9 golden test classes for the UI surfaces in 3.1f | Roborazzi setup (already present); a fixture extension per surface | goldens recorded and checked in; `:app:verifyRoborazziDebug` passes in CI on an unrelated PR (no false positives) | M | Low |
| WP-TEST-8 | 22 scenario scripts (section 4) as executable `.mjs` files consumed by harness (a)/(d) | WP-TEST-1, WP-TEST-4; per-extension toolchain fixtures (Python, Go, Rust, Java, etc. in the guest image) | each script runs standalone against its fixture and reports pass/fail per assertion, independent of the aggregate scorer | XL | High (toolchain provisioning per language is its own dependency chain, several outside this program's control) |
| WP-TEST-9 | `tools/vsx-audit/device/smoke.sh` + commit a reviewed copy of `tap.py` under `tools/vsx-audit/device/` (3.1g) | owner provides/approves the `tap.py` source (7); adb access to a device | smoke script installs the app, provisions Node, installs 3 extensions and runs their scenarios end to end on a physical device at least once, logged | M | Med (depends on external file the audit could not read) |
| WP-TEST-10 | Extension-driven Macrobenchmark scenarios (3.1h) wired to optimisation.md budgets | optimisation.md (WP-PERF-*); WP-TEST-8 | activation-latency and RSS-with-N-extensions benchmarks run nightly and fail against optimisation.md's stated budgets, not a number invented here | M | Med |
| WP-TEST-11 | CI wiring: PR/nightly/weekly tiers, regression rule, flake policy (section 5) | WP-TEST-4, WP-TEST-7 | a deliberately regressed fixture (one gate flipped to 0) fails the nightly job with the correct message (`S_in` delta or named extension+gate) | S | Low |
| WP-TEST-12 | Monthly corpus refresh automation: fetch + scan + diff + PR template (section 5) | `tools/vsx-audit/corpus-fetch.mjs` (exists), `lib/corpus-*.mjs` (exist) | a dry run against a second live snapshot produces a diff summary and a PR-ready description without manual editing | S | Low |

## 7 UNVERIFIED

- **`tap.py`'s actual contents and interface.** Referenced only by its workstation path
  `/home/ravi/Desktop/tab-code-wt/tap.py`; not present in this repository or in `$SP`. This
  document describes it from its established usage convention (tap/screenshot helper driven
  via `adb`) but could not read its source to confirm its CLI flags, element-lookup strategy,
  or licensing. Verify by: the owner sharing the file so WP-TEST-9 can commit a reviewed copy
  under `tools/vsx-audit/device/tap.py` with a proper header.
- **`clangd` arm64 binary availability in the guest image today.** The clangd scenario (4)
  assumes it can be provisioned but this audit did not verify a working install path in the
  current guest toolchain. Verify by: a manual `proot`/device session attempting `clangd
  --version` after whatever install step the guest's C/C++ story uses (owned outside this
  document - see gap-analysis.md's language-server provisioning notes).
- **Android device farm / CI device availability.** test-plan.md's baseline states "No
  Android emulator is available in the current development environment" and instrumented
  results are "not run" until one exists; this program's weekly-on-device tier (5) assumes a
  single physical Xiaomi Pad 6 reachable by CI, which was not confirmed as wired into any CI
  system during this audit. Verify by: checking the CI configuration (`.github/workflows/`)
  for a self-hosted or device-farm runner, or asking the owner.
- **Claude Code extension's network reachability from the guest sandbox** for its scenario
  (4). COMMON.md's allow-list covers audit-tooling hosts (open-vsx.org, github raw, etc.),
  not necessarily whatever backend `Anthropic.claude-code` talks to at runtime. Verify by:
  running its activation in the guest and checking for network-refused errors.
- **Production VS Code tag pin.** The spike is pinned to commit `0b16cb97` (pre-release,
  notes-only); the number that design.md/ADR 0031 will actually ship on a numbered stable tag
  was not finalized as of this snapshot (`2026-09-25`). Verify against design.md once merged.
