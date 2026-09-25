# VS Code extensions: backend gap analysis (part 2 of 2)

Continues [backend.md](backend.md) (legend, aliases, owners and the list of what the vendored host
gives for free are in its sec 0). This part: tasks, terminals, git, authentication, process spawning and
native code, network, l10n, telemetry/identity, extension-set management (kind, dependencies, updates,
enable/disable, safe mode, kill switch), the language-feature router (D10), then the summary table,
the WP-HOST-* and WP-API-* lists and the UNVERIFIED list.

## 12. Tasks

| Aspect | Content |
|---|---|
| VS Code | Host side `extHostTask.ts` (+ `api/node/extHostTask.ts`) registers providers and supported executions (`$registerSupportedExecutions`, `$registerTaskSystem`, seen in the spike hello run, route-spike sec 3); the main side (`MainThreadTaskShape`, `extHost.protocol.ts:2042`) owns `tasks.json`, runs Shell/Process executions in terminals, applies problem matchers (`contrib/tasks/common/problemMatcher.ts`, 1,834 lines, reachable from the host closure, route-spike sec 1), and runs `CustomExecution` through an extension `Pseudoterminal`. |
| easyIDE today | `.easyide/tasks.json` in the VS Code 2.0.0 shape, shell/process kinds (`APP/extensions/adapters/Tasks.kt:12-82`); "problem matchers are not applied yet" (`Tasks.kt:38`); `taskDefinitions`/`problemMatchers` parsed only (`SCH/manifest/ContributesDecoder.kt:228-246`); `problemPatterns` rejected by `SCHJ:48`. |
| Corpus | `tasks.executeTask` 40.7 w%, `ShellExecution` 21.2, `registerTaskProvider` 21.2, `ProcessExecution` 14.9, `taskExecutions` 14.8, `onDidEndTask` 14.6, `fetchTasks` 13.2, `CustomExecution` 12.0; implicit `onTaskType` 25.5. |
| Gap | Partial (runner Have; provider model, `.vscode/tasks.json`, matchers Missing-backend; task UI Missing-UI) |
| Under 2b | **Task system lives in the adapter (JS)**: it merges `.vscode/tasks.json` + `.easyide/tasks.json` + provider tasks (`$provideTasks`, raising `onTaskType:<type>` first), resolves variables, and starts executions as terminals through the terminal bridge (sec 13): Shell -> interactive shell with the command line, Process -> argv, Custom -> Pseudoterminal. Problem matching reuses VS Code's `problemMatcher.ts` from the same tag inside the adapter bundle (MIT, no port), fed by the terminal byte stream, output as a diagnostics collection per task (`ServerKey(env, project, "task:<label>")`). Kotlin: task picker/run/stop UI and terminal hosting only. Events `onDidStart/EndTask(Process)` are emitted by the adapter. |
| Effort / risk / WP | L / Med / **WP-API-7** |

## 13. Terminals

| Aspect | Content |
|---|---|
| VS Code | `MainThreadTerminalServiceShape` (`extHost.protocol.ts:674`): `$createTerminal(id, config)` (`:675`), `$registerProcessSupport` (`:680`), `$registerProfileProvider` (`:681`), `$setEnvironmentVariableCollection(extId, persistent, collection, descriptions)` (`:687`), `$sendProcessData` for extension terminals (`:698`); ext side `$startExtensionTerminal` (`:3170`) and shell-integration events `$shellIntegrationChange/$shellExecutionStart/End/Data` (`:3188-3191`). Shell integration = scripts injected into bash/zsh/fish/pwsh (`contrib/terminal/common/scripts/`; `vs/platform/terminal/node/terminalEnvironment.ts:53`) emitting OSC 633 (`vs/platform/terminal/common/xterm/shellIntegrationAddon.ts:48`). |
| easyIDE today | PTY through vendored Termux JNI (`services/mobile/terminal-emulator/src/main/java/com/termux/terminal/JNI.java:26-29`), command/interactive pty params (`SBX/shell/SandboxShell.kt:63-113`, `SBX/LinuxEnvironment.kt:246-256`), `TerminalPort` (`SCH/action/HostPort.kt:41-46`, `APP/extensions/host/AppHostPort.kt:76-83`); interactive shell is `/bin/sh` (`SandboxShell.kt:100-113,185`). Pseudoterminal, env collections, profiles, links, shell integration: Missing (research/ourcode-backend.md 2.4). |
| Corpus | `createTerminal` 37.1 w%, `env.shell` 28.1, `onDidCloseTerminal` 22.9, `activeTerminal` 17.8, shell-integration events 15.0-17.3 (`onDidChangeTerminalShellIntegration`, `onDidStart/EndTerminalShellExecution`), `registerTerminalLinkProvider` 11.1, `registerTerminalProfileProvider` 0.6; `onTerminalShellIntegration` activation 5.4. |
| Gap | Partial (pty Have; Pseudoterminal, env collections, shell integration, profiles, links Missing-both) |
| Under 2b | Free in host: `Terminal` objects, event ordering, `Pseudoterminal` state machine, env-collection merging per extension. Kotlin: `terminal/create(config)` -> `commandPtyParams` with `shellPath/shellArgs/cwd/env` (guest paths), name/icon/color; `sendText`, `show`, `dispose`, dimensions and exit status back to the adapter; **byte-fed terminal** for `$startExtensionTerminal` (a `TerminalSession` whose input goes to the adapter and whose output is `$sendProcessData`; whether `terminal-view` can be driven without a process is UNVERIFIED, research/ourcode-backend.md 2.4); env collections stored per `(envId, extId)` (persistent flag honoured) and applied (replace/append/prepend) to every new pty's `extraEnvironment`. Default extension-terminal shell = `/bin/bash` when present (Ubuntu base has it), which also makes `env.shell` match what extensions expect; keep `/bin/sh` fallback. Shell integration (separate WP): ship VS Code's `shellIntegration-bash.sh` (MIT) injected via `--init-file`, parse OSC 633 A/B/C/D/E/P in `terminal-emulator` into command start/end/cwd events forwarded to the adapter. Terminal links: provider calls on tap. Profiles: list contributed `terminal.profiles` and `$registerProfileProvider` entries in the new-terminal menu (WP-UI-13). |
| Effort / risk / WP | L / Med / **WP-API-8** (core, Pseudoterminal, env collections, links, profiles); M / Med / **WP-API-9** (shell integration) |

## 14. Git extension API (`vscode.git` `getAPI(1)`)

| Aspect | Content |
|---|---|
| VS Code | Built-in `vscode.git` (depends on `vscode.git-base`) exposes `GitExtension.getAPI(1)` (`extensions/git/src/api/git.d.ts:415-462`, 514 lines; `api/extension.ts:81`), activates on `*` and uses 34 proposed APIs (`extensions/git/package.json:12-56`, incl. `scmHistoryProvider`, `quickDiffProvider`, `timeline`). Built-ins keep their proposals: the main side nulls `enabledApiProposals` only for non-built-ins (`vs/workbench/services/extensions/common/extensionsProposedApi.ts:58-116`, `:110`), and the host only checks the description it receives (`extensions.ts:323-328`). Credentials: `GIT_ASKPASS` runs `process.execPath` with an askpass script over an IPC socket, consults registered `CredentialsProvider`s, else `window.showInputBox` (`extensions/git/src/askpass.ts:37-42,90-114`). |
| easyIDE today | JGit in-process (`SBX/git/GitRepository.kt:1-40`, `SBX/git/GitService.kt:21-150`), guest `git` for network with inline helper + `EASYIDE_GIT_TOKEN` (`SBX/git/GitCommandLine.kt:55-65`, ADR 0012); not reachable from a guest process. arch.md planned an in-host stub over guest git (`docs/extension-host/arch.md:161`). |
| Corpus | 6 extensions declare `extensionDependencies: vscode.git`; `getAPI(1)` in >= 24 bundles, `'vscode.git'` string in 24 (GitLens, GitHub PR, GitHub Actions, GitLab, Dart, Ruby LSP, git-blame, git-history, Continue, Cline, AWS, Sonar, ...). Open VSX hosts `vscode.git`/`vscode.git-base` **1.95.3** republished by `open-vsx` (corpus.json), i.e. 44 releases older than 1.139. |
| Gap | Missing-backend (+ SCM rendering is WP-UI-11) |
| Under 2b | **Vendor the real `vscode.git` + `vscode.git-base` from the pinned tag as built-ins** (MIT, same build; `isBuiltin: true` so its proposals stay enabled), instead of writing a stub: `getAPI(1)` is then exact, including `Repository.state` events, `log`, `diff`, `show`, branch/remote ops, and the `git:` file-system provider that GitLens/GitHub PR open. Include them in the host's extension set only when an enabled extension depends on `vscode.git` or its bundle references `'vscode.git'` (install-time scan), because `*` activation costs memory and a repo scan. Adapter must implement the SCM (`MainThreadSCM`, 17 methods) and quick-diff shapes at least headlessly; UI decision (for WP-UI-11): keep our native JGit SCM panel as the git UI and **do not render** vscode.git's own `SourceControl` for the same repo (suppress by provider id `git`), render other extensions' providers. Credentials: a small easyIDE built-in registers a `CredentialsProvider` through `getAPI(1).registerCredentialsProvider` (`git.d.ts:440`) that asks Kotlin (consent sheet naming the requesting operation; never the ADR 0012 env token by default). Risks: two git engines on one repo (JGit writes vs guest git `index.lock`), vscode.git status polling cost under proot (UNVERIFIED), proposal-shape stubs needed in the adapter for `scmHistoryProvider`/`timeline` etc. |
| Effort / risk / WP | L / High / **WP-API-10** |

## 15. Authentication providers (GitHub device flow / PAT)

| Aspect | Content |
|---|---|
| VS Code | `authentication.getSession(providerId, scopes, opts)` -> `MainThreadAuthentication` (`extHost.protocol.ts:222`); providers are contributed (`contributes.authentication`, implicit `onAuthenticationRequest`) and registered by extensions; the built-in `vscode.github-authentication` implements `github` with URL-handler, local-server, device-code and PAT flows (`extensions/github-authentication/src/flows.ts:197,287,387,522`) using Microsoft's OAuth client id (`src/config.ts:18`). |
| easyIDE today | Per-host git PAT store only (`SBX/git/GitCredentials.kt:24-60`), deliberately not exposed (ADR 0012); no auth API, no OAuth (research/ourcode-backend.md 2.4). |
| Corpus | `authentication.getSession` 4.5 w% (9 ext), `onDidChangeSessions` 4.3, `registerAuthenticationProvider` 1.1 (9 ext incl. GitLab, Terraform, SQLTools, Ansible). `GitHub.vscode-pull-request-github` (must-work) declares `extensionDependencies: vscode.github-authentication`. `microsoft` provider = OOS-MS (D8). |
| Gap | Missing-both |
| Under 2b | Free in host: `AuthenticationSession` objects, event plumbing, extension-registered providers' session bookkeeping. Adapter: `MainThreadAuthentication` - for `github` (and `github-enterprise`) forward to **Kotlin's provider** (WP-SEC-8: consent per `(extId, scopes)`, Keystore storage, Accounts sheet WP-UI-14); for providers registered by extensions, keep the registry in the adapter and route UI requests (sign-in badge, account menu) to Kotlin. Flow choice: GitHub **device flow** first (no redirect URI, works from a tablet with Custom Tabs for the verification page), **PAT** entry as fallback and for GHES; the OAuth client id must be easyIDE's own (reusing Microsoft's id is not acceptable; registration and policy UNVERIFIED, WP-SEC-8). Satisfy the dependency with a **stub built-in `vscode.github-authentication`** (our code, empty `activate`) so GitHub PR activates, since the provider itself lives main-side. `microsoft` provider requests fail with the typed "not supported on easyIDE" error (OOS-MS). |
| Effort / risk / WP | L / High / **WP-API-11** (+ WP-SEC-8 store/consent, WP-UI-14 UI) |

## 16. Process spawning and native code inside proot

| Aspect | Content |
|---|---|
| VS Code | Extensions use Node's `child_process` directly; VS Code does not mediate. VSIX extraction applies zip Unix modes (`vs/base/node/zip.ts`, registry-install.md sec 6.2 step 6). Platform builds: exact `linux-arm64` match or `universal`, no fallback (`extensionManagement.ts:138-167`, research/vscode-src.md sec 6). |
| easyIDE today | Guest `child_process` works natively (ADR 0030 dec. 5); host started with proot `--kill-on-exit` so children die with it (`SBX/backend/ProotLauncher.kt:20-57`); exec bits dropped by `PackageUnpacker` (`APP/extensions/install/PackageUnpacker.kt:38-61`); runtime-downloaded binaries run through the proot loader like any guest binary (ADR 0002; Play/W^X facts policy-licence sec 1.4-1.5). |
| Corpus (Node builtins, w%) | `child_process` 126 ext / 87.7, `net` 86.5, `https` 76.9, `worker_threads` 38 ext / 13.0, `module` 39.1, `inspector` 2.5 (`corpus-usage.json` `nodeBuiltins`). |
| Corpus (native) | 32 of 156 ship native files (32.8 w%): 17 ship ELF executables (24.3 w%: pyrefly, claude-code `claude`, redhat.java JRE, devsense PHP LS, openai `codex`, rust-analyzer, ruff, terraform-ls, lemminx, shellcheck, csharp LS/netcoredbg, sqlite-viewer...), 24 ship `.node` addons (22.3 w%). aarch64/glibc files: 100; highest required glibc symbol version 2.38 (guest noble has 2.39, policy-licence sec 3.2). Wrong-for-us files inside chosen builds: x86-64 (24), musl (7: TabNine aarch64-musl binary, jupyter musl prebuilds), arm32 (6) - harmless unless selected at runtime. `noGlibcArm64`: 1 extension (`devsense.intelli-php-vscode`, 0.7 w%, only a linux-x64 build) = platform-unavailable (D7). Runtime `chmod(` appears in >= 71 bundles (download-then-exec pattern). |
| Gap | Partial |
| Under 2b | Nothing to proxy (files and processes are not bridged, ADR 0030 dec. 5). Required: exec bits preserved at install (WP-REG-4); `process.execPath` is our pinned Node, so `fork()`/`ELECTRON_RUN_AS_NODE` children use it (ADR 0032); do **not** prepend our Node to `PATH` (a user's `node`/npm toolchain stays theirs); install-time native scan classifies each ELF as usable (aarch64 glibc <= guest glibc, or static) vs unusable (musl without a musl loader, x86-64, arm32) and records it as a per-extension blocker only when the file is on a known entry path (e.g. `bin/`, `server/`) - disclosure via WP-SEC-14 / M-VSX-22; best-effort spawn log from the host process tree (M-VSX-23). Runtime-downloading extensions (clangd, Java JRE fetchers, etc.) work only if the upstream publishes linux-arm64 glibc builds (UNVERIFIED per extension; clangd arm64 availability in particular) - prefer guest `apt` binaries and set the extension's path setting in a curated "device defaults" table (data file). Guest prerequisites checked at host start: `git` (vscode.git, GitLens), `bash` (terminals, shell integration), `rg` (optional, `findTextInFiles`), `ca-certificates`. |
| Effort / risk / WP | S (scan + prerequisites) / Med (per-binary behaviour under proot unmeasured) / **WP-HOST-11** |

## 17. Network and proxy

| Aspect | Content |
|---|---|
| VS Code | The host patches `http/https/net/tls` with `@vscode/proxy-agent` (inlined in the bundle, MIT, route-spike sec 1): reads `http.proxy`, `http.proxySupport` (default `off` here), `http.noProxy`, `http.proxyStrictSSL`; locally `isUseHostProxyEnabled` is always true (`api/node/proxyResolver.ts:43-46,73-74`), so it asks the main side `$resolveProxy(url)`, `$lookupAuthorization`, `$loadCertificates` (`extHost.protocol.ts:1978-1981`, `MainThreadWorkspace`). Kerberos is a native optional module (not bundled). |
| easyIDE today | No proxy handling; guest env cleared (`SBX/shell/SandboxShell.kt:159-171`), so `HTTP(S)_PROXY` never reach the guest (research/ourcode-backend.md 2.3 `net.proxy`). Registry traffic is Kotlin-side (security-licensing.md sec 1.4). |
| Corpus | `http.proxy` read directly in >= 12 bundles (GitLens, redhat.vscode-yaml/xml, AWS, TabNine, GitLab, csharp, ...). |
| Gap | Missing-backend |
| Under 2b | Adapter implements the three `MainThreadWorkspace` proxy methods. Phase 1: `$resolveProxy` returns `PROXY host:port` from the `http.proxy` setting (after `http.noProxy`) else `DIRECT`; `$loadCertificates` returns `[]` (the host then loads the guest's system CAs because `loadLocalCertificates` is on locally); `$lookupAuthorization` returns undefined. Phase 2 (Kotlin): answer from Android's `ProxySelector`/`ConnectivityManager.getDefaultProxy()` and export user-installed CAs so corporate MITM proxies work. Children (language servers, CLIs) do not get the patch: when a proxy is set, add `HTTP_PROXY/HTTPS_PROXY/NO_PROXY` to the host env allow-list (WP-SEC-5) so they inherit it. `http.proxy*` are protected settings (only the user writes them, WP-SEC-12). No filtering of guest traffic (security-licensing.md sec 1.4). |
| Effort / risk / WP | M / Low / **WP-API-14** |

## 18. Localisation (l10n)

| Aspect | Content |
|---|---|
| VS Code | Manifest `%key%` from `package.nls[.<locale>].json` at scan (registry-install.md sec 8); runtime `vscode.l10n.t` loads `<l10n>/bundle.l10n.<lang>.json` via `MainThreadLocalization.$fetchBundleContents(uri)` (`extHostLocalizationService.ts:79,103`; `extHost.protocol.ts:3604-3606`); language from initData `environment.appLanguage` (`extensionHostProtocol.ts:62-80`). Language packs (`localizations` point) translate VS Code itself. |
| easyIDE today | `SCH/manifest/Nls.kt:9-60` (whole-string `%key%`, most-specific locale first); `l10n/` dir only tolerated (`ManifestParser.kt:173`). |
| Corpus | `l10n.t` 24.1 w%, `l10n.bundle` 2.7, `env.language` 61.5. |
| Gap | Partial |
| Under 2b | Free in host: `l10n.t` formatting and bundle lookup. Adapter: `$fetchBundleContents` reads the guest file directly (no Kotlin); `appLanguage` + `VSCODE_NLS_CONFIG` = app locale (BCP-47, lower-case as VS Code uses). Manifest NLS for the host's `packageJSON` and for Kotlin's contribution reader both use `Nls.kt` semantics (adapter port of `extensionNls.ts` must match; test on the same fixtures). Language packs: Not-planned. |
| Effort / risk / WP | S / Low / **WP-API-15** |

## 19. Telemetry no-op and identity

| Aspect | Content |
|---|---|
| VS Code | `env.isTelemetryEnabled` = level `all` only (`extHostTelemetry.ts:58-68`); setting `telemetry.telemetryLevel` (`vs/platform/telemetry/common/telemetry.ts:99-115`); main receives `MainThreadTelemetry.$publicLog2` (spike saw 4 calls, route-spike sec 3). initData `telemetryInfo {sessionId, machineId, sqmId, devDeviceId, firstSessionDate}` (`extensionHostProtocol.ts:28-50`). |
| easyIDE today | Nothing (research/ourcode-backend.md 2.3 `telemetry`, `env.info` Partial: no machineId/sessionId). |
| Corpus | `env.appName` 75.8 w%, `env.machineId` 52.1, `env.isTelemetryEnabled` 51.9, `env.remoteName` 32.5, `env.uiKind` 24.4, `createTelemetryLogger` 5.0; GitLens/ms-python/Red Hat read `telemetryLevel` directly (policy-licence sec 5.2). |
| Gap | Missing-backend (trivial) |
| Under 2b | D9 values in initData (`appName "easyIDE"`, `appHost "desktop"`, `uiKind Desktop`, `remote.isRemote false`, version = pinned tag). `machineId`: random UUID per environment, created and stored by Kotlin (stable across host restarts, reset with the environment; not a device identifier); `sessionId` per host start; `sqmId`/`devDeviceId` empty or random. `telemetry.telemetryLevel = "off"` pinned in configuration (backend.md sec 6) and `$publicLog*` are no-ops in the adapter; telemetry-off env vars for the host (WP-SEC-17 owns the list and its test). |
| Effort / risk / WP | S / Low / **WP-API-15** |

## 20. `extensionKind`

| Aspect | Content |
|---|---|
| VS Code | `getExtensionKind`/`deduceExtensionKind` decide which host runs an extension (`extensionManifestPropertiesService.ts:150,262`); in a local window with no remote, `ui` and `workspace` extensions both run in the local host. |
| easyIDE today | Key rejected by `SCHJ:5`. |
| Corpus | not declared 118, `["workspace"]` 22, `["ui","workspace"]` 11, `["ui"]` 5. |
| Gap | Have once the manifest is accepted (sec 11): one host, `remoteName` undefined (D9), so everything runs there exactly as in a local VS Code window. `browser`-only extensions (none in the corpus) are Not-planned (OOS-WEB). |
| Effort / risk / WP | S / Low / part of **WP-HOST-10** |

## 21. `extensionDependencies` / `extensionPack`

| Aspect | Content |
|---|---|
| VS Code | Install resolves both recursively (deps: whole install fails; pack members: skipped) (registry-install.md sec 7); the host enforces activation order and reports missing/failed dependencies (`extHostExtensionActivator.ts:247-330`); cycles rejected (`extensionDescriptionRegistry.ts:122`). |
| easyIDE today | Both keys rejected (`SCHJ:5`); no resolver (research/ourcode-backend.md 2.1 `ext.deps`, `ext.pack`). |
| Corpus | 27 extensions declare dependencies (16.9 w%; most common: `vscode.git` 6, `ms-python.python` 4, `redhat.vscode-yaml` 3); 6 declare packs (12.4 w%). Unresolvable: `ms-python.vscode-pylance` (OOS-MS, corpus meta `unresolved`). |
| Gap | Missing-both |
| Under 2b | Free in host: ordering, error messages. Install closure: WP-REG-5. Backend: resolve `vscode.*` ids first against **our built-in set** (vscode.git, vscode.git-base, stub vscode.github-authentication), else Open VSX (where `vscode.*` built-ins are republished at 1.95.3); the adapter includes every enabled member in `$startExtensionHost`/`$deltaExtensions`; an unresolved dependency leaves the dependent installed but shown as "missing dependency X" (VS Code message) rather than hidden. |
| Effort / risk / WP | M / Med / **WP-HOST-10** (+ WP-REG-5) |

## 22. Updates and rollback

| Aspect | Content |
|---|---|
| VS Code | Update check every 12 h; an updated extension that is already activated needs "Restart Extensions" (Node cannot unload modules); versions live side by side until cleanup. |
| easyIDE today | Registry update check notifies only (`APP/extensions/registry/RegistryService.kt:117-120`); rollback via `current` flip (`APP/extensions/install/LocalInstaller.kt:172-240,319-323`). |
| Gap | Partial |
| Under 2b | Registry side = WP-REG-7 (daily, notify-only, pre-release channel, kill-list re-validation). Backend: install the new version beside the old; if the old one is activated, flip `current` only at the next host (re)start and show "Restart extensions to update" (the user chooses when; a stopped host flips immediately); keep the previous version until the new one activated once (mirrors ADR 0032's Node rule). Rollback = flip back + host restart. Memento/secrets are keyed by id, not version, so they survive both directions. |
| Effort / risk / WP | S / Low / **WP-HOST-10** |

## 23. Per-extension enable/disable

| Aspect | Content |
|---|---|
| VS Code | Enabling adds the description through `$deltaExtensions` (`extHost.protocol.ts:2612`) without restart; disabling an activated extension needs an extension-host restart. |
| easyIDE today | `EXT/host/Enablement.kt:45-100` (USER_DISABLED via `extensions.disabled` P-scope, SAFE_MODE, CRASH_DISABLED, REVOKED, NEEDS_APPROVAL, ...), approvals per install (`:32,95-99`). |
| Gap | Have (model) / Missing-backend (host delta) |
| Under 2b | Enable -> `$deltaExtensions(toAdd)` then Kotlin re-evaluates the activation index (already-fired events like `onLanguage` of open docs are replayed for the new id). Disable of a non-activated extension -> `$deltaExtensions(toRemove)`; of an activated one -> mark disabled, "Restart extensions" (security-licensing.md SC-10). Project-level disable stays `extensions.disabled` in the PROJECT layer; the host of that workspace simply never receives the id. |
| Effort / risk / WP | S / Low / **WP-HOST-10** |

## 24. Safe mode

| Aspect | Content |
|---|---|
| VS Code | `--disable-extensions`, extension bisect; host crash loop -> notification. |
| easyIDE today | Have: `EXT/host/SafeModeState.kt:13-57`, `APP/data/settings/SafeModeState.kt:11-20`, context key `isSafeMode`; 2 consecutive crashes -> safe mode (`SCH/ExtensionPolicy.kt:12`); built-ins stay on (`Enablement.kt:81-82`). |
| Gap | Have (model) / Partial (host wiring) |
| Under 2b | Safe mode = host not started for any workspace (SC-11). The D3 "3 failed restarts" banner offers: restart once more, open safe mode, or disable the last-activated extension (journal attribution, backend.md sec 1.4). Our vendored built-ins (vscode.git...) are code extensions and therefore also off in safe mode. |
| Effort / risk / WP | S / Low / **WP-HOST-7** |

## 25. Kill switch

| Aspect | Content |
|---|---|
| VS Code | None (closest: disable all extensions). |
| easyIDE today | Global `extensions.enabled` (`SCH/settings/ExtensionSettings.kt:18`) and revocation `REVOKED` (`Enablement.kt:80`); no per-environment kill switch (grep "kill switch" = 0; ADR 0030 dec. 6). |
| Gap | Missing-both |
| Under 2b | Supervisor action "stop code extensions in this environment": terminate every host of the environment (proot `--kill-on-exit` takes the children), mark all `host.run` extensions disabled for that environment, then on confirm wipe Memento, secrets, webview profiles and `/root/.easyide/exthost/*` (M-VSX-03). Reachable from the environment sheet and from the persistent "extensions running" notification. Acceptance and copy are WP-SEC-6; mechanism in WP-HOST-4. |
| Effort / risk / WP | M / Med / **WP-SEC-6** (mechanism in **WP-HOST-4**) |

## 26. Extension language providers and the LSP router (D10)

### 26.1 VS Code semantics that matter

- Provider choice is per document: `score(selector, uri, languageId, ...)` (`vs/editor/common/languageSelector.ts:29-131`; exact language 10, `*` 5) and ties go to the **newest registration** (`vs/editor/common/languageFeatureRegistry.ts:202-221`).
- Definition-family requests query **all** providers and flatten (`vs/editor/contrib/gotoSymbol/browser/goToSymbol.ts:32-50`); hover merges (`contrib/hover/browser/getHover.ts:38`); signature help takes the first ordered provider with a result (`contrib/parameterHints/browser/provideSignatureHelp.ts:32`); rename tries providers in order until one does not reject (`contrib/rename/browser/rename.ts:50-75`); formatting honours `editor.defaultFormatter` = an **extension id** (`contrib/format/browser/format.ts:44-53`); semantic tokens use the first ordered group with a result (`contrib/semanticTokens/common/getSemanticTokens.ts:41`).
- The adapter must implement `MainThreadLanguageFeatures` (47 methods, the largest in-scope shape, route-spike sec 4): `$register*Provider(handle, selector, ...)` + `$unregister`, then call back `ExtHostLanguageFeatures.$provide*` (72 methods).

### 26.2 easyIDE today (`LSP/client/FeatureRouting.kt`)

| Policy | Code | Features |
|---|---|---|
| merge (parallel, error = empty) + dedupe first-wins by key | `merge` `:33-44`, `dedupe` `:86-89` | completion (no dedupe), hover (`contents`), references (`uri+range`), documentHighlight, documentSymbol (`name+selectionRange`), workspaceSymbol, codeAction (`title+kind`), inlayHint, foldingRange, codeLens, documentLink (`LspClient.kt:116-166`) |
| firstNonEmpty (ordered) | `:47-60` | definition, declaration, typeDefinition, implementation, signatureHelp, selectionRange (`LspClient.kt:199-211,285-286`) |
| single owner (`editor.defaultFormatter` by **server id**, else first) | `owner` `:66-70`, `single` `:73-76` | rename/prepareRename, formatting/range/onType, willSaveWaitUntil, semanticTokens (`LspClient.kt:216-265`) |

Sessions are typed `LspSession`, ordered ready-first, priority desc, config order (`:22-24`) and chosen by `languageId` only (`LspClient.kt:280`). Results carry `FromServer(server, value, version)` for resolve/command routing (`:20`). WASM `ExtensionProviders` (completion, hover) are merged **in presenters** with a fake `ServerKey(..., "extension:"+id)` and forced `resolved=true, command=null` (`APP/ui/screens/workspace/lsp/CompletionController.kt:200-212,278`, `InfoController.kt:81`).

### 26.3 Refactor: `FeatureSource`

```kotlin
// :lsp (Android-free). SourceId: "lsp:<serverId>" | "ext:<publisher.name>#<handle>" | "wasm:<extId>"
interface FeatureSource {
    val id: SourceId
    val owner: String            // pack id for LSP ("easyide.python"), extension id for ext/wasm
    val selector: DocumentSelector  // [{language?, scheme?, pattern?}] (notebookType ignored, OOS-NB)
    val registeredAt: Long          // for VS Code's newest-first tie-break
    fun supports(feature: LspFeature): Boolean
    suspend fun <R> request(method: LspMethod<R>, params: JsonElement, uri: String?): Versioned<R>?
}
```

- `LspSession` implements it (selector = its configured languages, scheme `file`). The adapter exposes each
  `$register*Provider(handle, selector)` as an **LSP-shaped** source: it converts VS Code DTOs to LSP JSON
  (`ISuggestDataDto` compact form, `extHost.protocol.ts:2666`, -> `CompletionItem` with `command`,
  `labelDetails`, insert/replace ranges), so `LspClient`'s existing parsers, `FromSource` routing, resolve and
  `executeCommand` paths are reused. WASM providers become sources too (presenter merging deleted).
- Selection per request: sources with `score(selector, doc) > 0`, sorted by score desc, then **extension
  sources newest-first, then LSP sessions** in today's order (proposal: at equal score a user-installed
  extension outranks a built-in pack).
- `FromServer` -> `FromSource`; `DocContext` gains `scheme`.
- Per-feature policy changes: definition family becomes **merge + dedupe(`uri+range`)** for all sources (VS Code
  parity, also fixes multi-server LSP); signatureHelp and selectionRange stay first-non-empty; rename becomes
  **ordered fallback** (next source when one rejects/returns null); formatting keeps single owner but
  `editor.defaultFormatter` values are unified to owner ids (`publisher.name` for extensions, `easyide.<pack>`
  for built-in packs; legacy server-id values migrated), and with several formatters and no default the UI
  asks once (WP-UI-10); semanticTokens: highest-ranked source with a legend.
- Diagnostics: extension `DiagnosticCollection`s become synthetic `ServerKey(env, project, "ext:<id>/<name>")`
  in `DiagnosticStore` (`LSP/diagnostics/DiagnosticStore.kt:23-43`); no cross-source dedupe (VS Code shows all).
- New provider kinds absent from `LspFeature` (color, callHierarchy, typeHierarchy, linkedEditing,
  inlineCompletion; research/ourcode-backend.md 2.5) are added as features with merge policy where VS Code
  merges and first-wins otherwise; each needs a UI (WP-UI-10).

### 26.4 Dedupe / yield rule (D10), concrete

1. **Trigger (runtime, authoritative):** extension E is enabled in the workspace and registers, for scheme
   `file`, both a **completion and a definition** provider whose selectors score > 0 for language X (a full
   language server; hover alone is not enough because Ruff's server offers hover on rule codes). Then every
   built-in pack session that serves X **yields for X in this workspace**: it is not queried for X
   documents, its diagnostics for X documents are cleared, and if it serves no other open language it is
   stopped (memory). Complementary extensions (Ruff, Tailwind CSS IntelliSense - completion/hover/colour
   but no definition, UNVERIFIED per version -, ESLint, Prettier, Error Lens, Code Spell Checker) do not
   trigger a yield, so their results **merge** with the built-in pack under the per-feature policies above.
2. **Start-up race (static hint):** a generated data file (`lang-yield.json`, from corpus manifests:
   `contributes.languages` + `onLanguage` + bundle contains `vscode-languageclient`) lists extensions
   known to start a server for X (e.g. rust-lang.rust-analyzer -> rust, meta.pyrefly -> python, golang.Go ->
   go, llvm-vs-code-extensions.vscode-clangd -> c/cpp, Vue.volar -> vue, redhat.vscode-yaml -> yaml). When
   one is enabled, Kotlin delays the built-in session for X up to `extensions.host.yieldWaitMs` (proposal
   3000) waiting for rule 1; if no registration arrives, the built-in starts (no silent loss of features).
3. **Override:** setting `languages.provider.<X>`: `auto` (rules 1-2) | `builtin` (built-in never yields;
   extension sources still merge) | `<extension id>` (only that extension's sources for the yield-able
   features). Status bar language item shows "Python: Pyrefly (built-in paused)".
4. Yield is undone when E is disabled/uninstalled or the host stops for longer than the crash backoff.

| Item | Content |
|---|---|
| Gap | Partial (LSP routing Have; source abstraction, provider bridge, yield Missing-backend; UI deltas WP-UI-10) |
| Effort / risk / WP | M / Med **WP-API-12** (router refactor, can start before the host exists); L / Med **WP-API-13** (provider bridge ~25 kinds + yield rule) |

## 27. Summary

| # | Topic | Gap | Effort | WP |
|---|---|---|---|---|
| 1.1 | Node 24 provisioning in proot | Missing-backend | M | WP-HOST-2, WP-HOST-3 |
| 1.2 | Host process, lifecycle, supervision, one per workspace, parking | Partial | L | WP-HOST-4 |
| 1.3 | Memory budget, MemoryPolicy slot, shedding order | Partial | M | WP-HOST-6 (+WP-SEC-13) |
| 1.4 | Crash journal, attribution | Partial | S | WP-HOST-7 |
| 2 | Transport (host<->adapter free; adapter<->Kotlin) | Have/Missing-backend | M | WP-HOST-5 |
| 3 | Activation (Kotlin detects, host activates) | Partial | M | WP-API-1 |
| 4 | Storage, Memento, paths | Partial | S | WP-HOST-8 |
| 5 | Secrets | Missing-backend | S | WP-API-6 (+WP-SEC-7) |
| 6 | Configuration parity | Partial | M | WP-API-2 |
| 7 | FS providers, virtual docs, findFiles/findTextInFiles | Missing-both | M | WP-API-4 |
| 8 | File watching, file-operation events | Partial | M | WP-API-5 |
| 9 | Text documents and sync at scale | Partial | L | WP-API-3 |
| 10 | Workspace trust | Partial | S | WP-API-16 |
| 11 | Manifest schema blocker, package limits | Missing-backend | L | WP-HOST-9 (+WP-REG-4/8) |
| 12 | Tasks | Partial | L | WP-API-7 |
| 13 | Terminals; shell integration | Partial | L; M | WP-API-8; WP-API-9 |
| 14 | Git API (`vscode.git`) | Missing-backend | L | WP-API-10 |
| 15 | Authentication (GitHub) | Missing-both | L | WP-API-11 (+WP-SEC-8, WP-UI-14) |
| 16 | Process spawning, native code | Partial | S | WP-HOST-11 (+WP-REG-4, WP-SEC-14) |
| 17 | Network / proxy | Missing-backend | M | WP-API-14 |
| 18 | l10n | Partial | S | WP-API-15 |
| 19 | Telemetry no-op, identity | Missing-backend | S | WP-API-15 (+WP-SEC-17) |
| 20 | extensionKind | Have (after 11) | S | WP-HOST-10 |
| 21 | Dependencies / packs | Missing-both | M | WP-HOST-10 (+WP-REG-5) |
| 22 | Updates / rollback | Partial | S | WP-HOST-10 (+WP-REG-7) |
| 23 | Enable / disable | Have / Missing-backend | S | WP-HOST-10 |
| 24 | Safe mode | Have / Partial | S | WP-HOST-7 |
| 25 | Kill switch | Missing-both | M | WP-SEC-6 (+WP-HOST-4) |
| 26 | Language providers + LSP router (D10) | Partial | M + L | WP-API-12, WP-API-13 |

## 28. WP-HOST-* (process, runtime, supervision, storage, transport)

| WP | Scope | Deps | Acceptance test | Effort | Risk |
|---|---|---|---|---|---|
| WP-HOST-1 | Reproducible esbuild of `extensionHostProcess.ts` from the pinned stable tag (self-contained variant B), product-config/NLS injection, JS logger replacing `@vscode/spdlog`, natives external; adapter bundle from the same tag; NOTICE rows | - | Two builds are byte-identical; the route-spike harness (renderer + 8 extensions; ADR 0031 names `tools/vsx-audit/spike/`, not yet in the tree) activates 8/8 spike extensions on x86 CI with config defaults fed; no `require` of a native module at start | M | Low |
| WP-HOST-2 | Node 24 provisioning (ADR 0032): pinned URL+sha256, download/verify/unpack to `/opt/easyide/node/<v>/`, compile-cache dir, keep-previous rule, progress | - | Fresh env: first enable downloads once and `node -v` equals the pin; 1-byte-corrupted tarball refused; offline gives a retryable error; a pin bump keeps the old dir until the new host started once | M | Med |
| WP-HOST-3 | Device go/no-go run of ADR 0031 gates G1-G7 on the Pad 6 | 1, 2 | Report with G1-G7 numbers committed; go/no-go recorded in ADR 0031 | S | High |
| WP-HOST-4 | `ExtHostSupervisor`: per `(env, project)` process tree via `ServerProcessFactory`, env allow-list, initData, lazy start, stop/park rules, restart backoff x3 + banner, adapter ping, kill-switch mechanism | 1-3, WP-SEC-5 | Host absent until a fixture's `onCommand` fires; `kill -9` of host or adapter restarts with backoff 3 times then banner; parked workspace host stops after `parkedStopSec` and restores `globalState` on return; kill switch leaves no guest process (`ps`) | L | Med |
| WP-HOST-5 | `:exthost` Kotlin module on `JsonRpcConnection`; UI protocol v1 (arch.md re-scoped + amendments); adapter frame hygiene, notification coalescing, base64 binary with cap, timeouts | 1 | Device: Kotlin<->adapter<->host round trip p50 <= 15 ms / p99 <= 50 ms; completion cancelled within one RTT; 5k decoration updates/s coalesced with no dropped frames beyond the editor budget | M | Low |
| WP-HOST-6 | `Victim.Host` in `MemoryPolicy`, process-tree RSS, budget/heap keys, shedding order, parked-host policy, `onTrimMemory` mapping | 4 | Unit: eviction order for mixed sessions/hosts matches sec 1.3; device: `TRIM_MEMORY_RUNNING_CRITICAL` stops a parked host first and the active editor stays responsive (ST-VSX-10 in WP-SEC-13) | M | Med |
| WP-HOST-7 | Crash journal from host activation events, attribution, host-neutral crash keys, safe-mode wiring, host log capture | 4 | Fixture that throws in `activate` -> FAILED, host alive; fixture that `process.abort()`s during activation twice -> CRASH_DISABLED, other extensions active after restart; 3 unattributed crashes -> banner | S | Low |
| WP-HOST-8 | Guest storage/log homes, `ExtensionStoragePort` (Memento, quota), wipe on uninstall/kill switch | 4 | `globalState` survives host and app restart; `workspaceState` differs per project; quota overflow surfaces an error to the extension; uninstall removes its storage dir | S | Low |
| WP-HOST-9 | `.vsix` path: adapter `IExtensionDescription` builder (raw `packageJSON`, NLS, implicit events, proposal allow-list filter) + Kotlin tolerant manifest reader + vsx package rules | WP-REG-4, WP-REG-8 | All 156 corpus manifests produce a descriptor with 0 fatal errors; raw `package.json` bytes unchanged on disk; every contribution point count equals `corpus.json` `contributes` | L | Med |
| WP-HOST-10 | Extension set: `$startExtensionHost`/`$deltaExtensions`, restart-to-apply for disable/update, built-in set (vscode.git, vscode.git-base, stub github-authentication), `vscode.*` dependency resolution, `engines.vscode` check vs pinned tag, `extensionKind` accepted | 9, WP-REG-5, WP-REG-7 | Enabling activates without restart; disabling an activated extension shows "Restart extensions"; missing dependency shows VS Code's message; update applies at next restart and rollback restores the old version | M | Med |
| WP-HOST-11 | Guest prerequisites (git, bash, rg, CA certs), native-binary usability scan (arch/libc/glibc version), PATH policy, device-defaults table for binary path settings, spawn log hook | 4, WP-SEC-14 | rust-analyzer/ruff/pyrefly fixtures classified usable, TabNine musl binary flagged; missing `git` produces an actionable prompt before vscode.git activates | S | Med |

## 29. WP-API-* (API families: adapter + Kotlin ports)

| WP | Scope | Deps | Acceptance test | Effort | Risk |
|---|---|---|---|---|---|
| WP-API-1 | Activation bridge: raw event index incl. implicit events (data table), Kotlin event emitters, `$activateByEvent`, `$checkExists` glob walk, `NodeHostActivator`, warn-not-kill timeout | HOST-4, HOST-9 | For each corpus event kind in sec 3 a fixture activates exactly once on the right trigger; `workspaceContains:**/pyproject.toml` activates on open; OOS events never raised | M | Med |
| WP-API-2 | Configuration models (ENVIRONMENT -> userRemote), change deltas, `$updateConfigurationOption` mapping, protected keys, forced telemetry off, optional `.vscode/settings.json` import | HOST-5 | better-comments activates (defaults); `inspect()` fixture table matches VS Code run on the same layers; `update(Global)` writes USER; `affectsConfiguration` true only for changed keys/language | M | Med |
| WP-API-3 | Documents/editors: deltas from `DocumentStore` (+eol, dirty, untitled, encoding), coalesced changes with undo flags, size tiers, adapter background docs (<= 50 MB), save/will-save participants, `applyEdit`, `setTextDocumentLanguage` | HOST-5, WP-UI-6 | ESLint diagnostics update while typing; Prettier format-on-save via `onWillSaveTextDocument`; `openTextDocument` of a 20 MB log works without the editor; 300 KB file visible read-only to extensions | L | Med |
| WP-API-4 | FS providers + scheme router, text content providers (read-only virtual docs), `$startFileSearch`, ripgrep stand-in | HOST-5 | GitLens revision doc (`gitlens:`) and vscode.git `git:` diff open read-only; memfs fixture read/write/stat/delete; `findFiles('**/*.go', '**/vendor/**', 100)` matches a reference list | M | Med |
| WP-API-5 | Guest watchers for `$watch` (recursive, excludes, batching, shared), app file-operation will/did events; LSP `didChangeWatchedFiles` fix | HOST-5 | Device: terminal `touch`/`rm`/`mv` under `/workspace` reach a `**/*.py` watcher within 1 s; explorer rename runs `onWillRenameFiles` edits; 20k-file project stays under the inotify limit with defaults | M | High |
| WP-API-6 | Secrets forwarding to `ExtensionSecrets` + change events | WP-SEC-7 | ST-VSX-02; GitLens stores and reads a token across host restarts | S | Low |
| WP-API-7 | Task system in the adapter (`.vscode/tasks.json` + `.easyide/tasks.json`, providers, variables, vendored problem matchers) | API-8 | Go/TypeScript `tasks.fetchTasks()` lists provider tasks; `$tsc` matcher turns output into diagnostics; `CustomExecution` task runs in a Pseudoterminal | L | Med |
| WP-API-8 | Terminals: create/sendText/dispose/events, byte-fed Pseudoterminal, env collections, links, profiles, bash default | WP-UI-13 | Python extension's env-collection activation reaches a new terminal's env; Code Runner runs in a terminal; an extension Pseudoterminal echoes input | L | Med |
| WP-API-9 | Shell integration (vendored bash script, OSC 633 parser, execution events) | API-8 | `onDidEndTerminalShellExecution` fires with exit code for `false`/`true` in bash; `shellIntegration.executeCommand` works | M | Med |
| WP-API-10 | Git API via vendored `vscode.git`/`vscode.git-base`, headless SCM + quick diff shapes, provider suppression, credentials provider -> Kotlin consent | HOST-10, API-4, WP-UI-11 | `getAPI(1).repositories[0].state.HEAD` correct in a fixture repo; GitHub PR and git-blame activate with their `vscode.git` dependency; push from an extension prompts our consent sheet and never sees the ADR 0012 token | L | High |
| WP-API-11 | Authentication: adapter `MainThreadAuthentication`, Kotlin GitHub provider (device flow + PAT), stub `vscode.github-authentication`, extension providers | WP-SEC-8, WP-UI-14 | GitHub PR obtains a session only after consent; sign-out fires `onDidChangeSessions`; `microsoft` request fails with the typed error | L | High |
| WP-API-12 | `FeatureSource` + `DocumentSelector` refactor of `FeatureRouting`/`LspClient`; WASM providers moved out of presenters; policy changes (definition merge, rename fallback, owner-id formatter) | - | Existing LSP tests green; new unit tests for score ordering, definition merge/dedupe, rename fallback, `editor.defaultFormatter="esbenp.prettier-vscode"` selection | M | Med |
| WP-API-13 | Provider bridge (`MainThreadLanguageFeatures` -> LSP-shaped sources, ~25 kinds) + D10 yield rule and override setting | API-12, API-3, WP-UI-10 | rust-analyzer enabled -> built-in rust pack yields (one set of completions/diagnostics); Ruff + built-in Python merge code actions; completion resolve and item commands reach the extension | L | Med |
| WP-API-14 | Proxy: `$resolveProxy/$lookupAuthorization/$loadCertificates`, Android proxy + user CAs (phase 2), proxy env for children | HOST-5 | With `http.proxy` set, an extension `https.get` goes through a test proxy; a language server child inherits `HTTPS_PROXY` | M | Low |
| WP-API-15 | Identity/env (D9, machineId/sessionId), telemetry no-ops, l10n bundles, `env.language`, `$registerUriHandler` forwarding to the deep-link router (WP-SEC-11) | HOST-4 | `env.appName === "easyIDE"`, `isTelemetryEnabled === false`, `l10n.t` returns the German string with app locale `de`; WP-SEC-17 fixture sees no telemetry hosts | S | Low |
| WP-API-16 | Workspace trust: trusted flag, request/grant, restricted-mode activation filter, `restrictedConfigurations` | HOST-4 | Untrusted project: `supported:false` fixture does not activate; granting trust fires `onDidGrantWorkspaceTrust` and activates it | S | Low |

Milestone fit (D11): M1 hello-world = HOST-1..5, 7, 8, 9 (minimal), 10 (enable only), API-1, 2, 6, 15;
M2 language extensions = API-3, 4, 5, 12, 13, HOST-6, 11; M4 GitLens + Claude Code = API-8, 10, 14 (with
WP-UI-8/9); M5 corpus = API-7, 9, 11, 16 and the rest of HOST-10.

## 30. UNVERIFIED (this part and backend.md) and how to verify

| # | Item | How to verify |
|---|---|---|
| 1 | Node 24 V8 JIT, startup and RSS under proot on arm64 (G1-G5) | WP-HOST-3 device run (route-spike sec 10 method) |
| 2 | `xz-utils` present in ubuntu-base noble; official Node binary's shared-library deps satisfied in the guest | fresh env: `command -v xz`; `ldd /opt/easyide/node/<v>/bin/node` |
| 3 | inotify through proot: events on bind-mounted `/workspace`, Node recursive `fs.watch`, `fs.inotify.max_user_watches` on the device, sharing with the app's `FileObserver`s | `node -e "require('fs').watch('/workspace',{recursive:true},console.log)"` then edits from terminal and editor; `cat /proc/sys/fs/inotify/max_user_watches` |
| 4 | Adapter process RSS (second Node process) | `/proc/<pid>/status` VmRSS during WP-HOST-3 |
| 5 | `dlopen` of `.node` addons from the guest on device (claude-code `audio-capture.node`, duckdb, sqlite3) | require each addon inside the host on the Pad 6; policy side is licensing-policy.md |
| 6 | Spawn/exec overhead under ptrace for git-heavy extensions (GitLens, vscode.git status polling) and JGit/guest-git `index.lock` contention | device trace: git invocations per minute with GitLens idle; concurrent in-app commit + vscode.git refresh |
| 7 | Single-folder `inspect().workspaceFolderValue` semantics in VS Code | run the WP-API-2 fixture in VS Code at the pinned tag |
| 8 | `PROOT_LOADER`/`LD_LIBRARY_PATH` leaking into guest `process.env` (affects children) | terminal `env \| grep -E 'PROOT\|LD_LIBRARY'` (research/ourcode-backend.md sec 9) |
| 9 | Kernel < 4.18 devices vs official Node builds | device matrix `node -e 'console.log(process.versions)'` (policy-licence sec 3.3) |
| 10 | Byte-fed terminal (no process) in `terminal-view` | prototype a `TerminalSession` fed from a flow |
| 11 | Runtime-downloaded binaries publishing linux-arm64 glibc builds (clangd, others) | static grep of corpus bundles for download URLs + upstream release listings |
| 12 | GitHub OAuth app with device flow for easyIDE (client id policy) | GitHub developer settings / docs; WP-SEC-8 |
| 13 | Corpus bundle-grep counts (globalState, secrets, getAPI, chokidar, chmod) are lower bounds (minified names) | runtime trace in the corpus harness (test-program) |
| 14 | proot behaviour of the workspace-storage lock (we plan to skip it) | run the host once with `skipWorkspaceStorageLock:false` on device |
| 15 | Corpus files may still be updating; numbers here are from snapshot `2026-09-25T14:49Z`, scanVersion `4913c6e67664` | re-run the queries in this doc against the final `corpus.json`/`corpus-usage.json` |

Doc drift to fix (not in scope of this file): security-licensing.md sec 0 item 1 and A14 still say "one host per
environment" (ADR 0030 dec. 1); D3/ADR 0031 amended that to one host per workspace session.
