# VS Code extension compatibility (Open VSX)

Goal: VS Code extensions from Open VSX work in easyIDE at **>= 99% usage-weighted compatibility**, backend and UI, tuned for
a tablet. This directory is the audit, design and live plan. Status: audit done 2026-09-25; implementation not started.

## Decision in one paragraph

We run **VS Code's own extension host, vendored unmodified (MIT)**, under a pinned Node 24 in the proot guest, plus a thin JS
**main-thread adapter** that turns VS Code's private RPC into a small JSON-RPC UI protocol for the Kotlin app, which draws
every surface natively (WebViews only where VS Code uses webviews). A spike activated unmodified GitLens, Claude Code, Go,
ESLint and Prettier this way ([research/route-spike.md](research/route-spike.md)). ADRs:
[0031](../decision/0031-vendor-vscode-extension-host.md) route (supersedes 0030 decision 2),
[0032](../decision/0032-node-runtime-provisioning.md) Node runtime,
[0033](../decision/0033-extension-webview-security-model.md) webview security,
[0034](../decision/0034-play-policy-stance-code-extensions.md) Play policy stance (proposed, legal review pending).

## Definition of 99%

Corpus C = the top 150 Open VSX extensions by downloads with a `main`/`browser` entry plus the 22 must-work extensions and
their dependencies: 156 extensions, snapshot 2026-09-25 ([corpus.md](corpus.md), [data/corpus.json](data/corpus.json)).
Per extension four gates, each 0/1: g1 activates cleanly, g2 every in-scope contribution point works, g3 every `vscode.*`
API it calls is implemented (static scan + runtime trace), g4 its scripted scenario passes. Weight w = downloads.

`S = sum(w * (g1+g2+g3+g4)/4) / sum(w)`, reported as **S_in** over the in-scope set E_in (target >= 99%) and S_all over C.
E_in excludes only extensions whose whole purpose is an out-of-scope track or that have no linux-arm64/universal build:
150 extensions, 90.36% of corpus weight; the 6 exclusions are listed with reasons in [corpus.md](corpus.md) section 2.
Out-of-scope tracks (debug adapters, notebooks, chat/lm/mcp, proposed APIs beyond an allow-list, Microsoft-service
features, browser-only extensions) are scored separately and planned in [roadmap-oos.md](roadmap-oos.md). Full rules,
harness and CI gate: [test-program.md](test-program.md).

## How to read

| Doc | What |
|---|---|
| [design.md](design.md), [design-protocol.md](design-protocol.md) | Target architecture, process model, ownership, sequences, all 87 MainThread shapes, UI protocol |
| [gap-analysis.md](gap-analysis.md), [matrix/](matrix/) | Capability matrix (API, contributes, activation events, when keys, menus, extHost shapes), priorities, projected score |
| [backend.md](backend.md), [backend-2.md](backend-2.md) | Backend gaps (host, runtime, storage, config, documents, terminals, git, auth, router) |
| [ui.md](ui.md), [ui-webviews.md](ui-webviews.md) | Every extension-drawable surface on tablet and phone; webview design |
| [optimisation.md](optimisation.md) | Budgets with measurement methods (start, memory, IPC, battery, install size) |
| [security-licensing.md](security-licensing.md), [licensing-policy.md](licensing-policy.md) | Threat-model delta, supply chain, licences, Play policy (for legal review), privacy |
| [registry-install.md](registry-install.md) | Open VSX client, target platforms, signatures, `.vsix` install |
| [corpus.md](corpus.md), [corpus-profiles.md](corpus-profiles.md) | The corpus, blockers, must-work profiles |
| [test-program.md](test-program.md) | Score, harness, scenarios, CI gates |
| [roadmap.md](roadmap.md), [roadmap-oos.md](roadmap-oos.md) | Ordered work packages, critical path, streams, milestones M1-M5, OOS tracks |
| [tracker.md](tracker.md), [agent-briefs/](agent-briefs/) | Live status; one ready-to-run prompt per work package |
| [research/](research/) | Primary-source evidence notes the docs cite |

Regenerate data: `tools/vsx-audit/` (`extract-vscode.mjs`, `corpus-fetch.mjs`, `corpus-scan.mjs`, `render-corpus.mjs`,
`exthost-deps.mjs`, `build-matrix.mjs`, `gen-tracker-briefs.mjs`; spike in `tools/vsx-audit/spike/`). Downloads are cached
outside the repo (`~/.cache/easyide-corpus`); no `.vsix` is ever committed.

## Stale statements elsewhere (to fix under WP-HOST-14)

[extension-host/](../extension-host/) is marked partly superseded. Still to align: ADR 0016 and
[extension-sdk/lld/registry-and-install.md](../extension-sdk/lld/registry-and-install.md) section 9 (Open VSX does sign with
Ed25519), [extension-sdk/m8-gap-analysis.md](../extension-sdk/m8-gap-analysis.md) ("do not build M8"), ADR 0013 (`.vsix`
secondary), [extensions/arch.md](../extensions/arch.md) (gallery adapter).
