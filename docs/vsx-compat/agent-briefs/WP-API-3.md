# WP-API-3 - Documents/editors sync at scale, will-save, applyEdit

Generated from [roadmap.md](../roadmap.md) row 39 by `tools/vsx-audit/gen-tracker-briefs.mjs`. Ready-to-run prompt for an
autonomous implementation agent. Milestone M2. Effort L / 15. Risk Med. Owner role: kotlin-exthost.

## Prompt

You are the **kotlin-exthost** agent implementing **WP-API-3 (Documents/editors sync at scale, will-save, applyEdit)** for easyIDE's VS Code extension compatibility
program. Work in your own git worktree on a branch named `wp/wp-api-3`, one work package per branch.

1. Read first: `.claude/CLAUDE.md` and [CONTRIBUTING.md](../../../CONTRIBUTING.md) (600-line file limit, no hardcoding,
   errors only at real boundaries, chainlog entry), [README.md](../README.md), [backend.md](../backend.md), [backend-2.md](../backend-2.md), [design.md](../design.md) section 6, [design-protocol.md](../design-protocol.md), the ADRs
   [0031](../../decision/0031-vendor-vscode-extension-host.md), [0032](../../decision/0032-node-runtime-provisioning.md),
   [0033](../../decision/0033-extension-webview-security-model.md), [0034](../../decision/0034-play-policy-stance-code-extensions.md),
   and the matrix rows that name WP-API-3 in [matrix/](../matrix/).
2. Dependencies that must be Done in [tracker.md](../tracker.md) before you start: WP-HOST-5, WP-UI-6.
3. Scope: exactly what the source docs above assign to WP-API-3; anything else goes to the lead as a note, not into this branch.
   Respect the ownership boundaries and merge order in [roadmap.md](../roadmap.md) section 4.
4. Acceptance (must be demonstrated, with the command output or device evidence in the PR): ESLint diagnostics update while typing; Prettier format-on-save; 20 MB `openTextDocument` works
5. Verify with the builds and tests that exist (`:app:testDebugUnitTest`, module tests, goldens, the harness in
   `tools/vsx-audit/`); anything you could not run (no device) is stated as not verified.
6. In the same PR: set WP-API-3 to Done (or In progress with a note) in [tracker.md](../tracker.md), update the matrix rows it
   closes (`tools/vsx-audit/mapping/*.json`, then re-run `tools/vsx-audit/build-matrix.mjs`), and append a chainlog entry.
7. Never describe proot as isolation; never bundle third-party extensions; never add telemetry.
