# Extension SDK - Author guide

Zero-to-published tutorials for third-party extension authors: a theme, a snippet + key
row pack, a language pack with a language server, and a WASM extension in Rust.
Status: PROPOSED (2026-09-23) - nothing here runs yet; commands and fields follow
[sdk-reference.md](sdk-reference.md) (API v0). Design background: [arch.md](arch.md).
Rules every published extension must meet: [rules.md](rules.md) section R-EXT.

## Before you start

**What you need**

- Java 17+ and the `easyide-ext` CLI (a jar; it is also preinstalled in every easyIDE
  environment, so you can author entirely on the tablet in an easyIDE terminal).
- For on-device testing from a computer: `adb`, a USB-debuggable device, and
  **Settings > Extensions > Developer mode** (`extensions.developerMode`) switched on.
- For tutorial 4 only: a Rust toolchain with the `wasm32-unknown-unknown` target.

**Three things to know about trust** - they shape what you can promise your users:

1. Declarative contributions (themes, snippets, key rows, commands, menus) run no code of
   yours. The app executes a fixed set of actions on your behalf.
2. Anything that runs **in the sandbox** - install steps, language servers,
   `runInTerminal`/`sandboxExec` actions - is **not contained**. proot and chroot+BusyBox
   do not isolate one extension from another or from the user's projects. Capabilities
   for these are disclosure: the user sees what you will run and decides.
3. WASM extensions run in the app, and every call they make to the host is checked
   against the capabilities you declared and the user approved. That check is enforced
   by easyIDE; it is not an audited sandbox, and you should not describe it as one.

Git credentials and the Claude Code API key are never reachable by any extension, through
any capability.

**Layers you will meet**: L1 = declarative data (tutorials 1-3), L2 = WASM (tutorial 4).

## The loop every tutorial uses

```
easyide-ext init --template <t> --publisher <you> --name <ext> ./<dir>   # scaffold
easyide-ext validate --strict ./<dir>                                    # schema, assets, capabilities
easyide-ext test ./<dir>                                                 # headless harness, no device
easyide-ext dev --watch --device <serial> ./<dir>                        # live reload on a tablet
easyide-ext package ./<dir> --out dist/                                  # deterministic .easyext + sha256
easyide-ext publish dist/<you>.<ext>-<ver>.easyext \
    --index-repo https://github.com/easyide/extension-index --key ~/.easyide/<you>.key
```

Exit codes: 0 ok, 1 validation/usage error, 2 I/O or network error. Add `--json` for
machine-readable output in your own CI.

Without a computer: in easyIDE, **Create extension** offers the same templates inside a
project; **Install from folder** installs the project as a dev extension with live reload
on save; `easyide-ext dev --local` does the same from the terminal.

## One-time publisher setup

1. Pick a publisher id: lowercase letters, digits and `-`, 1-63 chars
   (`^[a-z0-9][a-z0-9-]{0,62}$`). `easyide` and names implying first-party are reserved.
2. Generate a signing key, once:
   ```
   easyide-ext keygen --out ~/.easyide/
   ```
   It prints your `keyId`. The private key never leaves your machine; back it up offline.
   A lost key cannot be rotated (`keygen --rotate` needs the old key) - you would need a
   new publisher approval.
3. Your first `publish` adds `--register-publisher`, so the PR also adds your public key. A registry
   maintainer signs `publishers/<you>.json` with the registry root key when merging; after
   that, only packages signed by your key verify as yours.

---

## Tutorial 1 - Theme pack (zero code, ~10 minutes)

Goal: ship "Graphite Night", a dark colour theme, installable globally with no
capabilities.

### 1.1 Scaffold

```
easyide-ext init --template theme --publisher acme --name graphite-night ./graphite-night
```

```
graphite-night/
  package.json
  README.md
  LICENSE
  CHANGELOG.md
  icon.png                               128x128 or 256x256 square PNG
  themes/graphite-night-color-theme.json
```

### 1.2 Manifest

```json
{ "name": "graphite-night", "publisher": "acme", "version": "0.1.0",
  "displayName": "Graphite Night", "description": "Low-contrast dark theme for long sessions",
  "icon": "icon.png", "license": "MIT",
  "repository": { "type": "git", "url": "https://github.com/acme/graphite-night" },
  "engines": { "easyide": "^0.3.0" }, "categories": ["Themes"],
  "contributes": { "themes": [{ "label": "Graphite Night", "uiTheme": "vs-dark",
                                "path": "./themes/graphite-night-color-theme.json" }] } }
```

No `easyide` block and no capabilities: the install sheet shows only a summary.

### 1.3 Theme file

A standard VS Code colour theme. Existing VS Code themes work as-is (keep their license).

```json
{ "name": "Graphite Night", "type": "dark",
  "colors": { "editor.background": "#1b1d21", "editor.foreground": "#c9ccd1",
              "editorLineNumber.foreground": "#5a5f68", "editorCursor.foreground": "#8ab4f8" },
  "tokenColors": [
    { "scope": "comment", "settings": { "foreground": "#6b7280", "fontStyle": "italic" } },
    { "scope": ["keyword", "storage"], "settings": { "foreground": "#c792ea" } },
    { "scope": "string", "settings": { "foreground": "#a5d6a7" } },
    { "scope": ["entity.name.function", "support.function"], "settings": { "foreground": "#82aaff" } } ] }
```

What to expect:

- `colors` keys with no easyIDE token are ignored; `validate` lists them so you know.
- `tokenColors` scopes collapse onto easyIDE's 21 syntax roles, so very fine-grained scope
  rules may render the same colour. Check on device.
- Users can still override anything with `workbench.colorCustomizations` and
  `editor.tokenColorCustomizations` (optionally inside a `"[Graphite Night]"` block).

### 1.4 Validate, test, try on device

```
easyide-ext validate --strict ./graphite-night
easyide-ext dev --watch --device R52T1234 ./graphite-night
```

On the tablet: **Settings > Theme** now lists "Graphite Night (dev)". Edit a colour and
save; the `--watch` loop repackages and the app reloads it. Check light surfaces you did
not define (dialogs, Problems panel) still read well, and try with a hardware keyboard and
without.

### 1.5 Publish

```
easyide-ext package ./graphite-night --out dist/
easyide-ext publish dist/acme.graphite-night-0.1.0.easyext \
    --index-repo https://github.com/easyide/extension-index --key ~/.easyide/acme.key
```

`publish` computes the index entry, signs it, commits it to a branch and prints the PR URL.
Open the PR; pushing uses your own git credentials. Registry CI re-validates the package,
checks its sha256 and size, and a maintainer merges. The package `url` must be an
immutable https location (for example a GitHub release asset).

---

## Tutorial 2 - Snippet + key row pack (zero code, ~15 minutes)

Goal: Python snippets plus a touch key row with `self.`, `()`, `:` and a snippet key,
shown only in Python files.

### 2.1 Scaffold and tree

```
easyide-ext init --template snippets --publisher acme --name py-touch ./py-touch
```

```
py-touch/
  package.json
  README.md  LICENSE  CHANGELOG.md
  snippets/python.code-snippets
  l10n/package.nls.json
  test/keyrow-python.json
```

### 2.2 Manifest

```json
{ "name": "py-touch", "publisher": "acme", "version": "0.1.0",
  "displayName": "%displayName%", "description": "%description%", "license": "MIT",
  "engines": { "easyide": "^0.3.0" }, "categories": ["Snippets", "Keymaps"],
  "contributes": {
    "snippets": [{ "language": "python", "path": "./snippets/python.code-snippets" }] },
  "easyide": {
    "keyRows": [{ "id": "acme.py-touch.row", "title": "%row.title%",
                  "when": "editorLangId == python && inputMode == touch",
                  "keys": [
                    { "label": ":" },
                    { "label": "self.", "insert": "self." },
                    { "label": "()", "snippet": "($0)" },
                    { "label": "[]", "snippet": "[$0]" },
                    { "label": "def", "snippet": "def ${1:name}(${2}):\n\t$0" },
                    { "label": "Tab", "key": "tab" } ] }] } }
```

Each key has exactly one of `insert`, `snippet`, `key` or `command`; `longPress` adds an
alternative. Key rows need no capability. `l10n/package.nls.json`:

```json
{ "displayName": "Python Touch Kit", "description": "Python snippets and a touch key row",
  "row.title": "Python" }
```

### 2.3 Snippets

VS Code snippet JSON; tab stops, choices and `$TM_*` variables work.

```json
{ "Main guard": { "prefix": "ifmain", "body": ["if __name__ == \"__main__\":", "\t${1:main()}"],
                  "description": "if __name__ == '__main__'" },
  "Dataclass": { "prefix": "dc",
                 "body": ["@dataclass", "class ${1:Name}:", "\t${2:field}: ${3|int,str,float|}"] } }
```

### 2.4 Test headless, then on device

`easyide-ext test` evaluates when-clauses against fixture contexts, so you can check that
the row appears for Python on touch and not elsewhere (scenario shape in `lld/cli.md`):

```json
{ "name": "row only for python on touch",
  "context": { "editorLangId": "python", "inputMode": "touch" },
  "expect": { "visible": ["keyRow:acme.py-touch.row"] } }
```

```
easyide-ext test ./py-touch
easyide-ext dev --watch --device R52T1234 ./py-touch
```

On device: open a `.py` file without a keyboard - the row replaces the default one. Type
`ifmain` and accept from the completion popup; move between tab stops with the row's Tab
key. Users can reorder or replace your row (`keyRows.layouts`, `keyRows.active`) and hide
it (`workbench.contributions.hidden: ["keyRow:acme.py-touch.row"]`); design it to be
useful on its own, not as a dependency of anything.

### 2.5 Publish

Same as 1.5. Bump `version` for every publish; versions must strictly increase.

---

## Tutorial 3 - Language pack with a language server and sandbox install (~1 hour)

Goal: Zig support - grammar, language configuration, the `zls` language server installed
into the user's environment, and a "Run file" button. This pack runs code in the sandbox,
so it declares capabilities and installs **per environment**.

### 3.1 Scaffold and tree

```
easyide-ext init --template lsp-pack --publisher acme --name zig ./ext-zig
```

```
ext-zig/
  package.json
  README.md  LICENSE  CHANGELOG.md  icon.png
  language-configuration.json
  syntaxes/zig.tmLanguage.json          keep the grammar's own license + attribution
  snippets/zig.code-snippets
  test/lsp-start.json
```

### 3.2 Manifest

```jsonc
{ "name": "zig", "publisher": "acme", "version": "0.1.0",
  "displayName": "Zig", "description": "Zig highlighting, zls language server, run button",
  "icon": "icon.png", "license": "MIT",
  "engines": { "easyide": "^0.3.0" }, "categories": ["Programming Languages", "Language Packs"],
  "activationEvents": ["onLanguage:zig", "workspaceContains:**/build.zig"],
  "contributes": {
    "languages": [{ "id": "zig", "aliases": ["Zig"], "extensions": [".zig", ".zon"],
                    "configuration": "./language-configuration.json" }],
    "grammars": [{ "language": "zig", "scopeName": "source.zig", "path": "./syntaxes/zig.tmLanguage.json" }],
    "snippets": [{ "language": "zig", "path": "./snippets/zig.code-snippets" }],
    "commands": [{ "command": "zig.runFile", "title": "Run Zig File", "icon": "play_arrow",
                   "category": "Zig", "enablement": "envState == ready && envHasCommand:zig" }],
    "menus": { "editor/title": [{ "command": "zig.runFile", "when": "editorLangId == zig", "group": "navigation@1" }],
               "editor/touchToolbar": [{ "command": "zig.runFile", "when": "editorLangId == zig" }] },
    "configuration": { "title": "Zig", "properties": {
      "zig.path": { "type": "string", "default": "zig", "scope": "environment", "description": "zig executable" } } },
    "configurationDefaults": { "[zig]": { "editor.tabSize": 4, "editor.formatOnSave": true } } },
  "easyide": {
    "capabilities": ["sandbox.exec", "sandbox.install", "lsp.spawn", "network(ziglang.org,github.com,*.githubusercontent.com)"],
    "actions": {
      "zig.runFile": { "type": "runInTerminal", "terminal": "Zig", "cwd": "${fileDirname}",
                       "command": "${config:zig.path} run ${file}" } },
    "languageServers": [
      { "id": "zls", "languages": ["zig"], "command": ["zls"], "rootMarkers": ["build.zig", ".git"],
        "memoryBudgetMb": 300 } ],
    "sandbox": {
      "requires": ["zig", "zls"],
      "install": [
        { "id": "deps", "title": "Install download tools", "run": "apt-get install -y curl xz-utils",
          "when": "envDistro in 'debian,ubuntu'" },
        { "id": "zls", "title": "Install zls (pinned release)",
          "run": "curl -fsSL -o /tmp/zls.tar.xz <release-url-for-envArch> && echo '<sha256>  /tmp/zls.tar.xz' | sha256sum -c - && tar -xJf /tmp/zls.tar.xz -C /usr/local/bin zls" } ],
      "verify": "zls --version",
      "uninstall": ["rm -f /usr/local/bin/zls"] },
    "memoryBudgetMb": 300 } }
```

Notes on each part:

- `activationEvents` only matter for the server: the grammar, snippets and button are live
  as soon as the pack is installed. The server becomes eligible on the first Zig file and
  starts lazily.
- `capabilities`: `validate` fails if an action needs one you did not declare, and warns
  on declared-but-unused ones (the registry rejects unused).
- `sandbox.install` steps run in order in a visible terminal tab, never silently. Make
  them idempotent, pin versions, and **sha256-pin every binary download** (R-EXT-07,
  R-EXT-10). Fill `<release-url-for-envArch>` and `<sha256>` from the upstream release
  page and check the server's license before bundling or recommending it (R-EXT-04).
  This tutorial installs `zig` itself only via `requires`: if it is missing, the pack shows
  "Install toolchain" and your README should say how.
- `verify` must exit 0 or the install is rolled back and the previous version stays live.
- `memoryBudgetMb`: measure `zls` RSS on a real project (see 3.4) and set it from that
  number, stating the device in your README (R-EXT-13). A server over its budget is
  restarted once, then stopped with a notice.
- Values substituted into `command` strings are shell-quoted for you; do not add quotes
  around `${file}`.

### 3.3 Test headless

```
easyide-ext validate --strict ./ext-zig
easyide-ext test ./ext-zig
```

The harness runs actions against a fake host (so `zig.runFile` shows the exact command it
would send) and checks when-clauses; it does not run `zls`. Language-server behaviour is
tested on a device or in an easyIDE environment.

### 3.4 Test on device

```
easyide-ext dev --watch --device R52T1234 ./ext-zig
```

1. The install sheet lists every capability, the install commands verbatim and the
   server command, and states that sandbox commands are not contained. Approve it.
2. Watch the install steps stream in the terminal tab; `verify` runs last.
3. Open a `.zig` file. Status bar shows the server starting, then ready. Check squiggles,
   completion (touch and Ctrl+Space), hover (long-press), go to definition, rename,
   format on save.
4. Measure memory: in the easyIDE terminal, `grep VmRSS /proc/$(pgrep -f zls)/status`
   while working in a medium project.
5. Kill it (`pkill zls`) and watch it restart with backoff; the **Extension Log** panel
   shows its stderr (enable `lsp.trace` = `messages` for protocol traffic).
6. Users can override your server without editing your pack:
   ```jsonc
   "lsp.servers": { "acme.zig/zls": { "memoryBudgetMb": 500, "command": ["zls", "--enable-debug-log"] } }
   ```
   or disable it with `{ "enabled": false }`. Test that your pack still behaves (grammar,
   button) with the server disabled.

### 3.5 Publish

As 1.5. Registry maintainers read every `sandbox.install` step verbatim before merging a
pack that declares `sandbox.install`, `sandbox.exec`, `lsp.spawn` or `network(...)`, so
keep steps short and explained in your README.

---

## Tutorial 4 - WASM extension in Rust (~1 hour)

Goal: a "Word count" command that reads the active editor and shows a message. WASM is
for logic the action vocabulary cannot express; if a `sequence` of actions can do it, use
that instead.

### 4.1 Scaffold and tree

```
easyide-ext init --template wasm-rust --publisher acme --name wordcount ./wordcount
rustup target add wasm32-unknown-unknown
```

```
wordcount/
  package.json
  README.md  LICENSE  CHANGELOG.md
  Cargo.toml
  src/lib.rs
  wasm/main.wasm                        build output, referenced from the manifest
  test/wordcount.json
```

### 4.2 Manifest

```json
{ "name": "wordcount", "publisher": "acme", "version": "0.1.0",
  "displayName": "Word Count", "description": "Counts words in the active file", "license": "Apache-2.0",
  "engines": { "easyide": "^0.3.0" }, "categories": ["Other"],
  "activationEvents": ["onCommand:acme.wordCount"],
  "contributes": {
    "commands": [{ "command": "acme.wordCount", "title": "Count Words", "category": "Word Count" }],
    "menus": { "commandPalette": [{ "command": "acme.wordCount", "when": "editorFocus" }] } },
  "easyide": {
    "capabilities": ["fs.project(read)"],
    "wasm": { "module": "wasm/main.wasm", "abi": 1, "memoryMb": 16 } } }
```

`fs.project(read)` is needed for `editor.getText`. `ui.showMessage` needs no capability.
Every command you register at runtime must also be declared in `contributes.commands`.

### 4.3 Cargo.toml and code

```toml
[package]
name = "wordcount"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["cdylib"]

[dependencies]
serde_json = "1"

[profile.release]
opt-level = "z"
lto = true
panic = "abort"
```

The SDK's `easyide-guest` crate wraps the ABI; this tutorial uses the raw ABI so you can
see what happens. The complete, working `src/lib.rs` is the Rust guest listing in
[sdk-reference.md#wasm-host-api](sdk-reference.md#wasm-host-api) (it registers
`acme.wordCount`, calls `editor.getText`, then `ui.showMessage`). What it must do:

| Export | Purpose |
|---|---|
| `memory` | exported automatically by the Rust toolchain |
| `alloc(len) -> ptr`, `free(ptr, len)` | buffers the host writes messages into |
| `ext_abi_version() -> 1` | anything else refuses the load |
| `ext_activate(ptr, len) -> ptr` | receives `{extensionId, version, apiVersion, capabilities, settings, env}` |
| `ext_handle(ptr, len) -> ptr` | receives `{"type":"command","command":"acme.wordCount",...}` and events; return 0 for "no response" |

It may import only `easyide.host_call`. No WASI: a module that imports anything else is
refused at load, so do not use `std::fs`, `std::net`, threads or the system clock.

Build:

```
cargo build --release --target wasm32-unknown-unknown
cp target/wasm32-unknown-unknown/release/wordcount.wasm wasm/main.wasm
```

The module must be <= `extensions.wasm.maxModuleMb` (default 8 MB); the release profile
above keeps a serde_json guest well under that.

AssemblyScript instead of Rust (template `wasm-assemblyscript`; the SDK ships `@easyide/guest-as` with `alloc/free`, the length
prefix and JSON helpers):
```ts
import { hostCall, reply, register } from "@easyide/guest-as"; export { alloc, free } from "@easyide/guest-as";
export function ext_abi_version(): i32 { return 1; }
export function ext_activate(ptr: usize, len: i32): usize { register("acme.upper"); return reply(`{"v":1,"ok":true}`); }
export function ext_handle(ptr: usize, len: i32): usize {
  // parse {"type":"command","command":"acme.upper"} -> uppercase the selection
  const sel = hostCall("editor.getText", `{"range":"selection"}`);
  hostCall("editor.applyEdits", `{"edits":[{"range":"selection","text":${JSON.stringify(sel.toUpperCase())}}]}`);
  return reply(`{"v":1,"ok":true}`);
}
```

### 4.4 Test headless with the real limits

```
easyide-ext validate --strict ./wordcount     # also audits host fns used vs capabilities
easyide-ext test ./wordcount
```

`test` runs your module under the same limits as the app: memory
(`extensions.wasm.maxMemoryMb`), fuel per call (`extensions.wasm.fuelPerCall`), wall clock
(`extensions.wasm.callTimeoutMs`), host calls per call and message size. A scenario:

```json
{ "name": "counts words", "editor": { "path": "/workspace/a.txt", "text": "one two  three\n" },
  "steps": [{ "execute": "acme.wordCount" }],
  "expect": { "calls": [{ "fn": "ui.showMessage", "args": { "text": "3 words" } }] } }
```

Try removing `fs.project(read)` from the manifest: `validate` reports the missing
capability, and at runtime the host would answer `E_CAPABILITY`.

### 4.5 Test on device

```
easyide-ext dev --watch --device R52T1234 ./wordcount
```

Run **Count Words** from the palette. `log.write` output and any `E_LIMIT` / `E_TIMEOUT`
traps appear in the Extension Log. A trap discards the instance and the next call
re-instantiates it; `extensions.wasm.maxCrashes` traps within
`extensions.wasm.crashWindowSec` disable the extension until the user re-enables it.
Keep per-call work small: WASM runs on an interpreter, and heavy analysis belongs in a
language server.

### 4.6 Publish

As 1.5. Include the Rust source (or a link to it) in your README; reviewers may ask for it.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `validate`: engine range does not include app API | `engines.easyide` too narrow or too new | target the API version shown in **Settings > About**, e.g. `^0.3.0` |
| Install refused: "action needs undeclared capability" | e.g. `runInTerminal` without `sandbox.exec` | declare it; re-run `validate` |
| Theme colours partly ignored | `colors` keys with no easyIDE token | see the `validate` list; nothing to fix, or map to supported keys |
| Two scopes render the same colour | scopes collapse onto 21 roles | expected; choose distinct roles |
| Key row does not appear | `when` false, or user chose another row | check `keyRows.active`, test the when-clause with `easyide-ext test` |
| Button greyed out | `enablement` false (e.g. `envState` not `ready`, missing `envHasCommand:`) | install the toolchain; check the context in the contribution inspector |
| Server never starts | missing binary, wrong `command`, no matching `activationEvents` | run the command in the easyIDE terminal; check Extension Log |
| Server "paused (memory)" | over `memoryBudgetMb` or global `lsp.globalMemoryBudgetMb` | measure RSS, raise your default honestly, or document the override |
| Install rolled back | `verify` exited non-zero | run `verify` by hand in the terminal |
| `dev --device` does nothing | developer mode off, or device not in `adb devices` | enable `extensions.developerMode`; check USB debugging |
| WASM load refused | wrong `ext_abi_version`, missing export, WASI import | build for `wasm32-unknown-unknown`, avoid std fs/net/time |
| WASM `E_LIMIT` / `E_TIMEOUT` | fuel, memory, message size or time exceeded | do less per call; move heavy work to a server |
| Publish PR fails CI | hand-edited entry, non-immutable `url`, version not increasing, unused capability | re-run `package` and `publish`; bump version |
| Users see "unsigned, local" | sideloaded without `.easyext.sig` | `easyide-ext sign` for sideloads, or publish to the registry |

## FAQ

**Can I run my VS Code extension?** Its declarative parts, often yes: themes, grammars,
snippets, language configuration and settings work unmodified, and `.vsix` files from
Open VSX install as a labelled partial subset when the user enables that source. Its
JavaScript (`main`/`browser`) does not run. Rewrite logic as actions or WASM.

**Can I publish to the Microsoft Marketplace or use Microsoft's extensions?** No. The
Marketplace terms forbid use outside Microsoft products; Pylance, cpptools and C# are
unavailable. Do not bundle anything derived from them.

**Is my extension sandboxed?** WASM calls to the host are checked by easyIDE against your
declared and approved capabilities. Anything that runs in the Linux environment (install
steps, servers, terminal commands) is not contained - say so plainly in your README.

**Can my extension read the user's git token or API keys?** No capability grants that.
`secrets.read` only returns secrets the user entered for your extension.

**Will my update install automatically?** Never. Users are notified, see your version,
changelog and any added capabilities, and tap to apply. Adding a capability always needs
a fresh approval, so avoid adding them casually.

**Global or per environment?** Computed for you: anything with `easyide.sandbox`,
language servers or sandbox-executing actions installs per environment; themes, snippets,
grammars, key rows and pure WASM install globally.

**How do deprecations work?** Until API 1.0.0, minor releases may break. From 1.0.0, a
deprecated field, action or host function keeps working for 2 minor releases and
`validate` warns about it before it is removed in the next major.

**What license should I use?** Your choice; declare it as an SPDX expression and include
the file. The SDK pieces you build with (schema, CLI, guest bindings, templates) are
Apache-2.0 ([0015](../decision/0015-extension-sdk-licensing-apache.md)), so they impose
nothing on your extension.

**My key is lost or leaked.** Leaked: rotate with a `rotation` record signed by the old
key and ask maintainers to revoke it. Lost: it cannot rotate; ask for revocation and a
new publisher approval. Users who pinned the old key will see a hard failure until then.

## Deviations

- Resolved: scenarios follow the normative format in `lld/cli.md` sec 5.6; publisher
  onboarding follows `lld/cli.md` sec 5.5 (`--register-publisher`).
- sdk-reference.md shows the `easyide-guest` crate exists but not its API, so tutorial 4
  points at the raw-ABI listing rather than inventing crate functions.
