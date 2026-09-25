# OURCODE-UI: VS Code UI surfaces vs easyIDE's Compose UI (audit, 2026-09-25)

Agent OURCODE-UI. Documentation only. Data twin: `ourcode-ui.json` (59 surfaces, 100 menu locations, 44 when-keys, colour-id count).
Paths are relative to `$WT` (`/home/user/easyIDE-wt/audit-vsx`). Abbreviations used in tables:
`A/` = `services/mobile/app/src/main/java/dev/easyide/app/`, `S/` = `services/shared/extension-schema/src/main/kotlin/dev/easyide/extensions/`,
`MEP` = `$VS/src/vs/workbench/services/actions/common/menusExtensionPoint.ts` (microsoft/vscode main `0b16cb97`, 2026-09-25).
Gap classes, effort and risk as in COMMON.md/SURFACES.md. "native" = drawn with the Kit in Compose; "webview" = Android `WebView`.

## 0. Headline numbers

| Measure | Value | Evidence |
|---|---|---|
| UI surfaces audited | 59 (55 SURFACES.md ids + 4 new: `ui.testing`, `ui.folding`, `ui.language-status`, `ui.outline`) | ourcode-ui.json |
| Have / Partial / Missing-UI / Missing-both / Not-planned | 10 / 26 / 17 / 1 / 5 | ourcode-ui.json `surfaces[].status` |
| VS Code menu locations (MEP, lines 39-574) | 96 (+4 easyIDE-own) | MEP |
| ... with an easyIDE menu today | 4 (+2 own): `commandPalette`, `editor/title`, `editor/context`, `explorer/context` (+ own `editor/touchToolbar`, `keyRow`) | section 3 |
| ... known id but never rendered | 6: `editor/title/context`, `view/title`, `view/item/context`, `scm/title`, `scm/resourceState/context`, `terminal/context` | `S/contrib/MenuIds.kt:5-18` |
| ... out of scope (debug/notebook/chat/comments/remote/macOS) | 44 | section 3 |
| VS Code workbench colour ids we understand | **92** keys (71 of them in VS Code's `registerColor` registry of 968; the other 21 are the 16 `terminal.ansi*` registered in a loop and 5 `gitDecoration.*` from the git extension) = ~9% of ~990 | `A/ui/theme/ThemeColorMap.kt:17-118` |
| Codicons known | 0 of 762 (`codiconsLibrary.ts`) | no codicon font/table in app; `S/manifest/DecodeContext.kt:80-81` |
| Context keys | 37 fixed + 7 prefixed declared; 26 fixed + 5 prefixed actually set; no `setContext` | `S/whenclause/ContextKeys.kt:33-86`, `A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:109-169` |
| WebViews in the app | 1 (`MermaidView`, a markdown diagram renderer) | `A/ui/screens/workspace/MermaidView.kt:48` |

Status of the host itself: `docs/extension-host/tracker.md` phases 1-6 are all "Not started"; there is no `services/mobile/exthost` module yet
(`services/mobile/settings.gradle.kts` includes no `:exthost`). Everything below is what the **main side** could render today once the host exists.

## 1. Answers to the specific questions

### 1.1 Menus: data-driven? which locations?
- **Yes, data-driven.** `MenuModel.items(menuId, snapshot, context, hidden, builtInEnabled, order)` (`A/extensions/adapters/MenuModel.kt:57-78`) filters
  the contribution registry by menu id and `when`, drops hidden entries, binds to an existing command, greys by `enablement`, and sorts the VS Code way
  (`navigation` first, then group name, then `@order`, then title; ungrouped last; `MenuModel.kt:44-49`), then applies the user's `workbench.contributions.order`.
  `group@order` is split at decode (`S/manifest/ContributesDecoder.kt:59-65`). Unknown menu ids load with warning `MENU_UNKNOWN` and are ignored (`ContributesDecoder.kt:56`).
- **KitMenu** (`A/ui/kit/KitMenu.kt:28-49`): `Action(label, onClick, icon, checked, enabled, danger, hint)`, `Submenu(label, items)`, `Divider`; placement and
  keyboard navigation are pure and tested (`KitMenuLogic.kt`, docs/ui-redesign/vscode-parity-git.md:5-13). `ContributedMenu` turns `MenuEntry` sections into
  KitMenu items (`A/ui/screens/workspace/ext/ExtensionSlots.kt:79-90`), so any new location is one call.
- **Rendered locations**: `commandPalette` (palette filter, `MenuModel.kt:97`), `editor/title` (tab-strip trailing slot, `A/ui/screens/workspace/WorkspaceScreen.kt:461`,
  inline `navigation` + overflow `ExtensionSlots.kt:51-64`), `editor/context` (secondary click in the editor `A/ui/shell/workspace/FileDocument.kt:58-64`, and inside
  the editor/title overflow for touch because long-press in text is hover), `explorer/context` (scoped `resource*` keys, `WorkspaceExtensionUi.kt:77-88`, appended
  after built-ins `A/ui/screens/workspace/FileContextMenu.kt:95`), own `editor/touchToolbar` (`A/ui/shell/workspace/InputDock.kt:40`) and `keyRow` (`KeyRows.kt:92`).
- **Fixed (not data-driven) menus that have a VS Code twin**: tab menu (`TabMenuPlan.kt:8` enum `TabAction`), commit/history menu (`git/CommitMenu.kt:44`),
  SCM header and change-row actions (`git/ScmHeader.kt`, `git/ChangeRow.kt`), explorer toolbar, extension row menus. These are where `editor/title/context`,
  `scm/historyItem/context`, `scm/title`, `scm/resourceState/context`, `file/newFile`, `extension/context` belong.
- **Gaps in the item model**: no `submenu` items and no `contributes.submenus` (the manifest schema requires `command` and forbids other properties,
  `services/shared/extension-schema/manifest.schema.json:137`); `alt` is decoded but never used; icons are an easyIDE token or a package SVG only, so
  `$(codicon)`, `{light,dark}` objects and PNGs fail or fall back to text (`DecodeContext.kt:80-87`, schema `$defs.command.icon` is a string).
- Naming bug: `MenuIds.TERMINAL_TITLE = "terminal/title"` (`MenuIds.kt:18`) is not a VS Code id; VS Code's is `terminal/title/context` (MEP:241).

### 1.2 Keybindings
- **Service**: `Keymap` of `KeyBinding(chord, command, focus, prefix, whenExpr, args)` (`A/ui/commands/Keymap.kt:64-75`); dispatch picks the last binding whose chord,
  prefix, focus and `when` hold (`Keymap.kt:89-93`); `ChordDispatcher` keeps the pending first press of a two-step chord (`Keymap.kt:176-201`). Chords longer than two
  presses are refused with a diagnostic (`ContributedKeybindings.kt:30-31`).
- **Declarative sources**: built-in defaults are a Kotlin table `Keymap.DEFAULT` (`Keymap.kt:112-148`, 33 bindings); user `keybindings.json` parsed with diagnostics and
  conflict detection (`KeybindingOverrides.kt:19,67,107`); `contributes.keybindings` (`linux` over `key`, `mac` / `win` ignored, `cmd` = Meta; terminal only when the
  `when` mentions `terminalFocus`; `ContributedKeybindings.kt:10-42`). Keyboard Shortcuts page `A/ui/screens/settings/KeyboardPage.kt:40`.
- **Tablet/phone key row**: yes. `KeyRows` merges built-in, contributed (`keyRow` menu + `easyide.keyRows`) and user rows (`A/extensions/adapters/KeyRows.kt:38-98`),
  shown in the touch-only InputDock above the keyboard (`A/ui/shell/workspace/InputDock.kt:32`, `DockRules.kt:21-27`) and under the terminal (`TerminalKeyRow.kt:49`).
  A key can insert text, a snippet, a chord or a command (`S/contrib/Contributions.kt:191-195`), so any VS Code keybinding can be surfaced as a key-row button.
- ui-shell tracker still lists "Declarative keybinding table" as not-started (`docs/ui-shell/tracker.md`); that line is stale for physical keys, true for the
  default table (Kotlin, not JSON).

### 1.3 Context keys and `when`
- **Service**: `ContextKeyService` (`S/whenclause/ContextKeyService.kt:59-103`) holds a snapshot, typed `set`, `setAll`, `observe(expr): Flow<Boolean>`,
  `config.*` keys resolve live from settings (`ContextKeyService.kt:32-36`). Grammar: `WhenLexer`/`WhenParser`/`WhenEvaluator` with key, const, `!`, `&&`, `||`,
  comparisons, `=~` regex, `in` / `not in` (`WhenExpr.kt:11-84`, `WhenEvaluator.kt:24-99`). This is the whole VS Code grammar as far as the file shows.
- **Keys**: listed in `ourcode-ui.json whenKeys` with the line each is set. Declared but never set: `inSnippetMode`, `suggestWidgetVisible`, `hoverVisible`,
  `peekVisible`, `scmFocus`, `focusedView`, `focusedStage`, `devicePosture`, `isOffline`, `isSafeMode`, `envHasCommand:*`. `editorHasMultipleSelections` is always
  false (`WorkspaceContextFeed.kt:151`); `explorerFocus` exists only inside the explorer menu scope (`WorkspaceExtensionUi.kt:85`).
- **Missing for VS Code extensions**: `setContext` (no occurrence in app or schema), and common VS Code keys: `view`, `viewItem`, `resourceScheme`, `resource`,
  `isLinux`/`isWindows`/`isMac`, `isWeb`, `textInputFocus`, `editorIsOpen`, `activeEditor`, `sideBarVisible`, `panelFocus`, `activeViewlet`, `activePanel`,
  `listFocus`, `scmProvider`, `scmResourceGroup`, `workspaceFolderCount`, `workbenchState`, `gitOpenRepositoryCount`, `editorLangId` inside views, `languageId`.
  GitLens menus are gated on `view == ...` and `viewItem =~ /.../`; without those keys every tree/menu entry is either always shown or never.

### 1.4 Theme colours
- Mapping exists but is **lossy**: `ThemeColorMap.BY_VSCODE_KEY` maps 92 VS Code ids onto ~70 `ColorToken`s, several ids to one token (`ThemeColorMap.kt:9-13`),
  plus 12 derivations (`ThemeColorMap.kt:128-141`). Themes are read by `ColorThemeFile` (`A/extensions/adapters/ColorThemes.kt:82`) and `VsCodeThemeMapper`
  (`A/ui/theme/VsCodeThemeMapping.kt:52-83`); `workbench.colorCustomizations` by `ThemeCustomizer.apply` (`ColorCustomizations.kt:64`). Unlisted keys are reported
  as unmapped, not stored.
- Consequences: `--vscode-*` CSS variables for webviews and `new ThemeColor('x')` need a **raw colour registry** of all ~990 ids with VS Code's default
  derivations (MIT, can be generated from `registerColor` calls in `$VS/src/vs/platform/theme/common/colors/*.ts`). `contributes.colors` is rejected by the manifest
  schema today (`manifest.schema.json` `$defs.contributes.additionalProperties: false`, allowed keys: commands, configuration, configurationDefaults, grammars,
  iconThemes, keybindings, languages, menus, problemMatchers, snippets, taskDefinitions, themes, views, viewsContainers, viewsWelcome, walkthroughs).

### 1.5 Codicons
- None. No codicon font, CSS or name table in `services/mobile/app/src/main` (grep `codicon` = 0 hits). Icon tokens resolve through `resolveIconOrNull`
  (`A/ui/icons/IconResolver.kt:26-33`) against our custom glyph set and ~67 Material tokens (`MaterialIcons.kt`). A `$(sync~spin)` string would be decoded as the
  token `$(sync~spin)` (`DecodeContext.kt:81`: no `/` and no `.`) and render as nothing or text.
- `@vscode/codicons` latest on npm is `0.0.46-24`, licence field `CC-BY-4.0` (registry.npmjs.org/@vscode/codicons/latest); README: "grant you a license to the
  Microsoft documentation and other content in this repository under the Creative Commons Attribution 4.0 International Public License ... and grant you a license
  to any code in the repository under the MIT License" (raw.githubusercontent.com/microsoft/vscode-codicons/main/README.md:97-100). Bundling the font needs an
  attribution line in `assets/licenses`. VS Code main pins `^0.0.46-40` (`$VS/package.json:113`) and registers 762 names (`codiconsLibrary.ts`).

### 1.6 Editor decorations (ADR 0018)
Engine: one `BasicTextField`; decorations never enter the `AnnotatedString` (relayout cost) and are painted in the draw phase (0018 Decision 1).

| Capability | Today | Evidence |
|---|---|---|
| background range | yes (search, highlights) | `decor/DecorationPainters.kt:89-112` |
| squiggle underline | yes (diagnostics, by severity) | `DecorationPainters.kt:128-176` |
| inline before/after text | **no**; inlay hints as end-of-line ghost text only | `Decorations.kt:52-60`, `DecorationPainters.kt:190`, 0018 Decision 3 |
| gutter icons | fixed enum ERROR/WARNING/INFORMATION/LIGHTBULB/CODE_LENS, tap reports line | `Decorations.kt:69-75`, `DecorationPainters.kt:252-290` |
| whole-line background | only current line and bracket box (not a decoration layer) | `EditorLineHighlight.kt:33,66` |
| overview ruler | **no** (scrollbar thumb only) | `EditorScrollbar.kt:26` |
| hover message on range | hover exists for LSP/WASM providers, not attached to decorations | `lsp/InfoController.kt:40-47` |
| text colour / font style / opacity / border | **no** (would need spans = relayout, or PE1) | 0018 Context |
| between-line blocks (code lens) | **no**; gutter glyph + popup | 0018 Decision 3 |
| generic `TextEditorDecorationType` | **no**; layers are a sealed set of 6 typed layers | `decor/DecorationLayer.kt:27-57` |
Adding a seventh layer `ExtDecorations` is "one object here plus its painter" (`DecorationLayer.kt:24-25`). GitLens current-line blame (`after` text at end of
line) fits the existing EOL ghost-text painter; heatmap gutter bars and whole-line backgrounds are additive painters; coloured text is PE1.

### 1.7 Language-feature UI (native, all in `A/ui/screens/workspace/lsp/`)
| Feature | State | Evidence |
|---|---|---|
| completion widget | kind rows, detail + markdown docs beside (wide) or below, resolve on focus, commit characters, snippets with tab stops; WASM provider items have `command` stripped | `LspPopups.kt:91-126`, `CompletionController.kt:102,208-211`, `Snippet.kt:33` |
| hover | markdown (code fences highlighted), long-press / mouse rest / Ctrl+K Ctrl+I | `InfoController.kt:40-47`, `LspInfoPopups.kt:36`, `LspMarkdown.kt:54` |
| signature help | trigger/retrigger characters, popup | `InfoController.kt:37`, `LspInfoPopups.kt:76` |
| code actions / light bulb | gutter bulb on caret line, menu preferred first / disabled greyed, Ctrl+. | `EditActionsController.kt:31-56`, `Keymap.kt:129` |
| code lens | gutter glyph -> popup list | `CodeLensController.kt:19-30` |
| inlay hints | EOL ghost text | `CaretDecorations.kt:81` |
| diagnostics | squiggles, gutter, Problems panel grouped by file, status count; no tags (Unnecessary fade / Deprecated strike) and no `codeDescription.href` (grep 0 hits) | `DiagnosticsPresenter.kt:53`, `LspPanels.kt:92`, `ProblemsModel.kt:43` |
| peek / references | References side panel (no embedded peek editor) | `WorkspaceLspController.kt:57`, `NavigationController.kt:30-35` |
| rename | input + preview of files | `NavigationController.kt:46-49`, `LspDialogs.kt:28` |
| semantic highlighting | yes, theme `semanticTokenColors` | `SemanticTokensController.kt:35`, `A/ui/theme/SemanticTokenColors.kt:115` |
| folding | **no**; foldingRange withheld from client capabilities | `A/lsp/LspRuntime.kt:134-145` |
| breadcrumbs | path + symbols at caret | `A/ui/shell/workspace/EditorBreadcrumbs.kt:21`, `BreadcrumbModel.kt:27` |
| outline | Outline container in the secondary sidebar | `A/ui/shell/CoreShell.kt:52` |
All presenters are fed by the LSP client plus WASM providers; exthost providers are meant to join the same router (docs/extension-host/arch.md "Providers").

### 1.8 Other surfaces asked about
- **Diff** (`vscode.diff`): stage documents `git-diff:` / `git-commit:` only, read-only, side-by-side when wide else unified (`A/ui/shell/diff/DiffModel.kt:14-21`),
  hunk stage/unstage/discard (`DiffProvider.kt:26-36`). `DiffProviders.register(scheme)` exists but nothing registers extension schemes (`DiffProvider.kt:41-64`);
  `Comparison` is a git path + two git ends (`Comparison.kt:14`). `vscode.diff(left, right)` for arbitrary URIs needs a `compare:` provider.
- **Tabs / `window.tabGroups`**: model is there: `EditorStage` 1..4 groups on one axis (`EditorStage.kt:18-31`, `ShellLimits.kt:13`), tabs PREVIEW/KEPT/PINNED
  (`EditorGroup.kt:7`), open beside/new (`EditorStage.kt:6`). Only an API projection is missing. Split is one axis (no grid).
- **Custom editors**: nearest concept is `documentOpeners` (glob -> document type, `default`/`option`) (`A/ui/shell/ext/DocumentOpeners.kt`,
  `ShellContributions.kt:64-66`). Webview-based custom editors need the WebView document type first.
- **Webviews**: none for extensions; `MermaidView` is the only WebView (`MermaidView.kt:48-78`, JS on, `allowFileAccess`, `blockNetworkLoads`, JS interface).
  0030 decides webviews render in an Android WebView document in the stage; 0025/0027 and shell-model.md section 15 had listed webview panels as deferred.
- **Terminal UI**: multiple PTY tabs, rename, extension-named terminals (`WorkspaceTerminals.kt:30-172`), key row; no tab icon/colour, no link detection
  (grep `link|url` in terminal files = 0), no split, no profile picker.
- **SCM**: git-specific (JGit) native UI, not provider-generic (`SourceControlPane.kt:67`, `git/*`); no scm/* menus (vscode-parity-git.md:30).
- **Comments**: none; host protocol lists commentController as not planned. **Walkthroughs**: decoded (`ContributesDecoder.kt:248`), not drawn.
- **Settings UI from schema**: `ContributedSettings.controlOf` (`A/data/settings/ContributedSettings.kt:95-122`): enum -> choice with enumDescriptions;
  boolean -> switch; integer with small min..max -> stepper else number field; number -> field; string -> field with `pattern`; `string[]` -> list; everything else
  (objects, arrays of objects, unions) -> "Edit in settings.json". `markdownDescription` is flattened to plain text (`ContributedSettings.kt:75`); deprecation,
  `scope`, `order` kept; layers user/project/environment (`SettingsLayerControl.kt`). Missing: rendered markdown with `#setting#` links, `markdownEnumDescriptions`,
  `editPresentation: multilineText`, object key/value editor, `patternErrorMessage`.
- **Notifications / progress / quick pick / input box**: `UiPrompt.QuickPick|InputBox|Message|ConfirmUrl` (`A/extensions/host/ExtensionUiHost.kt:18-26`) drawn as
  KitDialogs (`ExtensionPrompts.kt:55-131`). Messages are always modal; no notification centre; toasts carry no actions (`A/ui/shell/host/Toasts.kt:19`);
  no progress API; quick pick lacks detail, separators, buttons, busy, steps, dynamic items; input box validates by regex only (`S/action/HostPort.kt:97-99`).
- **Output channels**: none; `output` container registered (`CoreShell.kt:55`) without a renderer (`WorkspacePanels.kt:32-39`); only the Extension Log page.
- **Status bar**: `StatusItemsRow` draws caption text with a click (`ExtensionSlots.kt:104-117`); tooltip carried but never shown; no `$(icon)`, colour,
  background, command args, accessibility label. Priority sorting and left/right alignment are VS Code's (`StatusItems.kt:49-56`). On a phone the strip drops
  extension items before file/save/problems (`StatusPlan.kt:6-14,28`).
- **Extension view containers on phone vs tablet**: containers go to sidebar / secondary sidebar / panel; a navigation item per container (max 3 per pack,
  `ShellLimits.kt:24`). COMPACT: bottom bar with 5 cells, the rest in the More sheet (`NavRules.kt:35-36`, `ShellLimits.kt:19`, `NavMoreSheet.kt:21`), panels as
  overlay sheets; MEDIUM: rail + one docked side panel; EXPANDED: rail + left/right/bottom docked (docs/ui-redesign/layout-spec.md:14-19).

## 1.9 Kit density and size classes (the placement rules used in every proposal)
| Rule | Value | Evidence |
|---|---|---|
| width classes | COMPACT < 600dp, MEDIUM 600-839, EXPANDED >= 840; height COMPACT < 480 | `A/ui/foundation/WindowSize.kt:13-35`, layout-spec.md:14-19 |
| density | AUTO = COMFORTABLE on COMPACT, DENSE otherwise; SPACIOUS optional | `A/ui/props/Appearance.kt:13-31` |
| rows | tree/list 28dp Dense / 36dp Comfortable; tab 34/40; status 24/28; field 30/40 | docs/ui-redesign/density.md section 2 |
| touch floor | 44dp for isolated controls on COMPACT; dense rows padded to 40dp hit box | density.md section 2 |
| long-press | never the only path (U-INT-03); no info only in tooltip/hover (U-A11Y-04) | docs/ui-redesign/ux-rules.md:68,103 |
| no tooltip primitive | the Kit has none (grep `Tooltip` in `ui/` = 0) | `A/ui/kit/` |
Proposal convention: right-click -> long-press or a visible `...` button; hover -> long-press card (editor) or a tap-to-reveal sheet (status/tree tooltips);
keybinding -> key-row button + palette entry; sidebar view -> panel on tablet, overlay sheet on phone; editor-column page -> stage document (full screen on phone).

## 1.10 Blockers outside the UI code that decide whether any of this is reachable
1. `manifest.schema.json` closes `contributes` (`additionalProperties: false`) to 16 keys, menu items to `{command, alt, when, group}`, commands' `icon` to a string,
   and `views[].type` to `["tree"]`. A VS Code `package.json` with `colors`, `icons`, `submenus`, `customEditors`, `authentication`, `terminal`, `jsonValidation`,
   webview views or `{light,dark}` icons would fail validation. UNVERIFIED: whether the exthost install path will validate `.vsix` manifests with this schema at all
   (0030 does not say); verify when `:exthost` lands.
2. PE1 (line-virtualised editor, `docs/ux-overhaul/tracker.md` "not-started") gates inline decorations, code lens blocks, inlay hints, folding and multi-cursor.
3. The host protocol (docs/extension-host/arch.md) has no messages yet for: status item colour/tooltip markdown, notification centre, `setContext`, file decorations
   UI mapping, quick pick buttons/steps. These are listed per surface in the JSON `change` field.

## 2. Surface table (all ui.* ids)

| id | status | render | what easyIDE has | evidence | tablet | phone | change | effort/risk |
|---|---|---|---|---|---|---|---|---|
| `ui.view-container` | Partial | native | R4 container registry: sidebar / secondarySidebar / panel; a bare `activitybar` container becomes a sidebar container plus a nav item; 10 containers/pack | A/extensions/adapters/ShellContributions.kt:53<br>A/extensions/adapters/ShellContributions.kt:118<br>A/ui/shell/CoreShell.kt:46<br>A/ui/shell/ext/ExtRenderers.kt:57 | rail icon -> primary panel (or secondary/bottom per placement); several views = collapsible sections | bottom-bar cell (<=5, rest in More sheet) -> panel as overlay sheet | map VSIX `viewsContainers` (activitybar/panel) with no `ui.contribute` gate for host.run exts; tint SVG icons that use currentColor, rasterise PNG to mono; render viewContainer/title | M/Low |
| `ui.tree-view` | Partial | native | schema `tree` component = eager JSON tree with local expand state; schema-less views = flat label/description list. No lazy fetch, inline actions, view/item/context, selection, checkbox, DnD, tooltip | A/ui/shell/ext/ViewDataNodes.kt:84<br>A/ui/shell/ext/ViewDataNodes.kt:96<br>A/extensions/adapters/LegacyView.kt:16<br>docs/extension-host/arch.md (Tree views section) | Dense 28dp rows, twistie+icon+label+muted description+trailing inline actions on hover/selection; right-click = view/item/context KitMenu | Comfortable 36dp rows; trailing `...` button and long-press open view/item/context as menu; inline actions shown only on the selected row | new native TreeViewHost fed by exthost tree/children|item|refresh|reveal: element-id keyed lazy LazyColumn, `view`/`viewItem` scoped context keys, inline (`inline` group) buttons, resourceUri -> file-icon theme + file decorations, selection/multi-select, checkbox, badge -> nav badge, message + viewsWelcome | L/Med |
| `ui.views-welcome` | Missing-UI | native | decoded and stored; only listed on the Extension page Contributions list; never drawn | S/manifest/ContributesDecoder.kt:224<br>services/mobile/extensions/src/main/kotlin/dev/easyide/extensions/contrib/ContributionRegistry.kt:64<br>A/ui/screens/extensions/ExtensionPageModel.kt:26 | KitEmptyState in the view body: paragraphs + KitButton per `[label](command:id)` line | same, buttons full width in the thumb zone | render ViewWelcomeContribution when the view has no rows (tree empty / webview not resolved) | S/Low |
| `ui.webview-view` | Missing-UI | webview | no webview surface; the only WebView in the app is MermaidView; manifest `views[].type` enum is only `tree` | A/ui/screens/workspace/MermaidView.kt:48<br>services/shared/extension-schema/manifest.schema.json ($defs.view.type enum ["tree"])<br>docs/decision/0030-vscode-extension-host-in-sandbox.md (Decision 4) | android.webkit.WebView inside the panel container; pointer/keyboard pass-through | panel opens as full-height sheet; Back closes; WebView resized above IME (adjustResize) | WebView host view type in PanelHost; lifecycle (dispose when hidden unless retainContextWhenHidden); memory budget with host (0030 Consequences) | L/High |
| `ui.webview-panel` | Missing-UI | webview | none; stage documents can host any registered document type (URI-keyed) so a `webview:` type fits | A/ui/shell/DocumentRegistry.kt:1<br>A/ui/shell/EditorStage.kt:25<br>A/ui/screens/workspace/MermaidView.kt:48 | stage document tab; viewColumn 1..4 -> groups; Beside -> split | full-screen stage document in the switcher; Beside opens full screen with Back to previous | `webview` document type + renderer, restore via WebviewPanelSerializer (webview/resolve) | L/High |
| `ui.webview-bridge` | Missing-UI | webview | MermaidView uses addJavascriptInterface + allowFileAccess (ux-overhaul PS6 wants WebViewAssetLoader); no theme var export (we know 92 of ~990 colour ids) | A/ui/screens/workspace/MermaidView.kt:57<br>A/ui/theme/ThemeColorMap.kt:17<br>docs/ux-overhaul/arch.md:61 | same bridge on both | same; plus touch: map long-press to contextmenu event, disable pinch zoom by default | WebMessagePort bridge, shouldInterceptRequest resource server (https://easyide-webview.local/<handle>/, arch.md), inject all `--vscode-*` vars from a full colour registry (ui.theme-colors), codicon.css (CC-BY-4.0) | L/High |
| `ui.custom-editor` | Missing-UI | webview | documentOpeners (glob -> document type, priority default|option) + DocumentRegistry already model 'which editor opens this file'; no webview renderer; manifest has no customEditors key | A/ui/shell/ext/DocumentOpeners.kt:1<br>A/extensions/adapters/ShellContributions.kt:64<br>services/shared/extension-schema/manifest.schema.json ($defs.contributes additionalProperties:false) | file opens in stage per priority; explorer `Open With...` entry | long-press file -> Open With... sheet | translate customEditors -> documentOpeners + webview document type; CustomTextEditor syncs with doc model | M/Med |
| `ui.activity-nav` | Have | native | NavSurface rail/bottom bar, badges, user order/hide, More sheet, 3 items per pack, title <=14 chars | A/ui/shell/nav/NavRules.kt:35<br>A/ui/shell/nav/NavSurface.kt:54<br>A/ui/shell/nav/NavMoreSheet.kt:21<br>A/ui/shell/ShellLimits.kt:19<br>A/ui/shell/ShellLimits.kt:24 | rail (labels on EXPANDED); Ctrl+Alt+n | bottom bar, 5 cells, rest in More sheet; hidden while soft keyboard is up | wire TreeView.badge/WebviewView.badge to NavBadge; truncate long VS Code titles to 14 chars instead of refusing | S/Low |
| `ui.panel` | Partial | native | BottomPanel with Terminal + Problems renderers; `output` container is registered but has no renderer | A/ui/shell/workspace/BottomPanel.kt:71<br>A/ui/shell/CoreShell.kt:55<br>A/ui/shell/workspace/WorkspacePanels.kt:32 | docked bottom panel, drag handle to resize, Ctrl+J | bottom sheet overlay with handle; height COMPACT -> overlay | bind Output renderer; extension panel containers already allowed (Placement.PANEL) | S/Low |
| `ui.status-bar` | Partial | native | declarative easyide.statusBarItems + WASM items: caption text, clickable command id; tooltip carried but not shown; no colour/background/icons/args/a11y label; phone drops EXT_LEFT/EXT_RIGHT before FILE/SAVE/PROBLEMS | A/ui/screens/workspace/ext/ExtensionSlots.kt:104<br>A/extensions/adapters/StatusItems.kt:16<br>A/ui/shell/workspace/StatusStrip.kt:60<br>A/ui/shell/workspace/StatusPlan.kt:6 | 24dp strip, left/right by priority; tooltip on hover/long-press as EditorPopup-style card; statusBarItem.error/warningBackground tokens | 28dp strip keeps PROBLEMS/SAVE/FILE; extension items collapse to one `+n` chip opening a sheet listing each item with tooltip and its command | extend StatusItem (icon tokens, colours, background, tooltip markdown, args, a11y); overflow sheet; transient message slot | M/Low |
| `ui.notifications` | Partial | native | every extension message is a modal KitDialog; ToastQueue shows plain text without actions; no notification centre | A/ui/screens/workspace/ext/ExtensionPrompts.kt:112<br>A/ui/shell/host/Toasts.kt:19<br>A/extensions/host/ExtensionUiHost.kt:23 | toast stack bottom-right above status strip (max 3), actions inline; bell item in status strip opens centre as side sheet | one snackbar above the bottom bar/input dock, <=2 actions + `More`; centre as bottom sheet; modal:true -> KitDialog | Notification model (severity, source ext, actions, progress, dismissed state) + centre; keep dialog only for modal | M/Low |
| `ui.progress` | Missing-UI | native | KitProgress primitive exists; nothing is wired to extensions | A/ui/kit/KitProgress.kt:35 | notification toast with bar + Cancel; Window -> spinner item in status strip; view -> 2dp bar under panel header | same; notification progress in the snackbar slot | progress controller keyed by id (arch window/progress*) | S/Low |
| `ui.quick-pick` | Partial | native | QuickPickDialog in a KitDialog: fuzzy filter on label, description subtitle, multi-select checkboxes; palette PickerOverlay is separate | A/ui/screens/workspace/ext/ExtensionPrompts.kt:55<br>A/ui/commands/PickerOverlay.kt:59<br>S/action/HostPort.kt:97 | top-centre PickerOverlay (as the palette), keyboard navigation | bottom-anchored full-width sheet, search field just above the keyboard (thumb zone), 44dp rows | QuickInput controller on PickerOverlay supporting the createQuickPick lifecycle (live items, busy, buttons, steps, separators, detail line) | M/Med |
| `ui.input-box` | Partial | native | InputBoxDialog: regex validation only, password, prompt/placeholder | A/ui/screens/workspace/ext/ExtensionPrompts.kt:89<br>S/action/HostPort.kt:98 | same overlay as quick pick | same, field above keyboard | async validation round trip (arch input/validate), severity messages, valueSelection, steps | S/Low |
| `ui.output` | Missing-UI | native | Extension Log section (global extension log) only; Output container has no renderer | A/ui/screens/extensions/ExtensionLog.kt:54<br>A/ui/shell/CoreShell.kt:55<br>A/ui/shell/workspace/WorkspacePanels.kt:32 | bottom panel `Output` with channel KitMenu picker, follow/clear/copy | bottom sheet; channel picker as sheet list; also openable as stage document `output://<channel>` | OutputChannel store (ring buffer, 5,000 lines like logStream) + renderer reusing LogSection | M/Low |
| `ui.menus` | Partial | native | data-driven MenuModel (group `navigation` first, group, @order, title; when; enablement; hidden; user reorder) rendered at 5 VS Code locations + 2 own; other ids load with a warning and are dropped | A/extensions/adapters/MenuModel.kt:33<br>A/extensions/adapters/MenuModel.kt:57<br>S/contrib/MenuIds.kt:4<br>S/manifest/ContributesDecoder.kt:56<br>A/ui/kit/KitMenu.kt:28 | right-click / secondary click opens KitMenu at the pointer; title groups as icon buttons | long-press or a visible `...` button opens the same KitMenu anchored to the row (U-INT-03: long-press never the only path) | render the missing ids (see menus table), accept `submenu` items + contributes.submenus, `alt`, $(codicon) and {light,dark} icons | M/Low |
| `ui.submenus` | Missing-UI | native | KitMenuItem.Submenu renders cascades, but the manifest schema requires `command` on every menu item and has no `submenus` key | A/ui/kit/KitMenu.kt:41<br>services/shared/extension-schema/manifest.schema.json:137 (menuItem required [command], additionalProperties:false)<br>S/manifest/ContributesDecoder.kt:63 | cascade beside the row (KitMenuLogic placement) | submenu replaces the menu body with a Back row (cascades do not fit 360dp) | decode submenus, MenuEntry.Submenu, recursion limit | S/Low |
| `ui.keybindings` | Have | native | Keymap with KeyChord + two-step prefix chords + when (WhenEvaluator) + args; built-in defaults declared in Kotlin (Keymap.DEFAULT); user keybindings.json layer with diagnostics and conflicts; contributed layer (linux over key; terminalFocus opt-in); Keyboard settings page | A/ui/commands/Keymap.kt:64<br>A/ui/commands/Keymap.kt:112<br>A/ui/commands/Keymap.kt:181<br>A/ui/commands/KeybindingOverrides.kt:67<br>A/extensions/adapters/ContributedKeybindings.kt:21<br>A/ui/screens/settings/KeyboardPage.kt:40 | hardware keyboard dispatch; chords shown in palette and menu hints | InputDock key row above the soft keyboard (contributed `keyRow` + builtin); commands reachable from palette; no chords needed | only as good as the context keys (ui.context-keys); `-command` removal entries and `win` ignored by design | S/Low |
| `ui.command-palette` | Have | native | CommandPalette over PickerOverlay with fuzzy match and chord labels; extension commands filtered by commandPalette `when`; QuickOpen with `@`/`#` symbol prefixes; `Commands` nav action | A/ui/commands/CommandPalette.kt:30<br>A/ui/screens/workspace/ext/WorkspaceExtensionUi.kt:56<br>A/extensions/adapters/MenuModel.kt:97<br>A/ui/screens/workspace/quickopen/QuickOpen.kt:46<br>A/ui/shell/CoreShell.kt:79 | top overlay, Ctrl+Shift+P / Ctrl+P | nav `Commands` action; bottom-anchored picker | recently-used section; `>` and `:` prefix parity; `category: title` display | S/Low |
| `ui.context-keys` | Partial | native | ContextKeyService + full when lexer/parser/evaluator in :extension-schema; 37 fixed + 7 prefixed keys declared, 29 set by the app; config.* resolved from settings; no `setContext` built-in | S/whenclause/ContextKeyService.kt:59<br>S/whenclause/WhenEvaluator.kt:24<br>S/whenclause/WhenExpr.kt:11<br>S/whenclause/ContextKeys.kt:33<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:109 | n/a (engine) | n/a; plus touch-only keys windowSizeClass, inputMode, hardwareKeyboard | `setContext` (ext-owned namespace), VS Code key names (view, viewItem, focusedView, resourceScheme, isLinux, textInputFocus, sideBarVisible, panelFocus, activeViewlet, scmProvider, workspaceFolderCount...), set the declared-but-unset keys | M/Low |
| `ui.editor-decorations` | Partial | native | engine-neutral DecorationModel with FIXED typed layers (search matches, highlights, diagnostics, inlay hints as EOL ghost text, gutter glyph enum, code lenses); draw-phase painters (backgrounds, squiggles, ghost text, gutter); no generic styled-range layer; colour/font changes of text forbidden on the current engine | A/ui/screens/workspace/decor/DecorationLayer.kt:27<br>A/ui/screens/workspace/decor/Decorations.kt:14<br>A/ui/screens/workspace/decor/DecorationPainters.kt:99<br>A/ui/screens/workspace/decor/DecorationPainters.kt:190<br>docs/decision/0018-editor-engine-and-decorations.md (Decision 1,3) | full set after PE1; now: background, border, underline, whole-line bg, EOL `after` text, gutter icon | same; hoverMessage via long-press on the decorated range (existing hover card) | new `ExtDecorations` layer: bg/border/outline/underline/strike, whole-line bg, EOL after-text (GitLens blame fits), gutter icon (tinted SVG/PNG), overview colour; text colour/fontStyle and inline before/after need PE1 | L/Med |
| `ui.gutter` | Partial | native | gutter glyph enum ERROR/WARNING/INFO/LIGHTBULB/CODE_LENS; gutter tap reports the line; no quick-diff (dirty diff) bars | A/ui/screens/workspace/decor/Decorations.kt:69<br>A/ui/screens/workspace/decor/DecorationPainters.kt:252<br>A/ui/screens/workspace/decor/DecorationPainters.kt:278<br>docs/ux-overhaul/arch.md:208 | 16dp glyph lane; right-click -> editor/lineNumber/context | tap glyph -> popup list; narrower lane on compact (DecorationMetrics) | icon glyph from decoration types, quick-diff bars (scm quickDiffProvider + built-in git), lineNumber menu | M/Low |
| `ui.overview-ruler` | Missing-UI | native | editor scrollbar draws the thumb only | A/ui/screens/workspace/EditorScrollbar.kt:26 | 4dp marks along the scrollbar track | marks on the fast-scroll track, shown while scrolling | paint marks from DecorationSet per line / total lines | S/Low |
| `ui.code-lens-ui` | Partial | native | CodeLensController: lenses as a gutter glyph, tap lists them in an EditorPopup (0018 deferral) | A/ui/screens/workspace/lsp/CodeLensController.kt:30<br>docs/decision/0018-editor-engine-and-decorations.md (Decision 3) | true between-line block after PE1 | gutter tap -> popup list (keep even after PE1: lens text is tiny on phones) | PE1 between-line blocks; exthost codeLens provider into same controller | XL/High |
| `ui.inlay-hint-ui` | Partial | native | end-of-line ghost text of joined labels | A/ui/screens/workspace/decor/Decorations.kt:59<br>A/ui/screens/workspace/lsp/CaretDecorations.kt:81 | inline after PE1 | EOL ghost text (inline would reflow narrow lines) | PE1 inline insertion; tooltip on long-press | XL/High |
| `ui.hover-ui` | Partial | native | HoverContent with LspMarkdownView; long-press, mouse rest, Ctrl+K Ctrl+I; WASM hover providers merged; no `command:` link handling, no codicons | A/ui/screens/workspace/lsp/InfoController.kt:45<br>A/ui/screens/workspace/lsp/LspInfoPopups.kt:36<br>A/ui/screens/workspace/lsp/LspMarkdown.kt:54 | mouse rest + keyboard | long-press a word -> hover card with action row | trusted `command:` links, $(codicon) in markdown, exthost providers through the router | S/Low |
| `ui.completion-ui` | Partial | native | CompletionController + CompletionContent: resolve on focus, docs beside or below, commit characters, snippet sessions; provider (WASM) items have their `command` stripped | A/ui/screens/workspace/lsp/LspPopups.kt:91<br>A/ui/screens/workspace/lsp/CompletionController.kt:102<br>A/ui/screens/workspace/lsp/CompletionController.kt:208<br>A/ui/screens/workspace/lsp/SnippetController.kt:16 | caret popup, docs side-by-side when wide | narrow popup, docs below; tap to accept; key row Tab/arrows | run CompletionItem.command for exthost items, labelDetails, deprecated strike | S/Low |
| `ui.code-action-ui` | Have | native | lightbulb as gutter marker on the caret line, quick-fix menu (preferred first, disabled greyed), Ctrl+. | A/ui/screens/workspace/lsp/EditActionsController.kt:31<br>A/ui/screens/workspace/lsp/LspInfoPopups.kt:113<br>A/ui/commands/Keymap.kt:129 | Ctrl+. or gutter bulb | tap bulb; add to editor/touchToolbar | exthost codeAction providers merged; refactor preview reuse RenamePreviewUi | S/Low |
| `ui.diagnostics-ui` | Partial | native | DiagnosticsPresenter -> squiggles + gutter; ProblemsView grouped by file; status counts; no tag rendering, no code href | A/ui/screens/workspace/lsp/DiagnosticsPresenter.kt:53<br>A/ui/screens/workspace/decor/DecorationPainters.kt:128<br>A/ui/screens/workspace/lsp/LspPanels.kt:92 | Problems in bottom panel, Ctrl+Shift+M | nav `Problems` -> sheet; status strip count opens it | DiagnosticCollection source from exthost; fade for Unnecessary, strike for Deprecated; code links | S/Low |
| `ui.peek` | Partial | native | References side panel list with preview lines; no inline peek; `peekVisible` key never set | A/ui/screens/workspace/lsp/WorkspaceLspController.kt:57<br>A/ui/screens/workspace/lsp/NavigationController.kt:35 | References panel (secondary sidebar) with preview rows | bottom sheet of locations with preview line | route peek commands (editor.action.peekLocations, showReferences) to the References panel; no embedded editor | S/Low |
| `ui.rename-ui` | Have | native | RenameUi input + RenamePreviewUi file list | A/ui/screens/workspace/lsp/NavigationController.kt:46<br>A/ui/screens/workspace/lsp/LspDialogs.kt:30 | dialog / F2 | dialog above keyboard | exthost rename provider via router | S/Low |
| `ui.diff-editor` | Partial | native | git-diff / git-commit stage documents, read-only, side-by-side at >= threshold else unified, hunk stage/unstage/discard; DiffProviders registry per scheme not fed by extensions; Comparison is git-only | A/ui/shell/diff/DiffModel.kt:14<br>A/ui/shell/diff/DiffProvider.kt:41<br>A/ui/shell/diff/Comparison.kt:14<br>A/ui/shell/diff/DiffProvider.kt:53 | side-by-side stage document, open beside | unified diff, full-screen document | generic `compare:` provider taking two document URIs (file, untitled, content-provider schemes); editable right side after PE1 | M/Med |
| `ui.tabs` | Have | native | EditorStage (1..4 groups, one axis), Tab state PREVIEW/KEPT/PINNED, DocumentStrip, phone DocumentSwitcher, TabMenu with fixed actions | A/ui/shell/EditorStage.kt:25<br>A/ui/shell/ShellLimits.kt:13<br>A/ui/shell/EditorGroup.kt:7<br>A/ui/shell/host/TabMenuPlan.kt:8<br>A/ui/shell/host/DocumentStrip.kt:74<br>A/ui/shell/host/DocumentSwitcher.kt:49 | tab strip per group, up to 4 groups | one group; switcher list; ViewColumn.Beside = full screen + Back | expose read model + close to exthost; append editor/title/context to TabMenu | S/Low |
| `ui.text-editor` | Partial | native | one selection per tab (EditorSelections), reveal, snippets, edits; editorHasMultipleSelections is always false | A/ui/screens/workspace/EditorSelections.kt:10<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:151<br>A/ui/screens/workspace/EditorReveal.kt:1 | same | same | multi-cursor needs PE1; visibleRanges from EditorGeometry; options per tab | L/Med |
| `ui.scm-view` | Partial | native | native git-only Source Control (JGit): changes/staged list or tree, commit split button, graph, commit menu; not provider-generic; no scm/* menus | A/ui/screens/workspace/SourceControlPane.kt:67<br>A/ui/screens/workspace/git/ScmList.kt:119<br>A/ui/screens/workspace/git/CommitMenu.kt:44<br>docs/ui-redesign/vscode-parity-git.md:15 | sidebar container Git | nav Git -> sheet | ScmProvider abstraction; extension providers rendered as extra sections with ChangeRow; scm menus; count badge on nav | L/Med |
| `ui.comments-ui` | Not-planned | not-planned | none; commentController listed as not planned in the host protocol | docs/extension-host/arch.md (Providers table: commentController not planned) | later: thread card in EditorPopup + Comments panel | later: bottom sheet thread | - | XL/Med |
| `ui.walkthroughs` | Missing-UI | native | decoded; listed on Extension page only | S/manifest/ContributesDecoder.kt:248<br>A/ui/screens/extensions/ExtensionPageModel.kt:26 | stage document `walkthrough://`, steps left, media right | single column, steps as collapsible rows, media inline | document type + markdown/image render; completionEvents onCommand/onSettingChanged | M/Low |
| `ui.color-theme` | Partial | native | ColorThemeFile + VsCodeThemeMapper: 92 workbench ids -> ColorTokens, tokenColors collapsed into SyntaxRoles, semanticTokenColors | A/extensions/adapters/ColorThemes.kt:82<br>A/ui/theme/VsCodeThemeMapping.kt:52<br>A/ui/theme/ThemeColorMap.kt:17<br>A/ui/theme/SemanticTokenColors.kt:115 | same | same | keep raw colour map for webviews/ThemeColor; map more ids | M/Low |
| `ui.icon-theme` | Have | native | icon themes installable (also from .vsix), Material default (ADR 0029) | A/extensions/adapters/IconThemes.kt:89<br>A/extensions/install/VsCodeIconThemeAdapter.kt:18 | same | same | - | S/Low |
| `ui.product-icon-theme` | Not-planned | not-planned | none | services/shared/extension-schema/manifest.schema.json (no productIconThemes key) | - | - | low usage; revisit after codicons | M/Low |
| `ui.theme-colors` | Missing-UI | native | no colour registry; manifest rejects `colors` (contributes additionalProperties:false) | services/shared/extension-schema/manifest.schema.json ($defs.contributes)<br>A/ui/theme/ThemeColorMap.kt:17 | same | same | ColorRegistry: VS Code id -> theme value | contributed default | core default table (~990 ids); ThemeColor resolves through it | M/Low |
| `ui.icons` | Missing-UI | native | none; manifest rejects `icons` | services/shared/extension-schema/manifest.schema.json ($defs.contributes) | same | same | load the declared woff/ttf, map id -> codepoint, draw as text glyph | M/Low |
| `ui.codicons` | Missing-UI | native | no codicon font or table; icon tokens resolve against our glyph set + ~67 Material tokens; `$(x)` is taken as an unknown token | A/ui/icons/IconResolver.kt:26<br>A/ui/icons/MaterialIcons.kt:1<br>S/manifest/DecodeContext.kt:80 | same | same | bundle @vscode/codicons font (CC-BY-4.0, attribution in licenses) + name->codepoint table; label parser for $(...) in status items, menus, tree labels, quick pick | S/Low |
| `ui.file-decorations` | Partial | native | git status tint + letter in explorer rows (git-only source) | A/ui/screens/workspace/FileTreeRow.kt:1<br>docs/ui-redesign/vscode-parity-git.md:36 | explorer rows, tabs, breadcrumbs | same (letter badge kept, colour tint) | generic decoration source merged with git; badge + colour in FileTreeRow/tabs | M/Low |
| `ui.timeline` | Missing-UI | native | none | absence: grep -ril timeline services/mobile/app/src/main (no view) | section in Explorer or secondary sidebar | file long-press -> Timeline sheet | timeline container + provider fan-in | M/Low |
| `ui.breadcrumbs` | Have | native | EditorBreadcrumbs: path crumbs + symbols at caret | A/ui/shell/workspace/EditorBreadcrumbs.kt:21<br>A/ui/shell/workspace/BreadcrumbModel.kt:27 | under tab strip | under switcher, scrolls horizontally | feed exthost documentSymbol providers | S/Low |
| `ui.settings-ui` | Partial | native | ContributedSettings.controlOf: enum -> choice, boolean switch, integer stepper/number, string field with pattern, string[] list, everything else `Edit in settings.json`; markdown flattened to plain; deprecation, scope, order; user/project/environment layers | A/data/settings/ContributedSettings.kt:95<br>A/data/settings/ContributedSettings.kt:75<br>A/ui/screens/settings/SettingControls.kt:85 | two-column rows (label left, control right) | stacked rows | render markdownDescription as markdown with `#setting#` and command links; multilineText; object key/value editor; patternErrorMessage | M/Low |
| `ui.accessibility` | Partial | native | kit rules U-A11Y; 202 semantics/contentDescription uses under ui/; view schema requires spoken labels; ext accessibilityInformation not carried | docs/ui-redesign/ux-rules.md:96<br>docs/extension-sdk/views.md (section 3) | TalkBack + keyboard focus ring | TalkBack; 44dp isolated targets | carry accessibilityInformation/label to semantics; UNVERIFIED: TalkBack in the BasicTextField editor (verify on device) | M/Med |
| `ui.terminal-ui` | Partial | native | multiple PTY tabs, rename, extension-named terminals, key row; no icons/colours/links/split/profiles UI | A/ui/screens/workspace/WorkspaceTerminals.kt:30<br>A/ui/screens/workspace/WorkspaceTerminals.kt:79<br>A/ui/screens/workspace/TerminalKeyRow.kt:49 | bottom panel with tab strip; `terminal://` stage document | sheet or full-screen document; key row above keyboard; long-press = terminal/context | tab icon/colour, URL + path:line link detection with tap to open, profile picker, terminal menus | M/Med |
| `ui.auth-ui` | Missing-UI | native | none (only a git credential dialog) | A/ui/screens/workspace/git/GitCredentialDialog.kt:1 (git token dialog only; no auth-provider UI) | Accounts entry in Settings + consent KitDialog | same, in a sheet; OAuth via Custom Tabs + env.uri-handler | auth provider UI shell | M/Med |
| `ui.extensions-ui` | Partial | native | Extensions panel + page (details, contributions, capabilities) over the easyIDE registry; `.vsix` accepted only for icon themes | A/ui/screens/extensions/BrowseState.kt:14<br>A/ui/screens/extensions/ExtensionPage.kt:1<br>A/extensions/install/VsCodeIconThemeAdapter.kt:18 | panel list + stage page | panel sheet + full-screen page | Open VSX source, README/CHANGELOG render, host.run capability prompt | M/Med |
| `ui.semantic-highlighting` | Have | native | SemanticTokensController + SemanticStyler + SemanticOverlay | A/ui/screens/workspace/lsp/SemanticTokensController.kt:35<br>A/ui/theme/SemanticTokenColors.kt:115 | same | same | exthost semanticTokens providers via router | S/Low |
| `ui.minimap` | Not-planned | not-planned | none | docs/ux-overhaul/arch.md:208 | - | - | - | L/Low |
| `ui.notebook` | Not-planned | not-planned | none | docs/decision/0030-vscode-extension-host-in-sandbox.md (Consequences) | - | - | - | XL/High |
| `ui.debug` | Not-planned | not-planned | none | docs/decision/0030-vscode-extension-host-in-sandbox.md (Consequences) | - | - | - | XL/High |
| `ui.testing` | Missing-both | native | none | absence: no TestController/testing view under services/mobile/app/src/main | sidebar container Testing | nav item via More sheet | TestController UI (tree + gutter + results) | L/Med |
| `ui.folding` | Missing-UI | native | none; LSP foldingRange withheld from client capabilities | A/lsp/LspRuntime.kt:145<br>docs/ux-overhaul/arch.md:66 | gutter chevrons (PE1) | same, larger hit slop | PE1 line-virtualised editor | XL/High |
| `ui.language-status` | Missing-UI | native | LSP status items only (LspStatusItems) | A/ui/screens/workspace/lsp/LspChrome.kt:82 | `{}` language item in status strip opens a popup | same, sheet | reuse LspStatusItems popup | S/Low |
| `ui.outline` | Have | native | Outline container (secondary sidebar) from document symbols | A/ui/shell/CoreShell.kt:52<br>A/ui/screens/workspace/lsp/WorkspaceLspController.kt:57 | secondary sidebar | nav Outline -> sheet | feed exthost documentSymbol | S/Low |
## 3. Menu locations (MEP keys; Not-planned ones listed after the table)

Tablet / phone column: where the menu would appear. `Have` rows cite our renderer. All missing rows reuse `MenuModel.items` + `ContributedMenu`, so each is S effort once the host surface exists.

| location | status | note | tablet / phone | evidence |
|---|---|---|---|---|
| `commandPalette` | Have | palette filter by `when` | palette / palette via nav Commands | A/extensions/adapters/MenuModel.kt:97<br>A/ui/screens/workspace/ext/WorkspaceExtensionUi.kt:56<br>MEP:39 |
| `editor/title` | Partial | `navigation` group inline (max EDITOR_TITLE_MAX_INLINE) + overflow; icon only for easyIDE tokens, else text; no `alt`, no toggled | icon buttons at the end of the tab strip + overflow / overflow `...` on the switcher row | A/ui/screens/workspace/WorkspaceScreen.kt:461<br>A/ui/screens/workspace/ext/ExtensionSlots.kt:51<br>MEP:51 |
| `modalEditor/editorTitle` | Missing-UI | no equivalent menu | - / - | MEP:56 |
| `editor/title/run` | Missing-UI | no equivalent menu | run button split at tab strip end / same in switcher row | MEP:61 |
| `editor/context` | Partial | secondary-click menu in the editor; also inside the editor/title overflow for touch (long-press on text is hover) | right-click at pointer / editor/title overflow `...` (long-press = hover), or selection toolbar | A/ui/shell/workspace/FileDocument.kt:60<br>A/ui/screens/workspace/ext/ExtensionSlots.kt:79<br>MEP:66 |
| `editor/context/copy` | Missing-UI | no equivalent menu | - / - | MEP:71 |
| `editor/context/share` (proposed: contribShareMenu) | Missing-UI | no equivalent menu | - / - | MEP:76 |
| `explorer/context` | Have | scoped resource* keys + explorerFocus; appended after built-ins; no multi-select | right-click on row / long-press on row / row `...` | A/ui/screens/workspace/ext/WorkspaceExtensionUi.kt:77<br>A/ui/screens/workspace/FileContextMenu.kt:95<br>MEP:82 |
| `explorer/context/share` (proposed: contribShareMenu) | Missing-UI | no equivalent menu | - / - | MEP:87 |
| `editor/title/context` | Missing-UI | id known to MenuIds (loads without warning) but never rendered; tab menu is fixed actions | append after TabMenu sections / long-press tab / switcher row | MEP:93<br>S/contrib/MenuIds.kt:7<br>A/ui/shell/host/TabMenuPlan.kt:8<br>A/ui/shell/host/TabMenu.kt:29 |
| `editor/title/context/share` (proposed: contribShareMenu) | Missing-UI | no equivalent menu | - / - | MEP:98 |
| `scm/title` | Missing-UI | id known to MenuIds (loads without warning) but never rendered; SCM header actions fixed | SCM header icon buttons + overflow / header `...` | MEP:153<br>S/contrib/MenuIds.kt:15<br>A/ui/screens/workspace/git/ScmHeader.kt:1 |
| `scm/sourceControl` | Missing-UI | no equivalent menu | repository row menu / long-press repo row | MEP:158 |
| `scm/repositories/title` (proposed: contribSourceControlTitleMenu) | Missing-UI | no equivalent menu | - / - | MEP:163 |
| `scm/repository` | Missing-UI | no equivalent menu | repository row inline / same | MEP:169 |
| `scm/resourceState/context` | Missing-UI | id known to MenuIds (loads without warning) but never rendered; change rows have fixed actions | right-click change row; inline group as hover buttons / long-press row | MEP:174<br>S/contrib/MenuIds.kt:16<br>A/ui/screens/workspace/git/ChangeRow.kt:1 |
| `scm/resourceFolder/context` | Missing-UI | no equivalent menu | tree folder rows / long-press folder | MEP:179 |
| `scm/resourceGroup/context` | Missing-UI | no equivalent menu | group header buttons / group header `...` | MEP:184 |
| `scm/change/title` | Missing-UI | no equivalent menu | diff document title actions / switcher `...` | MEP:189 |
| `scm/inputBox` (proposed: contribSourceControlInputBoxMenu) | Missing-UI | no equivalent menu | buttons inside commit box / same | MEP:194 |
| `scm/history/title` (proposed: contribSourceControlHistoryTitleMenu) | Missing-UI | no equivalent menu | - / - | MEP:200 |
| `scm/historyItem/context` (proposed: contribSourceControlHistoryItemMenu) | Missing-UI | commit menu fixed; doc names this hook as not built | after a divider at end of commitMenuItems / same via long-press | MEP:206<br>A/ui/screens/workspace/git/CommitMenu.kt:44<br>docs/ui-redesign/vscode-parity-git.md:30 |
| `scm/historyItemRef/context` (proposed: contribSourceControlHistoryItemMenu) | Missing-UI | no equivalent menu | ref chip menu / long-press chip | MEP:212 |
| `scm/artifactGroup/context` (proposed: contribSourceControlArtifactGroupMenu) | Missing-UI | no equivalent menu | - / - | MEP:218 |
| `scm/artifact/context` (proposed: contribSourceControlArtifactMenu) | Missing-UI | no equivalent menu | - / - | MEP:224 |
| `terminal/context` | Missing-UI | id known to MenuIds (loads without warning) but never rendered; MenuIds also defines `terminal/title` which is not a VS Code id (VS Code: terminal/title/context) | right-click terminal / long-press terminal (select mode conflicts: use key-row `...`) | MEP:236<br>S/contrib/MenuIds.kt:17<br>S/contrib/MenuIds.kt:18 |
| `terminal/title/context` | Missing-UI | easyIDE spells it `terminal/title`; terminal tab long-press only renames | terminal tab right-click / long-press terminal tab | MEP:241<br>S/contrib/MenuIds.kt:18 |
| `view/title` | Missing-UI | id known to MenuIds (loads without warning) but never rendered; R4 container header has no action slot | icon buttons in view section header + overflow / header `...` button | MEP:246<br>S/contrib/MenuIds.kt:13<br>A/ui/shell/ext/ExtRenderers.kt:57 |
| `viewContainer/title` (proposed: contribViewContainerTitle) | Missing-UI | no equivalent menu | container header actions / header `...` | MEP:251 |
| `view/item/context` | Missing-UI | id known to MenuIds (loads without warning) but never rendered; R4 tree rows have no menu | right-click row; `inline` group as row buttons / long-press row / row `...` -> menu | MEP:257<br>S/contrib/MenuIds.kt:14<br>A/ui/shell/ext/ViewDataNodes.kt:96 |
| `testing/item/context` | Missing-UI | no equivalent menu | test tree row / long-press | MEP:351 |
| `testing/item/gutter` | Missing-UI | no equivalent menu | gutter glyph menu / gutter tap | MEP:356 |
| `testing/profiles/context` | Missing-UI | no equivalent menu | - / - | MEP:361 |
| `testing/item/result` | Missing-UI | no equivalent menu | - / - | MEP:366 |
| `testing/message/context` | Missing-UI | no equivalent menu | - / - | MEP:371 |
| `testing/message/content` | Missing-UI | no equivalent menu | - / - | MEP:376 |
| `extension/context` | Missing-UI | Extensions page menus fixed | extension row menu / long-press row | MEP:381<br>A/ui/screens/extensions/ExtensionListRow.kt:1 |
| `timeline/title` | Missing-UI | no equivalent menu | timeline header / header `...` | MEP:386 |
| `timeline/item/context` | Missing-UI | no equivalent menu | row right-click / long-press | MEP:391 |
| `ports/item/context` | Missing-UI | no equivalent menu | ports view row / long-press | MEP:396 |
| `ports/item/origin/inline` | Missing-UI | no equivalent menu | - / - | MEP:401 |
| `ports/item/port/inline` | Missing-UI | no equivalent menu | - / - | MEP:406 |
| `file/newFile` | Missing-UI | explorer toolbar new file/folder fixed | explorer New... menu / same | MEP:411<br>A/ui/screens/workspace/FileTreePane.kt:1 |
| `webview/context` | Missing-UI | no equivalent menu | webview contextmenu event -> KitMenu / long-press in webview | MEP:417 |
| `editor/inlineCompletions/actions` (proposed: inlineCompletionsAdditions) | Missing-UI | no equivalent menu | - / - | MEP:428 |
| `editor/content` (proposed: contribEditorContentMenu) | Missing-UI | no equivalent menu | - / - | MEP:435 |
| `editor/lineNumber/context` | Missing-UI | gutter tap goes to lens/diagnostic popup; easyIDE-own `editor/gutter` id unrendered | right-click gutter / long-press gutter | MEP:441<br>A/ui/screens/workspace/decor/DecorationPainters.kt:278<br>S/contrib/MenuIds.kt:11 |
| `mergeEditor/result/title` (proposed: contribMergeEditorMenus) | Missing-UI | no equivalent menu | - / - | MEP:446 |
| `multiDiffEditor/content` (proposed: contribEditorContentMenu) | Missing-UI | no equivalent menu | - / - | MEP:452 |
| `multiDiffEditor/resource/title` (proposed: contribMultiDiffEditorMenus) | Missing-UI | no equivalent menu | - / - | MEP:458 |
| `diffEditor/gutter/hunk` (proposed: contribDiffEditorGutterToolBarMenus) | Missing-UI | native hunk actions exist (stage/unstage/discard), not extensible | - / - | MEP:464<br>A/ui/shell/diff/DiffProvider.kt:30 |
| `diffEditor/gutter/selection` (proposed: contribDiffEditorGutterToolBarMenus) | Missing-UI | no equivalent menu | - / - | MEP:470 |
| `editor/touchToolbar (easyIDE)` | Have | touch toolbar in input dock | input dock when no hardware keyboard / input dock above soft keyboard | A/ui/shell/workspace/InputDock.kt:40<br>S/contrib/MenuIds.kt:9 |
| `keyRow (easyIDE)` | Have | key row keys | input dock / input dock | A/extensions/adapters/KeyRows.kt:92<br>S/contrib/MenuIds.kt:20 |
| `editor/selectionToolbar (easyIDE)` | Missing-UI | declared, not rendered | floating bar over selection / floating bar over selection | S/contrib/MenuIds.kt:10 |
| `editor/gutter (easyIDE)` | Missing-UI | declared, not rendered | - / - | S/contrib/MenuIds.kt:11 |

Not-planned (44; debug, notebook, interactive, chat/agents, comments, menuBar, touchBar, remote indicator, issue reporter, share, AI search): `touchBar`, `debug/callstack/context`, `debug/variables/context`, `debug/watch/context`, `debug/toolBar`, `debug/createConfiguration`, `notebook/variables/context`, `menuBar/home`, `menuBar/edit/copy`, `chat/input/status`, `statusBar/remoteIndicator`, `comments/comment/editorActions`, `comments/commentThread/title`, `comments/commentThread/context`, `comments/commentThread/additionalActions`, `comments/commentThread/title/context`, `comments/comment/title`, `comments/comment/context`, `comments/commentThread/comment/context`, `commentsView/commentThread/context`, `notebook/toolbar`, `notebook/kernelSource`, `notebook/cell/title`, `notebook/cell/execute`, `interactive/toolbar`, `interactive/cell/title`, `issue/reporter`, `file/share`, `searchPanel/aiResults/commands`, `editor/context/chat`, `chat/input/editing/sessionToolbar`, `chat/input/editing/sessionTitleToolbar`, `chat/chatSessions`, `chatSessions/item/context`, `chatSessions/newSession`, `chat/multiDiff/context`, `chat/customizations/create`, `chat/customizations/item`, `chat/editor/inlineGutter`, `chat/contextUsage/actions`, `chat/newSession`, `agents/changes/actions`, `agents/changes/actions/primary`, `agents/change/inline`.

## 4. When-clause keys (declared in `S/whenclause/ContextKeys.kt`)

| key | state | evidence |
|---|---|---|
| `editorLangId` | set | S/whenclause/ContextKeys.kt:34<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:141 |
| `resourceLangId` | set | S/whenclause/ContextKeys.kt:35<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:142 |
| `resourceExtname` | set | S/whenclause/ContextKeys.kt:36<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:146 |
| `resourceFilename` | set | S/whenclause/ContextKeys.kt:37<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:144 |
| `resourceDirname` | set | S/whenclause/ContextKeys.kt:38<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:145 |
| `resourcePath` | set | S/whenclause/ContextKeys.kt:39<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:143 |
| `resourceIsFolder` | set | S/whenclause/ContextKeys.kt:40<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:147 |
| `editorFocus` | set | S/whenclause/ContextKeys.kt:41<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:110 |
| `editorTextFocus` | set | S/whenclause/ContextKeys.kt:42<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:111 |
| `editorReadonly` | set | S/whenclause/ContextKeys.kt:43<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:148 |
| `activeEditorIsDirty` | set | S/whenclause/ContextKeys.kt:44<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:149 |
| `editorHasSelection` | set | S/whenclause/ContextKeys.kt:45<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:150 |
| `editorHasMultipleSelections` | set | S/whenclause/ContextKeys.kt:46<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:151 |
| `inSnippetMode` | declared, never set (always undefined) | S/whenclause/ContextKeys.kt:47 |
| `suggestWidgetVisible` | declared, never set (always undefined) | S/whenclause/ContextKeys.kt:48 |
| `hoverVisible` | declared, never set (always undefined) | S/whenclause/ContextKeys.kt:49 |
| `peekVisible` | declared, never set (always undefined) | S/whenclause/ContextKeys.kt:50 |
| `terminalFocus` | set | S/whenclause/ContextKeys.kt:51<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:109 |
| `terminalProcessRunning` | set | S/whenclause/ContextKeys.kt:52<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:159 |
| `explorerFocus` | set only in explorer menu scope | S/whenclause/ContextKeys.kt:53<br>A/ui/screens/workspace/ext/WorkspaceExtensionUi.kt:85 |
| `scmFocus` | declared, never set (always undefined) | S/whenclause/ContextKeys.kt:54 |
| `focusedView` | declared, never set (always undefined) | S/whenclause/ContextKeys.kt:55 |
| `focusedStage` | declared, never set (always undefined) | S/whenclause/ContextKeys.kt:56 |
| `hardwareKeyboard` | set | S/whenclause/ContextKeys.kt:57<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:112 |
| `inputMode` | set | S/whenclause/ContextKeys.kt:58<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:113 |
| `windowSizeClass` | set | S/whenclause/ContextKeys.kt:59<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:114 |
| `devicePosture` | declared, never set (always undefined) | S/whenclause/ContextKeys.kt:60 |
| `envId` | set | S/whenclause/ContextKeys.kt:61<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:160 |
| `envState` | set | S/whenclause/ContextKeys.kt:62<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:161 |
| `envBackend` | set | S/whenclause/ContextKeys.kt:63<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:162 |
| `envDistro` | set | S/whenclause/ContextKeys.kt:64<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:165 |
| `envArch` | set | S/whenclause/ContextKeys.kt:65<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:163 |
| `gitRepo` | set | S/whenclause/ContextKeys.kt:66<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:167 |
| `gitBranch` | set | S/whenclause/ContextKeys.kt:67<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:168 |
| `gitDirty` | set | S/whenclause/ContextKeys.kt:68<br>A/ui/screens/workspace/ext/WorkspaceContextFeed.kt:169 |
| `isOffline` | declared, never set (always undefined) | S/whenclause/ContextKeys.kt:69 |
| `isSafeMode` | declared, never set (always undefined) | S/whenclause/ContextKeys.kt:70 |
| `stageVisible:<id>` | prefix; set WorkspaceContextFeed.kt:115 | S/whenclause/ContextKeys.kt:72 |
| `envHasCommand:<cmd>` | prefix; never set | S/whenclause/ContextKeys.kt:73 |
| `lspReady:<lang>` | prefix; set WorkspaceContextFeed.kt:247 | S/whenclause/ContextKeys.kt:74 |
| `lspState:<lang>` | prefix; set WorkspaceContextFeed.kt:248 | S/whenclause/ContextKeys.kt:75 |
| `lspSupports:<lang>:<feature>` | prefix; set WorkspaceContextFeed.kt:249 | S/whenclause/ContextKeys.kt:76 |
| `extensionEnabled:<id>` | prefix; set services/mobile/extensions/src/main/kotlin/dev/easyide/extensions/ExtensionsRuntime.kt:181 | S/whenclause/ContextKeys.kt:77 |
| `config.<key>` | resolved from settings, ContextKeyService.kt:34 | S/whenclause/ContextKeys.kt:79 |

## 5. Proposed order of UI work (what unblocks the most extensions first)

| # | Work item | Surfaces | Effort | Why first |
|---|---|---|---|---|
| 1 | Colour registry (~990 ids + VS Code defaults) and codicon font + `$(name)` label parser | ui.theme-colors, ui.codicons, ui.icons, feeds webview vars | S+M | every status item, tree item, menu icon and webview theme depends on them |
| 2 | Context keys: `setContext`, `view`/`viewItem`/`resourceScheme`/platform keys, set the declared-but-unset keys | ui.context-keys | M | menus and views are unusable without correct `when` |
| 3 | Provider-driven native tree view + `view/title`, `view/item/context`, viewsWelcome, badges | ui.tree-view, ui.menus, ui.views-welcome | L | GitLens, Docker, test and most sidebar extensions |
| 4 | Status bar item parity, notification toasts + centre, progress, quick input lifecycle, output channels | ui.status-bar, ui.notifications, ui.progress, ui.quick-pick, ui.input-box, ui.output | M+M+S+M+M | phase-1 host API in docs/extension-host/arch.md |
| 5 | `ExtDecorations` layer (bg, border, whole-line, EOL after-text, gutter icon) + overview ruler | ui.editor-decorations, ui.gutter, ui.overview-ruler | L | GitLens blame/heatmap, TODO highlighters, bracket colourisers (bg only) |
| 6 | WebView document + WebView panel view + bridge | ui.webview-panel, ui.webview-view, ui.webview-bridge, ui.custom-editor | L | GitLens Home/Graph, Claude Code, markdown previewers |
| 7 | Generic `compare:` diff provider; scm provider abstraction; file decorations; timeline | ui.diff-editor, ui.scm-view, ui.file-decorations, ui.timeline | M+L+M+M | SCM extensions, GitLens compare |
| 8 | PE1 editor: inline decorations, code lens blocks, inlay hints, folding, multi-cursor | ui.code-lens-ui, ui.inlay-hint-ui, ui.folding, ui.text-editor | XL | only fix for coloured text and inline widgets |

## 6. UNVERIFIED items and how to verify
- Whether `.vsix` manifests will be validated by `manifest.schema.json` (which would reject many VS Code keys): read the exthost install path once `:exthost` exists (0030 Decision 6).
- TalkBack behaviour inside the `BasicTextField` editor and in KitMenu popups: device check with TalkBack on (no device available here).
- Submenu cascades on a device: docs/ui-redesign/vscode-parity-git.md:13 says "Not seen on a device".
- VS Code colour-id total: 968 static `registerColor(` ids counted by regex over `$VS/src/vs` at `0b16cb97` + 21 dynamic/extension ids; a few multi-line or computed ids may be missed (re-count with the TypeScript AST if an exact figure is needed).
- `editor/title` inline limit value (`ExtensionUiPolicy.EDITOR_TITLE_MAX_INLINE`) not read; see `A/ui/screens/workspace/ext/ExtensionSlots.kt:52`.

