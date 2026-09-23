# Docs

Project-level documentation index and conventions. Feature-specific architecture/status lives one level down, per feature.

## Structure

```
docs/
  README.md                  -- this file: project overview + conventions
  <feat-name>/
    arch.md                   -- architecture for that feature
    tracker.md                 -- implementation status, open questions, risks
  decision/                    -- ADRs: major, hard-to-reverse choices, with rationale
  chainlog/                    -- append-only weekly log of AI-driven changes
```

## Current features

| Feature | Docs |
|---|---|
| Sandbox runtime (Theia + on-device Linux sandbox + terminal + git + session persistence) | [docs/sandbox-runtime/](sandbox-runtime/) |
| UI shell (screens, navigation, stage system, editor/terminal UX flow) | [docs/ui-shell/](ui-shell/) |
| Design system (themes, color, icons, imagery, animation, responsive layout) | [docs/design-system/](design-system/) |
| Extensions (tree-sitter + LSP language core, declarative contributions, custom stages, sandbox toolchain install, Open VSX) | [docs/extensions/](extensions/) |
| Git (JGit source control, staging/commits, auto change detection, planned clone/auth/push) | [docs/git/](git/) |
| UX overhaul (perf, visual identity, layout/input, settings schema, feature roadmap from the 2026-09 audit) | [docs/ux-overhaul/](ux-overhaul/) |
| Extension SDK & language intelligence (LSP client, no-server language features, declarative + WASM extensions, registry, full customization) | [docs/extension-sdk/](extension-sdk/) |

## Conventions

- **New feature** → `docs/<feat-name>/arch.md` + `tracker.md` (kebab-case name). Use the `new-feature` skill to scaffold.
- **Major decision** → a numbered ADR under `docs/decision/`. Use the `decision` skill to scaffold. Link it from the relevant feature's `arch.md`.
- **Any AI-driven change** → append an entry to this week's file under `docs/chainlog/`. Use the `changelog` skill.
- Project-wide instructions for how any AI session should work in this repo live in `.claude/CLAUDE.md`, not here — this doc is for humans and AI to read, not for behavioral rules.

## One-paragraph mental model of the product

The Android app is a thin native shell (Compose) around a WebView pointed at `localhost`, where `localhost` is a Theia IDE server running as a Node.js process inside a Linux sandbox (proot or chroot+BusyBox, device-dependent) living entirely in app-private storage. A foreground Android Service keeps that process tree alive across backgrounding, and tmux inside the sandbox provides session survival across process kills. Full detail: [docs/sandbox-runtime/arch.md](sandbox-runtime/arch.md).
