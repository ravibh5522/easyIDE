# Extension SDK - Registry and install (LLD)

Low-level design of registry index fetch and verification, signing trust, install/update/rollback/uninstall, the Open VSX adapter and the offline cache.

Status: PROPOSED (2026-09-23). Implemented so far (2026-09-24): sec 4.1 `Jcs` and sec 4.2 `KeyIds`/`SignatureVerifier`/`Ed25519` port, in `services/shared/extension-schema` package `dev.easyide.extensions.registry` (the shared library of sec 4.1, not `:extensions`). Sec 10 rollback for local (sideloaded) installs, in the app's `LocalInstaller.rollback` (not yet `InstallPipeline`): flips `current` to the retained previous version after re-validating it, re-asks for capabilities never approved for it, keeps the newer version as the new previous; `state.json` entries carry `previous{version, approvedCapabilities}` (optional, older files load); the revocation check is an injected predicate, wired to sec 6 below. App side of M6 (2026-09-24, `app/.../extensions/registry/`, not `:extensions`): sec 3 fetch/cache (`RegistryClient`, `HttpFetcher` port, `RegistryPolicy`), sec 5 `TrustStore` (pins in `state.json`), sec 6 revocation (disable on refresh, `isRevoked` for stage and rollback), sec 7 browse (Browse tab over the shared `Catalog`), sec 8 registry install up to commit (`RegistryInstaller` in front of `LocalInstaller`: no journal, no sandbox steps), sec 10 update check as a badge, sec 12 cache with LRU GC; Ed25519 on Android is Tink (0016 amendment). Not yet: journal/`recover()`, sandbox install/verify, capability-delta diff sheet, `.easyext.sig` sideloads, Open VSX (sec 9). Design context: [arch.md](../arch.md) sec 5.4, 6.2, 6.3 ("Install"), 9, 11, 12 (M3, M6).
Contract (package layout, index format, signatures, settings keys): [sdk-reference.md](../sdk-reference.md#registry-index-format).
Feature area: arch.md sec 5.4 (Ecosystem). Needs ADR-H (0016) before M6; install-from-file lands in M3.

## 1. Scope and responsibility

Lives in `:extensions` (`services/mobile/extensions`), package `dev.easyide.extensions.registry`
and `dev.easyide.extensions.install`. Owns:

- fetching, verifying and caching registry indexes (`RegistryClient`, `IndexCache`);
- RFC 8785 canonical JSON and ed25519 verification (`Jcs`, `SignatureVerifier`);
- publisher trust: root key, TOFU pins, rotation, revocation (`TrustStore`, `RevocationList`);
- search/browse model (`CatalogIndex`);
- the install pipeline, update, rollback, uninstall (`InstallPipeline`, `ExtensionStore`);
- the Open VSX adapter (`OpenVsxSource`, `VsixReader`);
- the offline package cache and on-disk layout (`ExtensionPaths`, `PackageCache`).

Not owned: manifest schema validation and contribution registration
([extension-runtime.md](extension-runtime.md)), WASM loading ([wasm-host.md](wasm-host.md)),
capability prompt UI (`app/.../ui/screens/extensions/`), CLI publishing ([cli.md](cli.md)).

Trust statement (repo rule): signatures prove *who published* and *that bytes were not
altered*. They do not make the package safe. Anything run by `sandbox.install`, language
servers or `sandboxExec` runs unconstrained in the environment; proot and chroot+BusyBox provide
no per-extension isolation (0002). UI copy says so on the capability sheet.

## 2. Data structures

```kotlin
data class RegistryConfig(val id: String, val url: String, val rootKey: PublicKeyB64)   // extensions.registries[]

data class IndexEntry(                       // one element of index.json "extensions"
    val id: String, val publisher: String, val name: String, val version: SemVer,
    val displayName: String?, val description: String?, val categories: List<String>,
    val license: String, val memoryBudgetMb: Int?, val engines: SemVerRange,
    val scope: ExtScope, val layers: Set<Layer>, val capabilities: List<String>,
    val url: String, val size: Long, val sha256: String, val publishedAt: Instant,
    val signature: Sig, val raw: JSONObject,      // raw kept: canonicalised for verification
)
data class Sig(val keyId: String, val alg: String, val sig: ByteArray)    // alg must be "ed25519"
data class PublisherKeys(val publisher: String, val keys: List<PubKey>, val rotation: List<Rotation>)
data class PubKey(val keyId: String, val publicKey: ByteArray, val added: LocalDate, val status: KeyStatus)
data class Rotation(val from: String, val to: String, val sigByOld: ByteArray)
data class Revocations(val updatedAt: Instant, val keys: List<RevokedKey>, val versions: List<RevokedRange>)

data class VerifiedIndex(                    // only ever constructed by RegistryClient.verify
    val registryId: String, val generatedAt: Instant, val fetchedAt: Instant,
    val entries: List<IndexEntry>, val revocations: Revocations,
)

enum class ExtScope { GLOBAL, ENVIRONMENT }
enum class Source { REGISTRY, OPEN_VSX, LOCAL_FILE, LOCAL_FOLDER_DEV }

data class InstallRecord(                     // <versionDir>/.easyide-install.json
    val id: String, val version: String, val source: Source, val registryId: String?,
    val sha256: String, val signedBy: String?,          // keyId, null = unsigned local
    val approvedCapabilities: List<String>, val approvedAt: Instant,
    val scope: ExtScope, val envId: String?,
    val sandboxSteps: List<StepRun>,                    // what actually ran, for undo + audit
    val compat: CompatReport?,                          // Open VSX only
)
data class StepRun(val id: String, val title: String, val command: String, val exitCode: Int?, val at: Instant)
```

`VerifiedIndex` has no public constructor: unverified data cannot reach the browse UI or the
install pipeline by construction ("eliminate, don't handle").

## 3. Index fetch

### 3.1 Sources

`extensions.registries` (G) lists `{id, url, rootKey}`; default is the first-party index,
`rootKey` shipped in the APK defaults. `url` is a static base: a git host raw URL (e.g.
`https://raw.githubusercontent.com/<org>/easyide-extensions-index/main/`) or any https mirror.
Only `https` is accepted. Open VSX is a separate source (sec 9), not an entry here.

Files fetched per refresh, in order: `revocations.json` + `.sig`, `index.json` + `.sig`;
`publishers/<p>.json` + `.sig` lazily, per publisher, when an entry by that publisher is shown
in detail or installed (and refreshed on each full refresh for publishers with installs).

### 3.2 Fetch procedure

```
refresh(registry):
  staging = ExtensionPaths.registryStaging(registry.id)       (fresh temp dir)
  for f in [revocations.json, revocations.json.sig, index.json, index.json.sig]:
      GET url/f  with If-None-Match: <cached ETag>            (HttpURLConnection)
      304 -> copy cached file into staging; 200 -> stream to staging, cap RegistryPolicy.maxIndexBytes
  verify(staging)                                              (sec 4)  -- failure: discard staging, keep old
  anti-rollback: new.generatedAt >= cached.generatedAt AND new.revocations.updatedAt >= cached
  atomic swap: rename staging -> ExtensionPaths.registryDir(id) (old dir renamed aside, then deleted)
  record fetchedAt, ETags in registry/<id>/meta.json
```

- Timeouts and caps are `RegistryPolicy` constants (one declarative table, sec 13).
- Network errors (the real boundary) leave the last verified index in place; the UI shows
  "Index from <age> ago" (`isOffline` when no connectivity); past
  `RegistryPolicy.staleIndexWarnDays` browse and update sheets add a staleness warning (R6).
- Anti-rollback prevents a mirror or MITM (TLS already assumed) from replaying an older,
  validly-signed index that predates a revocation.

### 3.3 Automatic checks

`extensions.autoCheckUpdates` (`off`/`daily`/`weekly`, default `weekly`): checked when the
Extensions screen opens or at app start if the last successful refresh is older than the period.
No background scheduler (no new dependency; WorkManager is not in the app). Checks only
notify, never install (sec 7).

## 4. Canonical JSON and signature verification

### 4.1 `Jcs` (RFC 8785)

Implemented in-house in the shared JVM library `services/shared/extension-schema/` (Kotlin, no
Android deps) so the app and `easyide-ext` produce byte-identical output ([cli.md](cli.md)).

- Objects: members sorted by key, comparing keys as UTF-16 code unit sequences.
- Strings: JSON escaping per RFC 8785 sec 3.2.2.2 (only `"`, `\`, control chars; `\b \t \n \f \r`
  short forms, others `\u00XX` lowercase hex); no escaping of non-ASCII or `/`.
- Literals `true`/`false`/`null` as-is; no whitespace.
- **Numbers**: the index schema permits only integers in `[-(2^53)+1, 2^53-1]` (`size`,
  `memoryBudgetMb`, `schemaVersion`). A non-integer or out-of-range number makes the document
  invalid. This removes ES6 double formatting from scope entirely.
- Parsing rejects duplicate keys (org.json silently keeps the last; `Jcs` parses with its own
  strict tokenizer).

Signed bytes: for an entry, `Jcs(entry minus "signature")`; for a `.sig` file, `Jcs(whole file)`.
Rotation record `sigByOld` signs `Jcs({"publisher","from","to"})` (defined here; summarised in sdk-reference).

### 4.2 `SignatureVerifier`

```kotlin
interface Ed25519 { fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean }
object KeyIds { fun of(rawPublicKey: ByteArray): String }   // first 16 hex chars of sha256(raw 32-byte key)
class SignatureVerifier(private val ed: Ed25519) {
    fun verifyFile(bytes: ByteArray, sig: Sig, key: PubKey): Boolean     // .sig over Jcs(file)
    fun verifyEntry(entry: IndexEntry, key: PubKey): Boolean
}
```

Ed25519 provider: JDK 15+ has `Signature.getInstance("Ed25519")` (used by the CLI on Java 17).
On Android the platform provider's Ed25519 availability across minSdk 26..current is **to
verify**; do not assume it. Candidate: Bouncy Castle lightweight `Ed25519Signer` (license and
maintenance **to verify** from bouncycastle.org and record in ADR-H before adoption). The
`Ed25519` interface keeps the choice swappable and testable with RFC 8032 test vectors.

### 4.3 Verification order (sdk-reference, unchanged; any failure is a hard stop)

1. `index.json.sig` and `revocations.json.sig` verify against the registry root key.
2. `publishers/<p>.json.sig` verifies against the root key; the entry's `signature` verifies
   against a key in it with `status: active` and not in `revocations.keys`.
3. TOFU pin check (sec 5).
4. Downloaded bytes match `size` and `sha256`; `engines.easyide` matches; capabilities shown.

Entries failing (2)-(3) are hidden from browse with a reason visible in developer mode; an
index failing (1) is not accepted at all.

## 5. TOFU pinning and rotation

`TrustStore` persists `pins: {registryId: {publisher: keyId}}` in `state.json`.

```
check(publisher, entryKeyId):
  pinned = pins[registry][publisher]
  if pinned == null:                 first install of this publisher on this device
      pin entryKeyId (written only when the install commits, not at browse)
  elif pinned == entryKeyId: ok
  else: walk rotation records from pinned:
      r = rotation.find(from == cur); verify r.sigByOld with key(cur); cur = r.to
      until cur == entryKeyId -> ok, re-pin to entryKeyId
      chain breaks or a key in the chain is revoked -> HARD FAIL "publisher key changed"
```

- Keys referenced by the chain must be present in `publishers/<p>.json` (old keys stay listed
  with `status: retired`).
- A revoked pinned key with no valid rotation leaves the publisher unusable until the user
  explicitly "forgets" the pin in extension detail (developer mode), shown with a warning.
- Sideloaded files (sec 8.4) use the same pins when a `.sig` is present.

## 6. Revocation

`RevocationList` is re-evaluated after every successful refresh against installed versions:

- Revoked key: every installed version signed by it (`InstallRecord.signedBy`) is revoked.
- Revoked version range (`versions: "<=0.1.3"`, SemVer range syntax): matching installs are revoked.
- Effect (sdk-reference): extension **disabled** at refresh (`state.json`
  `disabledReason = REVOKED`), user notified with the reason; not auto-uninstalled (sandbox
  changes need the user). Detail page offers Uninstall, or rollback if the previous retained
  version is not revoked.
- A revoked version is never installable, from registry, cache or sideload with a matching sig.

## 7. Search and browse model

`CatalogIndex` is built in memory from all `VerifiedIndex`es (+ Open VSX results when enabled):

```kotlin
data class CatalogItem(
    val id: String, val source: Source, val latestCompatible: IndexEntry?,   // newest with engines match + not revoked
    val versions: List<SemVer>, val installed: InstalledView?,               // per scope/env
    val searchText: String,                                                  // lowercased id, name, displayName, description, categories
)
class CatalogIndex(items: List<CatalogItem>) {
    fun search(query: String, filter: CatalogFilter): List<CatalogItem>
}
data class CatalogFilter(
    val categories: Set<String> = emptySet(), val layers: Set<Layer> = emptySet(),
    val maxCapabilities: Set<String>? = null,      // "only extensions needing at most these"
    val compatibleOnly: Boolean = true, val installedOnly: Boolean = false,
)
```

Search: token prefix match over `searchText`, ranked exact id > displayName prefix > other;
linear scan (index size is small; no FTS dependency). Icons and README are fetched lazily from
the package (after download) - the index carries no media, so browse is metadata only.

## 8. Install pipeline

### 8.1 State machine

```
 RESOLVED --cache hit(sha256)---------------------------+
    | cache miss                                        |
    v                                                   v
 DOWNLOADING --net err--> FAILED              VERIFYING_BYTES (size, sha256, .sig for local)
    | done (to cache/<sha>.partial -> rename)           |
    +-------------------------------------------------->+
                                                        v
                                                    AUDITING (unzip rules, manifest schema,
                                                        |      engines, capability consistency,
                                                        |      scope computation, revocation)
                                                        v
                                               AWAITING_APPROVAL --user declines--> CANCELLED
                                                        | approve (exact capability set recorded)
                                                        v
                                                    UNPACKING (to <id>/.tmp-<version>-<n>, rename)
                                                        |
                                 no easyide.sandbox ----+---- easyide.sandbox.install present
                                        |                              v
                                        |                   SANDBOX_INSTALLING (steps streamed)
                                        |                              | all exit 0
                                        |                              v
                                        |                   SANDBOX_VERIFYING (verify exits 0)
                                        v                              |
                                    FLIPPING (current symlink) <-------+
                                        |
                                        v
                                    REGISTERING (contributions, envHasCommand refresh)
                                        |
                                        v
                                      DONE
 any failure after UNPACKING --> ROLLING_BACK (delete version dir, current untouched) --> FAILED
```

Every transition is appended to a journal `ExtensionPaths.journal(txId)`; on app start,
`InstallPipeline.recover()` rolls back any transaction not in DONE/FAILED/CANCELLED (delete its
temp or version dir if it is not `current`). A transaction lock per `(scope, envId, id)`
serialises concurrent installs of the same extension.

### 8.2 Steps in detail

1. **Resolve**: registry entry, cached package, local `.easyext` (SAF), or local folder (dev).
   `engines.easyide` checked against the app API version before any download.
2. **Download**: `HttpURLConnection` streaming to `cache/<sha256>.partial`, capped at
   `min(entry.size, extensions.limits.packageMb)`, renamed to `<sha256>.easyext` only after
   size + sha256 match (same partial-then-rename pattern as `RootfsProvisioner.download`, plus
   the checksum that code lacks). Progress via `Flow<InstallProgress>`.
3. **Verify bytes**: size, sha256 against the publisher-signed entry. Registry packages: any
   mismatch is a hard fail with no "install anyway" (arch.md sec 9).
4. **Audit** (no writes outside temp):
   - zip rules from sdk-reference Package layout: relative `/` paths, no `..`, no absolute
     paths, no symlink entries (external attrs mode `S_IFLNK`), no nested archives, no
     case-insensitive duplicates; cumulative uncompressed size <= `extensions.limits.unpackedMb`,
     each file <= `extensions.limits.fileMb`, each `.wasm` <= `extensions.wasm.maxModuleMb`.
     Sizes are counted while inflating, never trusted from headers (zip bomb).
   - manifest: `ManifestParser` + JSON Schema ([extension-runtime.md](extension-runtime.md)).
   - capability consistency (every action/host fn needs a declared capability).
   - scope: computed per sdk-reference (`sandbox`, `languageServers`, `sandbox.*` capability or
     sandbox actions -> ENVIRONMENT). A stated `easyide.scope: global` that the rules contradict
     fails audit. ENVIRONMENT installs need a target env (default: active project's env).
   - update case: capability delta vs installed version (sec 10).
5. **Approval**: capability sheet (strings from sdk-reference Capabilities "Prompt text"),
   install commands verbatim, server commands, `memoryBudgetMb`, source label ("unsigned, local",
   "not signed by an easyIDE-registry publisher" for Open VSX). Approval stores the exact
   capability list; a later install needing more requires a new approval.
6. **Unpack**: into `<scopeDir>/<id>/.tmp-<version>-<nonce>/`, then `rename` to `<version>/`.
   `InstallRecord` written last inside the dir. If `<version>/` already exists (reinstall) the
   old dir is kept until the flip succeeds, then deleted.
7. **Sandbox install** (ENVIRONMENT scope with `easyide.sandbox.install`): `SandboxInstallRunner`
   runs each step whose `when` is true (`envDistro`, `envArch`, `envBackend`) in order, in a
   **visible** terminal tab named after the extension (never silently); with
   `extensions.sandbox.confirmEachStep` it pauses for a tap per step. Each step goes through
   `LinuxEnvironment.start(command, envId, hostProjectDir, extraEnvironment)` with a clean env
   (no `EASYIDE_GIT_TOKEN`, 0012); output lines stream from `TerminalProcess.stream`. Each step's
   exit is recorded in `InstallRecord.sandboxSteps`. The environment is kept alive by
   `SandboxForegroundService.start` for the duration. A non-zero exit stops the pipeline.
8. **Verify**: `easyide.sandbox.verify` must exit 0, else ROLLING_BACK. Rollback deletes the
   version dir and leaves `current` untouched; **software already installed into the environment
   is not undone** (stated in the failure sheet with the list of steps that ran and the
   `uninstall[]` commands offered as a best-effort cleanup).
9. **Flip**: create `current.new -> <version>` (relative symlink, `Files.createSymbolicLink` as
   `TarGzExtractor` already does), then `Files.move(current.new, current, ATOMIC_MOVE)` which is
   `rename(2)` over the old link. Atomic on the same filesystem; behaviour on the device's
   app-private filesystem **to verify** in an instrumented test.
10. **Register**: notify `ContributionRegistry` to reload this extension's static contributions;
    refresh the `envHasCommand:*` probe cache for the env; if the extension was active, it is
    deactivated and re-activated lazily (WASM instance dropped, servers restarted by the
    supervisor on next need). Guest-visible path `/opt/easyide/extensions/<id>` resolves at
    next process launch (sec 11.2).
11. **Retention**: keep `current` and the previous version (`RegistryPolicy.retainedVersions`
    = 1 previous); older version dirs are deleted after DONE. Package cache is kept (sec 12).

### 8.3 Error handling

Real boundaries only: network, file I/O, zip parsing, sandbox process spawning. Each maps to a
typed `InstallError` (`Network`, `Integrity(expected, actual)`, `Signature(reason)`,
`PinMismatch`, `Revoked`, `Engine(range, app)`, `Audit(list)`, `Declined`, `SandboxStep(id, exit)`,
`Verify(exit)`, `Storage(op)`) shown on the failure sheet. Pure audit logic returns
`List<AuditProblem>`, not exceptions.

### 8.4 Local files and folders

- `.easyext` via SAF: copied into the cache (content-addressed), then the same pipeline. If
  `<file>.easyext.sig` is picked alongside (or embedded name match in the same SAF dir), it is
  verified against the publisher's key from a cached registry publisher file plus the TOFU pin;
  otherwise the install is labelled "unsigned" and never auto-checked for updates.
- Folder (M3 "Install from folder", dev loop): packaged in memory with the CLI's deterministic
  packer semantics, `Source.LOCAL_FOLDER_DEV`, requires `extensions.developerMode` for live
  reload ([cli.md](cli.md) `dev`). Implemented as `Source.DEV` with a per-reload version
  `<version>-dev.<n>`; see cli.md Deviations.

## 9. Open VSX adapter

Enabled only by `extensions.openVsx.enabled` (G, default false). Base URL is fixed to
`https://open-vsx.org` in `RegistryPolicy` (no user-configurable marketplace URL: "No .vsix
from the Microsoft Marketplace, ever", arch.md sec 11).

- Search: `GET /api/-/search?query=<q>&category=<c>&size=<n>`; detail: `GET /api/<namespace>/<name>`
  (JSON has `version`, `files.download`, `files.sha256`, `engines`). Exact field names **to
  verify** against the live API before M6.
- Integrity: sha256 from Open VSX only (no easyIDE signature); install sheet label "not signed
  by an easyIDE-registry publisher". Downloaded bytes must match it.
- `VsixReader` (zip): reads `extension/package.json` and only the files it references; ignores
  `extension.vsixmanifest`, `[Content_Types].xml`. Same zip rules and limits as `.easyext`.
- Accepted subset (arch.md sec 11): `contributes.languages`, `grammars`, language-configuration
  files, `snippets`, `themes`, `iconThemes`, `configuration`, and `keybindings` whose command
  resolves to a built-in command. Everything else (`main`, `browser`, `commands` needing code,
  views, debuggers, `activationEvents`) is dropped.
- Output: a synthesized package dir with a rewritten `package.json` (subset + `engines.easyide`
  set to the current API major, `easyide.capabilities: []`), original kept as
  `.vsix-origin/package.json`. Scope is always GLOBAL (no sandbox parts can survive the subset).
- `CompatReport{used: [...], dropped: [{point, reason}]}` shown before approval; install marked
  "partial" when anything was dropped.
- Id: `<namespace>.<name>`; if the same id exists from the easyIDE registry, both are listed with
  source badges and only one may be installed per scope (the store key includes `Source`).

## 10. Update, notify, diff, rollback

- **Check**: for each installed REGISTRY/OPEN_VSX extension, `latestCompatible` newer than
  installed and not revoked -> update available. Result is a notification + badge. Never installs.
- **Diff sheet** (requires the new package; downloaded to cache first):
  version, `CHANGELOG.md` sections between the two versions (by `## <version>` headings,
  shown verbatim, else whole file), **capability delta** (added in bold, removed listed),
  new/changed `sandbox.install` steps verbatim, server command/budget changes, `memoryBudgetMb`
  change, scope change (global <-> environment is refused: uninstall + install instead).
- **Apply**: the full pipeline (sec 8) with `update = true`; added capabilities need approval,
  unchanged ones do not.
- **Rollback**: one tap flips `current` back to the retained previous version (sec 8.2 step 9) and
  records it in `state.json`; environment changes made by the newer version are not undone
  (stated). Rollback to a revoked version is refused.
- Local unsigned installs are never auto-checked (arch.md sec 9).

## 11. Storage layout and scope

### 11.1 Paths (anchored to `SandboxPaths`)

`SandboxPaths` (`sandbox-runtime/.../SandboxPaths.kt`) owns the root (`appContext.filesDir`,
see `AppContainer`) and per-environment dirs. `ExtensionPaths(paths: SandboxPaths)` derives
all extension paths; it needs one addition to `SandboxPaths`: `val extensionsDir = File(root, "extensions")`
(keeps the "no string-concatenated sandbox paths" rule of that class).

```
<files>/extensions/global/<id>/<version>/            GLOBAL installs (themes, snippets, grammars, WASM-only, Open VSX)
<files>/extensions/global/<id>/current -> <version>
<files>/environments/<envId>/extensions/<id>/<version>/   ENVIRONMENT installs  (SandboxPaths.environmentDir(envId))
<files>/environments/<envId>/extensions/<id>/current -> <version>
<files>/extensions/cache/<sha256>.easyext           offline package cache, content-addressed
<files>/extensions/registry/<registryId>/            last verified index: index.json(+.sig), revocations.json(+.sig),
                                                     publishers/<p>.json(+.sig), meta.json (fetchedAt, ETags)
<files>/extensions/journal/<txId>.json               install transactions (sec 8.1)
<files>/extensions/state.json                        approvals, pins, system disables (revoked, crash-loop, re-approval)
<files>/extensions/wasm-cache/, storage/             owned by wasm-host.md
```

Placing ENVIRONMENT installs under `environments/<envId>/` means `EnvironmentManager.delete`
(which removes the environment dir) removes them with the environment. This is organisation, not
protection: every path is readable by anything running as the app UID (0002).

### 11.2 Guest visibility

sdk-reference: the active version appears at `/opt/easyide/extensions/<id>` inside the guest
(a bind mount; not read-only under proot). Implementation: `LaunchRequest`
(`backend/SandboxLauncher.kt`) gains `extraBinds: List<Pair<File, String>>`; `ProotLauncher`
emits `-b <host>:<guest>` and `ChrootLauncher` a bind mount, for each enabled ENVIRONMENT
extension's `current` (resolved to the version dir at launch). A flip therefore affects only
processes started after it; the LSP supervisor restarts affected servers.

### 11.3 Per-environment vs global

| Aspect | GLOBAL | ENVIRONMENT |
|---|---|---|
| Installed | once per device | once per environment (may differ in version per env) |
| Enablement | `extensions.disabled` in any layer narrows per env/project | same |
| Sandbox steps, servers | not allowed | allowed |
| Deleted with env | no | yes |

### 11.4 `state.json`

Written atomically (temp + rename) by `ExtensionStateStore`; schema versioned (`"schemaVersion": 1`).
Holds `installs[{id, scope, envId, source, current, previous}]`, `approvals`, `pins`,
`disabled{id: reason}`, `lastRefresh{registryId: instant}`. The same JSON library as
`PersistedStateJson` (org.json). Not in the `SandboxStore` DataStore, to keep extension state
from blocking sandbox state writes.

## 12. Offline cache

- Content-addressed `cache/<sha256>.easyext`; a hit skips the download (arch.md 6.3 step 2).
  Integrity is re-checked on every use (hash the file) - the cache is not trusted.
- Offline install works from the last verified index + cached publisher files + cached package
  (success metric: < 10 s excluding `sandbox.install`). `sandbox.install` steps usually need
  network; the sheet warns when `isOffline`.
- GC: packages referenced by `current` or `previous` of any install are pinned; others are
  evicted LRU when the cache exceeds `RegistryPolicy.cacheMaxMb`.

## 13. Uninstall

1. Disable -> deactivate (WASM instance dropped, servers stopped via LSP supervisor).
2. If ENVIRONMENT scope and `easyide.sandbox.uninstall[]` exists: sheet "Also try to remove
   software it installed? (best effort)" listing commands verbatim; if accepted, run them
   visibly like install steps; failures are reported, not fatal.
3. Delete `<scopeDir>/<id>/` (all versions), remove from `state.json` (approvals too; pins stay
   so a reinstall still detects key changes). Cache entries become unpinned.
4. `envHasCommand` cache refresh; contributions unregistered.

Settings written by the extension's `configuration` in user/project files are left in place
(they are the user's files) and show as "unknown setting" rows with a remove action.

## 14. Public interfaces (Kotlin, concise)

```kotlin
class RegistryClient(http: HttpFetcher, verifier: SignatureVerifier, trust: TrustStore, paths: ExtensionPaths, clock: () -> Instant) {
    suspend fun refresh(registry: RegistryConfig): Result<VerifiedIndex>
    fun cached(registryId: String): VerifiedIndex?
    suspend fun publisherKeys(registryId: String, publisher: String): Result<PublisherKeys>
}
interface HttpFetcher {                        // boundary; HttpURLConnection impl, fake in tests
    suspend fun get(url: String, etag: String?, maxBytes: Long, into: File): FetchResult
}
class InstallPipeline(
    registry: RegistryClient, cache: PackageCache, auditor: PackageAuditor, store: ExtensionStore,
    sandbox: SandboxInstallRunner, approvals: ApprovalPrompter, journal: InstallJournal,
) {
    fun install(request: InstallRequest): Flow<InstallProgress>        // cold; collect to run
    fun update(id: String, scope: ScopeRef): Flow<InstallProgress>
    suspend fun rollback(id: String, scope: ScopeRef): Result<Unit>
    fun uninstall(id: String, scope: ScopeRef, runUndo: Boolean): Flow<InstallProgress>
    suspend fun recover()                                               // app start
}
sealed interface InstallRequest {
    data class FromRegistry(val entry: IndexEntry, val env: String?) : InstallRequest
    data class FromFile(val uri: String, val sigUri: String?, val env: String?) : InstallRequest
    data class FromFolder(val dir: File, val env: String?) : InstallRequest
    data class FromOpenVsx(val namespace: String, val name: String, val version: String) : InstallRequest
}
data class ScopeRef(val scope: ExtScope, val envId: String?)
data class InstallProgress(val state: InstallState, val line: String? = null, val error: InstallError? = null)
interface ApprovalPrompter { suspend fun ask(sheet: ApprovalSheet): Approval }   // :app implements (Compose sheet)
class SandboxInstallRunner(env: LinuxEnvironment, terminals: TerminalTabs) {
    fun run(envId: String, steps: List<InstallStep>, confirmEach: Boolean): Flow<StepEvent>
}
class ExtensionStore(paths: ExtensionPaths, state: ExtensionStateStore) {
    fun installed(): Flow<List<InstalledExtension>>
    suspend fun flip(id: String, scope: ScopeRef, version: String)
    fun versionDir(id: String, scope: ScopeRef, version: String): File
}
```

Threading: all pipeline work on `Dispatchers.IO`; hashing and inflating stream in fixed buffers
(no whole-file byte arrays; packages are up to `extensions.limits.packageMb`). UI observes
`Flow<InstallProgress>` on main. `CatalogIndex` is rebuilt on `Dispatchers.Default` after refresh.

## 15. Config keys used

`extensions.registries`, `extensions.openVsx.enabled`, `extensions.autoCheckUpdates`,
`extensions.developerMode`, `extensions.sandbox.confirmEachStep`, `extensions.limits.packageMb`
/ `unpackedMb` / `fileMb`, `extensions.wasm.maxModuleMb`, `extensions.enabled`,
`extensions.disabled`, `extensions.safeMode`. Internal constants in one declarative
`RegistryPolicy` table: `maxIndexBytes`, `connectTimeoutMs`, `readTimeoutMs`,
`retainedVersions`, `cacheMaxMb`, `staleIndexWarnDays`, `openVsxBaseUrl`. User enable/disable
is `extensions.disabled` (extension-runtime.md sec 4), never `state.json`.

## 16. Integration points with existing code

| Existing code | Use |
|---|---|
| `sandbox-runtime/.../SandboxPaths.kt` | root and `environmentDir(envId)`; add `extensionsDir` |
| `sandbox-runtime/.../LinuxEnvironment.kt` `start(...)` | runs install/verify/uninstall steps and `requires` probes |
| `sandbox-runtime/.../shell/TerminalProcess.kt` `stream` | streams step output to the terminal tab and the install log |
| `sandbox-runtime/.../backend/SandboxLauncher.kt` (`LaunchRequest`), `ProotLauncher.kt`, `ChrootLauncher.kt` | `extraBinds` for `/opt/easyide/extensions/<id>` |
| `sandbox-runtime/.../bootstrap/TarGzExtractor.kt` | precedent for `Files.createSymbolicLink` and path-escape checks (`resolveSafely`) |
| `sandbox-runtime/.../bootstrap/RootfsProvisioner.kt` `download` | partial-then-rename download pattern; **known gap: rootfs downloads have no checksum** (also noted in extensions/arch.md); this pipeline adds sha256 for packages but does not fix rootfs, which stays a sandbox-runtime tracker item |
| `sandbox-runtime/.../service/SandboxForegroundService.kt` | keep the process alive during long `sandbox.install` |
| `sandbox-runtime/.../EnvironmentManager.kt` `delete` | removes env-scoped installs with the env dir; must also drop their `state.json` rows |
| `sandbox-runtime/.../store/PersistedStateJson.kt` | org.json encode/decode style for `state.json` |
| `app/.../AppContainer.kt` | constructs `RegistryClient`, `InstallPipeline`, `ExtensionStore` |
| `app/.../ui/screens/workspace/WorkspaceViewModel.kt` | active env/project for the default install target and terminal tab for steps |

## 17. Testing hooks

| Test | Layer | What |
|---|---|---|
| JCS | JVM unit (shared lib) | RFC 8785 appendix vectors (non-number subset), key ordering with non-BMP chars, duplicate keys rejected, floats rejected; byte equality with the CLI |
| Ed25519 | JVM unit | RFC 8032 test vectors through the `Ed25519` interface for each provider candidate |
| Index verification | JVM unit, fixture index repo | tampered entry byte, wrong root key, revoked key, retired key, missing publisher file, anti-rollback (older `generatedAt`), each hard-fails with the right reason |
| TOFU | JVM unit | first pin; same key; valid rotation chain of 2; broken chain; revoked link in chain |
| Zip audit | JVM unit + fuzz | `..`, absolute, symlink entry, case duplicate, nested archive, zip bomb (small compressed, huge inflated), per-file and total limits |
| Pipeline | JVM unit with fakes (`HttpFetcher`, `SandboxInstallRunner`, `ApprovalPrompter`) | every state transition; verify fails -> version dir gone, `current` unchanged; crash injected at each journal step -> `recover()` restores a consistent store |
| Flip | instrumented (device) | atomic `current` swap on app-private storage; concurrent reader never sees a missing link |
| Update/rollback | JVM unit | capability delta computed; added capability requires approval; rollback to revoked refused |
| Open VSX | JVM unit with recorded API JSON + sample `.vsix` | subset extraction, `CompatReport`, commands dropped, scope forced GLOBAL |
| Offline | JVM unit + device | install from cache with `HttpFetcher` failing; staleness shown (M6 exit: signed install < 10 s offline) |
| M6 exit | integration | tampered package and revoked key both hard-fail |

## 18. Open issues

1. Registry host and who holds the root key offline (arch.md open question 4); ADR-H decides.
2. Ed25519 provider on Android (platform vs Bouncy Castle) - verify before M6.
3. Root key rotation: sdk-reference defines publisher rotation only. A root key change currently
   needs an app update; ADR-H should decide whether `extensions.registries` accepts a
   root-signed successor key.
4. Whether a project's `.easyide/settings.json` may list recommended extensions (and prompt).
5. Open VSX API field names and whether `files.sha256` is present for every version.

## Deviations

- Resolved in sdk-reference: install layout (`<id>/<version>/` + `current`, env installs under
  `environments/<envId>/`), integer-only signed JSON, `sigByOld` bytes, and the
  `generatedAt`/`updatedAt` anti-rollback step.
- `RegistryPolicy` constants (incl. `cacheMaxMb`) are not settings keys; if users should tune
  the cache size it becomes `extensions.cache.maxMb`.
