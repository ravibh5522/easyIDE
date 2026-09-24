# extension-templates

The extension templates behind `easyide-ext init` and the app's "Create extension", Apache-2.0
(decision 0015). One directory per template under `templates/`. `files.txt` lists the files
(jar resources and app assets cannot be listed portably), dotfiles are stored as `dot-<name>`, and
`{{publisher}}`, `{{name}}`, `{{displayName}}`, `{{engine}}`, `{{crate}}` and `{{year}}` are substituted
in file contents and paths. The WASM templates' guest bindings (`guest/easyide-guest`,
`guest/assembly/easyide.ts`) are copied in at build time from `services/shared/guest-rust` and
`services/shared/guest-as`, so there is one source.
