# {{displayName}}

A color theme for easyIDE. Edit `themes/{{name}}-color-theme.json`; `colors` keys without an easyIDE token are ignored, and `tokenColors` scopes map onto the editor's syntax roles.

## Develop

```sh
easyide-ext validate --strict   # same checks the app runs at install
easyide-ext package             # writes dist/{{publisher}}.{{name}}-<version>.easyext
```

Install the package in easyIDE from **Extensions > Install from file**, or install this
folder directly with **Install from folder** while you iterate.
