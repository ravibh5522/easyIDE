# Extension SDK - Rules

Enforceable rules for everyone who builds the SDK, the language core, or an extension.
Status: PROPOSED (2026-09-23). Design: [arch.md](arch.md). Contract: [sdk-reference.md](sdk-reference.md).

**MUST** = a PR or package that breaks it is rejected. **SHOULD** = break it only with a
written reason in the PR / README. Each rule has an id (cite it in reviews: "violates
R-SEC-04"), a one-line rationale and how it is checked:

- **lint** - automated static check (Gradle/Detekt/ktlint task, a `tools/` script, or
  `easyide-ext validate`).
- **test** - an automated test that fails when the rule breaks (see [test-plan.md](test-plan.md)).
- **review** - checked by a human against the PR checklist at the end of this file.

Rules added here that have no automated check yet are marked **(check TBD)**; building the
check is tracked in [tracker.md](tracker.md), not assumed.

## R-ENG - Engineering

| Id | Rule | Rationale | Check |
|---|---|---|---|
| R-ENG-01 | MUST: `:lsp` has no Android or Compose dependency; it depends only on Kotlin stdlib, coroutines and the app's existing JSON library. | Keeps the whole client JVM-testable headless (ADR [0017](../decision/0017-lsp-client-hand-rolled-server-lifecycle.md)). | lint: Gradle dependency check on `:lsp` (check TBD) |
| R-ENG-02 | MUST: Chicory is referenced only from `:ext-wasm`. No other module imports `com.dylibso.chicory.*`. | Runtime is swappable per [0014](../decision/0014-wasm-logic-layer-chicory.md). | lint: import grep in CI (check TBD) |
| R-ENG-03 | MUST: `:extensions` does not depend on app UI packages (`dev.easyide.app.ui.*`); UI observes it through flows / interfaces. | Loader and registry stay JVM-testable; UI can change without touching the runtime. | lint: Gradle module graph |
| R-ENG-04 | MUST: Apache-2.0 SDK paths (`services/shared/extension-schema/`, `tools/easyide-ext/`, guest bindings, templates, first-party packs) never import app (PolyForm NC) code. | Otherwise the SDK is not usable on its own license ([0015](../decision/0015-extension-sdk-licensing-apache.md)). | lint: path-based import check (check TBD); review |
| R-ENG-05 | MUST: one JSON Schema for the manifest, in `services/shared/extension-schema/`; app loader and CLI both load it. No second hand-written validator. | Prevents app/CLI drift (arch.md sec 13). | test: schema-conformance suite runs against both |
| R-ENG-06 | MUST: no file over 600 lines. Split by responsibility (e.g. `LspSession` vs `DocumentSync`), never by golfing. | Repo rule (`.claude/CLAUDE.md`). | lint: line-count script in CI (check TBD) |
| R-ENG-07 | MUST: every numeric threshold (timeouts, budgets, debounce, limits, backoff, crash counts) is a named settings key from [sdk-reference.md#settings-keys](sdk-reference.md#settings-keys) or an entry in the one `MemoryPolicy` table. No inline literal at the call site. | Repo "no hardcoding" rule; users can tune on their device. | review; lint: Detekt `MagicNumber` scoped to `:lsp`, `:extensions`, `:ext-wasm` |
| R-ENG-08 | MUST: a new threshold gets, in the same PR, a settings key in sdk-reference.md (name, type, default, scope) or, if no layer may relax it, a named policy constant listed under sdk-reference "Fixed limits". | The reference is the contract. | review |
| R-ENG-09 | MUST: try/catch only at real boundaries: file I/O, zip reading, sandbox process spawn and pipes, network (index/package fetch), JSON parse of untrusted input (manifests, LSP messages, WASM messages), the WebView/Kotlin bridge, Chicory traps. Pure logic (when-clause eval, settings resolution, path mapping, diffing) does not catch. | Repo rule; defensive catches hide bugs. | review |
| R-ENG-10 | MUST: invalid states are unrepresentable where cheap: sealed types for server state (arch.md sec 7.6), actions and WASM messages; no nullable-field "maybe" objects. | Data first; state machines are testable exhaustively. | review; test: exhaustive state-transition tests |
| R-ENG-11 | MUST: no blocking I/O or JSON parse of LSP/WASM/registry payloads on the main thread. Reader/writer loops on `Dispatchers.IO`; parse and map off main; only the final state apply on main. | arch.md sec 10 main-thread budget (< 4 ms per LSP response). | test: StrictMode in instrumented tests; macrobenchmark |
| R-ENG-12 | MUST: each WASM instance runs on its own single worker thread; host functions that touch UI hop to main explicitly. Guest code never runs on main. | One instance, one thread (sdk-reference WASM lifecycle). | test: thread assertion in `WasmHost` tests |
| R-ENG-13 | MUST: coroutine scopes are owned: session scope per `LspSession`, per-extension scope in `ActivationManager`; cancellation of the owner cancels every child request. No `GlobalScope`. | Leaked requests keep servers busy after close. | lint: Detekt `GlobalCoroutineUsage`; review |
| R-ENG-14 | MUST: server processes are spawned only through `ServerProcessFactory` (in `:sandbox-runtime`, beside `ShellRunner`), which builds a clean environment plus declared `env`. | Single place that guarantees no host env / secret leak. | test: env-content test; lint: no other `ProcessBuilder` in `:lsp`/`:extensions` (check TBD) |
| R-ENG-15 | MUST: contributions register into the Pillar 4 command registry / keymap and Pillar 5 settings schema ([ux-overhaul/arch.md](../ux-overhaul/arch.md)). No parallel command, keybinding or settings registry. | One source of truth per concern. | review |
| R-ENG-16 | MUST: install writes go to a temp dir and become live by atomic rename / symlink flip; a failed step leaves the previous `current` untouched. | Crash-safe install and one-tap rollback (arch.md sec 6.3). | test: fault injection at each install step |
| R-ENG-17 | SHOULD: an abstraction is extracted at its third real use, not its first (e.g. a generic "provider" layer only once three LSP features share it). | Repo "reusable, not speculative" rule. | review |
| R-ENG-18 | MUST: before adopting any new dependency (JSON Schema validator, ed25519 lib, Chicory upgrade), verify license and maintenance from the primary source and record it in the relevant ADR or arch.md. | Theia license was once assumed wrong ([0001](../decision/0001-ide-foundation-theia.md)). | review |

## R-API - Compatibility and versioning

| Id | Rule | Rationale | Check |
|---|---|---|---|
| R-API-01 | MUST: the app exposes one API version (semver). Schema, action vocabulary, contribution points, when-clause keys and WASM host functions all version with it. | One number authors can target (arch.md sec 11). | review |
| R-API-02 | MUST: additions (new optional field, action type, host function, context key, contribution point) bump minor; removals or meaning changes bump major. | Semver. | review; test: API snapshot diff (check TBD) |
| R-API-03 | MUST: from API `1.0.0`, a deprecated key, action or host function keeps working for 2 minor releases, warns in the Extension Log and in `easyide-ext validate`, and is removed only in the next major. Before `1.0.0` (API v0) minors may break, and the release notes say so. | Tablet update lag; authors need a window. | test: deprecation fixtures in the CLI suite |
| R-API-04 | MUST: install and load refuse a package whose `engines.easyide` range does not include the app API version, with a message naming both. | No half-working extensions. | test |
| R-API-05 | MUST: a new capability id is a minor API change and is **never granted by default** to installed extensions; an update that adds a capability requires a fresh approval. | Capabilities are never silently granted. | test: update-with-new-capability flow |
| R-API-06 | MUST: widening what an existing capability allows (e.g. new host functions gated by `fs.project(read)`) is listed in release notes and shown in the capability prompt text; if it grants materially more, it is a new capability instead. | Approval must mean what the user read. | review |
| R-API-07 | MUST: manifest schema changes land in `services/shared/extension-schema/` with a version bump, updated sdk-reference.md, and a fixture package exercising the change in the same PR. | Schema, docs and tests move together. | review; test |
| R-API-08 | MUST: unknown manifest keys and unknown `contributes` points are warnings, never load errors. | Forward compatibility with newer packages and `.vsix` files. | test |
| R-API-09 | MUST: WASM ABI changes that remove or change a host function signature require `easyide.wasm.abi` 2; the host keeps ABI 1 for the deprecation window. | sdk-reference WASM versioning. | test: ABI 1 conformance suite stays green |
| R-API-10 | MUST: VS Code-compatible points marked **same** in sdk-reference accept VS Code files unmodified. A deviation changes the Compat column to **subset** and lists the ignored fields. | Authors rely on the column. | test: VS Code corpus fixtures (themes, grammars, snippets) |
| R-API-11 | SHOULD: a new action type is justified in the PR by at least two concrete extensions that need it and cannot use WASM. | The vocabulary is permanent API ([0013](../decision/0013-extension-sdk-shape-manifest-easyext.md)). | review |

## R-SEC - Security

| Id | Rule | Rationale | Check |
|---|---|---|---|
| R-SEC-01 | MUST: no code comment, UI string or doc describes proot or chroot+BusyBox as isolating extensions, servers or projects. Use "not contained", "runs with your permissions in this environment". | Neither isolates ([0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md)). | lint: word-list grep over `docs/` and `res/values*/strings.xml` for "isolat", "sandboxed", "secure" near proot/extension (check TBD); review |
| R-SEC-02 | MUST: WASM capability enforcement is described as "enforced by easyIDE", never as audited, sandboxed or secure. | It is our code over Chicory's interpreter ([0014](../decision/0014-wasm-logic-layer-chicory.md)). | review |
| R-SEC-03 | MUST: the git token (`GitCredentials`, [0012](../decision/0012-git-token-in-process-env-not-credential-socket.md)), Claude Code API key and any credential-vault entry are unreachable from every layer: not a capability, not a host function, not a variable, not in any server or action environment. | Narrow authority for the most valuable secret. | test: env of spawned servers/actions contains no `EASYIDE_GIT_TOKEN`; `secrets.get` cannot name them |
| R-SEC-04 | MUST: `${env:NAME}` resolves from the environment's configured shell env only, never the Android process env. | Host env leakage. | test |
| R-SEC-05 | MUST: registry installs hard-fail on any of: index/revocations signature invalid, entry signature invalid, publisher key not active or revoked, TOFU pin mismatch without a valid rotation record, size or sha256 mismatch. No "install anyway". | [0016](../decision/0016-extension-registry-static-index-ed25519.md). | test: one tampered fixture per failure |
| R-SEC-06 | MUST: no automatic install or update. `extensions.autoCheckUpdates` only notifies; applying needs a tap after the version, changelog and capability delta are shown. | Supply-chain defence (arch.md goal G6). | test; review |
| R-SEC-07 | MUST: the capability prompt lists install commands verbatim, server commands, action types and network hosts, and for L1/L3 says in plain words that sandbox commands are not contained. Approval is recorded with the exact capability set. | Informed consent. | test: prompt snapshot; review of copy |
| R-SEC-08 | MUST: the loader rejects a manifest whose actions or WASM imports need a capability it did not declare (load-time error, never a runtime surprise). | Consistency; disclosure matches behaviour. | test; lint: `easyide-ext validate` capability audit |
| R-SEC-09 | MUST: every WASM `host_call` is checked against declared **and** approved capabilities before dispatch; denial returns `E_CAPABILITY` and is logged. | The only enforced layer. | test: one deny test per gated function group |
| R-SEC-10 | MUST: WASM guests get no WASI or other imports than `easyide.host_call`; a module importing anything else is refused at load. | No ambient authority. | test |
| R-SEC-11 | MUST: `openUrl` and `net.fetch` accept https only; `openUrl` always shows a confirm sheet with the full URL; `net.fetch` hosts must match a declared `network(...)` host. | Phishing and exfiltration. | test |
| R-SEC-12 | MUST: package entries with `..`, absolute paths, symlinks, nested archives, or case-insensitive duplicates are rejected; limits `extensions.limits.*` enforced before unpack completes. | Zip-slip and zip bombs. | test: malicious zip fixtures |
| R-SEC-13 | MUST: `lsp.servers` entries coming from a **project** `.easyide/settings.json` are not started without a once-per-project prompt showing the command. | Cloning a repo must not run its commands (arch.md sec 14 q2, proposed answer). | test |
| R-SEC-14 | MUST: sideloaded unsigned packages are labelled "unsigned, local" everywhere they appear and are never auto-updated or updated from the registry by id match. | Local installs are the user's choice, not ours. | test |
| R-SEC-15 | MUST: substituted values in shell strings (`runInTerminal.command`, `install[].run`) are shell-quoted; argv actions are never joined into a shell string. | Injection via filenames. | test: filename with `$(...)`, quotes, spaces |
| R-SEC-16 | MUST: no Microsoft Marketplace URL, API or `.vsix` from it, ever. | Terms of Use ([0009](../decision/0009-extension-platform-tiers.md)). | review |
| R-SEC-17 | MUST: logs (Extension Log, LSP trace) never contain secrets: `secrets.get` values and `showInputBox{password:true}` results are redacted. | Logs are exported in bug reports. | test |
| R-SEC-18 | MUST: security-relevant changes (capabilities, verification, secrets, process spawning) get the threat model ([threat-model.md](threat-model.md)) updated in the same PR. | Keeps the model honest. | review |

## R-PERF - Performance and memory

Numbers are starting values from arch.md sec 10 and sdk-reference defaults; the setting
key is authoritative, the number here is not.

| Id | Rule | Rationale | Check |
|---|---|---|---|
| R-PERF-01 | MUST: the sum of running servers' sampled RSS stays within `lsp.globalMemoryBudgetMb`; a breach triggers eviction per the `MemoryPolicy` kill order within 2 s. | Goal G5. | test: fake-server RSS injection; device macrobenchmark |
| R-PERF-02 | MUST: a server over its own `memoryBudgetMb` for 2 consecutive samples is restarted once, then stopped with a notice. | arch.md sec 7.7. | test |
| R-PERF-03 | MUST: the kill order never touches terminal sessions or editor buffers. | Losing a buffer is worse than losing completion. | test |
| R-PERF-04 | MUST: at most `lsp.maxServers` servers run; beyond that the LRU one is shut down. | Bounded concurrency. | test |
| R-PERF-05 | MUST: `onTrimMemory` at RUNNING_LOW or worse triggers the same kill order. | Android LMK. | instrumented test |
| R-PERF-06 | MUST: main-thread time per LSP response < 4 ms (p95) on the reference device. | Keystroke latency. | macrobenchmark gate |
| R-PERF-07 | MUST: `didChange` flush < 2 ms for a 400 KB document (prefix/suffix diff, no edit history). | Typing cost. | JVM microbenchmark + macrobenchmark |
| R-PERF-08 | MUST: keystroke-to-glyph latency with LSP active shows no regression vs LSP off. | Success metric. | macrobenchmark gate |
| R-PERF-09 | MUST: startup reads a cached parsed manifest index, not zips; < 20 ms for 20 extensions; app cold start < +50 ms with 20 declarative extensions. | Success metrics. | macrobenchmark gate |
| R-PERF-10 | MUST: WASM calls are bounded by `extensions.wasm.fuelPerCall`, `callTimeoutMs`, `maxMemoryMb`, `maxHostCallsPerCall`, `maxMessageKb`; exceeding any traps and discards the instance. | Guest cannot freeze UI or eat memory. | test: one per limit |
| R-PERF-11 | MUST: documents over `LspPolicy.MAX_FULL_SYNC_BYTES` are not synced to Full-sync servers, and the UI says features are off for that file. | Bounded memory and pipe traffic. | test |
| R-PERF-12 | SHOULD: packs whose server's desk-estimated RSS exceeds `lsp.defaultMemoryBudgetMb` (rust-analyzer, kotlin-lsp) ship opt-in with a warning shown before install. | Honest expectations. | review |
| R-PERF-13 | MUST: performance claims in docs or release notes cite a measurement on a named device; unmeasured numbers are labelled "target" or "estimate". | "Should be fast" is not data. | review |

## R-EXT - Extension author rules (published packages)

| Id | Rule | Rationale | Check |
|---|---|---|---|
| R-EXT-01 | MUST: `publisher` and `name` match `^[a-z0-9][a-z0-9-]{0,62}$`; id = `publisher.name`, unique case-insensitively in the registry. | Stable ids in paths, pins and settings. | lint: validate; registry CI |
| R-EXT-02 | MUST: a registry publisher id is owned by exactly one key set in `publishers/<publisher>.json`; `easyide` and names implying first-party (`easyide-*`, `official`) are reserved. | Impersonation. | registry CI; review |
| R-EXT-03 | MUST: `version` is SemVer 2.0 without build metadata and strictly greater than every published version of that id. | Updates and rollback ordering. | registry CI |
| R-EXT-04 | MUST: registry packages include `README.md`, `LICENSE[.md]` and an SPDX `license` field matching it. Bundled third-party files (grammars, themes) keep their original license and attribution in the package. | License hygiene; many grammars are MIT/Apache with attribution requirements. | lint: validate `--strict`; review |
| R-EXT-05 | MUST NOT: bundle files whose license forbids redistribution, or anything derived from Microsoft-only extensions (Pylance, cpptools, C#). | Legal. | review |
| R-EXT-06 | MUST: declare every capability used, and no capability unused (validate warns on unused; registry rejects unused). | Least privilege; honest prompts. | lint: validate capability audit |
| R-EXT-07 | MUST: `easyide.sandbox.install` steps are idempotent, show a human `title`, pin versions where the package manager allows it, and `verify` exits non-zero on failure. Declared `network(...)` hosts cover every host the steps contact. | Installs are re-run and rolled back. | review; `easyide-ext test` scenario |
| R-EXT-08 | MUST NOT: install steps modify files outside the environment's package-manager locations and `/opt/easyide/extensions/<id>` (no editing the user's dotfiles, projects or git config). | Least surprise; uninstall must be possible. | review |
| R-EXT-09 | MUST NOT: attempt to read git credentials, other extensions' storage or secrets, or `/proc/*/environ` of other processes; MUST NOT download and execute code outside declared install steps. | Would be malicious even where not prevented. | review; revocation if found |
| R-EXT-10 | MUST: binary downloads in install steps are sha256-pinned in the step (as the marksman recipe is). | Supply chain. | review |
| R-EXT-11 | MUST: icons: package `icon.png` 128x128 or 256x256 square PNG; command icons are easyIDE icon tokens or monochrome SVGs in the package (theme-tinted). No raster command icons, no hardcoded colours in SVG fills. | Consistent, theme-aware UI. | lint: validate |
| R-EXT-12 | MUST: user-facing strings (titles, descriptions, messages) are `%key%`-localisable; English defaults in `l10n/package.nls.json` for registry packages. | i18n. | lint: validate `--strict` (warning otherwise) |
| R-EXT-13 | SHOULD: language packs set `memoryBudgetMb` per server from a measurement and state the device in the README. | Budgets drive eviction. | review |
| R-EXT-14 | SHOULD: keybindings avoid overriding built-in bindings without `when` scoping; conflicts are listed by validate. | Users expect built-ins to keep working. | lint: validate conflict report |
| R-EXT-15 | MUST: WASM modules are <= `extensions.wasm.maxModuleMb`, export `ext_abi_version` returning the declared `abi`, and request `memoryMb` <= `extensions.wasm.maxMemoryMb`. | Load rules. | lint: validate; `easyide-ext test` |

## R-REG - Registry and publishing

| Id | Rule | Rationale | Check |
|---|---|---|---|
| R-REG-01 | MUST: publishing is a PR to the index repo produced by `easyide-ext publish`; entries are never hand-edited. | Canonical JSON and signatures must match. | registry CI: re-canonicalise and verify |
| R-REG-02 | MUST: index CI on every PR: schema-validate entry, verify publisher signature, download `url`, check size + sha256, run `easyide-ext validate --strict` on the package, reject capability additions not called out in the PR description. | Automated first line of review. | registry CI (check TBD until M6) |
| R-REG-03 | MUST: a human maintainer reviews every entry that declares `sandbox.install`, `sandbox.exec`, `lsp.spawn` or `network(...)`, reading install steps verbatim. | These run uncontained code. | review |
| R-REG-04 | MUST: the registry root key is held offline; signing the index happens on a maintainer machine, not in CI. Custody is recorded before M6 (arch.md sec 14 q4). | Root key compromise = all clients. | review |
| R-REG-05 | MUST: `url` is https and immutable (release asset or `packages/`); a changed byte at the same URL is a new version, never a re-upload. | sha256 pinning. | registry CI |
| R-REG-06 | MUST: revocation (key or version range) lands within 24 h of a confirmed malicious report and carries a `reason`; revoked entries stay in history. | Survivable compromise. | review; incident runbook (check TBD) |
| R-REG-07 | MUST: key rotation uses a `rotation` record signed by the old key; lost keys cannot rotate and require revocation plus a new publisher approval. | TOFU pins. | registry CI |
| R-REG-08 | MUST: Open VSX entries are shown as "not signed by an easyIDE-registry publisher", install only the declarative subset, and display the compatibility report. Off unless `extensions.openVsx.enabled`. | Honest labelling. | test |

## R-DOC - Documentation

| Id | Rule | Rationale | Check |
|---|---|---|---|
| R-DOC-01 | MUST: every feature has an id in [features.md](features.md) (NS/LSP/EXT/ECO/CUS/PLT-nn), an owning LLD section under `lld/`, and at least one test referenced from [test-plan.md](test-plan.md). PRs cite the feature id. | Traceability. | review; lint: id cross-reference script (check TBD) |
| R-DOC-02 | MUST: [tracker.md](tracker.md) status is updated in the PR that lands the work. | Repo rule; stale trackers mislead. | review |
| R-DOC-03 | MUST: every non-trivial change appends an entry to this week's `docs/chainlog/YYYY-Www.md`. | Repo rule. | review |
| R-DOC-04 | MUST: hard-to-reverse choices get an ADR in `docs/decision/` (0013-0017 cover the SDK so far) before or immediately after landing, linked from arch.md. | Repo rule. | review |
| R-DOC-05 | MUST: a contract change (manifest field, action, host function, capability, settings key, CLI flag) updates sdk-reference.md in the same PR. | sdk-reference.md is the contract. | review |
| R-DOC-06 | MUST: docs files stay under 600 lines, ASCII only. | Repo rule. | lint: line-count + non-ASCII check (check TBD) |
| R-DOC-07 | MUST: every licence or maintenance claim about a dependency or language server carries a "verified <date> from <source>" note. | [0001](../decision/0001-ide-foundation-theia.md) lesson. | review |
| R-DOC-08 | MUST: anything not verified on a device says so ("unverified on device", "no emulator available"). | Repo "verify before claiming done". | review |
| R-DOC-09 | SHOULD: the [author-guide.md](author-guide.md) tutorials are re-run end to end before each API minor release. | Tutorials rot silently. | manual release checklist |

## R-UX - User interface

| Id | Rule | Rationale | Check |
|---|---|---|---|
| R-UX-01 | MUST: touch targets for contributed buttons, key-row keys, gutter icons (lightbulb, fold, diagnostics) and popup items are >= the design-system minimum touch target token (Material 3: 48dp); visual glyphs may be smaller inside the target. | Tablet-first, finger input. | test: Compose `assertTouchHeightIsAtLeast` in UI tests; review |
| R-UX-02 | MUST: every app-side user-facing string is a string resource in `res/values/strings.xml` (and translations); none inline in Kotlin. Extension strings come from the manifest/`l10n`. | Repo no-hardcoding rule; i18n. | lint: Android Lint `HardcodedText` / `SetTextI18n`; review |
| R-UX-03 | MUST: colours come from theme tokens (`MaterialTheme.colorScheme.*`, `EditorColors`, `SyntaxColors`); diagnostic severities, inlay hints, lightbulb and semantic token colours get named tokens. Extension themes map onto tokens, never raw colours in component code. | [design-system/arch.md](../design-system/arch.md). | review; lint: raw-color grep in `ui/` (check TBD) |
| R-UX-04 | MUST: keybindings for new built-in commands go into the single keybinding table (Pillar 4), which also feeds the key row. | [ui-shell/arch.md](../ui-shell/arch.md) keybinding-table rule. | review |
| R-UX-05 | MUST: every LSP feature has a touch path (long-press menu, key-row button or toolbar) as well as a keyboard path; hover works by long-press. | No feature is keyboard-only on a tablet. | review; manual device matrix |
| R-UX-06 | MUST: LSP timeouts and cancellations are silent; server failures surface as a status-bar state with restart and log actions, never a modal dialog. | Servers must not interrupt typing. | test |
| R-UX-07 | MUST: every contribution is hideable and reorderable (`workbench.contributions.*`), and the Settings row shows which layer a value comes from. | Goal G4; arch.md risk "layering confuses users". | test |
| R-UX-08 | MUST: safe mode is reachable from Settings, the launcher shortcut, and automatically after 2 consecutive activation crashes. | Recovery from a bad extension. | test: fault injection |
| R-UX-09 | MUST: UI copy never promises Pylance-equivalent Python or "VS Code compatible" without the category table. | Honest claims (arch.md sec 8-9). | review |

## PR review checklist

Paste into the PR description; tick or write "n/a - <reason>".

```
Scope
[ ] Feature id(s) from features.md cited; LLD section updated if design changed (R-DOC-01)
[ ] tracker.md status updated; chainlog entry added (R-DOC-02, R-DOC-03)
[ ] ADR written/linked if hard to reverse (R-DOC-04)

Engineering
[ ] No file > 600 lines (R-ENG-06)
[ ] No new inline thresholds; new keys added to sdk-reference Settings keys (R-ENG-07/08)
[ ] try/catch only at real boundaries (R-ENG-09)
[ ] Module boundaries respected: :lsp pure JVM, Chicory only in :ext-wasm,
    Apache paths import no app code (R-ENG-01..04)
[ ] Nothing heavy on main; coroutine scopes owned (R-ENG-11..13)
[ ] New dependency: license + maintenance verified from primary source and recorded (R-ENG-18)

Contract
[ ] Contract changes reflected in sdk-reference.md and the shared schema (R-DOC-05, R-API-07)
[ ] Semver impact stated (minor/major); deprecations follow the window (R-API-02/03)
[ ] No capability silently granted or widened (R-API-05/06)

Security
[ ] No isolation claims for proot/chroot; WASM called "enforced by easyIDE" (R-SEC-01/02)
[ ] Git token / API keys unreachable; spawned env is clean (R-SEC-03/04)
[ ] Verification failures are hard; no auto-install/update (R-SEC-05/06)
[ ] Shell quoting for substituted values (R-SEC-15); logs redact secrets (R-SEC-17)
[ ] threat-model.md updated if trust boundaries changed (R-SEC-18)

Performance
[ ] Memory policy / budgets untouched, or macrobenchmark results attached (R-PERF-*)
[ ] Performance numbers cite device + measurement (R-PERF-13)

UX
[ ] Strings in resources; colours from tokens; touch targets >= token minimum (R-UX-01..03)
[ ] Touch path exists for every new feature (R-UX-05)

Verification
[ ] Tests added and run; commands pasted. Anything unverifiable on device stated
    explicitly (R-DOC-08)
```

Extension-author checklist (for registry PRs): R-EXT-01..15 plus `easyide-ext validate
--strict` and `easyide-ext test` output pasted into the PR.

## Deviations

- Resolved (2026-09-24): arch.md now uses the sdk-reference LSP and WASM keys and defaults;
  `lsp.maxSyncBytes`, `memorySampleSec`, `logBufferLines` are `LspPolicy` constants listed in
  sdk-reference "Fixed limits" (R-ENG-08 now allows that for limits no layer may relax).
