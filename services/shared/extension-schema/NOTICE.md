# services/shared/extension-schema

Licensed under the Apache License, Version 2.0 (see `LICENSE` in this directory), unlike the
PolyForm Noncommercial app code elsewhere in this repository
([decision 0015](../../../docs/decision/0015-extension-sdk-licensing-apache.md)).

Copyright 2026 Ravi (https://github.com/ravibh5522/easyIDE) and easyIDE contributors.

`manifest.schema.json` is the single JSON Schema for extension manifests (`package.json`),
loaded by the app's `:extensions` module and by the `easyide-ext` CLI (R-ENG-05). It uses
only the JSON Schema keyword subset implemented by `SchemaValidator`
(`docs/extension-sdk/lld/extension-runtime.md` sec 2.2); a unit test fails the build if it
uses any other keyword. Nothing in this directory may import app code (R-ENG-04).
