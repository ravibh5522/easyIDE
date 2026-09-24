# {{displayName}}

Snippets for easyIDE. Add entries to `snippets/{{name}}.code-snippets` (VS Code snippet format) and list more languages under `contributes.snippets`.

## Develop

```sh
easyide-ext validate --strict   # same checks the app runs at install
easyide-ext package             # writes dist/{{publisher}}.{{name}}-<version>.easyext
```

Install the package in easyIDE from **Extensions > Install from file**, or install this
folder directly with **Install from folder** while you iterate.
