# services/shared/extension-schema

Licensed under the Apache License, Version 2.0 (see `LICENSE` in this directory), unlike the
PolyForm Noncommercial app code elsewhere in this repository
([decision 0015](../../../docs/decision/0015-extension-sdk-licensing-apache.md)).

Copyright 2026 Ravi (https://github.com/ravibh5522/easyIDE) and easyIDE contributors.

Contents, all Apache-2.0:

- `manifest.schema.json`: the single JSON Schema for extension manifests (`package.json`).
- `builtin-commands.json`: command ids the app implements natively; manifests may reference
  them undeclared. The app's `CommandIds.ALL` must match it (`BuiltInCommandsContractTest`).
- `src/main/kotlin`: the SDK core that decides whether a package is valid - JSON helpers,
  `SchemaValidator`, `ManifestParser` and its decoders, when-clause parser, capability rules,
  package layout rules, zip symlink audit, RFC 8785 canonical JSON and ed25519 signature
  checks. Built as the `:extension-schema` Gradle module inside `services/mobile` (the app's
  `:extensions` depends on it) and compiled from source by `tools/easyide-ext`, so the app and
  the CLI (R-ENG-05) accept exactly the same packages.

The Kotlin sources moved here from `services/mobile/extensions` on 2026-09-24 and were
relicensed from PolyForm Noncommercial to Apache-2.0 at the owner's direction (decision 0015,
amendment of that date). The runtime that executes extensions (`ActionRunner`,
`ContributionRegistry`, activation, `WasmHost`, LSP client) stays in `services/mobile` under the
app licence. It uses
only the JSON Schema keyword subset implemented by `SchemaValidator`
(`docs/extension-sdk/lld/extension-runtime.md` sec 2.2); a unit test fails the build if it
uses any other keyword. Nothing in this directory may import app code (R-ENG-04).
