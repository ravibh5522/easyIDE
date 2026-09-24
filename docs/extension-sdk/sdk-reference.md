# Extension SDK - Reference (PROPOSED, API v0)

Status: **PROPOSED, nothing implemented** (2026-09-23). API v0 means `engines.easyide`
ranges below `1.0.0` may break between minor releases; from `1.0.0` the deprecation window
is 2 minor releases and new capabilities are never silently granted.

This file is the contract: schemas, names, defaults. Goals, layers (L0-L4), LSP client
design, security rationale and milestones live in [arch.md](arch.md). Where this file says
"see arch.md" the *why* is there, not here.

Conventions: `?` after a field name = optional. "Guest path" = a path inside the sandbox
(`/workspace/...`); extensions never see Android host paths. Every user-tunable limit is a
**named config default** ([Settings keys](#settings-keys)); protocol and safety limits that no
settings layer may relax are named policy constants ([Fixed limits](#fixed-limits-and-protected-keys)).
Nothing is an inline constant. "Disclosure" means shown to the user and *not* enforced: proot
and chroot+BusyBox provide no per-extension isolation (0002); only L2 WASM is enforced.

## Package layout

A package is a zip named `<publisher>.<name>-<version>.easyext`.

```
package.json                      REQUIRED  manifest (see Manifest)
README.md, LICENSE[.md]           required for registry publish (SPDX id in manifest); optional sideloaded
CHANGELOG.md                      optional; shown in the update diff
icon.png                          optional; PNG, 128x128 or 256x256, square
language-configuration.json       optional; any path, referenced from contributes
syntaxes/*.tmLanguage.json        optional; TextMate grammars (JSON or plist .tmLanguage)
snippets/*.code-snippets|*.json   optional; VS Code snippet JSON
themes/*-color-theme.json         optional; VS Code color theme JSON
icon-themes/*.json + svg/png      optional; VS Code file icon theme JSON
wasm/*.wasm                       optional; L2 modules, referenced from easyide.wasm
l10n/package.nls[.<locale>].json  optional; `%key%` substitution for manifest strings
media/                            optional; walkthrough images (png, webp, svg)
```

Rules (checked by `easyide-ext validate` and again at install):

- Entries must be relative, `/`-separated, no `..`, no absolute paths, no symlinks, no
  nested archives, no duplicate names (case-insensitive). Unix mode bits are ignored.
- Every path referenced from `package.json` must exist; unreferenced files produce a warning.
- `name` and `publisher`: `^[a-z0-9][a-z0-9-]{0,62}$`. Extension id = `publisher.name`,
  compared case-insensitively. `version`: SemVer 2.0 without build metadata.
- Limits: package <= `extensions.limits.packageMb` (default 50) compressed and
  <= `extensions.limits.unpackedMb` (default 200) unpacked; any single file <=
  `extensions.limits.fileMb` (default 20); each `.wasm` <= `extensions.wasm.maxModuleMb`
  (default 8). Toolchains installed by `easyide.sandbox` do not count; they live in the env.
- Unpacked to `<files>/extensions/global/<id>/<version>/` (global scope) or
  `<files>/environments/<envId>/extensions/<id>/<version>/` (environment scope, removed with
  the env); `<id>/current` -> active version, flipped atomically (lld/registry-and-install.md sec 11).
- Inside the guest the active version is visible at `/opt/easyide/extensions/<id>`
  (bind mount; not a read-only boundary under proot).

## Manifest

`package.json`. VS Code-shaped at the top level; every easyIDE-only key lives under
`"easyide"` so a real `.vsix` manifest never collides and our manifests stay parseable by
VS Code tooling.

| Field | Type | Req | Meaning |
|---|---|---|---|
| `name` | string | yes | Package name, see naming rules. |
| `publisher` | string | yes | Publisher id; must own a key in the registry to publish. |
| `version` | semver | yes | Package version. |
| `displayName` | string | no | Human name; `%nls%` allowed. Defaults to `name`. |
| `description` | string | no | One line; shown in browse and the install prompt. |
| `icon`, `license` | path, SPDX expr | no, registry: yes | PNG in package; e.g. `MIT`, `Apache-2.0`, or `SEE LICENSE IN <file>`. |
| `repository` | string or `{type,url}` | no | Source link shown on the detail page. |
| `engines.easyide` | semver range | yes | App API range, e.g. `^0.3.0`. Install refused outside it. `engines.vscode` is tolerated and ignored. |
| `categories` | string[] | no | `Programming Languages`, `Themes`, `Snippets`, `Linters`, `Formatters`, `Keymaps`, `Language Packs`, `Other`. |
| `activationEvents` | string[] | no | Only affects L2 WASM and language servers; declarative contributions are always live. Values: `onLanguage:<id>`, `onCommand:<id>`, `onView:<id>`, `onStage:<id>`, `workspaceContains:<glob>`, `onStartupFinished`. `*` is treated as `onStartupFinished` with a validate warning. |
| `contributes` | object | no | VS Code contribution points we honour (see Contribution points). Unknown keys: warning, ignored. |
| `easyide` | object | no | easyIDE-only block, below. |

`easyide` block:

| Field | Type | Meaning |
|---|---|---|
| `capabilities` | string[] | Capability ids (see [Capabilities](#capabilities)). Anything not declared is refused at load. |
| `scope` | `"environment"` or `"global"` | Computed and checked, may be stated: any `sandbox`, `languageServers`, `sandbox.*` capability or `sandboxExec`/`runInTerminal`/`runTask` action forces `environment`; everything else (themes, snippets, grammars, key rows, pure WASM) may be `global`. |
| `actions`, `inputs` | object, array | `commandId -> Action` bindings for `contributes.commands` (see Action vocabulary); VS Code `inputs` (`promptString`, `pickString`, `command`) resolving `${input:id}`. |
| `languageServers`, `sandbox` | array, object | LSP server definitions; toolchain requires/install/verify (see Contribution points). |
| `stages`, `keyRows`, `statusBarItems`, `viewData` | arrays/object | Tablet-native contribution points. |
| `wasm` | object | `{ "module": "wasm/main.wasm", "abi": 1, "memoryMb"?: int, "providers"?: [{kind, languages}] }`. |
| `memoryBudgetMb` | int | Sum budget the pack asks for (servers + wasm); shown before install. |

Complete example, Python language pack:
```jsonc
{
  "name": "python", "publisher": "easyide", "version": "1.0.0",
  "displayName": "Python", "description": "Python highlighting, pyright + ruff, run button",
  "icon": "icon.png", "license": "Apache-2.0",
  "repository": { "type": "git", "url": "https://github.com/easyide/ext-python" },
  "engines": { "easyide": "^0.3.0" },
  "categories": ["Programming Languages", "Language Packs"],
  "activationEvents": ["onLanguage:python", "workspaceContains:**/pyproject.toml"],
  "contributes": {
    "languages": [{ "id": "python", "aliases": ["Python"], "extensions": [".py", ".pyi"],
                    "firstLine": "^#!.*\\bpython[0-9.]*\\b", "configuration": "./language-configuration.json" }],
    "grammars": [{ "language": "python", "scopeName": "source.python", "path": "./syntaxes/python.tmLanguage.json" }],
    "snippets": [{ "language": "python", "path": "./snippets/python.code-snippets" }],
    "commands": [{ "command": "python.runFile", "title": "Run Python File", "icon": "play_arrow",
                   "category": "Python", "enablement": "envState == ready" },
                 { "command": "python.selectVenv", "title": "Select Virtualenv", "category": "Python" }],
    "menus": {
      "editor/title": [{ "command": "python.runFile", "when": "editorLangId == python", "group": "navigation@1" }],
      "editor/touchToolbar": [{ "command": "python.runFile", "when": "editorLangId == python" }],
      "commandPalette": [{ "command": "python.selectVenv", "when": "editorLangId == python" }]
    },
    "keybindings": [{ "command": "python.runFile", "key": "ctrl+shift+r", "when": "editorLangId == python" }],
    "configuration": { "title": "Python", "properties": {
      "python.interpreter": { "type": "string", "default": "python3", "scope": "environment", "description": "Used by Run" },
      "python.lint.onType": { "type": "boolean", "default": true, "scope": "language-overridable" } } },
    "configurationDefaults": { "[python]": { "editor.tabSize": 4, "editor.formatOnSave": true } }
  },
  "easyide": {
    "capabilities": ["sandbox.exec", "sandbox.install", "lsp.spawn", "network(pypi.org,files.pythonhosted.org)"],
    "actions": {
      "python.runFile": { "type": "runInTerminal", "terminal": "Python", "cwd": "${fileDirname}",
                          "command": "${config:python.interpreter} ${file}" },
      "python.selectVenv": { "type": "sequence", "steps": [
        { "type": "sandboxExec", "command": ["sh", "-c", "ls -d /workspace/*/bin/python 2>/dev/null"],
          "output": "capture", "as": "venvs" },
        { "type": "showQuickPick", "id": "venv", "itemsFrom": "${result:venvs}", "placeHolder": "Interpreter" },
        { "type": "setConfig", "key": "python.interpreter", "value": "${input:venv}", "target": "project" } ] }
    },
    "languageServers": [
      { "id": "pyright", "languages": ["python"], "command": ["pyright-langserver", "--stdio"],
        "settingsSection": "python", "rootMarkers": ["pyproject.toml", "setup.py", ".git"], "memoryBudgetMb": 500 },
      { "id": "ruff", "languages": ["python"], "command": ["ruff", "server"], "memoryBudgetMb": 80,
        "features": { "only": ["diagnostics", "codeAction", "formatting"] } } ],
    "sandbox": {
      "requires": ["python3"],
      "install": [{ "id": "base", "title": "Install pipx and Node", "run": "apt-get install -y pipx nodejs npm",
                    "when": "envDistro in 'debian,ubuntu'" },
                  { "id": "tools", "title": "Install pyright and ruff", "run": "npm install -g pyright && pipx install ruff" }],
      "verify": "pyright-langserver --version && ruff --version",
      "uninstall": ["npm uninstall -g pyright", "pipx uninstall ruff"]
    },
    "keyRows": [{ "id": "python.symbols", "title": "Python", "when": "editorLangId == python",
                  "keys": [{ "label": ":" }, { "label": "self." , "insert": "self." },
                           { "label": "()", "snippet": "($0)" }, { "label": "Run", "command": "python.runFile" }] }],
    "statusBarItems": [{ "id": "python.interpreter", "text": "${config:python.interpreter}", "command": "python.selectVenv",
                         "alignment": "right", "when": "editorLangId == python" }], "memoryBudgetMb": 600
  }
}
```

Minimal theme pack (installs globally, no capabilities, no prompt beyond the summary):
```json
{ "name": "graphite-night", "publisher": "acme", "version": "0.1.0", "license": "MIT",
  "engines": { "easyide": "^0.3.0" }, "categories": ["Themes"], "contributes": { "themes": [
    { "label": "Graphite Night", "uiTheme": "vs-dark", "path": "./themes/graphite-night-color-theme.json" }] } }
```

## Contribution points

Compat column: **same** = VS Code schema accepted as-is; **subset** = VS Code schema, some
fields ignored (listed); **easyIDE** = easyIDE-only, under `easyide.*`. Every point has a
user override; overrides always beat extension values (layering in [Settings keys](#settings-keys)).

| Point | Key fields | Compat | User override |
|---|---|---|---|
| `languages` | `id, aliases, extensions, filenames, filenamePatterns, firstLine, mimetypes, configuration` | same (`icon` ignored; icon themes do that) | `files.associations` |
| `grammars` | `language?, scopeName, path, embeddedLanguages, injectTo, tokenTypes` | same | `editor.syntaxHighlighting`, `[lang]` grammar choice via `files.associations` |
| `languageConfiguration` | file referenced by `languages[].configuration`: `comments, brackets, autoClosingPairs, surroundingPairs, colorizedBracketPairs, folding.markers, indentationRules, onEnterRules, wordPattern, autoCloseBefore` | same | `editor.autoClosingBrackets`, `editor.autoIndent`, `[lang]` |
| `snippets` | `language, path`; file: `{name: {prefix, body, description, scope?}}` | same (TextMate snippet syntax incl. `${1:x}`, `${1\|a,b\|}`, `$TM_*` vars) | user snippets `snippets/<lang>.json`, `editor.snippetSuggestions` |
| `themes` | `label, uiTheme (vs, vs-dark, hc-black, hc-light), path`; file: `colors, tokenColors, semanticTokenColors, semanticHighlighting` | subset: `colors` keys without an easyIDE token are ignored and listed by validate; `tokenColors` scopes collapse onto the 21 roles of `ScopeRules` (0010) | `workbench.colorTheme`, `workbench.colorCustomizations`, `editor.tokenColorCustomizations`, `editor.semanticTokenColorCustomizations` |
| `iconThemes` | `id, label, path`; file: `iconDefinitions, file, folder, fileExtensions, fileNames, languageIds` | subset: `fonts` (icon fonts) ignored; SVG/PNG only | `workbench.iconTheme` |
| `keybindings` | `command, key, mac?, linux?, when?, args?` | same (`win` ignored; `cmd` maps to Meta) | `keybindings.json` entries; `-command` removes |
| `commands` | `command, title, category?, icon?, enablement?, shortTitle?` | subset: `icon` must be an easyIDE icon token or an SVG in the package (monochrome, theme-tinted) | hide/order via `workbench.contributions.*`; rebind via keybindings |
| `menus` | per menu id: `[{command, when?, group?, alt?}]` | subset (ids below; unknown ids warn) | `workbench.contributions.hidden/order` |
| `configuration` | `title, order?, properties{key: JSON Schema + default, scope, enum, enumDescriptions, markdownDescription, deprecationMessage}` | same; `scope` adds `environment` and `project`; `configurationDefaults` (`{key: value}`, `{"[lang]": {...}}`) same | any settings layer |
| `views` | per container id: `[{id, name, when?, type: tree}]` | subset: content from `easyide.viewData` or WASM, not a JS TreeDataProvider | hide/move via `workbench.contributions.*` |
| `viewsContainers`, `viewsWelcome` | `activitybar` or `panel`: `[{id, title, icon}]`; `view, contents, when?` | same; `activitybar` = left rail, `panel` = bottom stage | hide/order rail entries |
| `taskDefinitions`, `problemMatchers` | `type, required, properties`; `name, owner, pattern, fileLocation, background?` | same | user `.easyide/tasks.json` (may define matchers) |
| `walkthroughs` | `id, title, description, steps[{id, title, description, media{image,markdown}, completionEvents}]` | subset: `media.svg` ok, `media.video` ignored | `workbench.welcome.enabled` |
| `easyide.stages` | `id, title, icon, defaultStage (left, main, right, bottom), views[], when?` | easyIDE | `workbench.stages.placement`, hide |
| `easyide.statusBarItems` | `id, text (vars ok), tooltip?, command?, alignment (left, right), priority?, when?` | easyIDE (VS Code has no declarative form) | hide/order `statusBar` |
| `easyide.keyRows` | `id, title, when?, keys[{label, insert? , snippet?, key?, command?, longPress?}]` (exactly one of insert/snippet/key/command) | easyIDE | `keyRows.layouts`, `keyRows.active` |
| `easyide.languageServers` | see below | easyIDE | `lsp.servers.<id>` (override or disable) |
| `easyide.sandbox` | see below | easyIDE | per-env install log; `extensions.sandbox.confirmEachStep` |
| `easyide.viewData` | `{viewId: {kind: list or tree, from: Action (output capture, JSON), refreshOn[]}}` | easyIDE | `workbench.contributions.hidden` |

Menu ids: `commandPalette`, `editor/title` (tab bar), `editor/title/context` (tab long-press),
`editor/context` (long-press / secondary click), `editor/touchToolbar` (bar above the touch key
row), `editor/selectionToolbar`, `editor/gutter` (line-number long-press), `explorer/context`,
`view/title`, `view/item/context`, `scm/title`, `scm/resourceState/context`, `terminal/context`,
`terminal/title`, `statusBar`, `keyRow` (appended to the active row). `group` sorting is VS Code's
(`navigation` first, then `name@order`).

`easyide.languageServers[]` fields: `id` (required; global key `<extId>/<id>`), `languages`
(required), `command` (required; guest argv, `${extensionPath}` and `${config:...}` allowed),
`env` (`{}`), `initializationOptions` (`{}`), `settingsSection` (answered on
`workspace/configuration` and `didChangeConfiguration`), `rootMarkers` (`[".git"]`; nearest
ancestor holding one is `rootUri`), `memoryBudgetMb` (default `lsp.defaultMemoryBudgetMb`;
RSS kill threshold, counts toward `lsp.globalMemoryBudgetMb`), `idleShutdownSec`,
`startupTimeoutSec` (defaults from the same-named `lsp.*` keys), `features`
(`{only?, exclude?}` feature ids, for pairing e.g. pyright + ruff), `priority` (0; higher wins
when two servers offer a feature).
Feature ids: `diagnostics, completion, hover, signatureHelp, definition, declaration,
typeDefinition, implementation, references, documentHighlight, documentSymbol,
workspaceSymbol, rename, codeAction, codeLens, formatting, rangeFormatting,
onTypeFormatting, inlayHints, semanticTokens, foldingRange, selectionRange, documentLink`.

`easyide.sandbox`: `requires` (command names probed with `command -v`; missing ones make
the pack show "Install toolchain"), `install[]` (`{id, title, run, when?}` shell strings,
run in order in a visible terminal, never silently), `verify` (must exit 0 or the install
is rolled back), `uninstall[]` (best-effort, labelled as such). Context for `when`:
`envDistro`, `envArch`, `envBackend`. Network use during install must be declared as
`network(...)`; under proot this is disclosure only.

## When-clause context

Grammar (same as VS Code): `!`, `&&`, `||`, parentheses, `==`, `!=`, `=~ /regex/flags`,
`<`, `<=`, `>`, `>=`, `in` and `not in` (right side is a context key holding an array or
object, or a quoted comma list, e.g. `envDistro in 'debian,ubuntu'`). Bare key = truthy
test. String literals in single quotes; unquoted right-hand words are strings. Unknown
keys evaluate to `undefined` (falsy), never an error; validate warns on unknown keys.

| Key | Type | Meaning |
|---|---|---|
| `editorLangId`, `resourceLangId` | string | Language of active editor / focused resource. |
| `resourceExtname`, `resourceFilename`, `resourceDirname`, `resourcePath`, `resourceIsFolder` | string (last bool) | Focused resource; paths are guest paths. |
| `editorFocus`, `editorTextFocus`, `editorReadonly`, `activeEditorIsDirty` | bool | Editor focus, read-only buffer, unsaved changes. |
| `editorHasSelection`, `editorHasMultipleSelections` | bool | Selection state. |
| `inSnippetMode`, `suggestWidgetVisible`, `hoverVisible`, `peekVisible` | bool | Editor popups. |
| `terminalFocus`, `terminalProcessRunning`, `explorerFocus`, `scmFocus`, `focusedView` | bool (last string) | Terminal state; focus by area / view id. |
| `stageVisible:<id>`, `focusedStage` | bool, string | Stage (left, main, right, bottom, or contributed id). |
| `hardwareKeyboard` | bool | A physical keyboard is attached. |
| `inputMode` | `touch`, `stylus`, `mouse`, `keyboard` | Last input kind. |
| `windowSizeClass`, `devicePosture` | `compact`/`medium`/`expanded`, `flat`/`book`/`tabletop` | Window width class, foldable posture. |
| `envId`, `envState` | string | Active environment id; `ready`, `provisioning`, `failed`, `stopped`. |
| `envBackend`, `envDistro`, `envArch` | string | `proot`/`chroot`; `debian`, `ubuntu`, `alpine`, ...; `arm64`, `x86_64`. |
| `envHasCommand:<name>` | bool | Cached `command -v <name>` probe (refreshed after installs). |
| `lspReady:<lang>`, `lspState:<lang>` | bool, string | Server finished `initialize`; `off`, `starting`, `ready`, `crashed`, `overBudget`. |
| `lspSupports:<lang>:<feature>` | bool | Server advertised the feature (feature ids above). |
| `gitRepo`, `gitBranch`, `gitDirty` | bool, string, bool | Project git state. |
| `isOffline`, `isSafeMode`, `extensionEnabled:<id>` | bool | No network; started with extensions off; that extension enabled here. |
| `config.<key>` | any | Resolved setting (VS Code form, e.g. `config.editor.formatOnSave`). |

## Action vocabulary

An action is `{ "type": <name>, ...params }`, bound to a command in `easyide.actions`.
Any step's result is bindable with `"as": "<name>"` and read as
`${result:<name>}`. All string params undergo [variable](#variables) substitution first; values
substituted into a shell string (`runInTerminal.command`, `install[].run`) are shell-quoted.
Common error behavior: a failing step aborts its sequence, shows a snackbar with the
command title, and writes the full error to the Extension Log; a user-cancelled prompt
aborts silently. A missing capability is a **load-time** error for the whole extension,
never a runtime surprise.

| type | Params | Result | Capability |
|---|---|---|---|
| `runInTerminal` | `command` (string), `cwd?`, `env?`, `terminal?` (name; reused if exists, else new), `focus?` (true), `clear?` | none (fire and forget) | `sandbox.exec` |
| `runTask` | `task` (label or `{type, ...}` definition) | exit code | `sandbox.exec` |
| `sandboxExec` | `command` (argv), `cwd?`, `env?`, `timeoutSec?` (`extensions.actions.execTimeoutSec`), `output`: `terminal`, `silent`, `capture` | `{exitCode, stdout, stderr}` (capture caps at `extensions.actions.captureKb`) | `sandbox.exec` |
| `openFile` | `path` (guest or workspace-relative), `line?`, `column?`, `preview?` | none | `fs.project(read)`; outside `/workspace` also `fs.outsideProject` |
| `openUrl` | `url` (`https` only) | none | none; always a confirm sheet showing the full URL, then the external browser |
| `applyEdit` | `edits: [{path?, range{start{line,character},end{...}}, text}]` or LSP `WorkspaceEdit` | bool | `fs.project(write)` |
| `insertSnippet` | `snippet` (body) or `name` + `language?` | none | none (active editor only) |
| `setConfig` | `key`, `value`, `target`: `user`, `language`, `environment`, `project` | none | none for own keys; other keys need `ui.settings` |
| `toggleConfig` | `key`, `values?` (cycle list, default `[true,false]`), `target?` | new value | as `setConfig` |
| `lspRequest` | `method`, `params?` (default: position params of caret), `language?`, `then?`: `showLocations`, `applyWorkspaceEdit`, `showMessage`, `none` | LSP result JSON | `lsp.request` |
| `executeCommand` | `command`, `args?` | command result | that command's own |
| `showQuickPick` | `id`, `items: [{label, description?, value?}]` or `itemsFrom` (newline text or JSON array), `placeHolder?`, `canPickMany?` | value(s); binds `${input:id}` | none |
| `showInputBox` | `id`, `prompt?`, `value?`, `placeHolder?`, `validate?` (regex), `password?` | string; binds `${input:id}` | none |
| `showMessage` | `text`, `severity`: `info`, `warning`, `error`, `actions?: [{title, action}]` | chosen title or null | none |
| `revealStage` | `stage` (id), `view?`, `focus?` | none | `ui.stage` for contributed stages |
| `sequence` | `steps: [Action]`, `continueOnError?` (false) | last step's result | union of steps |

Example: format then save only if the server is up.
```json
"acme.fmtSave": { "type": "sequence", "steps": [
  { "type": "lspRequest", "method": "textDocument/formatting", "then": "applyWorkspaceEdit" },
  { "type": "executeCommand", "command": "workbench.action.files.save" } ] }
```
with `"enablement": "lspSupports:python:formatting"` on the command.

## Variables

VS Code variable syntax. Path values are **guest paths**. Unresolvable variable = step error.

| Variable | Meaning |
|---|---|
| `${workspaceFolder}`, `${workspaceFolderBasename}` | Project root in the guest (`/workspace`); project name. |
| `${file}`, `${relativeFile}`, `${fileBasename}`, `${fileBasenameNoExtension}`, `${fileExtname}` | Active file (absolute guest / workspace-relative) and parts of its name. |
| `${fileDirname}`, `${relativeFileDirname}`, `${fileWorkspaceFolder}` | Directory of the active file / its root. |
| `${lineNumber}`, `${column}` | 1-based caret position (`${column}` is easyIDE-only). |
| `${selectedText}`, `${currentWord}`, `${lineText}` | Text at the caret (last two easyIDE-only). |
| `${languageId}`, `${cwd}`, `${pathSeparator}` | Active editor language; task/terminal start dir (default `${workspaceFolder}`); always `/`. |
| `${env:NAME}` | Variable from the environment's configured shell env, never the Android process env. |
| `${config:key}`, `${command:id}` | Resolved setting value; result of executing a command. |
| `${input:id}` | Bound by a prompt step, else resolved by the matching `easyide.inputs` entry. |
| `${result:name}` | Result bound by a previous step's `as` (easyIDE-only). |
| `${extensionPath}`, `${envId}`, `${envName}` | `/opt/easyide/extensions/<publisher.name>`; active environment (easyIDE-only). |

## WASM host API

L2 only. Modules run **in the app process** on an embedded pure-JVM runtime, one instance
and one worker thread per extension, and can reach nothing except `host_call`. Runtime:
Chicory, Apache-2.0 (verified status in ADR [0014](../decision/0014-wasm-logic-layer-chicory.md));
fuel is injected by our own metering pass at load time (lld/wasm-host.md sec 4).

**ABI v1.** Core wasm (no WASI, no Component Model; WIT considered for v2).

| Direction | Symbol | Signature | Meaning |
|---|---|---|---|
| guest export | `memory` | memory | Linear memory, <= `extensions.wasm.maxMemoryMb`. |
| guest export | `alloc`, `free` | `(len: i32) -> i32`, `(ptr: i32, len: i32)` | Buffers the host writes messages into, and their release. |
| guest export | `ext_abi_version` | `() -> i32` | Must return `1`; otherwise load refused. |
| guest export | `ext_activate` | `(ptr: i32, len: i32) -> i32` | Activation message in; returns response pointer. |
| guest export | `ext_handle` | `(ptr: i32, len: i32) -> i32` | Event/command message in; returns response pointer (0 = no response). |
| host import `easyide` | `host_call` | `(ptr: i32, len: i32) -> i32` | Request in; returns pointer to response in guest memory (allocated via guest `alloc`). |

Returned pointers address a buffer `[u32 little-endian length][UTF-8 JSON]`; the receiver
frees it with `free(ptr, 4 + length)`. Messages are UTF-8 JSON, max
`extensions.wasm.maxMessageKb` (default 4096).

```
request   {"v":1,"id":7,"fn":"editor.getText","args":{"range":null}}
response  {"v":1,"id":7,"ok":true,"result":"..."}
error     {"v":1,"id":7,"ok":false,"error":{"code":"E_CAPABILITY","message":"fs.project(read) not granted"}}
event     {"v":1,"type":"event","event":"workspace.didSave","data":{"path":"/workspace/a.py"}}
command   {"v":1,"type":"command","id":3,"command":"acme.count","args":[]}
request   {"v":1,"type":"request","id":9,"method":"provider.completion","params":{"uri","position","languageId","version"}}
```

Error codes: `E_CAPABILITY`, `E_ARGS`, `E_NOT_FOUND`, `E_TIMEOUT`, `E_CANCELLED`, `E_LIMIT`,
`E_UNAVAILABLE` (e.g. env stopped), `E_INTERNAL`. Versioning: `v` is the ABI version;
new host functions are additive within v1 and discoverable via `host.functions`; a
removed or changed function means ABI v2, and the host keeps v1 for the deprecation window.

**Lifecycle.** First matching `activationEvent` -> instantiate -> `ext_abi_version` ->
`ext_activate({"extensionId","version","apiVersion","capabilities":[granted],"settings":{...},"env":{"id","distro","arch"}})`
-> subscribed events and contributed commands arrive through `ext_handle` -> on disable,
uninstall, env stop or memory pressure the host sends `{"type":"deactivate"}` with
`extensions.wasm.deactivateTimeoutMs` to answer, then drops the instance. A trap, fuel
exhaustion or timeout fails that call (`E_LIMIT`/`E_TIMEOUT` logged) and discards the
instance; the next event re-instantiates it. `extensions.wasm.maxCrashes` traps within
`extensions.wasm.crashWindowSec` disables the extension until the user re-enables it.
`host_call` is synchronous from the guest's view; long operations return `{"handle":n}`
and complete by event (`sandbox.output`, `sandbox.exit`, `net.response`). A call that would
re-enter the same busy instance (e.g. `commands.execute` of its own command) gets `E_UNAVAILABLE`.

Host functions (`fn` names), grouped; capability in brackets, none = always available:

| Group | Functions |
|---|---|
| host, log | `host.functions`, `host.info` (app version, API version, locale); `log.write{level: trace, debug, info, warn, error, message}` -> Extension Log panel |
| editor [`fs.project(read)`; edits `fs.project(write)`] | `editor.active` (path, languageId, version, selections), `editor.getText{range?}`, `editor.applyEdits{edits}`, `editor.setSelections`, `editor.insertSnippet`, `editor.decorate{kind: diagnostic, inlay, gutter; items}` |
| workspace fs [`fs.project(read/write)`; paths outside `/workspace` need `fs.outsideProject`] | `fs.read`, `fs.write`, `fs.stat`, `fs.list`, `fs.delete`, `fs.rename`, `fs.watch{glob}` (events `fs.changed`) |
| events | `events.subscribe{names}`: `workspace.didOpen`, `workspace.didChange`, `workspace.didSave`, `workspace.didClose`, `editor.didChangeSelection`, `config.didChange`, `lsp.didChangeState` |
| config | `config.get{key}`, `config.set{key,value,target}` (own keys; others need `ui.settings`) |
| ui | `ui.showMessage`, `ui.showQuickPick`, `ui.showInputBox`, `ui.setStatusBarItem{id,...}`, `ui.setViewData{viewId, items}` [`ui.stage` for stage content], `ui.revealStage` |
| commands | `commands.execute{command,args}`, `commands.register{command}` (must be declared in `contributes.commands`) |
| providers | `providers.register{kind: completion or hover, languages}` (kind declared in `easyide.wasm.providers`); `request` messages answered with LSP result shapes |
| lsp [`lsp.request`] | `lsp.request{language, method, params}`, `lsp.notify`, `lsp.status{language}` |
| sandbox [`sandbox.exec`] | `sandbox.exec{argv, cwd?, env?, stdin?, output: capture or terminal}` -> handle, `sandbox.kill{handle}` |
| clipboard [`clipboard`] | `clipboard.read`, `clipboard.write` |
| net [`network(hosts)`] | `net.fetch{url, method, headers, body}`; host must match a declared host (suffix match on `*.x`); https only; no redirect to an undeclared host; loopback, link-local and private addresses refused; `extensions.wasm.netMaxResponseKb` |
| storage | `storage.get/set/delete/keys{scope: global, environment, project}`; quota `extensions.storage.quotaKb` |
| secrets [`secrets.read`] | `secrets.get{name}`: only secrets the user entered for this extension; git tokens (0012) are never reachable |

Limits: memory, fuel (instructions per call), wall clock per call and per activation, host
calls per call, message size - all `extensions.wasm.*` keys in [Settings keys](#settings-keys).

Rust guest (the SDK ships an `easyide-guest` crate wrapping this; shown raw for the ABI):
```rust
use serde_json::{json, Value};
#[link(wasm_import_module = "easyide")]
extern "C" { fn host_call(ptr: *const u8, len: u32) -> *mut u8; }
#[no_mangle] pub extern "C" fn ext_abi_version() -> i32 { 1 }  // manifest: easyide.wasm + fs.project(read)
#[no_mangle] pub extern "C" fn alloc(len: u32) -> *mut u8 { let mut v = Vec::<u8>::with_capacity(len as usize); let p = v.as_mut_ptr(); std::mem::forget(v); p }
#[no_mangle] pub unsafe extern "C" fn free(ptr: *mut u8, len: u32) { drop(Vec::from_raw_parts(ptr, 0, len as usize)) }

unsafe fn read(ptr: *mut u8) -> Value {       // [u32 len][json], then free
    let n = u32::from_le_bytes(*(ptr as *const [u8; 4])) as usize;
    let v = serde_json::from_slice(std::slice::from_raw_parts(ptr.add(4), n)).unwrap();
    free(ptr, 4 + n as u32); v }
fn reply(v: &Value) -> *mut u8 {
    let b = serde_json::to_vec(v).unwrap(); let p = alloc(4 + b.len() as u32);
    unsafe { p.copy_from((b.len() as u32).to_le_bytes().as_ptr(), 4); p.add(4).copy_from(b.as_ptr(), b.len()); } p }
fn call(f: &str, args: Value) -> Value {
    let req = serde_json::to_vec(&json!({"v":1,"id":1,"fn":f,"args":args})).unwrap();
    unsafe { read(host_call(req.as_ptr(), req.len() as u32)) } }
#[no_mangle] pub unsafe extern "C" fn ext_activate(p: *mut u8, n: u32) -> *mut u8 {
    free(p, n); call("commands.register", json!({"command":"acme.wordCount"})); reply(&json!({"v":1,"ok":true})) }
#[no_mangle] pub unsafe extern "C" fn ext_handle(p: *mut u8, n: u32) -> *mut u8 {
    let msg: Value = serde_json::from_slice(std::slice::from_raw_parts(p, n as usize)).unwrap(); free(p, n);
    if msg["command"] == "acme.wordCount" {
        let text = call("editor.getText", json!({}))["result"].as_str().unwrap_or("").to_owned();
        call("ui.showMessage", json!({"text": format!("{} words", text.split_whitespace().count())}));
        return reply(&json!({"v":1,"id":msg["id"],"ok":true}));
    }
    std::ptr::null_mut() }
```

AssemblyScript guests use `@easyide/guest-as` (sketch in [author-guide.md](author-guide.md#43-cargotoml-and-code)).

## Capabilities

Declared in `easyide.capabilities`; shown on the install sheet and again on any update that
adds one. **Enforced** = the runtime refuses the call. **Disclosure** = shown, not prevented:
anything running in the sandbox can do what any process in the sandbox can do.

| Id | Grants | L2 WASM | L1 declarative | Prompt text |
|---|---|---|---|---|
| `sandbox.exec` | Run commands in the environment (actions `runInTerminal`, `runTask`, `sandboxExec`; host `sandbox.exec`) | enforced at the call; the spawned process itself is unconstrained | disclosure | "Can run commands in environment <env>. Those commands can read and change anything in that environment and your project." |
| `sandbox.install` | `easyide.sandbox.install` steps | n/a | disclosure; steps are shown and run visibly | "Will install software into <env>: <step titles>." |
| `network(h1,h2,...)` | Outbound https to listed hosts (`*.x` allowed) | enforced for `net.fetch` | disclosure (sandbox processes can reach any host) | "Connects to: <hosts>." |
| `fs.project(read)` / `fs.project(write)` | Read / write files under the project | enforced | disclosure for sandbox commands; in-app actions enforced | "Can read [and change] files in this project." |
| `fs.outsideProject` | Paths outside `/workspace` | enforced | disclosure | "Can access files outside the project, including other projects in this environment." |
| `lsp.spawn` | Start the declared language servers | n/a | disclosure (servers are sandbox processes) | "Starts language servers: <commands> (up to <N> MB)." |
| `lsp.request` | Send arbitrary LSP requests to running servers | enforced | enforced (in-app) | "Can query language servers about your code." |
| `clipboard` | Read and write the clipboard | enforced | enforced | "Can read and change your clipboard." |
| `ui.stage` | Own a stage or fill contributed views | enforced | enforced | "Adds a panel: <title>." |
| `ui.settings` | Change settings it does not own | enforced | enforced | "Can change editor settings outside its own." |
| `secrets.read` | Secrets the user enters for this extension | enforced | n/a | "Can use secrets you give it. It never sees git credentials." |

Always available with no prompt: messages, quick pick, input box, own status bar items,
own settings, own storage, logging, `insertSnippet`, `openUrl` (confirm per use).
Git tokens (0012) and Claude Code API keys are not reachable through any capability.

## Settings keys

Layering (later wins): built-in default < extension `configurationDefaults` < user <
environment < project `.easyide/settings.json`; inside a layer `[lang]` beats plain. Scope: G = user global only;
E = global + environment; P = every layer except `[lang]`; L = every layer, also inside
`[lang]` blocks (which may appear in any layer's file). Rendered by the Pillar 5 schema
(`ux-overhaul/arch.md`); contributed `configuration` shows as native rows.

| Key | Type | Default | Scope |
|---|---|---|---|
| `extensions.enabled` / `safeMode` | bool | true / false (also launcher long-press "Start in safe mode") | G |
| `extensions.disabled` | string[] ids | `[]` | P |
| `extensions.registries` | `[{id, url, rootKey}]` | `[{id: "easyide", ...}]` | G |
| `extensions.openVsx.enabled` | bool | false | G |
| `extensions.autoCheckUpdates` | `off`, `daily`, `weekly` | `weekly` (notify only, never install) | G |
| `extensions.developerMode` / `wasm.enabled` | bool | false (enables `dev` push, Extension Log verbosity, contribution inspector) / true | G |
| `extensions.sandbox.confirmEachStep` | bool | false | E |
| `extensions.limits.packageMb` / `unpackedMb` / `fileMb` | int | 50 / 200 / 20 | G |
| `extensions.actions.execTimeoutSec` / `captureKb` | int | 600 / 1024 | G |
| `extensions.wasm.maxModuleMb` / `maxMemoryMb` | int | 8 / 64 | G |
| `extensions.wasm.fuelPerCall` / `maxHostCallsPerCall` | int | 50000000 / 1000 | G |
| `extensions.wasm.callTimeoutMs` / `activateTimeoutMs` / `deactivateTimeoutMs` | int | 2000 / 5000 / 2000 | G |
| `extensions.wasm.maxMessageKb` / `netMaxResponseKb` | int | 4096 / 4096 | G |
| `extensions.wasm.maxCrashes` / `crashWindowSec`; `extensions.storage.quotaKb` | int | 3 / 300; 5120 | G |
| `lsp.enabled` | bool | true | L |
| `lsp.globalMemoryBudgetMb` / `defaultMemoryBudgetMb` | int | 1200 / 400 | G |
| `lsp.maxServers` | int | 3 (LRU shutdown beyond; `lsp.trace`: `off`, `messages`, `verbose`, default `off`) | G |
| `lsp.idleShutdownSec` / `startupTimeoutSec`; `lsp.restart.maxRetries` / `backoffMs` | int | 600 / 30; 3 / 2000 (doubling) | G |
| `lsp.didChangeDebounceMs` / `requestTimeoutMs` | int | 150 / 5000 | G |
| `lsp.servers` | object, schema below | `{}` | P |
| `editor.quickSuggestions` / `quickSuggestionsDelay` | bool or `{other, comments, strings}` / int ms | `{other: true, comments: false, strings: false}` / 10 | L |
| `editor.suggestOnTriggerCharacters` / `acceptSuggestionOnEnter` | bool / `on`, `off`, `smart` | true / `on` | L |
| `editor.suggest.showSnippets` / `editor.snippetSuggestions` | bool / `top`, `bottom`, `inline`, `none` | true / `inline` | L |
| `editor.wordBasedSuggestions` / `parameterHints.enabled` / `hover.enabled` / `hover.delay` | bool / bool / bool / int ms | true / true / true / 500 | L |
| `editor.inlayHints.enabled` | `on`, `off`, `onUnlessPressed` | `on` | L |
| `editor.semanticHighlighting.enabled` | bool or `configuredByTheme` | `configuredByTheme` | L |
| `editor.formatOnSave` / `formatOnType` / `formatOnPaste` | bool | false / false / false | L |
| `editor.defaultFormatter` | server key `<extId>/<id>` | none | L |
| `editor.codeActionsOnSave` | `{kind: bool}` e.g. `{"source.organizeImports": true}` | `{}` | L |
| `editor.syntaxHighlighting` / `bracketPairColorization` / `treeSitter.enabled` | bool | true / true / false | L |
| `editor.matchBrackets` / `autoClosingBrackets` / `autoSurround` / `autoIndent` | VS Code enums | `always` / `languageDefined` / `languageDefined` / `full` | L |
| `editor.codeLens` / `lightbulb.enabled` / `occurrencesHighlight` / `links` | bool | true each | L |
| `editor.folding` / `foldingStrategy` | bool / `auto`, `indentation` | true / `auto` | L |
| `editor.diagnostics.minSeverity` / `showInGutter` / `showSquiggles` / `ignoreSources` | `error`, `warning`, `information`, `hint` / bool / bool / string[] | `hint` / true / true / `[]` | L |
| `files.associations` | `{glob: languageId}` | `{}` | P |
| `workbench.colorTheme` / `workbench.iconTheme` | theme label / id | built-in | G |
| `workbench.colorCustomizations` | `{token: color}` or `{"[Theme]": {...}}` | `{}` | G |
| `editor.tokenColorCustomizations` | `{roles: {...}, textMateRules: [...]}`, `[Theme]` nesting | `{}` | G |
| `editor.semanticTokenColorCustomizations` | `{enabled, rules: {selector: style}}` | `{}` | G |
| `workbench.contributions.hidden` | string[] refs `<kind>:<location>:<id>` e.g. `menu:editor/title:python.runFile`, `view:python.venvs`, `statusBar:python.interpreter`, `stage:acme.todo`, `keyRow:python.symbols` | `[]` | P |
| `workbench.contributions.order` | `{location: [id...]}` (unlisted ids keep default order after) | `{}` | P |
| `workbench.stages.placement` | `{stageId: left, main, right, bottom}` | `{}` | P |
| `keyRows.layouts` / `keyRows.active` | `[{id, title, keys[...]}]` / row id or `auto` (first matching `when`) | `[]` / `auto` | G / L |
| `profiles.active` / `workbench.welcome.enabled` / `breadcrumbs.enabled` | profile name / bool / bool | `default` / true / true | G |

Not in settings JSON (own files, same layering where meaningful): `keybindings.json` (VS Code
array; `"-cmd"` removes a binding), `snippets/<lang>.json`, `.easyide/tasks.json`,
`profiles/<name>.json` (`{settings, keybindings, enabledExtensions, keyRows}`), exported
together as one zip via SAF (secrets excluded).

`lsp.servers` schema: key = server key (`<extId>/<id>` to override a contributed server,
any other string to add one with no extension).

```jsonc
"lsp.servers": {
  "easyide.python/pyright": { "enabled": false },
  "easyide.python/ruff":    { "memoryBudgetMb": 120, "env": { "RUFF_CACHE_DIR": "/tmp/ruff" } },
  "my-zig": { "languages": ["zig"], "command": ["zls"], "rootMarkers": ["build.zig"],
              "initializationOptions": { "enable_inlay_hints": true }, "memoryBudgetMb": 300 }
},
"[python]": { "editor.inlayHints.enabled": "off", "editor.diagnostics.minSeverity": "warning" }
```
Accepted fields: every `easyide.languageServers` field plus `enabled` (bool). Needs the binary in
the environment; no capability prompt (the user wrote it; project-layer values need project trust).

## Fixed limits and protected keys

Not settings on purpose: no layer (a cloned project file included) may relax them. Each is a
named constant in its owner LLD's policy table; values are starting points to profile.

| Constant | Start | Guards | Owner |
|---|---|---|---|
| `LspPolicy.MAX_MESSAGE_BYTES` / `MAX_JSON_DEPTH` / `MAX_FULL_SYNC_BYTES` / `MEMORY_SAMPLE_MS` / `LOG_RING_LINES` / `CRASH_WINDOW_MS` | 64 MB / 256 / 2 MB / 5000 / 2000 / 300000 | JSON-RPC frames; Full-sync docs (larger: not synced, UI says so); RSS sampler; Extension Log ring; `lsp.restart.*` window | lld/lsp-client.md |
| `SettingsPolicy.MAX_FILE_BYTES` / `MAX_JSON_DEPTH` | 1 MB / 64 | settings files (over: treated as a parse error) | lld/customization.md |
| `ExtensionPolicy.GRAMMAR_LINE_TIME_LIMIT_MS` | 50 | per-line tokenization of extension grammars | lld/extension-runtime.md |
| `RegistryPolicy.staleIndexWarnDays` | 14 | index age warning (freeze by a MITM or dead host) | lld/registry-and-install.md |

Always on, no key: `setConfig` / `config.set` never write `extensions.*`, `lsp.servers`,
`profiles.active` or keybinding files, even with `ui.settings`. Exec-bearing project-layer values
(`lsp.servers.*.command`/`.env`/`.initializationOptions`, `execBearing` built-ins) are ignored
until the user trusts the project; the prompt shows argv and env (lld/customization.md sec 12).

## CLI

`easyide-ext` is a JVM jar (Java 17+), also preinstalled in easyIDE environments. Exit codes:
0 ok, 1 validation/usage error, 2 I/O or network error. `--json` on every subcommand for
machine output.

| Command | Flags | Does |
|---|---|---|
| `init [dir]` | `--template <t>`, `--name`, `--publisher`, `--yes` | Scaffold. Templates: `theme`, `snippets`, `language-pack`, `lsp-pack`, `toolbar-command`, `wasm-rust`, `wasm-assemblyscript`. |
| `validate [dir or file]` | `--strict` (warnings fail), `--engine <ver>` | JSON Schema check, path/asset checks, grammar and theme parse, when-clause parse, unknown context keys, capability audit (every action/host fn used vs declared, and declared but unused), size limits, VS Code keys ignored by easyIDE. |
| `package [dir]` | `--out <file>` | Validates, then writes a deterministic zip (sorted entries, fixed timestamps) and prints its sha256. |
| `keygen`, `sign <file.easyext>` | `--out <dir>`, `--rotate --from <old.key>`; `--key <private>` | ed25519 keypair (prints `keyId`; private key stays local); `--rotate` also writes a `rotation` record; detached `<file>.easyext.sig` over the exact package bytes. |
| `publish <file.easyext>` | `--index-repo <git url>`, `--key <private>`, `--branch`, `--url <https>`, `--fork <url>`, `--register-publisher` | Builds and signs the entry, commits `entries/<publisher>/<name>/<version>.json` (package under `packages/` unless `--url`) to a branch, prints the PR URL; your own git credentials push. `--register-publisher` adds `publishers/<p>.json` for root signing. |
| `verify <file.easyext>` | `--pub <key.pub.json>`, `--sig <file>` | Checks the detached `<file>.easyext.sig` against a publisher public key with the same verifier the app uses. |
| `registry build` | `--root-key <k>` | Maintainers: regenerate `index.json` from `entries/**` (each entry verified), set `generatedAt`, root-sign the `.sig` files. |
| `test [dir]` | `--filter <glob>`, `--engine <ver>` | Headless harness: loads the manifest, evaluates when-clauses from fixture contexts, runs actions against a fake host, runs WASM with the real limits, and replays `test/*.json` scenarios (format: lld/cli.md sec 5.6). No device needed. |
| `dev [dir]` | `--device <serial>`, `--watch`, `--local`, `--app-id <id>` (canary) | Build + install as an unpacked dev extension with live reload. Over adb: pushes to the app's dev inbox and signals a reload (requires `extensions.developerMode`). `--local` inside an easyIDE environment: registers the project folder as a dev extension directly. |

In-app equivalents (command sequences: [author-guide.md](author-guide.md)): "Create extension",
"Install from folder", dev live reload, Extension Log panel, contribution inspector (which
extension contributed each button, row, key and setting, and which user override hides it).

## Registry index format

A git repository served as static files (no backend):
```
index.json (+ .sig)        all extension entries          `registry build`, signed by registry root key
entries/<p>/<name>/<ver>.json  one publisher-signed entry    what `publish` PRs add
publishers/<publisher>.json publisher keys                signed by registry root key (+ .sig)
revocations.json (+ .sig)  revoked keys and versions      signed by registry root key
packages/ (optional)       or any https URL in entry.url
```

Signatures: ed25519. Signed bytes are the **canonical JSON** (RFC 8785 JCS) of the object
with its `signature` field removed; signed JSON holds integers only (in +-(2^53-1)), any other
number invalidates the document. JSON `.sig` files are `{"keyId","alg":"ed25519","sig":base64}`
over the canonical bytes of the whole file; `<file>.easyext.sig` is over the exact package
bytes. `sigByOld` signs `Jcs({"publisher","from","to"})`. `keyId` = first 16 hex chars of
sha256(raw public key). Package integrity is `sha256` of the exact `.easyext` bytes, carried
inside the publisher-signed entry.

`index.json`:
```json
{ "schemaVersion": 1, "generatedAt": "2026-09-23T10:00:00Z", "minAppVersion": "0.3.0",
  "extensions": [{
    "id": "easyide.python", "publisher": "easyide", "name": "python", "version": "1.0.0",
    "displayName": "Python", "description": "Python highlighting, pyright + ruff, run button",
    "categories": ["Programming Languages"], "license": "Apache-2.0", "memoryBudgetMb": 600,
    "engines": { "easyide": "^0.3.0" }, "scope": "environment", "layers": ["L1"],
    "capabilities": ["sandbox.exec", "sandbox.install", "lsp.spawn", "network(pypi.org,files.pythonhosted.org)"],
    "url": "https://github.com/easyide/ext-python/releases/download/v1.0.0/easyide.python-1.0.0.easyext",
    "size": 1843200, "sha256": "9f2c...e41a", "publishedAt": "2026-09-20T08:12:00Z",
    "signature": { "keyId": "a1b2c3d4e5f60718", "alg": "ed25519", "sig": "MEUCIQ..." }
  }] }
```

`publishers/<publisher>.json` and `revocations.json`:
```jsonc
{ "publisher": "easyide", "displayName": "easyIDE",
  "keys": [{ "keyId": "a1b2c3d4e5f60718", "publicKey": "base64...", "added": "2026-09-01", "status": "active" }],
  "rotation": [{ "from": "0011223344556677", "to": "a1b2c3d4e5f60718", "sigByOld": "base64..." }] }
{ "schemaVersion": 1, "updatedAt": "2026-09-22T00:00:00Z",
  "keys": [{ "keyId": "0011223344556677", "reason": "compromised", "since": "2026-09-01" }],
  "versions": [{ "id": "acme.zig", "versions": "<=0.1.3", "reason": "malicious install step" }] }
```

Client verification, in order; any failure is a hard stop with the reason shown:

1. `index.json.sig` and `revocations.json.sig` verify against the registry root key pinned in
   `extensions.registries` (shipped in the APK), and neither `generatedAt` nor
   `revocations.updatedAt` is older than the last verified copy (anti-rollback).
2. The entry's `signature` verifies against a key in `publishers/<publisher>.json` that is
   `active` and not revoked.
3. TOFU pin: the first install pins `publisher -> keyId` per device. A later different
   keyId is accepted only via a `rotation` record signed by the old key; otherwise hard fail.
4. Downloaded bytes match `size` and `sha256`; then `engines.easyide` must match and the
   capabilities are shown for approval.

Revocation: an installed revoked version is disabled at next refresh and the user notified,
never auto-uninstalled (sandbox changes need the user). Offline: the last verified index, sigs
and cached packages install with no network; the UI shows index age. Sideloaded `.easyext` files: verified against `<file>.easyext.sig` and the TOFU
pin when present, otherwise installed with an "unsigned" warning and the same capability
sheet. Open VSX entries carry Open VSX's sha256 only and are labelled "not signed by an
easyIDE-registry publisher"; see arch.md for which `.vsix` contents we can run.
