# easyide-ext

The extension author CLI: scaffold, validate, package, sign and verify easyIDE extensions.
Design: [docs/extension-sdk/lld/cli.md](../../docs/extension-sdk/lld/cli.md); command contract:
[sdk-reference.md#cli](../../docs/extension-sdk/sdk-reference.md#cli).

Licensed Apache-2.0 (`LICENSE` in this directory, [decision 0015](../../docs/decision/0015-extension-sdk-licensing-apache.md)),
unlike the PolyForm Noncommercial app code.

## Build and run

Needs Java 17+.

```sh
./gradlew jar                  # build/libs/easyide-ext-<version>.jar (dependencies merged)
./easyide-ext help             # launcher: java -jar on that jar
./gradlew test                 # golden templates, validator corpus, signing, exit codes
```

This is its own Gradle build (tools never ship inside a service). It compiles the Apache-2.0
SDK core from `services/shared/extension-schema/src/main/kotlin` - the same manifest parser,
schema validator, package layout rules, zip audit, canonical JSON and signature code the app
runs - so `validate` accepts and rejects exactly what an install does.

## Commands

| Command | Does |
|---|---|
| `init [dir] --template <t> --publisher <p> [--name] [--display-name] [--yes]` | Scaffold from `theme`, `snippets`, `language-pack`, `lsp-pack`, `toolbar-command`, `wasm-rust` or `wasm-assemblyscript` (templates in `services/shared/extension-templates`). Each passes `validate --strict` as generated (WASM ones after `./build.sh`). |
| `validate [dir or .easyext] [--strict] [--engine <ver>]` | Layout and size limits, the app's manifest pipeline (schema, references, when-clauses, capability audit, scope, content), the app's static WASM check, and with `--strict` registry readiness (README, LICENSE, SPDX `license`, icon size) with warnings failing. |
| `package [dir] [--out <file or dir>]` | Validates, then writes a deterministic zip (sorted entries, DOS-epoch timestamps, fixed DEFLATE level) to `dist/<publisher>.<name>-<version>.easyext` and prints its sha256. Skips `.git/`, `test/`, `guest/`, `target/`, `node_modules/`, `dist/`, keys and `.easyextignore` matches. |
| `keygen --publisher <p> [--out <dir>] [--no-passphrase] [--rotate --from <old.key>]` | Ed25519 key pair in `~/.easyide/keys/` (0700 dir, 0600 key), PBES2-encrypted with a passphrase from `EASYIDE_EXT_KEY_PASSPHRASE` or the terminal; `--rotate` also writes a rotation record signed by the old key. |
| `sign <file.easyext> --key <private.key> [--yes]` | Detached `<file>.easyext.sig` over the exact package bytes. |
| `verify <file.easyext> --pub <key.pub.json> [--sig <file>]` | Checks a detached signature with the verifier the app uses. |
| `test [dir] [--filter <glob>]` | Replays `test/` scenarios with the app's action runner on a recording host with fakes (L1; WASM commands are not executed). |
| `dev [dir] [--device <serial>] [--app-id <id>] [--local] [--watch]` | Installs the folder as a developer extension: over adb (dev inbox + `DEV_RELOAD` broadcast) or, inside an environment, via `<workspace>/.easyide/dev/<id>.json`. Needs `extensions.developerMode` on in the app. |
| `publish <file.easyext> --index-repo <git> --key <k> (--url <https> \| --package-base <https>) [--register-publisher]` | Signs the index entry and pushes it to a branch of the index repo with your own git. |
| `registry build [dir] --root-key <k>` | Maintainers: verifies every entry and root-signs the index, publisher files and revocations. |

Every command takes `--json` (exactly one JSON object on stdout, human text on stderr).
Exit codes: 0 ok, 1 validation or usage error, 2 I/O error. Diagnostic `code`s are stable.

Configuration: `~/.easyide/config.json`, then `./.easyide-ext.json`, then flags
(`publisher`, `key`, `keyId`, `engine`, `packageOut`, ...). Paths only, never secrets.

