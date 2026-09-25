# VS Code extension host - tracker

Architecture and protocol: [arch.md](arch.md). Decision: [0030](../decision/0030-vscode-extension-host-in-sandbox.md).

> **Superseded 2026-09-25.** Phases 1-6 below are replaced by the work packages and milestones in
> [../vsx-compat/tracker.md](../vsx-compat/tracker.md) (route per [ADR 0031](../decision/0031-vendor-vscode-extension-host.md)).
> This table is kept for history only; do not update it.

| Phase | Scope | Status |
|---|---|---|
| 0 | ADR, protocol spec | Done 2026-09-25 |
| 1 | Host process, loader, activation, core `vscode` API, Kotlin `:exthost`, launch in sandbox, `.vsix` install of code extensions, hello-world on device | Not started |
| 2 | Language providers, diagnostics, decorations, code lens | Not started |
| 3 | Tree views, view containers, menus | Not started |
| 4 | Webviews (panel + view) | Not started |
| 5 | git extension API, SCM, terminals, timeline, file decorations | Not started |
| 6 | Real-extension corpus (GitLens, Claude Code, ...) in CI, compatibility table | Not started |
