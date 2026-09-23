# 0013 - Extension SDK shape: VS Code-shaped manifest, `.easyext` zip, `easyide` key, fixed action vocabulary

Status: Accepted (2026-09-24)

## Context

[0009](0009-extension-platform-tiers.md) chose native declarative contributions (Tier 1)
before any VS Code extension host, but left the package format open. The Extension SDK
([extension-sdk/arch.md](../extension-sdk/arch.md), ADR-E there) now has to fix a format
that third parties will author against and that we will have to keep loading for years.

Constraints that shaped it:

- **Authors already know VS Code.** TextMate grammars, `language-configuration.json`,
  snippet JSON, colour theme JSON and the `contributes` shape are open, documented, and
  have large existing corpora (Open VSX, the 229 grammars bundled per
  [0010](0010-textmate-highlighting-bundled.md)).
- **We need things VS Code has no word for**: installing a toolchain into the sandbox,
  touch key rows, stages, memory budgets, declared capabilities, and language servers as
  data rather than as Node code.
- **Tier 1 has no extension code.** A button must still *do* something, so behaviour has to
  be expressible as data the host executes. Whatever that vocabulary is, it is an API we
  support forever.
- A real `.vsix` manifest must remain readable by us (Open VSX subset import, arch.md
  sec 11) without colliding with our own keys.

## Decision

An extension is a zip named `<publisher>.<name>-<version>.easyext` whose root holds a
**VS Code-shaped `package.json`**. Every easyIDE-only key lives under one top-level
**`"easyide": {}`** object. Behaviour behind a contributed command is one of a **small,
fixed, versioned action vocabulary** (`runInTerminal`, `sandboxExec`, `applyEdit`,
`lspRequest`, `sequence`, ... - listed in
[sdk-reference.md#action-vocabulary](../extension-sdk/sdk-reference.md#action-vocabulary)),
executed by the host. Compatibility is declared with a required **`engines.easyide`**
semver range. The schema is one JSON Schema in `services/shared/extension-schema/`, used
by both the app and the `easyide-ext` CLI.

## Alternatives considered

- **Our own manifest format (TOML/YAML, own field names).** Rejected: throws away every
  author's existing knowledge and every existing grammar/theme/snippet file, and makes
  Open VSX import a translation layer instead of a subset read.
- **Accept `.vsix` directly as the native format.** Rejected: a `.vsix` implies `main` JS
  that we do not run (L3 is conditional, M8), so most `.vsix` files would install
  "partially" by default. We read `.vsix` as a secondary, labelled source instead.
- **easyIDE keys mixed into `contributes`** (e.g. `contributes.keyRows`). Rejected: risks
  collision with a future VS Code contribution point of the same name and breaks
  "our manifests stay parseable by VS Code tooling".
- **Embedded scripting for Tier 1 (Lua, JS via QuickJS) instead of an action vocabulary.**
  Rejected: that is code, which reopens the trust question 0009 closed for Tier 1 and
  needs a sandbox of its own. Logic that the vocabulary cannot express goes to the WASM
  layer ([0014](0014-wasm-logic-layer-chicory.md)), which *is* enforced.
- **Unbounded action vocabulary (grow on request).** Rejected: every action is permanent
  API. The vocabulary stays small and additions are minor-version API changes.

## Consequences

- Themes, snippets, grammars and language configuration from VS Code work unmodified; a
  theme pack is a copy of an existing theme JSON plus a 6-line manifest.
- **The action vocabulary is lock-in by design.** Additions are minor; removals are major
  and go through the 2-minor-release deprecation window (arch.md sec 11). Reviewers must
  treat a new action type like a new public API.
- Any capability an action needs must be declared; the loader rejects a manifest whose
  actions need an undeclared capability. For sandbox-executing actions this is
  **consistency and disclosure, not enforcement** - proot and chroot+BusyBox do not
  isolate ([0002](0002-sandbox-backend-proot-default-chroot-optin.md)).
- VS Code fields we ignore (`main`, `browser`, `engines.vscode`, unknown contribution
  points) must be reported by `easyide-ext validate`, so authors are never surprised.
- One schema in `services/shared/` means the CLI and the app cannot disagree about what is
  valid; the cost is that a schema change is a coordinated release of both.
- Forecloses a non-JSON manifest and a second, parallel contribution registry: all
  contributions plug into the Pillar 4 command registry / keymap and the Pillar 5 settings
  schema ([ux-overhaul/arch.md](../ux-overhaul/arch.md)).
