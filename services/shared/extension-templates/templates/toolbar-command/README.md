# {{displayName}}

A command on the editor touch toolbar and in the command palette, run by a declarative action. Change `easyide.actions` to run your own steps; add capabilities when an action needs them.

## Develop

```sh
easyide-ext validate --strict   # same checks the app runs at install
easyide-ext package             # writes dist/{{publisher}}.{{name}}-<version>.easyext
```

Install the package in easyIDE from **Extensions > Install from file**, or install this
folder directly with **Install from folder** while you iterate.
