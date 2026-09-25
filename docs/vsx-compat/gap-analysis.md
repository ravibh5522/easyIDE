# VS Code extension compatibility: gap analysis (route 2b)

Hand-written summary of the generated matrices. Every number is from
[data/matrix-summary.json](data/matrix-summary.json), produced by `node tools/vsx-audit/build-matrix.mjs`. The script joins
`data/vscode-*.json`, `data/corpus*.json`, `data/ourcode-*.json` and the curated mapping in
`tools/vsx-audit/mapping/*.json` (capabilities, contributes, activation, menus, when, shapes, api-rules). Snapshot: corpus
2026-09-25T14:49Z, scanVersion `0638c27e3a2b`, VS Code 1.140.0 (`0b16cb9`). Decisions: D1-D13, ADR 0031-0034.

## 1. How to read the matrices

| File | Rows |
|---|---|
| [matrix/api.md](matrix/api.md) (+ parts) | 743 stable `vscode.d.ts` ids, 64 non-stable ids seen in the corpus, 21 noise ids dropped |
| [matrix/contributes.md](matrix/contributes.md) | 60 contribution points + 13 corpus-only keys |
| [matrix/activation-events.md](matrix/activation-events.md) | 42 activation events |
| [matrix/menus.md](matrix/menus.md) | 96 VS Code menu locations + 4 easyIDE-own locations + submenu ids seen in the corpus |
| [matrix/when-context.md](matrix/when-context.md) (+ `-corpus`, `-all`) | 208 VS Code keys that the corpus uses or that are documented, 1,015 extension-owned keys, 1,073 keys that appear only in VS Code source |
| [matrix/extHost-shapes.md](matrix/extHost-shapes.md) | 87 MainThread + 82 ExtHost RPC shapes |
| [matrix/milestone-coverage.md](matrix/milestone-coverage.md) | earliest milestone for each of the 156 corpus extensions |

Column meanings:
- **gap** is today's state compared with what route 2b needs end to end. No extension host exists today, so a capability that extensions reach through the host is never `Have`. For UI surfaces fed by API calls: if our UI already exists, the gap is `Missing-backend`; if our UI is partial or missing, it is `Missing-both`.
- **use** is the number of extensions, the call sites, the weight share over the whole corpus, the weight share over E_in, and the top 3 extensions.
- **prio** is the weight share over E_in (the in-scope set, D7), with ties broken by the number of extensions.
- **ms** is the milestone (D11) at which extensions using the row stop failing gates g2/g3. It comes from the design.md §6 shape milestone and the ui.md §9 WP milestone. When both are needed, the later one applies.

Scan notes:
- The static scan counts every member reference, including references in feature-detected branches. No discount is applied.
- `default.X` is folded into `X`, and `version.*` is folded into `version`.
- ALL_CAPS ids and bare JS member names (`length`, `slice`, and so on) are dropped as noise.
- Rule fixes applied to `api-rules.json` in this pass: `RelativePattern` was mapped to `tasks.api`, `CommentRule` to `comments.api`, and 3 chat option types to `misc.unclassified`. All three were wrong and are corrected.

## 2. Summary counts (gap class)

| Set | Have | Partial | Missing-backend | Missing-UI | Missing-both | Not-planned |
|---|---|---|---|---|---|---|
| Capabilities (168) | 7 | 8 | 75 | 7 | 55 | 16 |
| Stable API ids (743) | 0 | 0 | 305 | 2 | 266 | 170 |
| Stable API ids used by the corpus (388) | 0 | 0 | 183 | 1 | 124 | 80 |
| Contribution keys (73) | 6 | 8 | 16 | 5 | 3 | 35 |
| Activation events (42) | 0 | 0 | 7 | 1 | 8 | 26 |
| Menu locations (100) | 4 | 2 | 0 | 14 | 23 | 57 |
| When keys used by the corpus (1,189) | 309 | 0 | 0 | 118 | 696 | 66 |

## 3. Scores

**Today:** S_in = S_all = 0 for code extensions. No host exists, so g1 and g3 fail for all 150 E_in extensions.

Declarative-only partial credit:
- **Strict:** a raw `.vsix` installs only `iconThemes` today, because `manifest.schema.json` is closed. No E_in extension has icon themes as its only in-scope contribution, so the credit is **0**.
- **Engine-level:** this assumes the manifest blocker (ext.manifest, M1) is lifted. 6 E_in extensions contribute only points whose engine already exists (grammars, themes, snippets, languages, configuration, icon themes). They hold 1.10% of E_in weight, which is at most +0.27 points of S_in through g2.

**Projected:** computed mechanically in [matrix/milestone-coverage.md](matrix/milestone-coverage.md). An E_in extension counts as covered at Mk when every in-scope API capability, contribution point and menu location it uses has a milestone <= Mk. Items in out-of-scope tracks are excluded. These are **upper bounds**: g4 scenarios and bugs are not modelled.

| Milestone | E_in extensions covered | S_in (upper bound) | S_all (upper bound) |
|---|---|---|---|
| M1 | 5 | 1.00% | 0.90% |
| M2 | 16 | 3.18% | 2.87% |
| M3 | 25 | 5.86% | 5.30% |
| M4 | 47 | 11.51% | 10.40% |
| M5 | 143 | 99.19% | 89.62% |

Why M2 to M4 are so low: `vscode-languageclient` registers every provider kind that its server advertises. That is why `languages.registerColorProvider` is used by 58 extensions (47% weight). design.md row 43 schedules color, call/type hierarchy, linked editing, inline completion, semantic tokens and paste/drop for **M5**, so every LSP-client extension waits for M5 (`lang.color` sits on the critical path for 56.4% of E_in weight). There is a fix available: accept these registrations as no-op sources from M2. The runtime half of g3 only fails on Stub-reject, per test-program.md 2.2. With that change, most language extensions would move to M2/M3. This is recommended to the lead as a design.md amendment; the numbers above do not include it.

**Never covered by M5:** 7 extensions, 0.82% of E_in weight. The target is S_in >= 99%, and the M5 bound is 99.19%, so there is no margin for g4 failures.
- `svelte`, `astro` and `nrwl.angular-console` use `typescriptServerPlugins`. The `typescript-language-features` built-in is absent (design.md §7.2).
- Mermaid ×2, `markdown-all-in-one` and One Dark Pro use `markdown.*` points. `markdown-language-features` is marked "not now".

## 4. Top 15 gaps (usage-weighted, then critical-path weight)

- **use** is the E_in weight share of extensions that use the capability.
- **crit** is the E_in weight for which the capability is among the latest-milestone blockers.
- **sole** is the weight for which it is the only such blocker.

| # | Capability | Gap | ms | #ext | use % | crit % | sole % | Effort | WP | Evidence (today) |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | lang.color (+ the other 5 M5 LSP kinds) | Missing-both | M5 | 59 | 56.8 | 56.4 | 0.1 | M | WP-API-13, WP-UI-10 | `ServerCapabilities.kt:13-27` has no color kind |
| 2 | ext.manifest | Missing-backend | M1 | 150 | 100 | 1.0 | 0 | L | WP-HOST-9, WP-REG-4/8 | `manifest.schema.json:5` is closed |
| 3 | host.process / host.* | Missing-backend | M1 | 150 | 100 | 1.0 | 0 | L | WP-HOST-1..5 | `services/mobile/exthost` does not exist |
| 4 | commands.registry | Missing-backend | M1 | 145 | 99.1 | 0.2 | 0 | M | WP-API-17 | `Command.kt:28-61`, declared commands only |
| 5 | config.model | Missing-backend | M1 | - | 96.4 | 0.2 | 0 | M | WP-API-2 | resolver exists: `SettingsResolver.kt` |
| 6 | ui.notifications | Missing-both | M2 | 130 | 96.1 | 2.0 | 0.5 | M | WP-API-18, WP-UI-4 | modal KitDialog only |
| 7 | ui.text-editor | Missing-both | M2 | 127 | 95.8 | 0.4 | 0 | L | WP-API-19, WP-UI-6 | one selection per tab |
| 8 | doc.model / doc.sync | Missing-backend | M2 | - | 95.0 | 0.2 | 0 | L | WP-API-3 | `DocumentStore.kt:18-84` |
| 9 | workspace.folders | Missing-backend | M2 | 122 | 93.8 | 0.7 | 0 | S | WP-API-2/3 | single `/workspace` |
| 10 | log.output | Missing-both | M2 | 111 | 92.7 | 0.8 | 0 | M | WP-API-18, WP-UI-4 | log ring only |
| 11 | fs.api / fs.watch | Missing-backend | M2 | - | 84.6 / 79.3 | 0.2 | 0 | M | WP-API-4/5 | watcher covers expanded dirs only |
| 12 | diagnostics | Missing-backend | M2 | - | 81.2 | 0.2 | 0 | S | WP-API-13 | `DiagnosticStore.kt:23-43` |
| 13 | ui.menus (+ submenus) | Partial | M3 | 108 | 75.1 | 9.2 | 1.1 | M | WP-UI-3 | 16 menu ids; `view/*` not rendered |
| 14 | ui.quick-pick / ui.input-box | Missing-both | M2 | 98 / 86 | 73.0 / 69.3 | 0.9 | 0 | M/S | WP-API-18, WP-UI-4 | KitDialog quick pick |
| 15 | ui.tree-view | Missing-both | M3 | 73 | 64.1 | 1.2 | 0 | L | WP-API-20, WP-UI-5 | eager JSON tree (`ViewDataNodes.kt:84`) |

The full ranking is in `matrix-summary.json#topBlockers`. Per-row evidence links are in the matrices.

## 5. Out-of-scope tracks (D8)

"Touched" means the extension uses at least one item in that track.

| Track | Ext touched | w% all | w% E_in | Effort | Basis |
|---|---|---|---|---|---|
| OOS-DAP | 76 | 68.5 | 66.2 | XL (~8-10 agent-weeks) | DebugService 21 methods + debug UI + DAP transport (design.md row 25) |
| OOS-NB | 70 | 56.3 | 61.3 | XL (~10-12 agent-weeks) | 5 notebook shapes (18-method kernels) + notebook editor + renderers (WebView) |
| OOS-CHAT | 31 | 28.6 | 29.0 | XL (~12+ agent-weeks, plus a model provider) | ChatAgents2 20, LanguageModels 12, Mcp 11 methods; chat UI |
| OOS-PROP | 14 | 26.6 | 23.4 | L (~3 agent-weeks per allow-list tranche) | per-proposal; P7 candidates |
| OOS-MS | 2 | 0.1 | 0.1 | Not planned (service-bound) | tunnels, remote, Settings Sync |
| OOS-WEB | 0 | 0 | 0 | M | no browser-only extension in the corpus |
| fork | 5 | 2.5 | 2.7 | none (fork-only code paths) | Cursor/Antigravity APIs |

The large DAP and NB shares come from extensions that are in scope but also touch these tracks. For example, Python registers debug and notebook APIs. Only 6 extensions are excluded outright (corpus.md §2). The efforts above are this document's estimates from shape and method counts. No design document estimates them.

## 6. Cross-document contradictions (for the lead; other documents were not edited)

1. **Effort scale:** COMMON uses S <= 2 agent-days, M <= 1 week, L <= 3 weeks. backend.md §0 uses S <= 3 days, M <= 2 weeks, L <= 5 weeks, XL > 5 weeks.
2. **MainThread method count:** D1 and exthost-deps.json say 524. design.md §6 and vscode-exthost-shapes.json say 522.
3. **Milestone mismatches between design.md §6 (shape) and ui.md §9 (WP):**
   - Feedback shapes (MessageService, QuickOpen, StatusBar, Progress, OutputService) are M1 in design.md, but their UI WP-UI-4 is M2.
   - Decorations is M3 and TextEditors decoration types are M2, but WP-UI-7 is M4.
   - SCM/QuickDiff is M4, but WP-UI-11 is M5.
   - TerminalService create is M4, but WP-UI-13 is M5.
   - Authentication is M5, but the WP-UI-14 accounts UI is M4.
   - The matrices use the later milestone.
4. **Other milestone mismatches:**
   - SecretState: design.md says M2; the backend-2 §29 milestone fit puts WP-API-6 at M1.
   - Workspace trust: design.md says M3; backend-2 puts WP-API-16 at M5.
   - Proxy: design.md phase 1 is M2; backend-2 puts WP-API-14 at M4.
   - `workspaceContains` globs: `$checkExists` is M2 in design.md; WP-API-1 is M1.
5. **Semantic tokens:** design.md row 43 puts semantic tokens at M5, yet also says M2 covers "the 23 kinds FeatureRouting already routes". Semantic tokens are routed today (ourcode-backend `lang.semantic-tokens`).
6. **Gap classes:** ui.md §2 and ourcode-ui.json disagree on:
   - `ui.webview-view`: Missing-both vs Missing-UI
   - `ui.tabs`: Partial vs Have
   - `ui.timeline`: Not-planned vs Missing-UI
   - `ui.comments-ui`: Missing-both vs Not-planned
   - `ui.auth-ui`: Missing-both vs Missing-UI
7. **Comments menus:** ui.md §3 lists `comments/*` menus under Not-planned, but WP-UI-15 renders `comments/*` menus in M5.
8. **WP-UI-17** is referenced in the brief, but ui.md defines only WP-UI-1..16 plus PE1.
9. **Missing link target:** design-protocol.md P9 links `roadmap.md`, which does not exist.
10. **Duplicated device run:** WP-HOST-3 (G1-G7 on device) and WP-PERF-1 describe the same run.
11. **extensionKind:** backend-2 §20 says Have once the manifest is accepted; ourcode-backend `ext.kind` says Missing.
12. **Stale scanVersion:** backend.md cites corpus scanVersion `4913c6e67664`; the current value is `0638c27e3a2b`.
13. **REG/SEC/PERF/TEST WPs** have no milestones. The capabilities that depend on them use the host/API WP milestone.

## 7. Language-server provisioning notes (test-program.md reference)

Language servers are extension-shipped (rust-analyzer, ruff, pyrefly and others, `native.modules`, M2, WP-HOST-11) or runtime-downloaded (clangd, UNVERIFIED linux-arm64 glibc availability; backend-2 §30 item 11). The per-extension ELF classification is in `data/corpus.json` (`nativeBinaries`).
