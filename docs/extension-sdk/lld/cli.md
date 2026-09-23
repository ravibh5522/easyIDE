# Extension SDK - easyide-ext CLI (LLD)

Low-level design of `tools/easyide-ext`, the author-side tool that scaffolds, validates, packages, signs, publishes, tests and live-deploys extensions.

Status: PROPOSED (2026-09-23). Nothing implemented. Design context: [arch.md](../arch.md) sec 3 (Extension author persona), 5.4, 6.2, 12 (M5, M6).
Contract (commands, flags, exit codes, index format): [sdk-reference.md#cli](../sdk-reference.md#cli), [#registry-index-format](../sdk-reference.md#registry-index-format).
Feature area: arch.md sec 5.4 (Ecosystem: Publish, Dev loop, In-app authoring). License Apache-2.0 per ADR-G (0015).

## 1. Scope and responsibility

`easyide-ext` is the only author tool. It must produce **exactly** what the app accepts: same
schema, same canonical JSON, same limits, same WASM runtime. So it is built from the same JVM
code the app uses rather than reimplementing it.

Not owned: registry hosting, the index repo's CI, in-app "Create extension" UI (reuses the
templates from here), the app's install pipeline ([registry-and-install.md](registry-and-install.md)).

## 2. Language and runtime choice

| Option | Verdict |
|---|---|
| **Kotlin/JVM fat jar, Java 17+** | **chosen**. Shares `services/shared/extension-schema` (schema, JCS, signature format) and `:ext-wasm` (Chicory + metering + host table) with the app, so `validate`, `package` and `test` cannot drift from install-time behaviour (arch.md sec 13 "Manifest schema drift"). JDK 17 has Ed25519 in the standard provider (JEP 339, Java 15), so no crypto dependency. Runs inside easyIDE environments (sdk-reference: preinstalled) with a JDK from the distro. |
| Node/TypeScript | rejected: second implementation of JCS, schema and WASM limits; no Chicory. |
| Rust/Go native binary | rejected: same duplication; per-arch builds for arm64 guests. |

Cost accepted: JVM startup (~0.3-0.6 s, unmeasured) per invocation; `dev --watch` stays
resident so the loop does not pay it per save.

Build: its own Gradle build in `tools/easyide-ext/` (not part of `services/mobile/settings.gradle.kts`,
since `tools/` never ships in a service) that includes `services/shared/extension-schema` and
`services/mobile/ext-wasm` as source dependencies via `includeBuild`. Output
`easyide-ext-<version>.jar` (dependencies merged with a plain `jar { from(runtimeClasspath) }`,
no shadow plugin) plus a POSIX launcher script `easyide-ext` (`exec java -jar ...`).
Dependencies: Chicory (via `:ext-wasm`), a JSON library (org.json on the JVM to match the app's
Android platform copy - Maven `org.json:json` license **to verify** before adoption), and nothing
else. Argument parsing is hand-rolled (7 commands, few flags) rather than adding a CLI library.

## 3. Architecture

```
Main.kt -> CommandTable (name -> Command) -> Command.run(args, ctx): ExitCode
                         |
   CliContext { cwd, config: CliConfig, out: Output (text | --json), fs, clock, git: GitRunner, adb: AdbRunner }
                         |
   shared: ManifestParser, SchemaValidator, Jcs, SignatureFormat, KeyIds   (services/shared/extension-schema)
           WasmHost + HostFunctionTable + Meter                            (:ext-wasm)
   cli-only: Templates, Packager, KeyStoreFiles, IndexEntryBuilder, TestHarness, DevDeployer
```

```kotlin
interface Command { val name: String; fun run(args: Args, ctx: CliContext): ExitCode }
enum class ExitCode(val code: Int) { OK(0), VALIDATION(1), IO(2) }          // sdk-reference exit codes
data class Diagnostic(val code: String, val severity: Severity, val file: String?, val pointer: String?, val message: String)
enum class Severity { ERROR, WARNING, INFO }
interface Output { fun diagnostics(d: List<Diagnostic>); fun result(obj: JSONObject); fun line(s: String) }
```

Exit code mapping (sdk-reference: 0 ok, 1 validation/usage, 2 I/O or network):

| Condition | Code |
|---|---|
| success; warnings without `--strict` | 0 |
| usage error (unknown command/flag, missing arg), any validation ERROR, `--strict` + WARNING, failing `test` scenario, signature/key mismatch | 1 |
| file not found/unreadable, write failure, network/git/adb failure, subprocess spawn failure | 2 |

Error handling happens at the real boundaries: filesystem, network, `git` and `adb`
subprocesses. Each boundary exception becomes one `Diagnostic` + exit 2; validation logic
returns diagnostics, never throws.

`--json` (every command): stdout is one JSON object `{"ok", "command", "diagnostics": [...], "result": {...}}`;
human text goes to stderr. Stable diagnostic `code`s (e.g. `E_MANIFEST_SCHEMA`, `E_CAP_UNDECLARED`,
`W_CAP_UNUSED`, `W_VSCODE_KEY_IGNORED`) are the API for editors/CI.

## 4. Config file

`~/.easyide/config.json` (global) and `./.easyide-ext.json` (per extension, committed), later
wins, flags win over both:

```jsonc
{
  "publisher": "acme",
  "key": "~/.easyide/keys/acme-a1b2c3d4e5f60718.key",
  "indexRepo": "https://github.com/easyide/extension-index",
  "engine": "0.3.0",                  // default for --engine
  "device": "R52T1234",              // default for dev --device
  "packageOut": "dist/"
}
```

No secrets in config files: the key file path only. Unknown keys warn.
`EASYIDE_EXT_KEY_PASSPHRASE` env var supplies the key passphrase non-interactively (CI).

## 5. Commands

### 5.1 `init [dir] --template <t> --name --publisher --yes`

- Templates are directories inside the jar under `templates/<t>/`, files with `{{publisher}}`,
  `{{name}}`, `{{displayName}}`, `{{engine}}` placeholders (plain substitution, no template
  engine). The same set is exposed to the in-app "Create extension".
- Templates (sdk-reference): `theme`, `snippets`, `language-pack`, `lsp-pack`,
  `toolbar-command`, `wasm-rust`, `wasm-assemblyscript`. Each includes `README.md`, `LICENSE`
  placeholder (SPDX in manifest), `CHANGELOG.md`, `.easyide-ext.json`, and a `test/` scenario
  that passes out of the box.
- WASM templates add `guest/` (Cargo crate depending on `easyide-guest`, or AssemblyScript with
  `@easyide/guest-as`) and a build script writing `wasm/main.wasm`. The CLI does not bundle
  compilers; it prints the needed toolchain if missing.
- Refuses a non-empty `dir` (exit 1) unless `--yes`, which only writes missing files. Without
  `--yes` and without flags, prompts for name/publisher on a TTY; with no TTY, missing values are
  a usage error.

### 5.2 `validate [dir or file] --strict --engine <ver>`

Input: an extension dir or a `.easyext` (read in memory via the same zip audit as install).
Checks, in order, all collected (not fail-fast) as diagnostics:

1. Package rules and limits (sdk-reference Package layout): paths, symlinks, duplicates,
   `extensions.limits.*` and `extensions.wasm.maxModuleMb` **defaults**, measured on the
   would-be package.
2. `package.json` JSON Schema (shared schema), naming regexes, SemVer, `engines.easyide`
   satisfiable by `--engine` (default: config `engine`, else the CLI's bundled API version).
3. Referenced files exist; unreferenced files -> warning.
4. Grammars parse (TextMate JSON/plist), themes parse, `colors` keys without an easyIDE token
   listed, `tokenColors` scopes reported with the `ScopeRules` role they collapse to (role table
   exported to the shared lib from `app/.../syntax/ScopeRules.kt` data).
5. Every `when` clause parses; unknown context keys warn.
6. Capability audit: every action type and (for WASM) every host `fn` string found in the module
   (static scan of data segments for `"fn":"..."` is heuristic, reported as INFO) vs declared
   capabilities; undeclared -> ERROR, declared but unused -> WARNING.
7. Scope computation matches a stated `easyide.scope`.
8. WASM: the same `WasmModuleLoader` static validation as the app (imports, exports, memory max,
   no start function) and the metering pass must succeed.
9. VS Code keys ignored by easyIDE -> WARNING list.
10. Registry-publish readiness (only with `--strict`): README, LICENSE, `license` SPDX, icon size.

### 5.3 `package [dir] --out <file>`

Runs `validate` (errors abort, exit 1). Then writes a **deterministic zip**:

- entries sorted by path (byte order of UTF-8); only files (no directory entries);
- fixed timestamp 1980-01-01 00:00:00 (DOS epoch), no extra fields, no comments, UTF-8 flag set;
- DEFLATE at a fixed level for all entries except `.png`/`.webp` (STORED);
- excludes: `.git/`, `test/`, `guest/`, `target/`, `node_modules/`, files matched by
  `.easyextignore` (gitignore syntax, subset: `*`, `**`, `/`, `!`);
- name `<publisher>.<name>-<version>.easyext`; prints `sha256` and size (and in `--json`).

Determinism test: two runs on the same tree produce identical bytes on Linux, macOS, Windows.

### 5.4 `keygen --out <dir>` and `sign <file.easyext> --key <private>`

- `keygen`: `KeyPairGenerator.getInstance("Ed25519")`. `keyId` = first 16 hex chars of
  sha256(raw 32-byte public key) (raw key = last 32 bytes of the X.509 SPKI encoding).
  Writes `<publisher>-<keyId>.key` (PKCS#8, PEM) and `<publisher>-<keyId>.pub.json`
  (`{"keyId","publicKey": base64 raw}` - the fragment for `publishers/<p>.json`).
- Key storage: default dir `~/.easyide/keys/`, created mode 0700, key file 0600 (POSIX attrs where
  supported; on Windows a warning). Optional passphrase (prompted, or `EASYIDE_EXT_KEY_PASSPHRASE`):
  PKCS#8 `EncryptedPrivateKeyInfo` with PBES2 (PBKDF2-HMAC-SHA256 + AES-256) via JDK APIs.
  The private key is never printed, never written to `--json` output, never sent anywhere.
  Guidance printed after keygen: back the key up offline; losing it means a rotation needs
  maintainers.
- `sign`: detached `<file>.easyext.sig` = `{"keyId","alg":"ed25519","sig": base64}` over the
  **exact package bytes** (binary; JCS does not apply to the zip). Refuses if the key's `keyId`
  differs from the one recorded in `.easyide-ext.json` unless `--yes`.
- `keygen --rotate --from <old.key>`: new keypair plus a rotation record
  `{"from","to","sigByOld"}` with `sigByOld` over `Jcs({"publisher","from","to"})`
  ([registry-and-install.md](registry-and-install.md) sec 4.1).

### 5.5 `publish <file.easyext> --index-repo <git url> --key <private> --branch`

No backend: publishing is a pull request to the static index repo.

1. Validate the package (`--strict` semantics) and read its manifest.
2. Determine `url`: `--url <https>` (e.g. a GitHub release asset the author uploaded), else the
   package is committed under `packages/<publisher>/<name>-<version>.easyext` in the index repo
   (sdk-reference: `packages/` optional).
3. Build the entry (`IndexEntryBuilder`): fields of sdk-reference `index.json` from the manifest
   (`scope` and `layers` computed, `capabilities` verbatim), `size`, `sha256`, `publishedAt`
   (UTC now), then `signature = sign(Jcs(entry minus signature))`.
4. `git` via the system binary (`GitRunner`, `ProcessBuilder`, argv form - no shell), so the
   author's own credentials/SSH agent apply: shallow clone to a temp dir, branch
   `--branch` or `publish/<id>-<version>`, write `entries/<publisher>/<name>/<version>.json`,
   commit (`Add <id> <version>`), push to the author's fork remote if `--fork <url>` else origin.
5. Print the PR URL to open (host-specific compare URL for GitHub/GitLab/Gitea patterns, else the
   branch name). The CLI does not call any host API and holds no host tokens.

Refusals (exit 1): version already present in the index; `publisher` mismatch with key's
publisher file entry (checked against the cloned `publishers/<p>.json` when present);
`engines.easyide` missing. First-time publishers: `publish --register-publisher` adds
`publishers/<p>.json` (unsigned by root) in the PR for maintainers to review and root-sign.

Maintainer side (same jar, `registry` subcommand group, documented but not in sdk-reference's
author table): `easyide-ext registry build --root-key <k>` regenerates `index.json` from
`entries/**` (verifying each entry signature against `publishers/`), sets `generatedAt`, and
writes root-signed `.sig` files for `index.json`, `publishers/*.json`, `revocations.json`.
Run offline by whoever holds the root key (arch.md open question 4).

### 5.6 `test [dir] --filter <glob> --engine <ver>`

Headless harness, no device:

- Loads the manifest with the app's `ManifestParser`, registers contributions into an in-memory
  `ContributionRegistry` fake.
- Scenario files `test/*.json` (this is the normative format). Every field but `name` is
  optional: `context` (when-clause keys), `settings`, `editor` (`{path, text, selection?}`
  fixture for the active editor), `steps` (`{execute: commandId, args?}`), `fakes` (answers
  for `sandboxExec`, `quickPick`, `inputBox`), `expect`: `visible` (contribution refs as in
  `workbench.contributions.hidden`), `calls` (recorded L1 action calls `{type,...}` and L2 host
  calls `{fn, args}`, matched as subsets in order), `messages`, `errors`.

```jsonc
{ "name": "run button only for python",
  "context": { "editorLangId": "python", "envState": "ready" },        // when-clause context
  "settings": { "python.interpreter": "python3.12" },
  "editor": { "path": "/workspace/a.py", "text": "print(1)\n" },
  "steps": [ { "execute": "python.runFile" } ],
  "fakes": { "sandboxExec": [{ "match": ["sh","-c","ls*"], "stdout": "/workspace/.venv/bin/python\n", "exitCode": 0 }],
             "quickPick": ["/workspace/.venv/bin/python"] },
  "expect": { "visible": ["menu:editor/title:python.runFile"],
              "calls": [{ "type": "runInTerminal", "command": "python3.12 /workspace/a.py" }],
              "messages": [], "errors": [] } }
```

- Actions run through the real `ActionRunner` logic against a `FakeHost` that records calls
  (terminal, exec, edits, config writes, UI prompts answered from `fakes`).
- WASM modules run in the real `:ext-wasm` `WasmHost` with **the real default limits**
  (`extensions.wasm.*` defaults), host functions bound to the same `FakeHost`; capability
  denials behave as on device.
- Output: per-scenario pass/fail with a diff of expected vs recorded calls; `--json` for CI.

### 5.7 `dev [dir] --device <serial> --watch --local`

Loop: package (in memory, no signing) -> deploy -> signal reload; `--watch` re-runs on file
change (JDK `WatchService`, debounced by `CliPolicy.watchDebounceMs`).

- **adb** (`AdbRunner`, system `adb`, argv form): push the package to the app-specific external
  dir `/sdcard/Android/data/dev.easyide.app/files/dev-inbox/<id>.easyext` (writable by adb shell,
  not by other apps on modern Android - **to verify** per API level), then
  `adb shell am broadcast -a dev.easyide.app.action.DEV_RELOAD -n dev.easyide.app/<receiver> --es id <id>`.
  The app receiver acts only when `extensions.developerMode` is true, is protected so only the
  shell UID can send it (e.g. `android:permission="android.permission.DUMP"`, which adb shell
  holds - **to verify**), and installs through the normal pipeline as `Source.LOCAL_FOLDER_DEV`
  (unsigned label; capability sheet on first install and whenever capabilities change, then
  silent reloads for identical capability sets).
  Canary builds (`applicationIdSuffix ".canary"`) are targeted with `--app-id`.
- **--local** (inside an easyIDE environment): no adb. Writes a request file
  `/opt/easyide/dev/<id>.json` naming the guest folder; the app's dev watcher (developer mode
  only) maps it to the host project dir and installs from folder. Exact handoff path is an open
  issue (needs a guest-writable, host-watched dir).
- Reload result is not observable over adb in v1; the CLI prints "sent" and tells the author to
  check the Extension Log. (A result channel is an open issue.)

## 6. Key storage for publishers (summary)

| Item | Where | Protection |
|---|---|---|
| Private key | `~/.easyide/keys/<publisher>-<keyId>.key` | 0600, optional PBES2 passphrase; never in repo (template `.gitignore` excludes `*.key`) |
| Public key fragment | `<publisher>-<keyId>.pub.json` | public |
| Key id for this extension | `.easyide-ext.json` `keyId` | public, detects wrong-key signing |
| CI | secret file + `EASYIDE_EXT_KEY_PASSPHRASE` | CI secret store; the CLI never logs either |

Inside an easyIDE environment the keys dir lives in the guest home, i.e. in the environment
rootfs: readable by anything running in that environment (0002). The docs tell authors to keep
registry keys off-device or passphrase-protected.

## 7. Config keys and constants used

Settings keys (defaults mirrored from sdk-reference, used for validation and `test`):
`extensions.limits.packageMb` / `unpackedMb` / `fileMb`, `extensions.wasm.maxModuleMb`,
`maxMemoryMb`, `fuelPerCall`, `maxHostCallsPerCall`, `callTimeoutMs`, `activateTimeoutMs`,
`deactivateTimeoutMs`, `maxMessageKb`, `netMaxResponseKb`, `extensions.actions.execTimeoutSec`,
`captureKb`. The defaults come from the shared schema module, never duplicated in CLI code.
CLI-only constants in one `CliPolicy` table: `watchDebounceMs`, `gitTimeoutSec`, `adbTimeoutSec`.

## 8. Integration points

| Code | Use |
|---|---|
| `services/shared/extension-schema/` | JSON Schema, `ManifestParser`, `Jcs`, `SignatureFormat`, `KeyIds`, settings defaults (shared with `:extensions`) |
| `services/mobile/ext-wasm` | `WasmModuleLoader`, `Meter`, `WasmHost` for `validate` and `test` ([wasm-host.md](wasm-host.md)) |
| `app/.../ui/screens/workspace/syntax/ScopeRules.kt` | role table exported as data for theme validation |
| `tools/build-grammars.py` | precedent for tools that feed the app; unrelated code path |
| app `DEV_RELOAD` receiver + dev inbox (new, `:app`) | target of `dev` |
| index repo layout (sdk-reference + `entries/`) | target of `publish` and `registry build` |

## 9. Testing

| Test | What |
|---|---|
| Golden templates | `init` each template -> `validate --strict` exit 0 -> `package` -> `test` passes; runs in CI on every change |
| Validator corpus | `tools/easyide-ext/src/test/fixtures/invalid/*` one fixture per diagnostic code, asserting code, pointer and exit 1; the same corpus is run by `:extensions` tests to prove app/CLI agreement (M5 exit: "validate catches schema + capability errors in samples test suite") |
| Determinism | package twice -> identical sha256; CI matrix Linux/macOS/Windows |
| Signing | keygen -> sign -> verify with the app's `SignatureVerifier`; wrong passphrase exit 1; `--json` never contains key material (grep test) |
| Publish | against a local bare git repo fixture: branch, entry file, entry signature verifies, duplicate version refused; `registry build` output verifies with the app's `RegistryClient` fixture path |
| Test harness | scenario pass/fail/diff; WASM infinite loop -> `E_LIMIT` with default fuel |
| Dev | `AdbRunner`/`GitRunner` are interfaces; fakes assert exact argv (no shell strings) |
| Exit codes | table-driven: each row of sec 3 mapping |
| In-environment | smoke test running the jar inside the Ubuntu base environment with the distro JDK |

## 10. Open issues

1. `--local` dev handoff path between guest and app (guest-writable, host-watched directory
   that does not assume isolation).
2. Result channel for `dev` over adb (logcat tag parsing vs a content provider).
3. Whether `publish` should also open the PR via the host API when the author opts in (would
   need a token; currently deliberately not).
4. Distribution of the jar: GitHub release + preinstall in environment images (0007
   `setupCommands`) - which images.
5. org.json on Maven: license must be verified; fallback is kotlinx.serialization-json (Apache-2.0,
   to verify) in the shared module, which would also change the app side.

## Deviations

- Resolved in sdk-reference (CLI table, registry format): `publish --url/--fork/--register-publisher`
  writing `entries/<publisher>/<name>/<version>.json` (one `index.json` edited by concurrent PRs
  would always conflict, and only the root key holder can sign it), maintainer `registry build`,
  `keygen --rotate`, `dev --app-id`, and `.easyext.sig` over raw package bytes.
