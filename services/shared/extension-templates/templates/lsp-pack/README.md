# {{displayName}}

A language server pack. It installs nothing itself: `easyide.sandbox.install` lists the setup
commands, which the user sees on the approval sheet and runs from the "server not installed"
notice in a visible terminal. The example wires up `yaml-language-server`; change `languages`,
`command`, the setup steps and `verify` for your server.

- `languages` must be language ids the editor detects (bundled grammars or your own `contributes.languages`).
- Declare every host the setup downloads from in `network(...)`.
- Users override `command`, `env` and settings per server with `lsp.servers["{{publisher}}.{{name}}/server"]`.

```sh
easyide-ext validate --strict
easyide-ext test
easyide-ext package
```
