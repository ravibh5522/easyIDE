# 0015 - Extension SDK licensing: schema, guest bindings, CLI and samples under Apache-2.0

Status: Accepted (2026-09-24)

## Context

[0008](0008-noncommercial-source-available-licensing.md) put easyIDE's own source under
PolyForm Noncommercial 1.0.0 with a paid commercial license. That is the right answer for
the app, and the wrong one for the pieces third parties build *with*:

- An extension author links the guest bindings (`easyide-guest` crate, `@easyide/guest-as`)
  into their WASM module and copies template files into their package. Under PolyForm NC
  every commercial author - a company publishing a free theme, a paid language pack -
  would need a commercial license from us just to ship an extension.
- The manifest JSON Schema and the CLI are needed by CI systems, editors and other tools
  that validate `.easyext` packages. Noncommercial terms make that unusable for most of
  them.
- An ecosystem needs authors more than we need control over the SDK. The app, which is
  what 0008 protects, is unaffected by the SDK's license.

0008's inbound CLA grants the licensor sublicensing rights, so relicensing our own SDK
files is within our power (Extension SDK arch.md, ADR-G).

## Decision

These parts are licensed **Apache-2.0**, each directory carrying its own `LICENSE` file:

- the manifest JSON Schema in `services/shared/extension-schema/`;
- the guest bindings (`easyide-guest` Rust crate, `@easyide/guest-as`);
- the `easyide-ext` CLI in `tools/easyide-ext/`;
- `init` templates, samples, and first-party extension packs (e.g. `easyide.python`).

**Everything else stays under 0008**, including the in-app loader, `ContributionRegistry`,
`ActionRunner`, LSP client and `WasmHost`. Extensions are separate works; their authors
choose their own license (declared as an SPDX expression in `package.json`).

## Alternatives considered

- **Everything under PolyForm NC (no change).** Rejected: makes commercial authoring a
  sales conversation and blocks tool integration; the ecosystem would not form.
- **MIT.** Equally permissive, but no explicit patent grant. Apache-2.0 matches the rest of
  the dependency tree (Kotlin, AndroidX, Chicory) and gives authors that grant.
- **A linking exception on PolyForm NC.** Bespoke license text, no case law, no tooling
  recognition - the same reasons 0008 rejected a custom license.
- **Relicense the whole app Apache-2.0.** Out of scope; would reverse 0008.

## Consequences

- The repo becomes **mixed-license**. Directory-level `LICENSE` files and `NOTICE.md` must
  state which paths are Apache-2.0; a file moving between an Apache path and an app path
  changes its license and needs review.
- The schema lives in `services/shared/`, which the app depends on. That dependency is
  fine (Apache-2.0 into PolyForm NC); the reverse - Apache code importing app code - is
  forbidden, or the SDK pieces stop being usable on their own terms.
- The SDK pieces may be described as open source; the app still may not (0008).
- Contributions to SDK paths still go through the CLA, so a later relicense stays possible.
- Anyone can fork the CLI and schema, including to build a competing host. Accepted: the
  format was designed to be open (0013).
- Not legal advice; the directory split should be reviewed with the CLA before the first
  external SDK contribution is merged.

## Amendment (2026-09-24): the validation core is part of the SDK

Building `easyide-ext` exposed a conflict: the CLI must reject exactly what the app rejects
(lld/cli.md sec 1), so it has to run the app's manifest parser, but that parser lived in the
PolyForm NC `:extensions` module, and Apache code may not import app code. Reimplementing it
would reintroduce the schema drift this design exists to prevent.

At the owner's direction, the code that decides whether a package is valid moved to
`services/shared/extension-schema/src/main/kotlin` and is **Apache-2.0**: JSON helpers,
`SchemaValidator`, `ManifestParser` and its decoders, the when-clause lexer/parser and
`WhenExpr`, capability rules, the `Action`/`Contributions` data model the parser produces,
`PackageLayoutReader`, `ZipSymlinks`, `ExtensionSettings`/`SettingsPort` (key definitions),
`ExtensionPolicy`/`AppApi`, and the new `Jcs`/signature code; plus `builtin-commands.json`.
It builds as `:extension-schema`, which `:extensions` depends on.

What **executes** extensions stays under 0008: `ActionRunner`/`StepExecutor`,
`VariableResolver`, `ContributionRegistry`/`ContributionResolver`, `WhenEvaluator` and
`ContextKeyService`, activation and the extension host, `:ext-wasm` (`WasmHost`,
`WasmModuleLoader`) and `:lsp`. Consequence for the CLI: WASM static validation and the
headless `test` harness, which need those runtimes, cannot live in the Apache CLI as designed.
`validate` checks only the WASM header until that is resolved (options: move the module
validator and metering pass to the SDK core, or ship `test` as a separate non-Apache tool).
