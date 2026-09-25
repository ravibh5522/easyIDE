# WP-HOST-4 - `ExtHostSupervisor` (per workspace, lazy start, backoff, park, kill mechanism)

Generated from [roadmap.md](../roadmap.md) row 6 by `tools/vsx-audit/gen-tracker-briefs.mjs`. Ready-to-run prompt for an
autonomous implementation agent. Milestone M1. Effort L / 15. Risk Med. Owner role: kotlin-exthost.

## Prompt

You are the **kotlin-exthost** agent implementing **WP-HOST-4 (`ExtHostSupervisor` (per workspace, lazy start, backoff, park, kill mechanism))** for easyIDE's VS Code extension compatibility
program. Work in your own git worktree on a branch named `wp/wp-host-4`, one work package per branch.

1. Read first: `.claude/CLAUDE.md` and [CONTRIBUTING.md](../../../CONTRIBUTING.md) (600-line file limit, no hardcoding,
   errors only at real boundaries, chainlog entry), [README.md](../README.md), [backend.md](../backend.md), [backend-2.md](../backend-2.md), [design.md](../design.md), [design-protocol.md](../design-protocol.md), the ADRs
   [0031](../../decision/0031-vendor-vscode-extension-host.md), [0032](../../decision/0032-node-runtime-provisioning.md),
   [0033](../../decision/0033-extension-webview-security-model.md), [0034](../../decision/0034-play-policy-stance-code-extensions.md),
   and the matrix rows that name WP-HOST-4 in [matrix/](../matrix/).
2. Dependencies that must be Done in [tracker.md](../tracker.md) before you start: WP-HOST-1, WP-HOST-2, WP-HOST-3, WP-SEC-5.
3. Scope: exactly what the source docs above assign to WP-HOST-4; anything else goes to the lead as a note, not into this branch.
   Respect the ownership boundaries and merge order in [roadmap.md](../roadmap.md) section 4.
4. Acceptance (must be demonstrated, with the command output or device evidence in the PR): host absent until fixture `onCommand`; `kill -9` -> 3 restarts then banner; kill leaves no guest process (`ps`)
5. Verify with the builds and tests that exist (`:app:testDebugUnitTest`, module tests, goldens, the harness in
   `tools/vsx-audit/`); anything you could not run (no device) is stated as not verified.
6. In the same PR: set WP-HOST-4 to Done (or In progress with a note) in [tracker.md](../tracker.md), update the matrix rows it
   closes (`tools/vsx-audit/mapping/*.json`, then re-run `tools/vsx-audit/build-matrix.mjs`), and append a chainlog entry.
7. Never describe proot as isolation; never bundle third-party extensions; never add telemetry.
