# OURCODE-BACKEND - what easyIDE already has (backend) for a VS Code extension-compat layer

Agent: OURCODE-BACKEND. Audit date 2026-09-25. Repo: `$WT` = worktree `audit/vsx` at `49725be`
("add ADR 0030 and protocol spec..."). Documentation-only; no code changed. Machine-readable twin:
`$SP/notes/ourcode-backend.json` (items / contributes / activationEvents / whenKeys / commandsBuiltin).

## 0. Path aliases (all relative to `$WT`)

| Alias | Path |
|---|---|
| `SCH/` | `services/shared/extension-schema/src/main/kotlin/dev/easyide/extensions/` (Apache-2.0 SDK core: manifest, schema, when, capability, view) |
| `SCHJ` | `services/shared/extension-schema/manifest.schema.json` |
| `EXT/` | `services/mobile/extensions/src/main/kotlin/dev/easyide/extensions/` (runtime: registry, activation, crash journal) |
| `LSP/` | `services/mobile/lsp/src/main/kotlin/dev/easyide/lsp/` (hand-rolled JSON-RPC + LSP client) |
| `SBX/` | `services/mobile/sandbox-runtime/src/main/java/dev/easyide/sandbox/` (proot, env, files, git) |
| `APP/` | `services/mobile/app/src/main/java/dev/easyide/app/` |
| `WSM/` | `services/mobile/ext-wasm/src/main/kotlin/dev/easyide/extwasm/` |
| `AST/` | `services/mobile/app/src/main/assets/` |

Notes on the brief: there is no `services/mobile/extension-schema`; the schema/manifest/when/view core lives
in `services/shared/extension-schema` (ADR 0013 line 36). `services/mobile/exthost` (ADR 0030 dec. 2/4) does
**not exist yet** (tracker phase 1 "Not started", `docs/extension-host/tracker.md:8`).

## 1. Headline findings (backend)

1. **The manifest pipeline rejects real `.vsix` manifests outright.** `SCHJ:5` requires `engines.easyide` and has
   `additionalProperties:false` at top level (so `main`, `browser`, `extensionDependencies`, `extensionPack`,
   `extensionKind`, `scripts`, `dependencies`, `capabilities`, `contributes.*` unknown points are schema *errors*);
   `SCHJ:48` `contributes` is also closed (16 points). `SCHJ:35` names must be `^[a-z0-9][a-z0-9-]{0,62}$`
   (lower-case only). Command `icon` must be a string (`SCHJ:128`), menu items cannot carry `submenu` (`SCHJ:137`).
   Only path in today: `VsCodeIconThemeAdapter` (`APP/extensions/install/VsCodeIconThemeAdapter.kt:25-59`),
   which rewrites the manifest keeping *only* `iconThemes`. The Open VSX subset importer (`VsixReader`,
   `docs/extension-sdk/lld/registry-and-install.md:334-356`) is design-only ("Not yet ... Open VSX (sec 9)", line 5).
2. **Package layout rules reject common `.vsix` content**: no symlinks, **no nested archives** (`.jar .zip .tgz .gz
   .vsix ...` anywhere in the tree) and no case-insensitive duplicates (`SCH/manifest/PackageFiles.kt:57,77,87-124`);
   default limits 50 MB package / 200 MB unpacked / 20 MB per file (`SCH/settings/ExtensionSettings.kt:22-24`).
   Java/Python/C# extensions routinely ship jars / bundled servers / `.gz` data -> refused.
3. **The language-feature router is typed to `LspSession`, not to a "source" interface.** `FeatureRouting`
   (`LSP/client/FeatureRouting.kt:30-90`) merges/dedupes over `List<LspSession>`. The only non-LSP sources today
   are WASM `ExtensionProviders` with **completion and hover only** (`APP/lsp/ExtensionProviders.kt:24-48`,
   `SCH/../extwasm/WasmPolicy.kt:47`), merged *in the presenters*, not in the router
   (`APP/ui/screens/workspace/lsp/CompletionController.kt:200-212`, `InfoController.kt:81`). ADR/arch claim
   "same `FeatureRouting`" (`docs/extension-host/arch.md:141`) needs a refactor first.
4. **Commands must be declared.** `ExtensionsRuntime.Catalog` resolves a command only if the extension declares it in
   `contributes.commands` and it has a declarative action or WASM (`EXT/ExtensionsRuntime.kt:186-196`);
   `CommandRegistry` is an immutable list rebuilt with `plus` (`APP/ui/commands/Command.kt:45-61`). VS Code
   `registerCommand` of undeclared/internal ids (very common) has no home.
5. **Activation events: 6 kinds** (`SCH/manifest/ExtensionDescriptor.kt:22-47`); `*` silently becomes
   `onStartupFinished`; unknown events are dropped with a warning (`SCH/manifest/ManifestParser.kt:153-161`).
   No implicit activation from contributions (VS Code 1.74+ behaviour). Activation timeout reuses the **WASM**
   key `extensions.wasm.activateTimeoutMs` = 5000 ms (`EXT/host/ActivationManager.kt:142`,
   `SCH/settings/ExtensionSettings.kt:30`) - too short for Node extensions and the wrong key.
6. **One host per environment (ADR 0030 dec. 1) conflicts with the sandbox model.** Every launch binds one
   *project* to `/workspace` (`SBX/backend/ProotLauncher.kt:34-36`, `SBX/SandboxPaths.kt:62,149`), and the
   launcher is keyed by `ServerKey(environmentId, projectId, serverId)` (`LSP/session/ServerConfig.kt:13`,
   `APP/lsp/SandboxServerLauncher.kt:57-62`). A host process therefore sees exactly one project; ADR 0017 already
   chose one server per (env, project) for this reason. The host must be per (env, project) or re-bind.
7. **Global-scope extensions are invisible in the guest.** Only ENVIRONMENT-scope installs are bound at
   `/opt/easyide/extensions/<id>` (`SBX/extensions/EnvironmentExtensionBinds.kt:20-28`), while the Open VSX design
   says imports are "always GLOBAL" (`registry-and-install.md:352`). A Node host could not `require` them.
8. **Document model is snapshot-based, UTF-8 only, 256 KB editable.** `DocumentStore`/`DocSnapshot`
   (`LSP/docs/DocumentStore.kt:18-84`) holds full text per version; changes are re-derived as *one* range by
   `TextDiff.compute` (`LSP/text/TextDiff.kt:30`); files are decoded as UTF-8 (`SBX/files/ProjectFiles.kt:110`);
   editable max 256 KB, read-only prefix viewer to 8 MB (`SBX/files/FileContent.kt:48-54`); EOL is detected for the
   status bar only (`APP/ui/screens/workspace/layout/StatusFacts.kt:42-45`).
9. **No file-watch event stream for tools.** Kotlin `ProjectFileWatcher` watches only the explorer's expanded dirs
   (`SBX/files/ProjectFileWatcher.kt:27-79`, 400 ms debounce, non-recursive). The LSP client *advertises*
   `didChangeWatchedFiles` with dynamic registration (`LSP/protocol/ClientCapabilities.kt:139-142`) but no code
   sends it (repo-wide grep: only that line). WASM `fs.changed` fires only on in-app saves
   (`APP/extensions/wasm/WasmRuntime.kt:194-200`).
10. **Secrets: none for extensions** (`APP/extensions/wasm/WasmSmallPorts.kt:40-47` `NoSecrets`); the only
    Keystore use is `GitCredentials` AES-GCM (`SBX/git/GitCredentials.kt:14-24,74`) - a reusable pattern.
11. **Node in the guest today: none by default.** Rootfs is Ubuntu 24.04 (`APP/data/SandboxImages.kt:24-41`);
    the "Ubuntu + Node.js" preset and several packs `apt-get install nodejs npm`
    (`AST/extensions/easyide.yaml/package.json:40-42`, `.../easyide.shell/package.json:41-42`). Ubuntu noble's
    `nodejs` is **18.19.1** (verified: https://packages.ubuntu.com/noble/nodejs shows "Package: nodejs
    (18.19.1+dfsg-6ubuntu5)"; noble-updates not checked - UNVERIFIED whether a newer build is published). Node 18
    is EOL (2025-04-30 per nodejs.org release schedule - UNVERIFIED here, check https://nodejs.org/en/about/previous-releases)
    and older than the Node VS Code bundles (Electron; VS Code 1.9x ships Node 20/22 - UNVERIFIED, check
    `$VS/package.json`/`.nvmrc`). ADR 0030's `apt-get install nodejs` choice is a compat risk.

## 2. Capability table (backend ids from SURFACES.md)

Status = what exists vs what VS Code offers: Have / Partial / Missing. "Reuse" = the concrete hook for the compat layer.

### 2.1 Host process, lifecycle, packaging

| Id | Status | Exists today (file:line) | Reuse point / notes |
|---|---|---|---|
| host.process | Partial | `SBX/shell/ServerProcessFactory.kt:67-88` (single spawn point, `/bin/sh -c 'exec "$0" "$@"'` + argv, stdio pipes, `TERM=dumb`), `APP/lsp/SandboxServerLauncher.kt:46-62`, `SBX/LinuxEnvironment.kt:215` `startPiped`, proot `--kill-on-exit`, `-0` fake root (`SBX/backend/ProotLauncher.kt:20-57`) | Launch `node exthost.js` exactly as a server (ADR 0030 dec. 1). Env: `SBX/shell/SandboxShell.kt:159-171` clears host env, sets `HOME=/root, PATH, TERM, LANG=C.UTF-8` (`SBX/backend/SandboxLauncher.kt:86-119`) + declared env; proot adds `PROOT_LOADER(_32)`, `LD_LIBRARY_PATH` (host paths) (`SandboxShell.kt:178-182`) - whether they leak into the guest `process.env` is UNVERIFIED (run `env` in the terminal). cwd = `/workspace` when a project is bound (`ProotLauncher.kt:43-46`). No pid (Android `Process`), RSS via `/proc` match (`APP/lsp/SandboxServerLauncher.kt:79-90`, `APP/lsp/ProcTree.kt`). |
| host.transport | Have | `LSP/jsonrpc/JsonRpcConnection.kt:50-150` (JSON-RPC 2.0, both directions, per-request job, `$/cancelRequest` both ways `:118,260,292`), `LSP/jsonrpc/Framing.kt:27-29` (8 KB header, 64 MB body, depth 256 from `LSP/LspPolicy.kt:13-24`) | Reuse as-is (ADR 0030 dec. 3). Protocol draft invents `params.cancelId` + `$/cancel` (`docs/extension-host/arch.md:35`) - redundant with the existing `$/cancelRequest {id}`. Every `request()` needs a timeout (`:122`); notifications are pumped one at a time in wire order (`:50-56`) - fine for `doc/changed`, a bottleneck for chatty `decoration/set`. `:lsp` must stay Android-free (rules R-ENG-01). |
| host.loader | Missing | - | New `exthost.js` (CommonJS `require('vscode')` interception). Nothing in repo. |
| host.activation | Partial | `SCH/manifest/ExtensionDescriptor.kt:22-47` (onLanguage, onCommand, onView, onStage, workspaceContains, onStartupFinished; `*` -> onStartupFinished), `EXT/host/ActivationManager.kt:51-182` (per-id mutex, states `:22`, `Activator` interface `:35-39`), emit sites `EXT/ExtensionsRuntime.kt:128-139,201`, `workspaceGlobs()` `:157-158` | Add an `Activator` for the Node host (it already has `handles/activate/deactivate`). Needs: raw event strings (VS Code has ~25 kinds), implicit events from `contributes`, a Node-specific timeout key. `workspaceContains` scan bounded by `ExtensionPolicy.WORKSPACE_SCAN_MAX_FILES=20000` (`SCH/ExtensionPolicy.kt:14`). |
| host.lifecycle | Partial | `EXT/host/ActivationManager.kt:22` (DISABLED..CRASH_DISABLED), LSP session state machine with restart backoff `LSP/session/SessionStateMachine.kt` + `LspPolicy.kt:50-60` (shutdown grace 2 s, crash window 300 s, backoff cap) | Model the host process on `LspSession`/`ServerInstance` (spawn, initialize, shutdown, backoff) and per-extension state on `ActivationManager`. |
| host.crash | Partial | `EXT/host/CrashJournal.kt:36-60` (fsynced begin/end around REGISTER/ACTIVATE; 2 consecutive -> safe mode, `SCH/ExtensionPolicy.kt:12`), `EXT/host/ActivationManager.kt:111-127` (`reportCrash`, crash window -> CRASH_DISABLED persisted) | Host death = `reportCrash` for every active extension of that host; attribute by last `journal.begin`. Crash keys are `extensions.wasm.*` (`ExtensionSettings.kt:32-33`) - need host-neutral keys (R-ENG-07/08). |
| host.memory | Partial | `LSP/manager/MemoryPolicy.kt:5-70` (kill order IDLE -> NOT_VISIBLE -> PressureHooks -> FOCUSED over `SessionView` only), `MemoryPressureHook` `:31-33`, `APP/lsp/LspRuntime.kt:122-160` (onTrimMemory mapping), budget `lsp.globalMemoryBudgetMb`=1200, `lsp.maxServers`=3 (`APP/data/settings/LspSettingsSchema.kt:52-65`), WASM trim `APP/extensions/wasm/WasmRuntime.kt:203-204,313-314`, parked workspaces `APP/session/ParkPolicy.kt` | ADR 0030 says the host is "counted in the LSP memory budget ... first thing shed after idle servers". Kill order has no host slot: either register the host as a `MemoryPressureHook` (step 3) or add a `Victim.Host` step. RSS probe reusable (`ProcMemoryProbe`). 1200 MB default leaves little for Node+GitLens. |
| ext.deps | Missing | top-level `additionalProperties:false` (`SCHJ:5`) rejects `extensionDependencies` | Needs manifest acceptance + ordered activation; built-in `vscode.git` stub (arch.md:161). |
| ext.pack | Missing | same (`SCHJ:5`) | Needs install-time expansion. |
| ext.kind | Missing | `extensionKind` rejected (`SCHJ:5`) | Everything is "workspace" in our model; can be ignored once accepted. |
| ext.enable | Have | `EXT/host/Enablement.kt:45-100` (OTHER_ENVIRONMENT, EXTENSIONS_OFF, SAFE_MODE, USER_DISABLED via `extensions.disabled` P-scope, NOT_IN_PROFILE, NEEDS_APPROVAL, CRASH_DISABLED, REVOKED) | Reuse for "disable (workspace)"; project-level disable is `extensions.disabled` in the project layer. |
| ext.update | Partial | registry update check only notifies (`APP/extensions/registry/RegistryService.kt:117-120`); rollback in `APP/extensions/install/LocalInstaller.kt:172-240` | No Open VSX version check (sec 9 not built). |
| ext.safemode | Have | `EXT/host/SafeModeState.kt:13-57`, `APP/data/settings/SafeModeState.kt:11-20`, bridge `APP/extensions/ExtensionsContainer.kt:278-292`, context key `isSafeMode` | Built-ins stay on in safe mode (`Enablement.kt:81-82`). |
| ext.killswitch | Partial | global `extensions.enabled` (`SCH/settings/ExtensionSettings.kt:18`), revocation -> `REVOKED` (`Enablement.kt:80`) | ADR 0030 dec. 6 per-environment host kill switch + state wipe: not implemented (no "kill switch" anywhere in code). |
| ext.api | Missing | inventory exists: `EXT/ExtensionsRuntime.kt` `extensions.loaded/enabled`, `ExtensionDescriptor.kt:58-86` | `extensions.getExtension/all` served in-host from `ExtDesc` (arch.md:157); needs raw `packageJSON`, which our descriptor does not keep (parsed/typed only). |
| ext.context | Partial | pieces: storage port, `guestRoot` (`ExtensionDescriptor.kt:80-85`), logs | See storage.*, secrets, log.output. |
| storage.memento | Partial | `APP/extensions/wasm/WasmStoragePort.kt:5-20` (per ext x {global, env-<id>, project-<id>} JSON, temp+fsync+rename, quota `extensions.storage.quotaKb`) | `globalState` = global, `workspaceState` = project scope. `setKeysForSync` n/a. |
| storage.paths | Partial | extension dir in guest `/opt/easyide/extensions/<id>` (`SCH/manifest/ExtensionDescriptor.kt:84`, `SBX/SandboxPaths.kt:128`); storage files live on the host side only | `globalStorageUri`/`storageUri`/`logUri` need guest-visible dirs (e.g. under `/root/.easyide/...` or a new bind). Not defined anywhere. |
| secrets | Missing | `NoSecrets` (`APP/extensions/wasm/WasmSmallPorts.kt:40-47`); Keystore AES-GCM in `SBX/git/GitCredentials.kt:14-24,74,87-90` | Build `ExtensionSecrets` (wasm-host.md sec 13 "future work") on the GitCredentials pattern; namespace by ext id; must never expose git tokens. |

### 2.2 Configuration, files, documents, workspace

| Id | Status | Exists today (file:line) | Reuse point / notes |
|---|---|---|---|
| config.model | Have | layers `BUILT_IN, EXTENSION, USER, ENVIRONMENT, PROJECT` (`APP/data/settings/LayerDoc.kt:10`), resolver `APP/data/settings/SettingsResolver.kt:38-100`, contributed schema from `contributes.configuration` (`APP/data/settings/ContributedSettings.kt:21-60`, wired `APP/extensions/ExtensionsContainer.kt:226-230`), JSON-schema subset validator `APP/data/settings/SchemaValidator.kt`, port `SCH/settings/SettingsPort.kt:37-53` (value/write, targets user/language/environment/project `:19-24`) | `getConfiguration(section)` = resolve every key under the prefix; `config.update` -> `SettingsPort.write`. Scope mapping: application/machine -> G, machine-overridable -> E, language-overridable -> L, **window and resource -> P** (`ContributedSettings.kt:49-53`); no folder layer (single root). |
| config.inspect | Partial | `Resolved(value, winner: Provenance(layer, language, source), shadowed)` (`SettingsResolver.kt:19-22`), `SettingsSnapshot.inspect` (`:115`) | Map BUILT_IN/EXTENSION -> defaultValue, USER -> globalValue, PROJECT -> workspaceValue; ENVIRONMENT has no VS Code slot; `workspaceFolderValue` always undefined. |
| config.langOverride | Have | `SettingScope.L` + `[lang]` blocks (`APP/data/settings/Setting.kt:18-28`, resolver `:45-47`) | `getConfiguration(section, {languageId})` direct. |
| config.defaults | Have | `configurationDefaults` incl. `[lang]` (`SCH/contrib/Contributions.kt:98-99`, `SCH/manifest/ContributesDecoder.kt:112-121`) | EXTENSION layer. |
| fs.api | Partial | host-side `SBX/files/ProjectFiles.kt:28-254` (project-relative CRUD, atomic write), WASM `fs.*` (`WSM/host/HostFunctionTable.kt:68-80`) | For the Node host `workspace.fs` on `file:` can use Node `fs` in the guest (ADR 0030 dec. 5) - no bridge needed. |
| fs.provider | Missing | - | Protocol drafts `fsp/*` (arch.md:108); nothing in app routes non-`file` schemes. |
| fs.watch | Partial | `SBX/files/ProjectFileWatcher.kt:27-79` (Android `FileObserver`, expanded dirs only, 400 ms); LSP capability advertised but never sent (`LSP/protocol/ClientCapabilities.kt:139-142`); settings file watcher `APP/data/settings/SettingsFiles.kt:3` | Inside the guest, Node `fs.watch`/chokidar on bind-mounted `/workspace` via proot inotify pass-through: UNVERIFIED (test `node -e "require('fs').watch('/workspace',console.log)"` + touch on device). Fixing LSP `didChangeWatchedFiles` and the host `workspace/fileEvents` need one project-wide watcher. |
| doc.model | Partial | `LSP/docs/DocumentStore.kt:18-84` (`DocSnapshot{uri, openId, languageId, version, text, saveCount}`, canonical URIs, one per (env, project)), `LSP/text/LineIndex.kt:12-54` (UTF-16 positions), dirty/external state `APP/session/ExternalChange.kt:7-30` | Source of truth for `doc/opened|changed|saved|closed`. Missing: `eol`, `isDirty` (lives in the tab model), `isUntitled`, encoding. |
| doc.sync | Partial | `LSP/session/DocumentSync.kt:31-60` (reconcile snapshots -> didOpen/didChange/didSave/didClose, debounced flush), `LSP/text/TextDiff.kt:11-60` (single range diff) | Host sync = one more consumer of `DocumentStore.snapshots` (same as a session). Changes are coalesced (one range per flush); VS Code sends exact per-edit `contentChanges` - some extensions care (UNVERIFIED impact). |
| doc.content-provider | Missing | read-only env files via `PathMapper` (ADR 0017:65-66) are real files, not virtual docs | Needs a scheme router in the editor. |
| doc.encoding | Missing | UTF-8 decode `SBX/files/ProjectFiles.kt:110`; EOL display only `StatusFacts.kt:42-45`; limits `SBX/files/FileContent.kt:39-72` (256 KB edit, 8 MB view, 2 MB LSP full-sync `LSP/LspPolicy.kt:30`) | `TextDocument.eol`, `encoding` unsupported; large files never become documents. |
| workspace.folders | Partial | single root `/workspace` (`SBX/SandboxPaths.kt:62,149`, `SCH/action/HostPort.kt:25-27`), LSP `workspaceFolders` (`LSP/session/ServerRequests.kt:69`) | `workspace.workspaceFolders` = one folder; `workspaceFile` undefined. Multi-root planned as `/workspace/<name>` binds (ADR 0017:67-68). |
| workspace.trust | Partial | `APP/data/settings/ProjectTrust.kt:1-31,106-120` (exec-bearing project settings need consent; TrustState) | `workspace.isTrusted` = `TrustState.TRUSTED or NOT_REQUIRED`; restricted-mode capabilities (`capabilities.untrustedWorkspaces`) not modelled. |
| workspace.edit | Have | `LSP/workspace/WorkspaceEditApplier.kt` (documentChanges + create/rename/delete, `ClientCapabilities.kt:131-136`), `APP/extensions/host/AppHostPort.kt:112-121` | `workspace.applyEdit` -> same applier. |
| workspace.find | Partial | quick-open index `APP/ui/screens/workspace/files/FileIndexer.kt:47-60` (bounded `MAX_FILES`), text search absent: "A text search inside files needs a search engine the app does not have yet" (`APP/ui/shell/workspace/SearchPanel.kt:36-40`) | `findFiles`: in-guest glob in JS is simpler. `findTextInFiles`: Missing (no rg in rootfs). |
| workspace.save | Have | commands `workbench.action.files.save/saveAll` (`APP/ui/commands/Command.kt:69-70`), `willSaveWaitUntil` pipeline (`LSP/client/LspClient.kt:244-253`) | `onWillSaveTextDocument.waitUntil` can join the same pipeline. |

### 2.3 Commands, context, env

| Id | Status | Exists today (file:line) | Reuse point / notes |
|---|---|---|---|
| commands.registry | Partial | `APP/ui/commands/Command.kt:28-61` (`Command{id,title,category,enabled,runWithArgs}`, built-ins win: `plus` `:59-60`), manifest commands `SCH/contrib/Contributions.kt:60-63`, runtime dispatch `EXT/ExtensionsRuntime.kt:142,186-208` (`CommandHandler.Declarative` or `.Logic`) | Add `CommandHandler.Host` + dynamic registration of undeclared ids (VS Code allows it); `executeCommand` result values (ours returns `ActionOutcome`). `getCommands` = registry ids. |
| commands.builtin | Partial | 33 shared ids `services/shared/extension-schema/builtin-commands.json:4-36`, `APP/ui/commands/Command.kt:67-140` (+ EDITING/STAGE sets not exposed to manifests `:112-128`) | Missing the API-level built-ins: `vscode.open`, `vscode.openWith`, `vscode.diff`, `setContext`, `vscode.executeXxxProvider` (completion, hover, definition...), `revealInExplorer`, `workbench.action.openSettings`, `workbench.extensions.installExtension`. Our `edit.undo`/`edit.redo` differ from VS Code `undo`/`redo` (`Command.kt:103-104`). |
| env.info | Partial | WASM `host.info` (locale, abi) `WSM/host/HostFunctionTable.kt:43-47`; ADR sets appName/uiKind/machineId/sessionId (arch.md:54) | machineId/sessionId generation not present. `language` from app locale. |
| env.clipboard | Have | `APP/extensions/wasm/WasmSmallPorts.kt:14-37` (`AndroidClipboard`), capability `clipboard` (`SCH/capability/Capability.kt:20`) | Direct. |
| env.openExternal | Have | `APP/extensions/host/AppHostPort.kt:140-147` (confirm then open) | Direct; `asExternalUri` identity. |
| env.uri-handler | Missing | - | Needs an Android intent filter + `onUri` activation. |
| env.shell | Partial | interactive shell is `/bin/sh` as guest root (`SBX/shell/SandboxShell.kt:100-113,185`) | `env.shell` = `/bin/sh`; many extensions assume bash. |
| net.proxy | Missing | no proxy handling; guest env cleared (`SandboxShell.kt:159-171`) | Node in guest uses device network directly; `http.proxy` settings not propagated. |
| l10n | Partial | `%key%` from `package.nls[.<locale>].json` (`SCH/manifest/Nls.kt:9-60`, used `ManifestParser.kt:59`) | `vscode.l10n.t` + `l10n/bundle.l10n.*.json` runtime bundles: Missing (the `l10n/` dir is only tolerated `ManifestParser.kt:173`). |
| telemetry | Missing | - | ADR: no-op `TelemetryLogger` (arch.md:158). Trivial. |
| log.output | Partial | per-extension log `APP/extensions/ExtensionLogRing.kt:22-42`, `SCH/action` `ExtensionLog/LogEntry`, LSP `LSP/session/LogRing.kt` | `OutputChannel`/`LogOutputChannel` need named channels (UI surface separate). |
| process.spawn | Partial | Kotlin-side `ExecRequest` (`SCH/action/HostPort.kt:49-52`) via `AppHostPort.exec` (`AppHostPort.kt:85`); WASM `sandbox.exec` (`HostFunctionTable.kt:124-125,150-163`) | In the Node host `child_process` runs natively in the guest (ADR 0030 dec. 5) - no bridge. Git token never in host env (arch.md:26-27). |
| native.modules | Missing | nothing; glibc arm64 rootfs (Ubuntu 24.04) | `.node` prebuilds work only for linux-arm64 builds; Open VSX `targetPlatform` selection not designed; layout rules (no nested archives/symlinks) will refuse many. UNVERIFIED per extension. |

### 2.4 Tasks, terminal, git/SCM, auth

| Id | Status | Exists today (file:line) | Reuse point / notes |
|---|---|---|---|
| tasks.api | Partial | `.easyide/tasks.json` (VS Code 2.0.0 shape, shell/process) `APP/extensions/adapters/Tasks.kt:12-82`; `runTask` action / `HostPort.runTask` (`AppHostPort.kt:102`); `taskDefinitions` parsed only (`SCH/manifest/ContributesDecoder.kt:228-233`) | `tasks.registerTaskProvider`/`executeTask` need a provider hook; file is `.easyide/tasks.json`, not `.vscode/tasks.json`. |
| tasks.problem-matchers | Partial | `problemMatchers` decoded, raw JSON kept (`SCH/contrib/Contributions.kt:159-160`, `ContributesDecoder.kt:235-246`); "problem matchers are not applied yet" (`Tasks.kt:38`) | Engine missing; `problemPatterns` point rejected by schema (`SCHJ:48`). |
| terminal.api | Partial | PTY via vendored Termux JNI `services/mobile/terminal-emulator/src/main/java/com/termux/terminal/JNI.java:26-29`, `SBX/shell/SandboxShell.kt:63-113` (command/interactive pty params), `TerminalPort` (`SCH/action/HostPort.kt:41-46`, `AppHostPort.kt:76-83`) | `createTerminal({name, shellPath, cwd, env})` maps to `commandPtyParams` (`SBX/LinuxEnvironment.kt:246-256`). `sendText` = `TerminalPort` write. |
| terminal.pty | Missing | - | `Pseudoterminal` needs a non-process terminal backend feeding `TerminalEmulator`; UNVERIFIED how much of `terminal-view` can be fed from bytes. |
| terminal.shell-integration | Missing | - | |
| terminal.env-collection | Missing | - | Could extend `extraEnvironment` of `commandPtyParams`. |
| terminal.profiles | Missing | - | |
| terminal.links | Missing | - | |
| git.api | Partial | JGit in-process `SBX/git/GitRepository.kt:1-40`, `SBX/git/GitService.kt:21-150` (status, stage, commit, branches, remotes, stash, diffs); network via guest git with inline helper + `EASYIDE_GIT_TOKEN` (`SBX/git/GitCommandLine.kt:55-65`, ADR 0012) | ADR plans an in-host `vscode.git` stub over the guest `git` binary (arch.md:161); our Kotlin GitService is not reachable from the host. Authenticated push/fetch from an extension would lack the token (by design). |
| scm.api | Missing | native SCM UI only (menu ids `scm/title`, `scm/resourceState/context` exist `SCH/contrib/MenuIds.kt:15-16`) | `SourceControl` provider model absent. |
| auth.api | Missing | - | |
| auth.github | Partial | per-host PAT store `SBX/git/GitCredentials.kt:24-60` | Not exposed to extensions (deliberately, ADR 0012); no OAuth/device flow. |

### 2.5 Language features

Existing LSP method table: `LSP/protocol/LspMethods.kt:19-64`; features enum `LSP/protocol/ServerCapabilities.kt:13-27`;
facade `LSP/client/LspClient.kt:72-304`. Routing policy per feature (lsp-features.md 1.1) is implemented as:

| Policy | Code | Features |
|---|---|---|
| merge (parallel, errors = empty) + dedupe (first server wins by key) | `FeatureRouting.kt:33-44,86-89` | completion (no dedupe, `LspClient.kt:116-126`), hover (`distinctBy contents` `:135-138`), references (`uri+range` `:140-143`), documentHighlight (`range`), documentSymbol (`name+selectionRange`), workspaceSymbol (`name+location`), codeAction (`title+kind` `:157-166`), inlayHint (`position+text`), foldingRange (`start+end line`), codeLens (`range+title`), documentLink (`range+target`) |
| firstNonEmpty (ordered) | `FeatureRouting.kt:47-60` | definition, declaration, typeDefinition, implementation (`LspClient.kt:199-202,285-286`), signatureHelp (`:204-206`), selectionRange (`:208-211`) |
| single owner (`editor.defaultFormatter` preferred) | `FeatureRouting.kt:66-75` | rename/prepareRename (`:216-227`), formatting/rangeFormatting/onTypeFormatting (`:229-242`), willSaveWaitUntil (`:249-253`), semanticTokens (`:256-265`) |

Session order = ready first, priority desc, config order (`FeatureRouting.kt:22-24`). Results carry `FromServer(server, value, version)`
so resolve/executeCommand go back to the producer (`:20`, `LspClient.kt:129-133,169-174,270-273`).

| Id | Status | LSP path | Extension-provider path / notes |
|---|---|---|---|
| lang.router | Partial | above | Only `ExtensionProviders` (completion, hover) merged in presenters with a fake `ServerKey(env, project, "extension:"+id)` (`CompletionController.kt:208-211,278`); provider items are forced `resolved=true`, `command=null` - VS Code providers need resolve + commands. Refactor target: a `FeatureSource` interface implemented by `LspSession` and by a host-provider adapter, selected by `DocumentSelector` (language/scheme/pattern) instead of languageId only (`LspClient.kt:280`). |
| lang.completion | Partial | Have | WASM/ext: completion only, no resolve. |
| lang.hover | Partial | Have | WASM/ext: yes. |
| lang.definition | Partial | Have (+declaration/typeDefinition/implementation) | no ext path |
| lang.references | Partial | Have | no ext path |
| lang.symbols | Partial | Have | no ext path |
| lang.workspace-symbols | Partial | Have (per language, palette `#`) | no ext path; VS Code queries all providers regardless of language |
| lang.code-action | Partial | Have (+resolve) | no ext path; `CodeActionProviderMetadata.providedCodeActionKinds` not modelled |
| lang.code-lens | Partial | Have (+resolve) | no ext path |
| lang.formatting | Partial | Have (doc/range/onType, defaultFormatter by *server id*) | `editor.defaultFormatter` values are extension ids in VS Code (`publisher.name`) - mapping needed |
| lang.rename | Partial | Have (+prepare) | no ext path |
| lang.signature-help | Partial | Have | no ext path |
| lang.highlight | Partial | Have | no ext path |
| lang.links | Partial | Have (+resolve) | no ext path |
| lang.folding | Partial | Have (limit 5000 `LspPolicy.kt:87`) | no ext path |
| lang.selection-range | Partial | Have | no ext path |
| lang.inlay-hint | Partial | Have (+resolve, refresh) | no ext path |
| lang.semantic-tokens | Partial | Have (full/delta/range, `LSP/client/SemanticTokensCache.kt`) | no ext path; legends per provider |
| lang.color | Missing | not in `LspFeature` | - |
| lang.call-hierarchy | Missing | - | - |
| lang.type-hierarchy | Missing | - | - |
| lang.linked-editing | Missing | - | - |
| lang.inline-completion | Missing | - | - |
| lang.inline-values | Missing | - (debug-only) | - |
| lang.drop-paste | Missing | - | - |
| lang.set-language | Partial | detection `TextMateHighlighter.languageIdFor` (`APP/ui/screens/workspace/syntax/TextMateHighlighter.kt:135`), `LanguageConfigs` name maps (`.../syntax/LanguageConfigs.kt:60-71`) | `setTextDocumentLanguage` needs re-keying `DocSnapshot.languageId` (store has no API for it). |
| lang.language-config | Partial | `APP/ui/screens/workspace/syntax/LanguageConfigs.kt:95-150` reads comments, brackets, autoClosingPairs, surroundingPairs, autoCloseBefore, indentationRules (increase/decrease), onEnterRules | **Bug vs VS Code:** regexes read only in object form (`optJSONObject("increaseIndentPattern")`, `getJSONObject("beforeText")` `:98,108-109,134,149`); VS Code accepts `string \| IRegExp` (`$VS/src/vs/workbench/contrib/codeEditor/common/languageConfigurationExtensionPoint.ts:29,41`) and its own configs use strings (`$VS/extensions/typescript-basics/language-configuration.json:229`). String `beforeText` throws -> whole config falls back to GENERIC (`:90-92`). `wordPattern`, `folding.markers`, `indentationRules.indentNextLinePattern/unIndentedLinePattern`, `colorizedBracketPairs` not read. `vscode.languages.setLanguageConfiguration`: Missing. |
| lang.grammar | Have | TextMate highlighter with extension grammars/injections/embedded languages (`APP/extensions/ExtensionsContainer.kt:208-210`, `SCH/contrib/Contributions.kt:106-109`), slow-line guard 50 ms (`SCH/ExtensionPolicy.kt:20`) | tree-sitter is a separate Tier-0 path (ADR 0009). |
| lang.snippets | Have | `APP/extensions/adapters/Snippets.kt`, `SnippetBody.kt` (wired `ExtensionsContainer.kt:212-218`) | `SnippetString` for `insertSnippet` can reuse `SnippetBody`. |
| lang.json-validation | Missing | settings JSON has its own schema completion/diagnostics (`APP/data/settings/SettingsJsonCompletion.kt`, `SettingsJsonDiagnostics.kt`) | `jsonValidation` point absent (schema rejects). |
| diagnostics | Partial | `LSP/diagnostics/DiagnosticStore.kt:23-43` (uri -> ServerKey -> DiagnosticSet, push+pull `LspMethods.kt:52`) | `DiagnosticCollection` = synthetic `ServerKey(env, project, "ext:<id>/<collection>")`; `clearServer` = dispose. |
| tests.api | Missing | - | M8 "Gap 3" (m8-gap-analysis.md:36,56). |
| comments.api | Missing | - | not planned in arch.md:139. |

## 3. When-clauses and context keys

| Aspect | Today | VS Code |
|---|---|---|
| Parser | `SCH/whenclause/WhenLexer.kt:25-60`, `WhenParser.kt`, AST `WhenExpr.kt:7-84` | - |
| Operators | `!`, `&&`, `\|\|`, `()`, `==`, `!=`, `<`, `<=`, `>`, `>=`, `=~ /re/imsu`, `in`, `not in`, `true/false`, quoted strings, numbers (`WhenLexer.kt:44-58`, `WhenExpr.kt:40-84`) | also `===`, `!==` (`$VS/src/vs/platform/contextkey/common/scanner.ts:38-39,127-130`). Ours throws "unexpected character '='" -> `E_WHEN_SYNTAX` **error** (`SCH/manifest/DecodeContext.kt:41-44`) -> manifest invalid. |
| Service | `SCH/whenclause/ContextKeyService.kt:59-100` (`set`, `setRaw` any name `:70`, `setAll`, `observe(expr)` `:92`), snapshot `with(overrides)` for item-scoped keys `:45-47` | `setContext` can map to `setRaw` directly; `config.*` resolves live through `SettingsPort` (`:32-36`). |
| Known keys | 37 fixed + 7 parameterised prefixes (`SCH/whenclause/ContextKeys.kt:33-100`) | Unknown keys only warn (`DecodeContext.kt:46-48`); VS Code keys we lack: `view`, `viewItem`, `resourceScheme`, `isWindows/isLinux/isMac`, `activeEditor`, `editorIsOpen`, `textInputFocus`, `inputFocus`, `listFocus`, `sideBarVisible`, `panelFocus`, `activeViewlet`, `scmProvider`, `workspaceFolderCount`, `workbenchState`, `isWeb`, `remoteName`, `resourceLangId` (have), `gitOpenRepositoryCount`... (list the full set from `$VS` in the UI audit). `view`/`viewItem` matter most (`view/item/context` menus). |

## 4. Activation manager and contributions: detail

- Activators today: WASM host and language-server supervisor (`EXT/host/ActivationManager.kt:30-39` doc); declarative
  contributions never wait for activation. `ensureActive` backs `onCommand` (`:95-99`, `EXT/ExtensionsRuntime.kt:199-208`).
- Registry: typed store per point (`EXT/contrib/ContributionRegistry.kt:51-78`), first-wins conflict resolution with
  inspector (`EXT/contrib/ContributionResolver.kt:63-194`), languages merged by id (`:181-191`).
- Consumers wired in the app (`APP/extensions/ExtensionsContainer.kt:199-253`): languages+grammars+language
  configs -> TextMate; snippets -> SnippetCatalog; keybindings -> keymap; configuration(+Defaults) -> settings
  registry; themes+iconThemes -> theme catalog; views/containers -> shell (`APP/extensions/adapters/ShellContributions.kt`);
  statusBarItems (`adapters/StatusItems.kt`), menus (`adapters/MenuModel.kt`), keyRows, languageServers
  (`adapters/ContributedServers.kt:164`). **Parsed but not consumed** (grep: only the extension details list
  `APP/ui/screens/extensions/ExtensionsViewModel.kt:257`): `viewsWelcome`, `taskDefinitions`, `problemMatchers`, `walkthroughs`.

Contribution points (VS Code names) - full data in the JSON `contributes` array:

| Point | Status | Evidence | Notes |
|---|---|---|---|
| commands | Partial | `SCH/manifest/ContributesDecoder.kt:39-50`, `SCHJ:128` | `icon` string only (token or package SVG), `{light,dark}` object rejected; id-prefix warn `DecodeContext.kt:145-147` |
| menus | Partial | `ContributesDecoder.kt:52-69`, `SCH/contrib/MenuIds.kt:4-26` | 16 ids (incl. `editor/touchToolbar`, `keyRow`); unknown ids warn+ignored; no `submenu` items (`SCHJ:137`) |
| submenus | Missing | `SCHJ:48` | rejected |
| keybindings | Partial | `ContributesDecoder.kt:71-76`, `APP/extensions/adapters/ContributedKeybindings.kt:13-42` | `linux` > `key`; `mac`/`win` ignored; <=2-press chords; terminal opt-in by `terminalFocus` in `when` |
| languages | Have | `ContributesDecoder.kt:123-137` | `icon` key rejected by `SCHJ:84` |
| grammars | Have | `ContributesDecoder.kt:140-147` | injectTo, embeddedLanguages, tokenTypes |
| snippets | Have | `ContributesDecoder.kt:149-152` | |
| themes | Have | `ContributesDecoder.kt:154-157`, `APP/extensions/adapters/ColorThemes.kt` | vs / vs-dark / hc-black / hc-light |
| iconThemes | Have | `ContributesDecoder.kt:159-162`, `VsCodeIconThemeAdapter.kt` | only point a raw `.vsix` can install today |
| productIconThemes | Missing | `SCHJ:48` | |
| configuration | Have | `ContributesDecoder.kt:79-110` | unvalidated schema keywords warn (`:96`); array-of-sections form handled |
| configurationDefaults | Have | `ContributesDecoder.kt:112-121` | |
| views | Partial | `ContributesDecoder.kt:204-217` | schema view (R4 `viewSchema:1`) or legacy list; no provider-driven lazy tree |
| viewsContainers | Partial | `ContributesDecoder.kt:164-202` | `activitybar`, `panel` + shell's `sidebar`, `secondarySidebar` |
| viewsWelcome | Partial | `ContributesDecoder.kt:224-226` | parse-only |
| customEditors | Missing | | not planned (ADR 0030:73-74) |
| walkthroughs | Partial | `ContributesDecoder.kt:248-270` | parse-only; video ignored |
| colors | Missing | `SCHJ:48` | rejected - common in themes/GitLens |
| icons | Missing | `SCHJ:48` | |
| jsonValidation | Missing | `SCHJ:48` | |
| taskDefinitions | Partial | `ContributesDecoder.kt:228-233` | parse-only |
| problemMatchers | Partial | `ContributesDecoder.kt:235-246` | parse-only |
| problemPatterns | Missing | `SCHJ:48` (a `problemPattern` def exists at `SCHJ:220` for inline use) | |
| terminal | Missing | | profiles |
| debuggers / breakpoints | Missing | | not planned (ADR 0030:73) |
| resourceLabelFormatters | Missing | | |
| semanticTokenTypes/Modifiers/Scopes | Missing | | |
| authentication | Missing | | |
| codeActions | Missing | | |
| typescriptServerPlugins | Missing | | |
| localizations | Missing | | M8 Gap 2 |
| easyIDE-only (`easyide.*`) | Have | `SCH/manifest/EasyideDecoder.kt:23-110`, `UiDecoder.kt` | stages, statusBarItems, keyRows, languageServers, sandbox, viewData, navigation, documents, layoutPresets, actions, wasm |

## 5. Capability model

`SCH/capability/Capability.kt:11-64`: closed sealed set `sandbox.exec`, `sandbox.install`, `fs.outsideProject`,
`lsp.spawn`, `lsp.request`, `clipboard`, `ui.stage`, `ui.settings`, `ui.contribute`, `secrets.read`,
`fs.project(read|write)`, `network(hosts)`. **`host.run` does not exist**; `parse` returns null for unknown ids and
the loader errors (`SCH/manifest/ManifestChecks.kt:164`). Granted = declared AND approved (`EXT/host/Enablement.kt:95-99`);
approval stored per install (`Enablement.kt:32`). For a `.vsix` the capability must be *synthesized* at import
(the manifest cannot declare `easyide.capabilities`). Capability rules are "disclosure, not enforcement" in proot
(ADR 0013:64-67).

## 6. Built-in packs (`AST/extensions/*/package.json`)

All declare `engines.easyide`; top-level keys: name, publisher, version, displayName, description, license, categories,
engines (+ `activationEvents`, `contributes`, `easyide` where listed).

| Pack | contributes | easyide | capabilities | activationEvents |
|---|---|---|---|---|
| core-snippets | snippets | keyRows | - | - |
| cpp | - | languageServers, sandbox, memoryBudgetMb, capabilities | lsp.spawn, sandbox.install, network(ubuntu) | onLanguage:c, onLanguage:cpp |
| file-icons | iconThemes | - | - | - |
| git-commands | commands, keybindings, menus | actions, statusBarItems, capabilities | sandbox.exec | - |
| git-extras | commands, menus | actions, capabilities | sandbox.exec | - |
| go | commands, menus | actions, languageServers, sandbox, memoryBudgetMb | lsp.spawn, sandbox.exec, sandbox.install, network | onLanguage:go |
| key-rows | commands, configuration | actions, keyRows, statusBarItems | ui.settings | - |
| markdown | - | languageServers, sandbox, memoryBudgetMb | lsp.spawn, sandbox.install, network(npm, ubuntu) | onLanguage:markdown |
| material-icons | iconThemes | - | - | - (vendored Material Icon Theme 5.38.1, ADR 0029) |
| project-tasks | commands, menus | actions | sandbox.exec | - (uses `node -e` via sandboxExec, `package.json:56`) |
| python | commands, configuration, configurationDefaults, grammars, keybindings, languages, menus, snippets | actions, languageServers, sandbox, statusBarItems, memoryBudgetMb | sandbox.exec, sandbox.install, lsp.spawn, network(npm, pypi) | onLanguage:python, workspaceContains:**/pyproject.toml |
| rust | - | languageServers, sandbox, memoryBudgetMb | lsp.spawn, sandbox.install, network(rustup) | onLanguage:rust |
| shell | - | languageServers, sandbox | lsp.spawn, sandbox.install, network | onLanguage:shellscript (installs `nodejs npm shellcheck`, `package.json:41-42`) |
| tablet-toolbar | menus | - | - | - |
| themes | themes | - | - | - |
| toggles | commands | actions, statusBarItems | ui.settings | - |
| typescript | commands, menus | actions, languageServers, sandbox | lsp.spawn, sandbox.exec, sandbox.install, network | onLanguage:typescript, javascript, **tsx, jsx** (VS Code ids are `typescriptreact`/`javascriptreact`; `LanguageConfigs.kt:68-71` maps names) |
| web | - | languageServers, sandbox | lsp.spawn, sandbox.install, network | onLanguage:html, css, scss, less |
| yaml | - | languageServers, sandbox | lsp.spawn, sandbox.install, network | onLanguage:yaml (installs `nodejs npm`, `package.json:40-42`) |

Samples (`AST/extension-samples/`): sample-agents, sample-chat, sample-docker (R4 schema views).

## 7. WASM layer (L2) - relevance

`WSM/host/HostFunctionTable.kt:42-139` exposes `host.*, log.write, editor.{active,getText,applyEdits,decorate},
fs.{read,stat,list,watch,write,delete,rename}, events.subscribe, config.{get,set}, ui.{setStatusBarItem,setViewData},
commands.{execute,register}, providers.register (completion|hover), lsp.{request,notify,status}, sandbox.{exec,kill},
clipboard.*, net.fetch, storage.*, secrets.get`, each gated by a `Rule` (`WSM/host/Rules.kt`). Chicory interpreter,
no WASI (ADR 0014). It cannot run VS Code extensions (ADR 0030:55-56), but its **ports** are the nearest existing
main-side implementations for storage, config, clipboard, status bar, view data, providers and command dispatch -
the Kotlin `:exthost` handlers should call the same `:app` ports (`APP/extensions/wasm/Wasm*Port.kt`) rather than
duplicate them.

## 8. Things that must change on our side

| # | Change | Why (evidence) | Size |
|---|---|---|---|
| C1 | A second manifest path for `.vsix`: tolerant reader of VS Code `package.json` (no `engines.easyide`, open top-level, mixed-case ids, `icon` objects, `submenu`, unknown points kept as data) that produces an `ExtensionDescriptor` + raw `packageJSON` | `SCHJ:5,35,48,128,137`; `ManifestParser.kt:61-79`; ADR 0013:44-46 rejected `.vsix` as native format; importer design drops `main`/commands (`registry-and-install.md:346-349`) - incompatible with ADR 0030 | L |
| C2 | Relax/branch package layout rules for `.vsix` (nested archives, symlinks inside `node_modules`, size limits) | `PackageFiles.kt:57,87-124`, `ExtensionSettings.kt:22-24` | M |
| C3 | Install scope: code extensions must be guest-visible; bind GLOBAL installs too (or force ENVIRONMENT) | `EnvironmentExtensionBinds.kt:20-28` vs `registry-and-install.md:352` | S |
| C4 | Host granularity: one host per (env, project), not per environment, or a host that is re-bound on project switch | ADR 0030:24 vs `ProotLauncher.kt:34-36`, ADR 0017 decision 3 | S (doc) / M (code) |
| C5 | Router abstraction: `FeatureSource` (LSP session or host provider) + `DocumentSelector` matching; move WASM/ext merging out of presenters into `LspClient` | `FeatureRouting.kt:30-90`, `CompletionController.kt:200-212`, R-ENG-17 (extract at third use - this is it) | M |
| C6 | Command registry: dynamic registration, `CommandHandler.Host`, return values, `setContext` and `vscode.*` API commands as a declarative table (no-hardcoding: a JSON like `builtin-commands.json` listing id -> handler kind) | `Command.kt:45-61`, `ExtensionsRuntime.kt:186-196`, `builtin-commands.json` | M |
| C7 | When-clause parity: accept `===`/`!==`; downgrade `E_WHEN_SYNTAX` for `.vsix` to a warning (drop the item, not the extension); add VS Code's context keys (`view`, `viewItem`, platform keys...) as a declarative key table | `WhenLexer.kt:44-58`, `DecodeContext.kt:41-44`, `ContextKeys.kt:33-100` | S |
| C8 | Activation: accept every VS Code event string (store raw, match generically), implicit events from contributions, `*` as real eager start, Node-specific `extensions.host.activateTimeoutMs` / crash keys (R-ENG-07/08 require settings keys) | `ExtensionDescriptor.kt:22-47`, `ActivationManager.kt:142`, `ExtensionSettings.kt:30-33` | S |
| C9 | Memory: give the host a slot in the one kill order (`MemoryPolicy`) and its own budget key; revisit 1200 MB / 3 servers defaults | `MemoryPolicy.kt:58-68`, `LspSettingsSchema.kt:52-65`, ADR 0030:62-64 | S |
| C10 | Node version: `apt-get install nodejs` on noble = Node 18.19 (EOL). Pin a supported Node (22 LTS) via a verified download (VerifiedDownloader + sha256 exists: `SBX/download/VerifiedDownloader.kt`) or NodeSource; record in ADR 0030 | packages.ubuntu.com/noble/nodejs; ADR 0030:25-27 | M |
| C11 | Documents: add `eol`, dirty, untitled, per-edit change events (or accept coalesced), encoding (at least UTF-8 BOM / UTF-16 detect), raise the 256 KB editable cap or expose large files read-only to extensions | `DocumentStore.kt`, `TextDiff.kt:30`, `ProjectFiles.kt:110`, `FileContent.kt:48-54` | M-L |
| C12 | File events: one project-wide watcher (guest inotify or host recursive) feeding `workspace/fileEvents`, LSP `didChangeWatchedFiles` (currently advertised and never sent) and WASM `fs.watch` | `ClientCapabilities.kt:139-142`, `ProjectFileWatcher.kt`, `WasmRuntime.kt:194-200` | M |
| C13 | Secrets: `ExtensionSecrets` on the Keystore pattern; add `host.run` to the closed `Capability` set + schema + validator; kill switch per environment that also wipes host storage | `WasmSmallPorts.kt:40-47`, `Capability.kt:43-62`, ADR 0030 dec. 6 | S-M |
| C14 | Language configuration: accept string regex forms, read `wordPattern`, `folding.markers`, remaining indentation rules (bug today even for Tier-1 packs) | `LanguageConfigs.kt:98,108-109,134,149` | S |
| C15 | Diagnostics/output/log: collections as synthetic `ServerKey`s; named output channels | `DiagnosticStore.kt:23-43`, `ExtensionLogRing.kt` | S |
| C16 | Protocol draft fixes (`docs/extension-host/arch.md`): reuse `$/cancelRequest` (not `$/cancel`+`cancelId`, line 35); `ExtDesc.packageJSON` must be the *raw* manifest (we only keep a typed descriptor); `host/initialize.configuration` snapshot of every section is large - send defaults + overrides; `workspace/getConfiguration` needs `languageId` in scope; `provider/register` selector needs `notebookType`/`pattern` as RelativePattern; add `tree/getParent`, `tree/resolveItem`; `doc/changed` must say whether ranges are coalesced; file content "base64" for binary `fs` only on non-file schemes | arch.md:35,54,78,105,112,144 | S |
| C17 | Docs in conflict with ADR 0030 need updating: m8-gap-analysis "Do not build M8 now" (`m8-gap-analysis.md:64-72`), ADR 0013's "`.vsix` is secondary, `main` dropped" (0013:44-46,68-69), ADR 0009 Tier ordering, glossary "L3 ... Conditional, M8" (`glossary.md:19`), sdk-reference activationEvents "Only affects L2 WASM and language servers" (`sdk-reference.md:73`), registry-and-install sec 9 accepted subset | as cited | S |
| C18 | Files over 600 lines forbidden (R-ENG-06): the `vscode` shim and `:exthost` handlers must be split per namespace from day one; `LspClient.kt` (304) and `LanguageServerManager.kt` (413) are the files the router refactor touches - keep splits | `rules.md:27` | - |
| C19 | No-hardcoding (R-ENG-07/08): compat tables (implemented API names, supported activation events, contribution points, context keys, command aliases, VS Code `scope` -> layer map now inline at `ContributedSettings.kt:49-53`) must be declarative data files generated into the published compatibility table (ADR 0030 consequences) | `rules.md:28-29` | S |

## 9. UNVERIFIED items and how to verify

| Item | How to verify |
|---|---|
| proot passes inotify for bind mounts (Node `fs.watch` in guest) | On device: `node -e "require('fs').watch('/workspace',{recursive:false},console.log)"`, then `touch /workspace/x` from the app editor save and from the terminal |
| guest sees `PROOT_LOADER`/`LD_LIBRARY_PATH` host paths | Terminal: `env \| grep -E 'PROOT\|LD_LIBRARY'` |
| noble-updates nodejs version | `apt-cache policy nodejs` in a fresh env, or https://launchpad.net/ubuntu/+source/nodejs |
| Node 18 EOL date / VS Code bundled Node | https://nodejs.org/en/about/previous-releases ; `$VS/.nvmrc` or `$VS/remote/.npmrc` `target` |
| impact of coalesced `contentChanges` on real extensions | corpus run in phase 6 |
| `.node` prebuild availability (linux-arm64 glibc) per extension | corpus scan of `*.node` ELF headers (`file`) in `$CACHE` |
