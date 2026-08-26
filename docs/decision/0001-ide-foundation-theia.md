# 0001 - IDE foundation: Theia over native editor / code-server / Spyder

Status: Accepted

## Context

The product is a VS Code-like IDE for Android tablets: file explorer, terminal, git, and a code editor, with Claude Code CLI running inside the terminal. Building the editor, LSP integration, git UI, and terminal panel entirely native (Compose + Sora-Editor + custom git panel) was the original plan, but represents a large amount of engineering to reach parity with an existing, mature IDE.

## Decision

Use **Eclipse Theia**, run as a local Node.js server inside the on-device sandbox and displayed through a WebView, rather than building a native Compose editor from scratch or basing the product on **code-server** or **Spyder**.

**License correction (verified 2026-08-10)**: this doc originally stated Theia was "MIT-licensed." That was wrong. Theia core is dual-licensed **EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0** (confirmed via GitHub repo metadata, the Eclipse Foundation project page, and [eclipse-theia/theia#12583](https://github.com/eclipse-theia/theia/issues/12583)). Practical implication: EPL-2.0 is file-level weak copyleft — if we modify Theia's own source files and distribute the result, those modified files' source must be made available on request. Our own extension/widget code, as a separate module calling Theia's public contribution APIs (DI bindings, `WidgetFactory`, `AbstractViewContribution`), is a "combined work" under EPL-2.0 and is **not** required to be open-sourced. Bundling Theia binaries (modified or not) is permitted provided license/NOTICE files are preserved. No action needed beyond not forking Theia core itself.

## Alternatives considered

- **Native Compose + Sora-Editor + custom git panel + custom terminal integration** — full control and a genuinely native touch UX, but requires building LSP integration, git UI, and file-watching/conflict-resolution logic from scratch. Rejected as too much scope for a first version.
- **code-server** (literal VS Code, served headless) — near-certain compatibility with the official Claude Code VS Code extension (it's real VS Code), but heavier runtime footprint (full VS Code feature set always loaded) and harder to reskin for touch, since Microsoft's frontend isn't designed for deep customization.
- **Spyder** — desktop Qt/PyQt application, Python-only, no headless/browser-serving model, no extension marketplace equivalent. Wrong category of tool entirely for a general-purpose multi-language IDE.

## Consequences

- Get a working editor, file explorer, git UI, and terminal essentially for free — this is the primary reason the from-scratch native editor plan (Sora-Editor, custom `FileObserver`-based file watching) was dropped; see [/docs/sandbox-runtime/arch.md](../sandbox-runtime/arch.md).
- **Open risk, not yet resolved**: Theia implements a large subset of the VS Code extension API but not 100% of it. The official Claude Code VS Code extension has not been verified to run correctly under Theia. This needs an early spike — if it fails, code-server becomes the fallback (heavier, but near-guaranteed compatibility since it's literal VS Code). Tracked in [/docs/sandbox-runtime/tracker.md](../sandbox-runtime/tracker.md).
- Resource footprint is better than code-server (Theia's modular build lets unused subsystems be excluded), which matters on tablet-class hardware, but worse than a hypothetical minimal native editor.
- Touch UX still needs real adaptation work — Theia's frontend is desktop-first like any VS Code-derived UI, but its DI-based architecture makes reskinning more tractable than patching Microsoft's own VS Code source would be.
