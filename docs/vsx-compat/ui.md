# VS Code extension UI on easyIDE: gap analysis and plan (audit, 2026-09-25)

Scope: every VS Code UI surface an Open VSX extension can reach (`ui.*` ids from the canonical list), what easyIDE draws today,
and the work to close the gap on Android tablets and phones. Documentation only; no code was changed. Architecture per the lead's
decisions: route 2b (ADR 0031: VS Code's extension host vendored unmodified + a guest-side JS main-thread adapter that speaks the
adapter↔Kotlin **UI protocol**, the re-scoped docs/extension-host/arch.md), webviews per ADR 0033, identity per D9, language features per D10.
Kotlin owns every surface below; the adapter owns VS Code-side protocol state and stubs out-of-scope shapes.
Related: [README.md](README.md) · [design.md](design.md) · [backend.md](backend.md) · [gap-analysis.md](gap-analysis.md) ·
[optimisation.md](optimisation.md) · [matrix/menus.md](matrix/menus.md) · [matrix/when-context.md](matrix/when-context.md) ·
webview detail in [ui-webviews.md](ui-webviews.md).

Evidence: code audit in [research/ourcode-ui.md](research/ourcode-ui.md) (file:line per claim) and
[research/ourcode-backend.md](research/ourcode-backend.md); VS Code source map in [research/vscode-src.md](research/vscode-src.md);
Android WebView facts in [research/policy-licence.md](research/policy-licence.md) §4. **Usage** = `weightShare` from
[data/corpus-usage.json](data/corpus-usage.json): the share of all corpus downloads (156 extensions, 1.09 bn downloads, snapshot
2026-09-25) held by extensions that use the API or contribution point.
Aliases: `A/` = `services/mobile/app/src/main/java/dev/easyide/app/`, `S/` = `services/shared/extension-schema/src/main/kotlin/dev/easyide/extensions/`,
`SCHJ` = `services/shared/extension-schema/manifest.schema.json`, `VS:` = microsoft/vscode `0b16cb97` (MIT).
Gap classes: Have | Partial | Missing-backend | Missing-UI | Missing-both | Not-planned. Effort S ≤2 agent-days, M ≤1 wk, L ≤3 wk, XL >3 wk.

## 0. Headline

| Measure | Value |
|---|---|
| UI surfaces | 59 (55 canonical + `ui.testing`, `ui.folding`, `ui.language-status`, `ui.outline`) |
| Status (this doc, §2) | Have 9 · Partial 27 · Missing-UI 14 · Missing-both 4 · Not-planned 5. Changes vs the code audit: `ui.tabs` Have → Partial (no API projection), `ui.webview-view` and `ui.auth-ui` → Missing-both (schema/backend also missing), `ui.comments-ui` Not-planned → Missing-both (§8.5), `ui.timeline` → Not-planned (provider API is proposed-only) |
| Menu locations | 96 VS Code (54 stable + 42 proposal-gated) + 4 easyIDE-own. VS Code rows: Have 2, Partial 2, Missing-UI 48, Not-planned 44 |
| Colour ids understood | 92 of about 990 (9%) (`A/ui/theme/ThemeColorMap.kt:17-118`) |
| Codicons | 0 of 762 |
| Context keys | 37 fixed + 7 prefixes declared, 26 + 5 set, no `setContext`; the corpus uses 1,189 distinct keys |
| Extension webviews | 0 (the only WebView is `MermaidView`) |
| Biggest blockers outside UI | manifest schema closes `contributes` to 16 points and forbids `submenu`/icon objects (`SCHJ:48,128,137`, backend C1); PE1 editor not started (ADR 0018); no `:exthost` module yet |

## 1. Principles for tablet and phone

### 1.1 Rendering rule
| Rule | Applies to | Reason |
|---|---|---|
| **Native Compose (Kit)** for every surface where VS Code draws its own widget from structured data | trees, menus, status bar, quick input, notifications, progress, output, decorations, SCM, tabs, diff, settings, walkthroughs, terminal, problems, peek, hover, completion | data → Kit widgets keeps density, TalkBack, theming and the 44dp touch rules (docs/ui-redesign/ux-rules.md) and costs no WebView memory |
| **Android WebView only where VS Code itself uses a webview** | `WebviewPanel`, `WebviewView`, webview-based custom editors | the extension supplies HTML/JS; nothing else can render it ([ui-webviews.md](ui-webviews.md)) |
| Never a WebView for workbench chrome | - | ADR 0006 (native shell), ADR 0026 (Kit is the only widget layer) |
| Markdown in hovers, tooltips, walkthroughs, settings descriptions | native `LspMarkdownView` (`A/ui/screens/workspace/lsp/LspMarkdown.kt:54`) extended with codicons and trusted `command:` links | no WebView for text |

### 1.2 Touch equivalents (U-INT-03: long-press is never the only path)
| VS Code gesture | Tablet with keyboard/mouse | Touch-only (tablet or phone) |
|---|---|---|
| right-click context menu | secondary click → KitMenu at pointer | long-press **and** a visible `...` button on rows/headers; in the editor, long-press = hover, so `editor/context` sits in the editor/title overflow and the selection toolbar |
| hover (tooltip, hover card) | mouse rest | long-press card (editor); tap-to-reveal sheet (status items, tree tooltips). The Kit has no tooltip primitive yet (grep `Tooltip` in `A/ui/` = 0); add `KitTooltip` (WP-UI-4) |
| keybinding | hardware keyboard via `Keymap` + chords (`A/ui/commands/Keymap.kt:64-201`) | key row button (`keyRow` menu, `A/extensions/adapters/KeyRows.kt:38-98`) + command palette entry; no chords needed |
| inline row actions on hover | shown on hover/selection | shown on the selected row only |
| drag and drop (tree DnD, tab DnD) | pointer drag | long-press-drag; the `...` menu offers "Move to..." |
| multi-select | Ctrl/Shift click | selection mode entered from `...` → checkboxes |

### 1.3 Placement by window size class (`A/ui/foundation/WindowSize.kt:13-35`; docs/ui-redesign/layout-spec.md:14-19)
| Class | Width | Density (AUTO) | Sidebar views | Panel views | Editor-column pages (webview panel, diff, walkthrough) |
|---|---|---|---|---|---|
| COMPACT (phone) | <600dp | COMFORTABLE: rows 36dp, status 28dp, 44dp isolated targets | bottom-bar cell (≤5, rest in More sheet) → overlay sheet | bottom sheet | full-screen stage document; `Beside` = full screen + Back |
| MEDIUM (small tablet, foldable) | 600-839dp | DENSE: rows 28dp, status 24dp | rail + one docked side panel | overlay sheet or docked | stage, 1-2 groups |
| EXPANDED (tablet) | ≥840dp | DENSE | rail + left/right docked | docked bottom panel | stage, 1-4 groups on one axis |
Height COMPACT (<480dp, landscape phone): panels become overlays. Soft keyboard up: bottom bar hidden, InputDock (touch toolbar + key row) shown (`A/ui/shell/workspace/DockRules.kt:21-27`).

## 2. Surface table (all `ui.*` ids)

Ordered by area. "Today" cites the main evidence (full list in [research/ourcode-ui.md](research/ourcode-ui.md) §2). Render: N = native, W = WebView, – = not planned.
Tablet / phone = placement proposal. WP = the work package in §9 that closes it.

| id | Usage | VS Code behaviour | easyIDE today | Gap | R | Tablet | Phone | Change required | E/Risk | WP |
|---|---|---|---|---|---|---|---|---|---|---|
| `ui.view-container` | 0.255 viewsContainers | activitybar/panel containers with icon, title, badge; `viewContainer/title` menu | R4 container registry: sidebar/secondary/panel, nav item per container, 3 per pack (`A/extensions/adapters/ShellContributions.kt:53,118`, `A/ui/shell/ShellLimits.kt:24`) | Partial | N | rail icon → docked panel, views as collapsible sections | bottom-bar cell or More sheet → overlay sheet | map `.vsix` `viewsContainers` without the easyIDE gate; mono-tint SVG/PNG icons; header actions slot; lift the 3-per-pack cap for `.vsix` (GitLens has 3+1) | M/Low | 5 |
| `ui.tree-view` | 0.369 createTreeView (+0.099 registerTreeDataProvider); 0.442 contributes.views | lazy `TreeDataProvider` (getChildren/getTreeItem/resolveTreeItem/getParent), reveal, selection, checkboxes, DnD, badge, message, description, `resourceUri` icons + decorations, `contextValue` → `viewItem` | eager JSON tree or flat list; no lazy fetch, inline actions, item menus, selection, checkbox, tooltip (`A/ui/shell/ext/ViewDataNodes.kt:84,96`, `A/extensions/adapters/LegacyView.kt:16`) | Partial | N | 28dp rows, twistie + icon + label + muted description + inline buttons on hover/selection | 36dp rows, `...` + long-press → `view/item/context`, inline buttons on selected row | new `TreeViewHost` fed by the UI protocol `tree/*` (adapter implements `MainThreadTreeViews`): id-keyed lazy `LazyColumn`, scoped `view`/`viewItem` keys, inline group, file-icon theme for `resourceUri`, checkbox, multi-select, badge → nav badge, `message`, reveal | L/Med | 5 |
| `ui.views-welcome` | 0.271 | markdown with `[label](command:x)` buttons when a view is empty; `when` gated | decoded, never drawn (`S/manifest/ContributesDecoder.kt:224`) | Missing-UI | N | `KitEmptyState` with buttons | same, full-width buttons in thumb zone | render when tree empty / webview unresolved | S/Low | 5 |
| `ui.webview-view` | 0.148 (views type webview); 0.137 registerWebviewViewProvider | HTML view inside a sidebar/panel container | none; `views[].type` enum is `["tree"]` (`SCHJ` `$defs.view`) | Missing-both | W | WebView in the docked panel section | panel sheet at full height; IME resize | [ui-webviews.md](ui-webviews.md) §3-5 | L/High | 8,9 |
| `ui.webview-panel` | 0.395 createWebviewPanel; 0.150 serializer | HTML editor-column tab, `viewColumn`, reveal, serializer restore | none; stage takes URI-keyed document types (`A/ui/shell/DocumentRegistry.kt`, `A/ui/shell/EditorStage.kt:25`) | Missing-UI | W | stage document tab, column 1-4 → group | full-screen document in the switcher | `webview:` document type + lifecycle + serializer | L/High | 8,9 |
| `ui.webview-bridge` | 0.395 (max of above) | `acquireVsCodeApi`, state, `asWebviewUri`, `cspSource`, `--vscode-*` vars, `webview/context` | MermaidView only (`addJavascriptInterface`, `allowFileAccess`; `A/ui/screens/workspace/MermaidView.kt:36-58`) | Missing-UI | W | same | same + long-press context menu | [ui-webviews.md](ui-webviews.md) §3-4; ADR 0033 | L/High | 8 |
| `ui.custom-editor` | 0.142 API; 0.146 contributes | custom (text or binary) editor per glob, priority default/option, "Open With" | `documentOpeners` glob → document type (`A/ui/shell/ext/DocumentOpeners.kt`); `customEditors` rejected by schema | Missing-UI | W | opens per priority; explorer "Open With..." | long-press file → Open With sheet | map to documentOpeners + webview doc; CustomDocument RPC ([ui-webviews.md](ui-webviews.md) §7) | M/Med | 9 |
| `ui.activity-nav` | via view-container | activity bar icons + badges | NavSurface rail/bottom bar, badges, More sheet (`A/ui/shell/nav/NavRules.kt:35`) | Have | N | rail; labels on EXPANDED | bottom bar 5 cells | truncate long titles instead of refusing; wire view badges | S/Low | 5 |
| `ui.panel` | via view-container | bottom panel hosting Problems/Output/Terminal/extension containers | BottomPanel with Terminal + Problems; `output` container has no renderer (`A/ui/shell/workspace/WorkspacePanels.kt:32-39`) | Partial | N | docked bottom panel, Ctrl+J | bottom sheet | Output renderer; ext panel containers (Placement.PANEL exists) | S/Low | 4 |
| `ui.status-bar` | 0.540 createStatusBarItem | text with `$(icon)`, colour, background (error/warning), tooltip (string or markdown), command + args, a11y info, priority/alignment, name, `setStatusBarMessage` | caption + command id; tooltip carried not shown; no icons/colours/args/a11y (`A/ui/screens/workspace/ext/ExtensionSlots.kt:104-117`); phone drops ext items first (`A/ui/shell/workspace/StatusPlan.kt:6-14`) | Partial | N | 24dp strip, left/right by priority; tooltip card on hover/long-press | 28dp strip keeps PROBLEMS/SAVE/FILE; ext items → one `+n` chip → sheet listing items with tooltip + action | extend StatusItem (codicons, ThemeColor, background, markdown tooltip, args, a11y); overflow sheet; transient message slot | M/Low | 4 |
| `ui.language-status` | 0.211 createLanguageStatusItem | `{}` item per language with severity, busy, detail, command, pin | LSP status items only (`A/ui/screens/workspace/lsp/LspChrome.kt:82`) | Missing-UI | N | `{}` item → popup list | same → sheet | feed ext items into the LspStatusItems popup, selector match on active doc | S/Low | 4 |
| `ui.notifications` | 0.928 show*Message | non-modal toasts with actions + centre; `modal:true` dialog; `detail` | every message a modal KitDialog; plain toasts without actions (`A/ui/screens/workspace/ext/ExtensionPrompts.kt:112`, `A/ui/shell/host/Toasts.kt:19`) | Partial | N | toast stack bottom-right (≤3) with actions; bell in status strip → centre side sheet | one snackbar above bottom bar/dock, ≤2 actions + More; centre bottom sheet | Notification model (severity, source ext, actions, progress, dismissed) + centre; dialog only for `modal` | M/Low | 4 |
| `ui.progress` | 0.790 withProgress | Notification (toast + cancel), Window (status spinner), `{viewId}` (bar under view header), SourceControl | `KitProgress` exists, unwired (`A/ui/kit/KitProgress.kt:35`) | Missing-UI | N | toast bar + Cancel; status spinner; 2dp view bar | same; snackbar slot | progress controller keyed by id | S/Low | 4 |
| `ui.quick-pick` | 0.652 showQuickPick; 0.554 createQuickPick; 0.409 QuickPickItemKind | fuzzy list with label/description/detail, separators, buttons, busy, multi-step, dynamic items, `matchOnDescription/Detail`, `activeItems` | KitDialog quick pick: label filter, description, multi-select (`ExtensionPrompts.kt:55`); palette uses `PickerOverlay` (`A/ui/commands/PickerOverlay.kt:59`) | Partial | N | top-centre `PickerOverlay` | bottom-anchored full-width sheet, field above keyboard, 44dp rows | QuickInput controller on PickerOverlay covering the `createQuickPick` lifecycle | M/Med | 4 |
| `ui.input-box` | 0.681 showInputBox; 0.376 createInputBox | prompt, password, async `validateInput` with severity, `valueSelection`, steps, buttons | regex validation only (`S/action/HostPort.kt:97-99`) | Partial | N | same overlay | field above keyboard | async validate round trip, severity, selection, steps | S/Low | 4 |
| `ui.output` | 0.921 createOutputChannel | named channels (plain / `LogOutputChannel` with levels), dropdown picker, `show(preserveFocus)` | Extension Log only (`A/ui/screens/extensions/ExtensionLog.kt:54`) | Missing-UI | N | bottom panel "Output" + channel picker, follow/clear/copy | sheet or `output://` document | channel store (ring buffer) + renderer reusing LogSection; log-level filter | M/Low | 4 |
| `ui.menus` | 0.759 contributes.menus | 96 locations, groups/order, `when`, `alt`, icons, toggled, submenus | data-driven `MenuModel` at 4 VS Code + 2 own locations (`A/extensions/adapters/MenuModel.kt:33-97`) | Partial | N | right-click KitMenu; title groups as icon buttons | long-press or `...` | §3 | M/Low | 3 |
| `ui.submenus` | 0.204 | `contributes.submenus` + `{submenu}` items, nested | KitMenu cascades exist; schema forbids `submenu` (`SCHJ:137`) | Missing-UI | N | cascade beside row | body replaced + Back row | §3 | S/Low | 3 |
| `ui.keybindings` | 0.475 | `key`/`linux`/`mac`/`win`, `when`, `args`, chords | full Keymap + user + contributed layers (`A/extensions/adapters/ContributedKeybindings.kt:10-42`) | Have | N | hardware keyboard | key row + palette | depends on context keys (WP-UI-2); `-command` removal ignored by design | S/Low | 2 |
| `ui.command-palette` | 0.641 menu commandPalette | `category: title`, `when`, recent | CommandPalette + QuickOpen (`A/ui/commands/CommandPalette.kt:30`) | Have | N | Ctrl+Shift+P overlay | nav `Commands` → bottom picker | recently-used group; `category: title` display; `>` prefix parity | S/Low | 3 |
| `ui.context-keys` | 0.527 `view`; 0.364 `viewItem` | 1,281 keys in VS Code; `setContext` | ContextKeyService + full when grammar except `===`/`!==`; 37 keys, no `setContext` (`S/whenclause/ContextKeyService.kt:59-103`) | Partial | N | engine | engine | §4.1 | M/Low | 2 |
| `ui.editor-decorations` | 0.407 createTextEditorDecorationType | `DecorationRenderOptions` on ranges | 6 fixed typed layers, draw-phase painters (`A/ui/screens/workspace/decor/DecorationLayer.kt:27-57`) | Partial | N | §5 | same; hover message via long-press | `ExtDecorations` layer now; text colour and inline before/after need PE1 | L/Med | 7 |
| `ui.gutter` | (decorations) | gutter icons, quick-diff bars, `editor/lineNumber/context` | fixed glyph enum; no quick diff (`decor/Decorations.kt:69-75`) | Partial | N | 16dp lane, right-click menu | tap glyph → popup; long-press → line menu | decoration icons (tinted SVG/PNG), quick-diff bars, line-number menu | M/Low | 7 |
| `ui.overview-ruler` | 0.027 OverviewRulerLane | marks beside the scrollbar | thumb only (`A/ui/screens/workspace/EditorScrollbar.kt:26`) | Missing-UI | N | 4dp marks on the track | marks on fast-scroll track while scrolling | paint from DecorationSet | S/Low | 7 |
| `ui.code-lens-ui` | 0.609 registerCodeLensProvider | clickable lines above code | gutter glyph → popup (`lsp/CodeLensController.kt:19-30`) | Partial | N | between-line block after PE1 | keep gutter → popup (tiny text on phones) | host lenses into the same controller now; blocks with PE1 | S now / XL PE1 / High | 10 / PE1 |
| `ui.inlay-hint-ui` | 0.464 registerInlayHintsProvider | inline hint labels, parts with tooltip/command | EOL ghost text (`lsp/CaretDecorations.kt:81`) | Partial | N | inline after PE1 | keep EOL ghost text | host providers now; inline with PE1 | S now / XL PE1 / High | 10 / PE1 |
| `ui.hover-ui` | 0.592 | markdown, codicons, trusted `command:` links, verbosity (proposed) | markdown card; no `command:` links, no codicons (`lsp/InfoController.kt:40-47`) | Partial | N | mouse rest / Ctrl+K Ctrl+I | long-press word → card | codicons + trusted links; host providers | S/Low | 10 |
| `ui.completion-ui` | 0.652 | widget with kinds, details, docs, `command` after insert, `labelDetails`, tags | resolve, docs, snippets; provider `command` stripped (`lsp/CompletionController.kt:208-211`) | Partial | N | caret popup, docs beside | docs below; key row Tab/arrows | run `command`, `labelDetails`, deprecated strike | S/Low | 10 |
| `ui.code-action-ui` | 0.682 | lightbulb, quick fix menu, refactor preview | bulb + menu + Ctrl+. (`lsp/EditActionsController.kt:31-56`) | Have | N | Ctrl+. / bulb | bulb; touch toolbar entry | merge host providers; disabled reasons | S/Low | 10 |
| `ui.diagnostics-ui` | 0.639 createDiagnosticCollection; 0.579 DiagnosticTag | squiggles, Problems, tags (Unnecessary fade, Deprecated strike), `code.target` links, related info | squiggles, gutter, Problems grouped by file (`lsp/DiagnosticsPresenter.kt:53`) | Partial | N | Problems in bottom panel | nav Problems → sheet | host collections; tags; code links; related info rows | S/Low | 10 |
| `ui.peek` | 0.470 references | inline peek editor | References side panel (`lsp/NavigationController.kt:30-35`) | Partial | N | References panel | location sheet with preview | route `editor.action.peekLocations`/`showReferences` to the panel; no embedded editor | S/Low | 10 |
| `ui.rename-ui` | 0.467 | inline rename box + preview | dialog + preview (`lsp/LspDialogs.kt:28`) | Have | N | dialog / F2 | dialog above keyboard | host providers | S/Low | 10 |
| `ui.semantic-highlighting` | 0.467 | theme `semanticTokenColors`, custom token types/modifiers/scopes | yes (`lsp/SemanticTokensController.kt:35`) | Have | N | same | same | host providers; `semanticTokenTypes/Modifiers/Scopes` (0.056/0.051/0.185) into the styler | S/Low | 10 |
| `ui.folding` | 0.470 registerFoldingRangeProvider | gutter chevrons, fold commands | none; foldingRange withheld (`A/lsp/LspRuntime.kt:134-145`) | Missing-UI | N | chevrons (PE1) | same, larger slop | PE1 | XL/High | PE1 |
| `ui.outline` | 0.476 documentSymbol | Outline view | Outline container (`A/ui/shell/CoreShell.kt:52`) | Have | N | secondary sidebar | nav → sheet | host providers | S/Low | 10 |
| `ui.breadcrumbs` | 0.476 documentSymbol | path + symbols | yes (`A/ui/shell/workspace/EditorBreadcrumbs.kt:21`) | Have | N | under tab strip | under switcher, h-scroll | host providers; file decorations on crumbs | S/Low | 10 |
| `ui.text-editor` | 0.858 activeTextEditor; 0.868 showTextDocument | selections, visibleRanges, reveal, edit, insertSnippet, options, viewColumn | one selection per tab; `editorHasMultipleSelections` always false (`A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:151`) | Partial | N | same | same | visibleRanges from geometry; options per tab; multi-cursor with PE1 | L/Med | 6 / PE1 |
| `ui.tabs` | 0.543 tabGroups; 0.507 TabInputTextDiff; 0.440 TabInputCustom | `window.tabGroups` read model + close + events | EditorStage 1-4 groups, PREVIEW/KEPT/PINNED (`A/ui/shell/EditorStage.kt:18-31`) | Partial (API projection missing) | N | tab strip per group | one group, switcher | §8.1 | S/Low | 6 |
| `ui.diff-editor` | 0.507 TabInputTextDiff; 0.148 when isInDiffEditor | `vscode.diff(left, right, title)` over any URIs | git-only stage docs (`A/ui/shell/diff/DiffModel.kt:14-21`, `Comparison.kt:14`) | Partial | N | side-by-side, open beside | unified, full screen | §8.2 | M/Med | 6 |
| `ui.scm-view` | 0.002 createSourceControl; 0.038 menu scm/title | provider-generic SCM view, input box, groups, quick diff, `scm/*` menus | git-only JGit UI (`A/ui/screens/workspace/SourceControlPane.kt:67`) | Partial | N | sidebar Git | nav Git → sheet | §8.4 | L/Med | 11 |
| `ui.file-decorations` | 0.095 registerFileDecorationProvider | badge letter, colour, tooltip, propagate | git-only tint + letter (`A/ui/screens/workspace/FileTreeRow.kt`) | Partial | N | explorer, tabs, breadcrumbs, tree items with `resourceUri` | same | generic provider fan-in merged with git | M/Low | 7 |
| `ui.timeline` | 0.016 timeline menus; provider API proposed only (`VS:src/vscode-dts/vscode.proposed.timeline.d.ts`) | Timeline view | none | Not-planned | – | – | – | stable extensions cannot provide items; revisit only if our own git timeline ships | M/Low | – |
| `ui.comments-ui` | 0.064 createCommentController (Claude Code, GitHub PR) | threads in editor + Comments view | none (arch.md Providers table: not planned) | Missing-both | N | §8.5 | §8.5 | adapter stubs `MainThreadComments` now (ADR 0031 stub table); native UI later | L/Med | 15 |
| `ui.testing` | 0.208 createTestController | Testing view, gutter run icons, results, coverage | none | Missing-both | N | sidebar Testing container | nav via More sheet | TestController UI: tree (TreeViewHost), gutter glyph, results in Output-like panel; coverage later | L/Med | 13 |
| `ui.walkthroughs` | 0.277 | Welcome page steps with media, completion events | decoded (`S/manifest/ContributesDecoder.kt:248`) | Missing-UI | N | `walkthrough://` document, steps left, media right | single column, collapsible steps | §8.6 | M/Low | 12 |
| `ui.settings-ui` | 0.956 contributes.configuration | UI generated from any JSON schema | enum/bool/int/number/string/string[]; rest → "Edit in settings.json" (`A/data/settings/ContributedSettings.kt:95-122`) | Partial | N | two-column rows | stacked rows | §8.7 | M/Low | 12 |
| `ui.terminal-ui` | 0.368 createTerminal | tabs with icon/colour, links, split, profiles, `terminal/*` menus | PTY tabs, rename, ext-named terminals, key row (`A/ui/screens/workspace/WorkspaceTerminals.kt:30-172`) | Partial | N | bottom panel tabs | sheet or full screen + key row | §8.8 | M/Med | 13 |
| `ui.auth-ui` | 0.045 getSession; 0.122 registerUriHandler | Accounts menu, consent dialog, sign-in via browser + URI handler | git credential dialog only | Missing-both | N | Accounts in Settings + consent KitDialog | same in a sheet; Custom Tabs + `env.uri-handler` intent | accounts UI over backend `auth.api` | M/Med | 14 |
| `ui.extensions-ui` | all | marketplace browse/install/details | easyIDE registry panel + page; `.vsix` only for icon themes | Partial | N | panel + stage page | sheet + full-screen page | Open VSX source, README render, `host.run` prompt (see [backend.md](backend.md)) | M/Med | backend |
| `ui.color-theme` | 0.103 activeColorTheme; 0.008 themes | full workbench colour set + tokenColors | 92 ids mapped to tokens (`A/ui/theme/VsCodeThemeMapping.kt:52-83`) | Partial | N | same | same | keep raw colour map (§4.2) | M/Low | 1 |
| `ui.theme-colors` | 0.433 ThemeColor; 0.065 contributes.colors | colour registry, `contributes.colors` defaults, `--vscode-*` | none; `colors` rejected by schema | Missing-UI | N | – | – | §4.2 | M/Low | 1 |
| `ui.icons` | 0.055 contributes.icons | extension icon font glyphs as `$(id)` | none; `icons` rejected | Missing-UI | N | – | – | load woff/ttf, id → codepoint | M/Low | 1 |
| `ui.codicons` | 0.592 ThemeIcon | 762-glyph font; `$(name)`, `$(name~spin)` in labels | none (`A/ui/icons/IconResolver.kt:26-33`) | Missing-UI | N | – | – | §4.3 | S/Low | 1 |
| `ui.icon-theme` | 0.009 iconThemes (Material Icon Theme) | file icon themes | installable, Material default (`A/extensions/adapters/IconThemes.kt:89`) | Have | N | – | – | Material Icon Theme also ships `main` (it calls createOutputChannel): its code part needs the host | S/Low | – |
| `ui.product-icon-theme` | 0.000 (0 corpus extensions) | swaps workbench codicons | none | Not-planned | – | – | – | no corpus use | – | – |
| `ui.accessibility` | – | `accessibilityInformation {label, role}` on tree items, status items; screen-reader mode | Kit semantics rules; ext labels not carried (docs/ui-redesign/ux-rules.md:96) | Partial | N | TalkBack + focus ring | TalkBack; 44dp targets | §8.9 | M/Med | 16 |
| `ui.minimap` | – | minimap | none | Not-planned | – | – | – | out of scope | – | – |
| `ui.notebook` | 0.479 NotebookCellKind (mostly Python/Jupyter) | notebooks | none | Not-planned | – | – | – | track OOS-NB (D8); the adapter stubs the notebook shapes so the API loads without throwing (ADR 0031) | – | – |
| `ui.debug` | 0.378 contributes.debuggers | debug UI | none | Not-planned | – | – | – | track OOS-DAP (D8); `debug/*` menus ignored | – | – |

Priority for the must-work list: every row with WP 1-10 is used by at least 5 of the 22 must-work extensions (corpus `extensions` lists).
Rows with usage below 0.1 and no must-work user (overview ruler, product icons, timeline) are last.

## 3. Menus

The full per-location table (96 VS Code + 4 own, status, placement, corpus use) is generated in [matrix/menus.md](matrix/menus.md);
it is not repeated here. Summary by group (status from [research/ourcode-ui.md](research/ourcode-ui.md) §3, usage from
`corpus-usage.json#menus`):

| Group | Locations | Today | Plan | Highest usage |
|---|---|---|---|---|
| Command palette | `commandPalette` | Have | recent section | 0.641 |
| Editor | `editor/title`, `editor/title/run`, `editor/title/context`, `editor/context`, `editor/context/copy`, `editor/lineNumber/context`, `editor/content`*, `modalEditor/editorTitle`, `editor/inlineCompletions/actions`* | Partial 2, Missing 7 | in scope: all but `modalEditor/*` and `editor/inlineCompletions/actions` (the latter two need proposed APIs) | editor/context 0.476, editor/title 0.337, editor/title/run 0.234 |
| Explorer / files | `explorer/context`, `file/newFile` | Have 1, Missing 1 | in scope | explorer/context 0.385, file/newFile 0.101 |
| Views | `view/title`, `view/item/context`, `viewContainer/title`* | Missing 3 (two ids known, unrendered) | in scope, WP-UI-3/5 | view/title 0.479, view/item/context 0.380 |
| SCM | `scm/title`, `scm/sourceControl`, `scm/resourceState/context`, `scm/resourceGroup/context`, `scm/resourceFolder/context`, `scm/change/title`, `scm/repository`, + 7 proposal-gated (`scm/inputBox`, `scm/history*`, `scm/artifact*`, `scm/repositories/title`) | Missing 14 | stable 7 in scope (WP-UI-11); proposal-gated ones only for extensions on the D8 proposal allow-list | scm/title 0.038 |
| Terminal | `terminal/context`, `terminal/title/context` | Missing 2; fix our wrong id `terminal/title` (`S/contrib/MenuIds.kt:18`) | in scope | 0.054 |
| Webview | `webview/context` | Missing | in scope with WP-UI-9 | 0.088 |
| Testing | `testing/item/context`, `testing/item/gutter`, `testing/profiles/context`, `testing/item/result`, `testing/message/context`, `testing/message/content` | Missing 6 | in scope with WP-UI-13 | 0.092 |
| Timeline / ports / extension | `timeline/title`, `timeline/item/context`, `ports/*` (3), `extension/context` | Missing 6 | `extension/context` in scope; timeline and ports Not-planned (no provider API / no port forwarding) | 0.016 |
| Diff / merge / multi-diff | `diffEditor/gutter/hunk`*, `diffEditor/gutter/selection`*, `mergeEditor/result/title`*, `multiDiffEditor/*`* | Missing 5 | only for extensions on the D8 proposal allow-list; low | - |
| Share (proposed) | `editor/context/share`, `explorer/context/share`, `editor/title/context/share` | Missing 3 | Not-planned (no share provider) | - |
| Not-planned (44; OOS tracks per D8) | debug (5, OOS-DAP), notebook/interactive (7, OOS-NB), chat/agents (16 incl. `editor/context/chat`, OOS-CHAT), comments (9), `menuBar/*`, `touchBar`, `statusBar/remoteIndicator`, `issue/reporter`, `file/share`, `searchPanel/aiResults/commands` | Not-planned | items are loaded and ignored without a warning; `issue/reporter` (0.123) is dropped silently | issue/reporter 0.123 |
(* = proposal-gated key in `VS:src/vs/workbench/services/actions/common/menusExtensionPoint.ts`.)

**Menu engine changes (WP-UI-3):**
| # | Change | Where |
|---|---|---|
| M1 | Accept `{submenu, group, when}` items and `contributes.submenus {id, label, icon}`; `MenuEntry.Submenu`; recursion depth cap (setting) | schema `SCHJ:137` / tolerant `.vsix` reader (backend C1), `S/manifest/ContributesDecoder.kt:59-65`, `A/extensions/adapters/MenuModel.kt` |
| M2 | `alt` command: Alt/Shift-held variant on hardware keyboards; on touch a long-press on the icon button shows the alt action in a small menu | `MenuModel`, `ExtensionSlots.kt:51-64` |
| M3 | Icons: `$(codicon)` strings, `{light, dark}` path objects, PNG/SVG paths; `toggled` state (`checked` in KitMenu) with `toggled.icon/title` | `S/manifest/DecodeContext.kt:80-87`, KitMenu `Action.checked` |
| M4 | Scoped context per location: `view`, `viewItem` (tree rows), `resourceScheme/resource/resourcePath/...` (explorer, editor title, SCM rows), `scmProvider/scmResourceGroup` (SCM), `webviewId/webviewSection` (webview), `terminal*` | overlay snapshot `ContextKeyService.with(overrides)` (`S/whenclause/ContextKeyService.kt:45-47`) |
| M5 | Command arguments per location as VS Code passes them: tree item element; `Uri` + selected `Uri[]` for explorer/editor title; `SourceControlResourceState[]` for SCM; webview context object | UI protocol `commands/execute` args contract (see [backend.md](backend.md)) |
| M6 | Inline vs overflow: `navigation` (editor/title) and `inline` (view/item/context, scm rows) groups become icon buttons; max inline count per size class as settings | `ExtensionUiPolicy.EDITOR_TITLE_MAX_INLINE` → per class |
| M7 | Unknown or proposal-gated location for an extension without that proposal: ignore silently (VS Code logs); no `MENU_UNKNOWN` warning spam for the 44 not-planned ids | `ContributesDecoder.kt:56` |

## 4. Context keys, colours, icons

### 4.1 Context keys and `setContext`
Per-key detail lives in [matrix/when-context.md](matrix/when-context.md). Engine changes (with backend C7):
1. `setContext` built-in command → `ContextKeyService.setRaw` in an extension-owned layer. Any key name (VS Code does not namespace);
   values JSON (bool, string, number, array, object for `in`). Keys cleared when the extension deactivates.
2. `===` / `!==` operators (VS Code scanner accepts them); a `.vsix` when-syntax error drops the item, not the extension.
3. Add the VS Code keys that the corpus uses, ordered by usage (weightShare):

| Key(s) | Usage | Source of value in easyIDE |
|---|---|---|
| `view` | 0.527 | scoped: view id of the tree/webview view the menu belongs to |
| `viewItem` | 0.364 | scoped: `TreeItem.contextValue` |
| `editorLangId`, `resourceLangId`, `editorTextFocus`, `resourceFilename`, `resourceExtname`, `editorHasSelection`, `editorFocus` | 0.40 … 0.116 | set today (`WorkspaceContextFeed.kt:109-169`) |
| `workspaceFolderCount` | 0.193 | workspace model (1 per project today) |
| `explorerResourceIsFolder` | 0.180 | alias of our `resourceIsFolder` in the explorer scope |
| `resourceScheme`, `resource`, `resourcePath`, `resourceDirname` | 0.159, 0.031, 0.050, 0.039 | scoped resource (file, untitled, git, virtual schemes) |
| `virtualWorkspace` | 0.156 | `false` (no virtual workspaces) |
| `shellExecutionSupported` | 0.153 | `true` when the environment is ready (tasks/terminals can run) |
| `isInDiffEditor`, `inDiffEditor` | 0.148, 0.091 | diff document focused |
| `isWorkspaceTrusted` | 0.106 | workspace trust state (`workspace.trust`, backend) |
| `webviewId`, `activeWebviewPanelId`, `webviewSection` | 0.106, 0.095, 0.004 | webview host (WP-UI-9) |
| `activeEditor`, `editorIsOpen`, `editorReadonly` | 0.077, 0.045, 0.053 | stage |
| `focusedView`, `isWeb`, `isMac/isWindows/isLinux`, `remoteName` | 0.061, 0.064, ≤0.043 | view focus; per D9 identity (uiKind Desktop, appHost `desktop`, remoteName undefined): `isWeb=false`, `remoteName` unset, `isLinux=true` (guest is Linux; extensions pick binaries from it); telemetry keys false/off |
| `commentController`, `inDebugMode`, `notebook*`, `debug*` | 0.058, 0.079 | comments stub: never true; debug/notebook: undefined (Not-planned) |
| extension-own keys (`java:*`, `javaLSReady`, `jupyter.*`, `config.*`) | ≤0.127 | `setContext` / settings |
Also set our declared-but-unset keys (`inSnippetMode`, `suggestWidgetVisible`, `hoverVisible`, `peekVisible`, `focusedView`) and fix
`editorHasMultipleSelections` (always false).

### 4.2 Colour registry (proposal)
VS Code registers 968 colour ids with `registerColor` across `VS:src/vs` (plus 16 `terminal.ansi*` in a loop and git-extension
`gitDecoration.*`; ≈990). Each default is a `ColorValue`: hex, another id, or a transform (`Darken, Lighten, Transparent, Opaque, OneOf,
LessProminent, IfDefinedThenElse, Mix`, `VS:src/vs/platform/theme/common/colorUtils.ts:47-56`) per theme kind (dark, light, hcDark, hcLight).

| Part | Proposal |
|---|---|
| Data | generated `vscode-colors.json` (id, description, defaults per kind as an expression tree) from `registerColor` call sites, same extractor style as `tools/vsx-audit` (MIT source, NOTICE.md row as for `language-configuration.json`). No hand-written table (R-ENG-07 no-hardcoding) |
| `RawColorMap` beside `ThemeColorMap` | holds every theme `colors` entry and `workbench.colorCustomizations` verbatim (today unlisted keys are reported unmapped and dropped). `ThemeColorMap` keeps mapping the subset that drives Kit tokens; nothing in the Kit changes |
| Resolution | `resolve(id, kind)` = customization > theme value > contributed default > core default expression, evaluated lazily with cycle guard; cached per theme |
| `contributes.colors` | `{id, description, defaults {light, dark, highContrast, highContrastLight?}}`; defaults can name another id; hc falls back to dark/light (`VS:src/vs/workbench/services/themes/common/colorExtensionPoint.ts:20-74`). Corpus: 14 ext, 0.065 (GitLens 76 ids, Error Lens, clangd, GitHub PR, Dart) |
| Consumers | `ThemeColor` in status items, decorations, file decorations, tree icons, terminal colours; `--vscode-*` export for webviews (first-dot naming, [ui-webviews.md](ui-webviews.md) §2); `window.activeColorTheme.kind` |
| Sizes | 36 `registerSize` ids exported to webviews as `--vscode-*` too (`VS:src/vs/platform/theme/common/sizes/baseSizes.ts`) |

### 4.3 Codicons and icons
| Item | Proposal |
|---|---|
| Font | bundle `@vscode/codicons` font (npm latest `0.0.46-24`, licence `CC-BY-4.0`; VS Code pins `^0.0.46-40`, [research/ourcode-ui.md](research/ourcode-ui.md) §1.5) + generated name → codepoint table (762 names, `VS:src/vs/base/common/codiconsLibrary.ts`). Font and icons are CC-BY-4.0, not MIT: add a NOTICE.md "Bundled in the APK" row with the attribution line; the CSS/code part is MIT ([research/policy-licence.md](research/policy-licence.md) §2.5 item 4). Verify the exact version and licence at adoption (NOTICE rule) |
| Label parser | `$(name)` and `$(name~spin)` in status text, menu titles, tree labels/descriptions, quick-pick labels, notification text, markdown (`supportThemeIcons`) → inline glyph runs in `KitText`; `~spin` → rotating glyph (respecting reduced motion) |
| `ThemeIcon(id, color?)` | codicon + optional `ThemeColor`; `ThemeIcon.File/Folder` → our file-icon theme for the `resourceUri` |
| `contributes.icons` | `{id: {description, default: {fontPath, fontCharacter}}` or `{default: "codicon-id"}` → load the declared woff/ttf from the extension dir (Typeface from file), map id → glyph; usable as `$(id)`. Corpus 0.055 (GitLens 94 icons, Code Spell Checker) |
| Webviews | VS Code does not inject codicons into webviews (`pre/index.html` has no codicon reference); extensions ship their own CSS. Nothing to do |
| Product icon themes | Not-planned (0 corpus extensions) |

## 5. Editor decorations

`TextEditorDecorationType` is built on the ADR 0018 layer model: decorations stay out of the `AnnotatedString` and are painted in
the draw phase. A new seventh layer `ExtDecorations` is "one object here plus its painter" (`A/ui/screens/workspace/decor/DecorationLayer.kt:24-25`).
Each decoration type becomes a style record (+ `light`/`dark` overrides resolved by theme kind; `ThemeColor` via §4.2). `setDecorations`
replaces the ranges of one type in one editor; ranges move with edits per `rangeBehavior` (0.106 DecorationRangeBehavior).

| `DecorationRenderOptions` field | Before PE1 (current text field) | With PE1 |
|---|---|---|
| `backgroundColor`, `border*`, `outline*`, `borderRadius` | yes: range rectangles (the search-highlight painter) | yes |
| `isWholeLine` + background | yes: full-width line rect (like the current-line painter) | yes |
| `textDecoration` underline / line-through / wavy | yes: squiggle and strike painters | yes |
| `opacity` (e.g. clangd inactive regions) | approximated: paint editor background at `1 - opacity` alpha over the range (the text looks dimmed) | real |
| `color`, `fontStyle`, `fontWeight`, `letterSpacing`, `cursor` | no: dropped and counted in the extension log (changing them means relayout) | yes |
| `after` / `before` with `contentText` **at end of line** | yes: the EOL ghost-text painter (GitLens blame, Error Lens messages, Dart closing labels) | yes |
| `after`/`before` **mid-line**, `contentIconPath`, `width/height/margin` | no: moved to end of line if it is the last decoration on the line, else dropped | inline boxes |
| `gutterIconPath`, `gutterIconSize` | yes: new gutter glyph kind "image" (tinted SVG/PNG from the extension dir) | yes |
| `overviewRulerColor`, `overviewRulerLane` | yes: new overview-ruler painter (WP-UI-7) | yes |
| `hoverMessage` (on `DecorationOptions`) | yes: joins the hover card for that range (long-press on touch) | yes |
| `renderOptions` per range (instance overrides) | yes for the fields above | yes |

**What must-work extensions get before PE1:** Error Lens (EOL message + whole-line background + gutter icon: full), GitLens (current-line
blame EOL: full; heatmap gutter/border-left: full; inline annotations mid-line: degraded), Code Spell Checker (underline: full), clangd
(inactive regions via opacity approximation), Tailwind (colour swatches are `before` mid-line: degraded; the `languages.registerColorProvider`
path, 0.468, is better handled by a native colour decorator in WP-UI-10), Claude Code / GitHub PR (line backgrounds for diffs/comments:
full). Dependencies: PE1 (docs/ux-overhaul/tracker.md) for text colour and inline boxes; UI protocol `editor/setDecorations` (adapter implements `MainThreadTextEditors` decoration methods).
Performance: at most one painter pass per layer per frame; decorations limited to the visible line window (settings key for max ranges per type).

## 6. Language-feature UI details

All presenters live in `A/ui/screens/workspace/lsp/` and are fed by LSP sessions and WASM providers. Per D10, `FeatureRouting` becomes a
`FeatureSource` abstraction with `DocumentSelector` matching; extension providers are sources. When an enabled extension contributes or
starts a language server for language X, our built-in LSP pack for X yields in that workspace, so the UI never shows doubled results
(e.g. rust-analyzer, clangd, Pyrefly, Ruff replace the built-in pack); otherwise results merge under the existing per-feature policies.
UI work is small once that lands.

| Feature | VS Code detail extensions rely on | Today | UI change | Phone note |
|---|---|---|---|---|
| Completion | `command` after accept, `additionalTextEdits`, `labelDetails`, tags (deprecated), `insertReplace` ranges, `CompletionList.isIncomplete`, snippet `SnippetString` | resolve, docs, commit chars, snippets; provider `command` stripped | run `command` via command registry; `labelDetails`; strike deprecated | docs below list; accept by tap; key row arrows |
| Hover | `MarkdownString` (`isTrusted`, `supportThemeIcons`, `supportHtml` subset), multiple providers merged | markdown card | codicons; trusted `command:` links (allow-list per `isTrusted.enabledCommands`); `<span style=color>` subset | long-press; action row |
| Signature help | active parameter, retrigger | popup | none beyond host routing | above the caret line to avoid the keyboard |
| Code actions / lightbulb | kinds, `isPreferred`, `disabled.reason`, `command` vs `edit`, refactor preview | bulb + menu | show `disabled.reason`; group by kind | bulb tap; touch toolbar entry |
| Code lens | `resolveCodeLens`, `command.title` with `$(icons)` | gutter glyph → popup | codicons in titles; host lenses | keep popup after PE1 |
| Inlay hints | label parts with `tooltip`, `command`, `location`; `paddingLeft/Right` | EOL ghost text | long-press → part tooltip/command sheet | EOL always |
| Diagnostics / Problems | tags, `code: {value, target}`, `relatedInformation`, `source` | squiggle, gutter, Problems | Unnecessary = opacity dim (§5 trick), Deprecated = strike; code link opens externally/in doc; related info as child rows | Problems sheet from status count |
| Peek / references | `editor.action.peekLocations`, `editor.action.showReferences` commands (used by lens commands) | References panel | route both commands to the panel | location sheet |
| Rename | `prepareRename` range/placeholder, rejection message | dialog + preview | show prepare rejection text | dialog above keyboard |
| Semantic highlighting | legend with custom types/modifiers, `semanticTokenScopes` | theme `semanticTokenColors` | fold custom types into `SemanticStyler` via scopes | – |
| Folding | ranges + kinds, fold commands | none | PE1 | – |
| Outline / breadcrumbs | `DocumentSymbol` tree, `SymbolTag.Deprecated` | outline + crumbs | strike deprecated | outline sheet |
| Language status | severity, busy, `command`, `detail` | LSP items only | host items into the same popup | sheet |
| Colour provider | swatches + picker (Tailwind, CSS) | none | native swatch decoration before colour literals (painter) + Kit colour picker sheet | picker as sheet |

## 7. Webviews (summary; detail in [ui-webviews.md](ui-webviews.md); model = D5 / ADR 0033)

- One `android.webkit.WebView` per **visible** webview instance (panel = stage document, view = panel section, custom editor = document).
  It loads VS Code's `pre/index.html` host page **vendored unmodified** (MIT, NOTICE row). That page creates the inner frame, provides
  `acquireVsCodeApi`/`getState`/`setState`, and applies the `--vscode-*` variables and body classes we send.
- Per-instance origin `https://<H>.webview.easyide.invalid`, where `H` is VS Code's `parentOriginHash` over a per-instance salt (the
  vendored page verifies it). The salt is persisted for serializer restore. Different instances never share storage or a bridge.
- Resources: the unmodified host's `asWebviewUri` yields `*.vscode-resource.vscode-cdn.net` URIs and `cspSource` `'self' https://*.vscode-cdn.net`.
  `shouldInterceptRequest` answers every such request (never falls through to the network), only from that instance's `localResourceRoots`.
- The page runs with `disableServiceWorker`, so we ship no service worker; extension SW fetches get 403.
- Bridge: a small easyIDE script at document start relays the page's `MessagePort` to `addWebMessageListener` (exact origin, main
  frame only). No `addJavascriptInterface`, no `file://`. The extension CSP is kept; we add no network; links → confirm + external browser.
- Isolation: a WebView `Profile` per extension where `MULTI_PROFILE` exists; permission requests denied.
- Lifecycle: destroyed when hidden unless `retainContextWhenHidden` (VS Code: "high memory overhead"), which is honoured within
  `extensions.webview.maxLive` (proposal 2/3/4 by size class). Beyond that, hidden webviews are evicted LRU and restored from state or the
  `WebviewPanelSerializer` (0.150). Per-WebView memory is UNVERIFIED (measurement plan T1).

## 8. Other surfaces

### 8.1 Tabs: `window.tabGroups` (usage 0.543)
Projection of `EditorStage` (1-4 groups on one axis) to the API. It is read-only apart from `close`.
| API | From |
|---|---|
| `TabGroup.viewColumn` | group index + 1; `isActive` = focused group |
| `Tab.label/isActive/isDirty/isPinned/isPreview` | `EditorGroup` tab state PREVIEW/KEPT/PINNED (`A/ui/shell/EditorGroup.kt:7`) |
| `Tab.input` | `TabInputText` (file/untitled/virtual doc), `TabInputTextDiff` (diff document, §8.2), `TabInputCustom` (custom editor), `TabInputWebview` (webview panel, `viewType`), `TabInputTerminal` (`terminal://` document); our other documents (settings, walkthrough, extension page) → `unknown` |
| events | `onDidChangeTabGroups`, `onDidChangeTabs` (opened/closed/changed) from stage events |
| `close(tab or group, preserveFocus)` | stage close; dirty → our save prompt |
| Phone | one visible group; the API still reports all groups (stage keeps them) |

### 8.2 Diff editor: generic `vscode.diff`
Add a `compare:` diff provider over two document URIs: `file`, `untitled`, `git` and any `TextDocumentContentProvider` scheme (usage
0.432 registerTextDocumentContentProvider), plus title and `TextDocumentShowOptions`. It is read-only left and right before PE1 (the right side
becomes editable with PE1). Reuses `DiffProviders.register(scheme)` (`A/ui/shell/diff/DiffProvider.kt:41-64`) and the side-by-side/unified
switch. Sets `isInDiffEditor`. Also covers `vscode.changes` (multi-file, as a list document of diffs) at M effort.

### 8.3 Custom editors
See [ui-webviews.md](ui-webviews.md) §7. Text custom editors (the GitLens rebase editor) come first; binary custom documents second.

### 8.4 SCM generic provider view
`scm.createSourceControl` has very low corpus use (0.002, 2 extensions). The value is in the `scm/*` menus and SCM providers that GitLens
and GitHub PR attach to the **built-in git** provider (`scmProvider == git`). Plan: (1) name our JGit view as the `git` provider, expose
`scmProvider`, `scmResourceGroup`, `scmResourceState` keys and render the stable `scm/*` menus on the existing rows (`git/ScmHeader.kt`,
`git/ChangeRow.kt`); (2) the `vscode.git` extension API is backend (`git.api`); (3) extension providers render as extra sections with the
same `ChangeRow`, input box, count badge and `QuickDiffProvider` gutter bars (WP-UI-7). Effort L, risk Med.

### 8.5 Comments: decision
**Missing-both, deferred with a stub, not Not-planned.** Reason: 2 of the 22 must-work extensions call `comments.createCommentController`
(Claude Code, GitHub PR; weightShare 0.064). For GitHub PR, inline review comments are core. Plan: until then the adapter stubs `MainThreadComments`
(controller and threads accepted as no-ops, nothing drawn, `commentController` key unset; ADR 0031 stub table), so activation never throws. WP-UI-15 adds a native thread card
(EditorPopup anchored to a gutter glyph; replies with a markdown field; `comments/commentThread/*` menus) and a Comments list in the bottom
panel. On a phone the thread opens as a bottom sheet. The 9 `comments/*` menu ids move out of Not-planned then.

### 8.6 Walkthroughs (0.277)
`walkthrough://<ext>/<id>` stage document; steps with markdown description (buttons = `command:` links), media image/SVG/markdown
(no video), completion events `onCommand`, `onSettingChanged`, `onContext`, `onView`, `onLink`, `onExtensionInstalled`, `onStepSelected` (`VS:src/vs/workbench/contrib/welcomeGettingStarted/browser/gettingStartedExtensionPoint.ts`).
Opens once after install (setting to suppress). Tablet: steps left, media right; phone: single column, collapsible steps.

### 8.7 Settings UI from arbitrary schema (0.956)
| Schema shape | Today | Add |
|---|---|---|
| `markdownDescription`, `markdownEnumDescriptions`, `#setting.id#` links, `command:` links | flattened to plain text (`ContributedSettings.kt:75`) | native markdown with links |
| `editPresentation: multilineText` | single field | multiline field |
| `object` with `properties` / `additionalProperties` (string/bool/enum) | "Edit in settings.json" | key/value editor (VS Code draws the same subset) |
| `array` of enum / of objects | string[] only | checklist for enum items; objects → JSON |
| `patternErrorMessage`, `minimum/maximum`, `minLength/maxLength`, `anyOf` with `type`s | pattern only | messages, bounds, type union → first type |
| `order`, `scope` (window/resource/language-overridable/machine), `tags`, `deprecationMessage`, `ignoreSync` | kept | language-override UI (`[lang]` section) |
Everything else stays in settings.json (VS Code behaves the same).

### 8.8 Terminal UI (0.368)
Tab icon (`ThemeIcon`) and colour (`ThemeColor`); `terminal/context` + `terminal/title/context` menus; link detection (URLs, `path:line:col`)
with tap to open, plus `registerTerminalLinkProvider` (0.111); `TerminalLocation.Editor` → `terminal://` stage document; profile picker
for `contributes.terminal.profiles` (0.006, low); split = Not-planned on phone, second tab on tablet. Shell integration is backend.

### 8.9 Accessibility
`accessibilityInformation {label, role}` on `TreeItem`, `StatusBarItem`, `LanguageStatusItem`, and quick pick `ariaLabel` go to Compose
`semantics { contentDescription; role }`. Webviews: set `vscode-using-screen-reader` when TalkBack is on; the WebView's own a11y tree
is Chromium's. Checks: TalkBack order on the tree host, status strip overflow sheet, notification snackbar (live region), menus.
UNVERIFIED: TalkBack in the `BasicTextField` editor and KitMenu popups (device check).

### 8.10 File decorations
`FileDecorationProvider` (badge ≤2 chars, `ThemeColor`, tooltip, `propagate`) merged with git status. Shown in explorer rows, tabs,
breadcrumbs, tree items with `resourceUri`, SCM rows. Last provider wins per field as in VS Code. Phone: letter badge + tint (no tooltip; tap row info).

## 9. Work packages (dependency order)

Priorities use corpus usage. Every WP ships with Roborazzi goldens in `services/mobile/app/src/test/java/dev/easyide/app/ui/screens/golden/`
(light/dark × Dense/Comfortable, as docs/ui-redesign/kit.md:172-174) and a device check where stated. "UI protocol" = the adapter↔Kotlin
JSON-RPC of ADR 0031 (docs/extension-host/arch.md re-scoped; see [backend.md](backend.md)). Milestones per D11: M1 hello-world on device,
M2 language extensions, M3 views/menus, M4 webviews + GitLens + Claude Code, M5 corpus ≥ 99%.

| WP (milestone) | Scope | Deps | Acceptance | E | Risk |
|---|---|---|---|---|---|
| **WP-UI-1** Theme & icon foundations (M2) | generated colour registry (~990 ids + transforms), `RawColorMap`, `contributes.colors`, ThemeColor resolve, `--vscode-*` + size + font export; codicon font + table + `$(x)`/`~spin` label parser; `ThemeIcon`; `contributes.icons` | tolerant `.vsix` manifest reader (backend C1); NOTICE rows (codicons CC-BY-4.0) | unit: every registry id resolves in Dark+/Light+ equal to VS Code's computed value for a sample of 50 (fixture from VS Code); `CodiconLabelGoldenTest`; `ThemeColorExportTest` (first-dot naming) | M | Low |
| **WP-UI-2** Context keys (M3) | `setContext`, `===`/`!==`, VS Code key table (§4.1) as data, scoped overlays per location, unset-key fixes | backend C6/C7 | unit: GitLens `view`/`viewItem` when-clauses from the corpus evaluate as in VS Code (table-driven fixture); `matrix/when-context.md` coverage ≥ corpus keys with usage ≥ 0.05 | M | Low |
| **WP-UI-3** Menu engine & locations (M3) | M1-M7; render in-scope locations: `view/title`, `view/item/context`, `editor/title/run`, `editor/title/context`, `editor/lineNumber/context`, `file/newFile`, `extension/context`, `terminal/*`, `webview/context` (hook), `scm/*` (hook); palette recent | WP-UI-1, WP-UI-2 | `ExtMenuGoldenTest` (submenu cascade tablet / back-row phone, alt, toggled, codicons); device: long-press + `...` on every location (U-INT-03) | M | Low |
| **WP-UI-4** Workbench feedback surfaces (M2) | notifications + centre, progress (3 locations), status items parity + phone overflow sheet, language status, QuickInput lifecycle (createQuickPick/InputBox), output channels + Output panel, `KitTooltip` | UI protocol `window/*`, `output/*`, `progress/*`; WP-UI-1 | `NotificationToastGoldenTest`, `StatusOverflowSheetGoldenTest`, `QuickPickStepsGoldenTest`, `OutputPanelGoldenTest`; scripted UI-protocol fixture replays GitLens + Python activation messages | L | Low |
| **WP-UI-5** Tree views & containers (M3) | `TreeViewHost` (lazy, reveal, selection, checkbox, DnD via menu, badge, message), views-welcome, `.vsix` view containers, nav badges | UI protocol `tree/*` (incl. getParent/resolve); WP-UI-2/3 | `ExtTreeViewGoldenTest` (28dp/36dp rows, inline actions, welcome); 10k-node lazy tree scroll at 60 fps on the tablet (device); GitLens Commits/Branches, Docker Containers, Go/Java test explorers render (corpus smoke) | L | Med |
| **WP-UI-6** Editor model projection (M3) | `window.tabGroups`, visibleTextEditors/visibleRanges/options, generic `compare:` diff (`vscode.diff`, `vscode.changes`), `isInDiffEditor` | UI protocol `editor/*`, `tabs/*`; doc.content-provider (backend) | unit: tab model ↔ API mapping; `CompareDiffGoldenTest` (side-by-side / unified); GitLens "compare with previous" opens | M | Med |
| **WP-UI-7** Decorations (pre-PE1) (M4) | `ExtDecorations` layer (§5), overview ruler, gutter image icons, quick-diff bars, file decorations fan-in | ADR 0018 layers; WP-UI-1 | `ExtDecorationsGoldenTest` (Error Lens EOL + whole line, blame EOL, opacity dim); frame time with 5k decorations on a 5k-line file within the editor budget in [optimisation.md](optimisation.md) | L | Med |
| **WP-UI-8** Webview foundation (M4) | ADR 0033 (D5): WebView host, vendored `pre/index.html` + `fake.html` (NOTICE row), outer-host adapter script, per-instance origin store, resource server, `styles` payload, lifecycle, budget/eviction, Profile isolation | WP-UI-1 (vars); UI protocol `webview/*` (adapter implements `MainThreadWebviews/Panels/Views`) | device tests T1-T9 ([ui-webviews.md](ui-webviews.md) §8); unit: path-escape and root checks (`WebviewResourceServerTest`) | L | High |
| **WP-UI-9** Webview panels, views, custom editors (M4) | stage `webview:` doc, panel section views, serializer restore, `webview/context`, `webviewId` keys, custom text/binary editors, Open With | WP-UI-8, WP-UI-3, WP-UI-5 | T10 goldens: GitLens Home, Claude Code panel, Git Graph, GitHub PR, Code Spell Checker; restart restores Claude Code panel | L | High |
| **WP-UI-10** Language-feature UI deltas (M2) | host providers in presenters; completion command/labelDetails, hover codicons + trusted links, diagnostic tags/links/related, peek command routing, colour swatches + picker, custom semantic types | D10 `FeatureSource` router + built-in pack yield (backend C5); WP-UI-1 | `HoverTrustedLinkTest`, `DiagnosticTagsGoldenTest`, `ColorSwatchGoldenTest`; rust-analyzer, Pyrefly, Ruff, Volar, Tailwind smoke in the corpus harness | M | Low |
| **WP-UI-11** SCM (M5) | git as named provider, `scm/*` menus on rows, extension providers as sections, quick diff | WP-UI-3, WP-UI-7; backend git.api/scm.api | `ScmExtMenuGoldenTest`; GitLens SCM inline actions visible and working | L | Med |
| **WP-UI-12** Settings & walkthroughs (M3) | §8.6, §8.7 | WP-UI-1 (markdown codicons) | `SettingsObjectEditorGoldenTest`, `WalkthroughGoldenTest` (tablet two-column / phone single) | M | Low |
| **WP-UI-13** Terminal & testing UI (M5) | §8.8 terminal features; TestController tree + gutter + results | WP-UI-3, WP-UI-5, WP-UI-7; backend terminal.*, tests.api | `TerminalLinkTest`; `TestingViewGoldenTest`; Go/Python/Rust test discovery shows and runs (device) | L | Med |
| **WP-UI-14** Accounts & URI handler UI (M4) | Accounts page, consent dialog, sign-in via Custom Tabs, `onUri` intent round trip | backend auth.api, env.uri-handler | device: GitHub PR sign-in round trip; `AccountsGoldenTest` | M | Med |
| **WP-UI-15** Comments (M5) | native thread card, gutter glyph, Comments panel, `comments/*` menus | WP-UI-3, WP-UI-7 | `CommentThreadGoldenTest`; GitHub PR review comment reply (device) | L | Med |
| **WP-UI-16** Accessibility pass (M5) | §8.9 across WP-UI-3..15 | all above | TalkBack walkthrough script on tablet + phone; `accessibilityInformation` unit tests | M | Med |
| (PE1 track) | code-lens blocks, inline inlay hints, inline before/after, text colour, folding, multi-cursor | ADR 0018 PE1 | owned by the editor track; listed here so it is not double-counted | XL | High |

Critical path for the must-work list: M2 = WP-UI-1, 4, 10 (language extensions: Python, Go, Java, rust-analyzer, clangd, Ruff,
Pyrefly, ESLint, Prettier, YAML, Volar, Tailwind, Ruby LSP, Dart); M3 = WP-UI-2, 3, 5, 6, 12 (views/menus: Docker, test explorers,
Code Spell Checker); M4 = WP-UI-7, 8, 9, 14 (GitLens, Claude Code, Git Graph, Error Lens, GitHub PR sign-in); M5 = the rest.
Material Icon Theme needs no UI work beyond the host running its `main`.

## 10. UNVERIFIED items and how to verify

| Item | How to verify |
|---|---|
| Memory per WebView and `retainContextWhenHidden` cost; renderer sharing across WebViews | T1 in [ui-webviews.md](ui-webviews.md) §5 (`dumpsys meminfo`, 4 GB phone + 8 GB tablet) |
| Chromium WebView behaviour for `.invalid` origins (secure context, storage, SW refusal) | T3, T8 |
| CSP header + meta both enforced in WebView (Android docs are silent) | T4 |
| `contextmenu` on long-press inside a WebView | T5 |
| `navigator.clipboard` in WebView | T6 |
| guest (proot) localhost ports reachable from a WebView (`portMapping` substitute) | T7 |
| `MULTI_PROFILE` / `DOCUMENT_START_SCRIPT` / `WEB_MESSAGE_LISTENER` availability on our minSdk devices | `WebViewFeature.isFeatureSupported` log on the device matrix (T9) |
| Structured-clone payloads (`Uint8Array`) that extensions post and expect back | GitLens graph + Git Graph smoke under WP-UI-9 |
| Colour-id total 968 + dynamic ids (regex count) | re-count with the TypeScript AST in the WP-UI-1 generator |
| `@vscode/codicons` version and licence at adoption time | registry.npmjs.org + repo README at the pinned version; NOTICE rule |
| Whether `.vsix` manifests pass through `manifest.schema.json` | read the `:exthost` install path when it exists (backend C1) |
| TalkBack in the `BasicTextField` editor and KitMenu popups; submenu cascades on a device | device check (docs/ui-redesign/vscode-parity-git.md:13 "Not seen on a device") |
| Opacity approximation readability for inactive regions (clangd) | golden + device check in WP-UI-7 |
| `editor/title` inline limit value (`ExtensionUiPolicy.EDITOR_TITLE_MAX_INLINE`) | read `A/ui/screens/workspace/ext/ExtensionSlots.kt:52` source of the constant |
| Corpus weights are download-weighted Open VSX counts at one snapshot; per-surface "usage" counts an extension even if the call is on a rare path | cross-check with the per-extension blockers in `data/corpus.json` |
