# Extension SDK - LSP Client (features)

Low-level design of the per-feature request pipelines on top of the `:lsp` core:
`ClientCapabilities`, multi-server routing, debounce and staleness rules, and each feature
from diagnostics to document links, including semantic tokens merged with TextMate spans.

Status: PROPOSED (2026-09-23). Nothing here is implemented.
Core (JSON-RPC, transport, sync): [lsp-client.md](lsp-client.md); lifecycle and memory: [lsp-lifecycle.md](lsp-lifecycle.md).
Sources of truth: [arch.md sec 5.2 and 7](../arch.md#52-lsp-language-intelligence-l0-client-servers-via-l1-packs),
[sdk-reference.md](../sdk-reference.md) (feature ids, settings keys, `lspRequest` action).
Feature area: arch.md 5.2 LSP language intelligence. Milestones: M2 and M4 per arch.md 5.2.

## 1. Pipeline model

Every feature is one `FeaturePipeline` in `:lsp` (`dev.easyide.lsp.features`) plus a thin
presenter in app `ui/screens/workspace/lsp/`. Pipelines never touch Compose; they emit
immutable results into `LspEditorState`.

```kotlin
enum class LspFeature(val id: String) {       // ids verbatim from sdk-reference easyide.languageServers
    DIAGNOSTICS("diagnostics"), COMPLETION("completion"), HOVER("hover"), SIGNATURE_HELP("signatureHelp"),
    DEFINITION("definition"), DECLARATION("declaration"), TYPE_DEFINITION("typeDefinition"),
    IMPLEMENTATION("implementation"), REFERENCES("references"), DOCUMENT_HIGHLIGHT("documentHighlight"),
    DOCUMENT_SYMBOL("documentSymbol"), WORKSPACE_SYMBOL("workspaceSymbol"), RENAME("rename"),
    CODE_ACTION("codeAction"), CODE_LENS("codeLens"), FORMATTING("formatting"),
    RANGE_FORMATTING("rangeFormatting"), ON_TYPE_FORMATTING("onTypeFormatting"), INLAY_HINTS("inlayHints"),
    SEMANTIC_TOKENS("semanticTokens"), FOLDING_RANGE("foldingRange"), SELECTION_RANGE("selectionRange"),
    DOCUMENT_LINK("documentLink"),
}

data class DocContext(val env: String, val project: String, val uri: String, val languageId: String)

interface FeaturePipeline<Q, R> {
    val feature: LspFeature
    val trigger: Trigger                       // Debounced(key) | Immediate | OnVisibleRange
    val staleness: Staleness                   // VersionBound | VersionAgnostic | ShiftAdjusted
    suspend fun run(ctx: DocContext, query: Q, sessions: List<LspSession>): R?
}

class LspEditorState {                         // one per WorkspaceViewModel; read by EditorPane presenters
    val diagnostics: StateFlow<Map<String, List<UiDiagnostic>>>          // key: projectRelative path
    val decorations: StateFlow<Map<String, DocDecorations>>              // highlights, links, inlay, lens, semantic
    val popup: StateFlow<EditorPopup?>                                   // completion | hover | signature | peek
    val outline: StateFlow<Map<String, List<SymbolNode>>>
    val folding: StateFlow<Map<String, List<FoldRange>>>
}
```

A `FeatureScheduler` per open doc owns one `Job` per feature kind: starting a new run of the
same kind for the same doc cancels the previous job (arch.md 7.4), which sends
`$/cancelRequest` through the core.

### 1.1 Multi-server routing

`LanguageServerManager.sessionsFor(env, project, lang)` returns RUNNING sessions whose
`supports(feature)` is true (capability AND `features.only/exclude`), ordered by `priority`
desc then config order.

| Policy | Features | Rule |
|---|---|---|
| **Merge** | diagnostics, completion, codeAction, codeLens, documentLink, inlayHints, documentSymbol, workspaceSymbol, references, hover, documentHighlight, foldingRange | query all in parallel; concat; dedupe by (range, text/kind) |
| **First non-empty** | definition, declaration, typeDefinition, implementation, signatureHelp, selectionRange | query in priority order; first non-empty result wins; next only after the previous returns empty |
| **Single owner** | rename, formatting, rangeFormatting, onTypeFormatting, semanticTokens | one session: `editor.defaultFormatter` (server key `<extId>/<id>`) for formatting kinds if it supports the feature, else highest priority |

Merged items remember their `ServerKey` so resolve/command calls go back to the same session.

## 2. ClientCapabilities

`ClientCapabilitiesBuilder.build(milestone, ui)` produces exactly this set; a capability is
advertised only when the UI that renders it exists at that milestone (arch.md 7.5). `ui` is
a set of flags from the app (e.g. ADR-B decoration layers available), so a missing layer
removes the capability rather than advertising and discarding.

| Path | Value | M |
|---|---|---|
| `general.positionEncodings` | `["utf-16"]` | M2 |
| `general.staleRequestSupport` | `{cancel: true, retryOnContentModified: ["textDocument/semanticTokens/full", "textDocument/semanticTokens/range", "textDocument/semanticTokens/full/delta"]}` | M2 |
| `general.markdown` | `{parser: "easyide", version: "1"}` | M2 |
| `window.workDoneProgress` / `showMessage.messageActionItem.additionalPropertiesSupport` / `showDocument` | `true` / `false` / absent (no `window/showDocument` in v1) | M2 |
| `workspace.applyEdit` | `true` | M2 |
| `workspace.workspaceEdit` | `{documentChanges: true, resourceOperations: ["create","rename","delete"], failureHandling: "abort", normalizesLineEndings: false, changeAnnotationSupport: absent}` | M2 |
| `workspace.didChangeConfiguration.dynamicRegistration` | `true` | M2 |
| `workspace.didChangeWatchedFiles` | `{dynamicRegistration: true, relativePatternSupport: true}` | M2 |
| `workspace.configuration` / `workspaceFolders` | `true` / `true` | M2 |
| `workspace.executeCommand.dynamicRegistration` | `false` | M2 |
| `workspace.symbol` | `{symbolKind.valueSet: 1..26, resolveSupport: absent}` | M4 |
| `workspace.{semanticTokens,inlayHint,codeLens,diagnostics}.refreshSupport` | `true` per feature at its milestone | M2/M4 |
| `textDocument.synchronization` | `{didSave: true, willSaveWaitUntil: true, willSave: false, dynamicRegistration: false}` | M2 |
| `textDocument.publishDiagnostics` | `{relatedInformation: true, versionSupport: true, tagSupport: {valueSet: [1,2]}, codeDescriptionSupport: true, dataSupport: true}` | M2 |
| `textDocument.diagnostic` | `{relatedDocumentSupport: false}` | M2 |
| `textDocument.completion` | `{contextSupport: true, insertTextMode: 1, completionItem: {snippetSupport: true, commitCharactersSupport: true, documentationFormat: ["markdown","plaintext"], deprecatedSupport: true, preselectSupport: true, tagSupport: {valueSet:[1]}, insertReplaceSupport: true, resolveSupport: {properties: ["documentation","detail","additionalTextEdits"]}, labelDetailsSupport: true}, completionItemKind.valueSet: 1..25, completionList.itemDefaults: ["commitCharacters","editRange","insertTextFormat","data"]}` | M2 |
| `textDocument.hover` | `{contentFormat: ["markdown","plaintext"]}` | M2 |
| `textDocument.signatureHelp` | `{contextSupport: true, signatureInformation: {documentationFormat: ["markdown","plaintext"], parameterInformation.labelOffsetSupport: true, activeParameterSupport: true}}` | M2 |
| `textDocument.{definition,declaration,typeDefinition,implementation}` | `{linkSupport: true}` | M2 |
| `textDocument.references` / `documentHighlight` | `{}` | M2 |
| `textDocument.documentSymbol` | `{hierarchicalDocumentSymbolSupport: true, symbolKind.valueSet: 1..26, tagSupport.valueSet: [1], labelSupport: false}` | M2 |
| `textDocument.rename` | `{prepareSupport: true, prepareSupportDefaultBehavior: 1, honorsChangeAnnotations: false}` | M2 |
| `textDocument.codeAction` | `{codeActionLiteralSupport.codeActionKind.valueSet: ["", "quickfix", "refactor", "refactor.extract", "refactor.inline", "refactor.rewrite", "source", "source.organizeImports", "source.fixAll"], isPreferredSupport: true, disabledSupport: true, dataSupport: true, resolveSupport.properties: ["edit"]}` | M2 |
| `textDocument.{formatting,rangeFormatting,onTypeFormatting}` | `{}` (`rangeFormatting.rangesSupport: false`) | M2 |
| `textDocument.inlayHint` | `{resolveSupport.properties: ["tooltip","label.tooltip","label.location","textEdits"]}` | M4 |
| `textDocument.semanticTokens` | `{requests: {range: true, full: {delta: true}}, tokenTypes: <SemanticRules types>, tokenModifiers: <SemanticRules modifiers>, formats: ["relative"], overlappingTokenSupport: false, multilineTokenSupport: false, serverCancelSupport: true, augmentsSyntaxTokens: true}` | M4 |
| `textDocument.foldingRange` | `{rangeLimit: LspPolicy.FOLDING_RANGE_LIMIT, lineFoldingOnly: true, foldingRangeKind.valueSet: ["comment","imports","region"]}` | M4 |
| `textDocument.selectionRange` / `documentLink` | `{}` / `{tooltipSupport: true}` | M4 |
| `textDocument.codeLens` | `{}` | M4 |
| call/type hierarchy, `linkedEditingRange`, `moniker`, `inlineValue`, `colorProvider`, notebooks | absent | later |

`lineFoldingOnly: true` because folding is per line in the editor; `multilineTokenSupport:
false` because spans are per line (`DocumentHighlighter`'s `StyledSpan` is line-relative).

## 3. Debounce and staleness rules

### 3.1 Debounce and triggers

| Feature | Trigger | Delay (source) |
|---|---|---|
| didChange flush | edit | `lsp.didChangeDebounceMs` |
| completion (auto) | word char / trigger char | `editor.quickSuggestionsDelay`; `editor.quickSuggestions` per `{other, comments, strings}` using the TextMate role at caret (`COMMENT`/`STRING`) |
| completion (explicit) | Ctrl+Space, key-row button | immediate |
| signature help | trigger / retrigger chars; caret move inside args | `LspPolicy.debounce(SIGNATURE_HELP)` |
| hover | long-press; mouse hover | long-press immediate; mouse `editor.hover.delay` |
| document highlight | caret idle | `LspPolicy.debounce(DOCUMENT_HIGHLIGHT)` |
| code action (lightbulb) | caret idle, diagnostics change on caret line | `LspPolicy.debounce(CODE_ACTION)` |
| pull diagnostics | after each flush | `LspPolicy.debounce(PULL_DIAGNOSTICS)` after the flush |
| inlay hints, code lens, document links | visible range change, after flush | `LspPolicy.debounce(VIEWPORT_FEATURES)` |
| semantic tokens | after flush, visible range change | `LspPolicy.debounce(SEMANTIC_TOKENS)` |
| folding, document symbols | after flush | `LspPolicy.debounce(STRUCTURE)` |
| workspace symbols | palette `#` query typed | `LspPolicy.debounce(WORKSPACE_SYMBOL)` |
| definition family, references, rename, formatting, selection range | user action | immediate |

Visible range comes from `EditorPane`'s `LineWindow` (the same window that bounds
highlighting) plus `LspPolicy.VIEWPORT_MARGIN_LINES` above and below.

### 3.2 Staleness

Each result is `Versioned(value, uri, version)` (lsp-client.md sec 6).

| Class | Features | Rule when `version != current` |
|---|---|---|
| **VersionBound** | completion, signature help, hover, document highlight, code action, rename prepare, formatting (all kinds), selection range | drop; the pipeline re-runs only if its trigger is still live |
| **ShiftAdjusted** | diagnostics (push + pull), inlay hints, code lens, document links, semantic tokens, folding | keep, moved by the `EditDelta` chain since `version` (3.3); replaced when the fresh result arrives |
| **VersionAgnostic** | definition family, references, document symbols (outline), workspace symbols | apply; navigation re-maps the target by current text if the target doc is open and dirty (lines beyond EOF clamp) |

Formatting and rename are the strict cases: edits computed for version N are never applied
to N+1 (a stale formatting edit corrupts text). The user sees "Document changed, try again".

### 3.3 Shifting decorations

`DocumentSync` publishes one `EditDelta(startOffset, oldEndOffset, newEndOffset,
startLine, oldEndLine, lineDelta)` per flush (from the prefix/suffix diff). `DecorationShifter`
applies it to every ShiftAdjusted range:
- range entirely before `startOffset` -> unchanged;
- range entirely after `oldEndOffset` -> offsets + (new - old), lines + `lineDelta`;
- range intersecting the edit -> dropped (diagnostics) or truncated at the edit start
  (folding) until the fresh result.

This is the same invalidation shape as `DocumentHighlighter.setContent` (lines above the first
change keep their state), so squiggles below an edit move with the text instead of
flickering. O(decorations) per flush.

## 4. Feature pipelines

Each entry: request, when, result handling, UI surface (arch.md 7.9 decoration layer).

### 4.1 Diagnostics (M2)

- **Push**: `publishDiagnostics {uri, version?, diagnostics}` -> `DiagnosticStore.put(uri,
  serverKey, list)`. If `version` is given and < the doc's current version the list is
  shift-adjusted through deltas since that version (kept in a ring of the last
  `LspPolicy.DELTA_HISTORY` deltas per doc; older -> list waits for the next publish).
  Empty list clears that server's entry.
- **Pull** (server advertises `diagnosticProvider`): `textDocument/diagnostic {textDocument,
  identifier, previousResultId}` on open and after each flush (3.1). `kind: "unchanged"`
  keeps the previous list; `relatedDocuments` ignored (not advertised). `workspace/diagnostic`
  not used in v1. `workspace/diagnostic/refresh` -> re-pull all open docs of that server.
  A server with both push and pull is pulled only (VS Code behaviour) to avoid duplicates.
- Filters (per language): `editor.diagnostics.minSeverity`, `ignoreSources`,
  `showSquiggles`, `showInGutter`; `lsp.<lang>` disable via `lsp.enabled`.
- Closed files: push diagnostics for unopened uris are kept for the Problems panel
  (mapped via `PathMapper`; unmappable dropped) and cleared when the server stops.
- UI: underline decorations with severity colour tokens, gutter icons, Problems panel,
  status count. `tags` Unnecessary -> faded role; Deprecated -> strikethrough.
- Key: `UiDiagnostic(range, severity, message, source, code, codeHref, tags, serverKey,
  related)`.

### 4.2 Completion + resolve (M2)

Follows arch.md 6.3 "Completion round trip". Specifics:
- `CompletionContext.triggerKind`: 1 invoked, 2 trigger character (from the union of
  `triggerCharacters` of routed sessions; a session only receives trigger kind 2 for its
  own characters, else kind 1), 3 re-trigger for incomplete.
- Merge across servers (1.1) plus L0 sources: snippets (`editor.suggest.showSnippets`,
  `editor.snippetSuggestions` placement) and word-based (`editor.wordBasedSuggestions`,
  only when no server returned items or the list is empty, matching VS Code).
- `itemDefaults` from the list applied to every item before merge.
- Filter: client-side fuzzy match on `filterText ?: label` against the word before caret
  (`wordPattern` from language-configuration); sort by server `sortText`, then match score.
  While typing: refilter locally; re-request only if any source was `isIncomplete` or the
  caret left the replace range.
- Resolve: only the focused item, cancel-on-focus-change, for `documentation`, `detail`,
  `additionalTextEdits` (advertised `resolveSupport.properties`).
- Accept: `textEdit` (`InsertReplaceEdit` -> insert range when accepted by Enter with
  `editor.acceptSuggestionOnEnter`, replace range by Tab), `additionalTextEdits` and the main
  edit applied as one undo unit; `insertTextFormat == 2` goes through the L0 snippet engine;
  `command` executed via `workspace/executeCommand` on the owning session afterwards.
- `commitCharacters` (item or itemDefaults) accept then insert the character.
- Staleness: VersionBound; a response for an older version is dropped unless the only
  change since was typing inside the current word, in which case it is refiltered (this is
  the common case and avoids a re-request per keystroke).

### 4.3 Hover (M2)

`textDocument/hover` at the long-press / hover position; Merge policy; `MarkupContent` or
legacy `MarkedString[]` normalised to markdown; code fences highlighted via
`TextMateHighlighter` by fence language. Card dismissed on edit, scroll past the anchor or
caret move. Disabled by `editor.hover.enabled`.

### 4.4 Signature help (M2)

`textDocument/signatureHelp {context: {triggerKind, triggerCharacter?, isRetrigger,
activeSignatureHelp?}}`. Triggered by server `triggerCharacters`, updated by
`retriggerCharacters` and caret moves while shown; closed on Escape, caret leaving the call,
or empty result. `activeParameter` per signature wins over the top-level one;
`labelOffsetSupport` offsets are UTF-16 into the label. Disabled by
`editor.parameterHints.enabled`.

### 4.5 Definition family (M2)

`definition`, `declaration`, `typeDefinition`, `implementation`; results `Location`,
`Location[]` or `LocationLink[]` normalised to `NavTarget(hostLocation, range,
originSelectionRange?)`. One target -> open (read-only if rootfs, lsp-client.md sec 4) and
reveal; several -> peek sheet. Entry points: long-press menu, Ctrl+click, keybindings via
the command registry (Pillar 4); commands carry `enablement: lspSupports:<lang>:<feature>`.

### 4.6 References (M2)

`textDocument/references {context: {includeDeclaration: true}}`; Merge; grouped by file,
sorted by path then position; shown in the peek sheet or side panel; background-range
decoration for hits in open docs. No partial results in v1.

### 4.7 Document highlight (M2)

On caret idle; result ranges with kind Text/Read/Write -> background ranges; cleared on edit.
`editor.occurrencesHighlight`.

### 4.8 Document symbols (M2) and workspace symbols (M4)

- `documentSymbol`: hierarchical `DocumentSymbol[]` preferred; flat `SymbolInformation[]`
  converted to a tree by containment. Feeds Outline (right stage) and breadcrumbs
  (`breadcrumbs.enabled`, the ux-overhaul breadcrumb). ShiftAdjusted for breadcrumbs only.
- `workspace/symbol {query}` from the palette `#` prefix; Merge; results mapped through
  `PathMapper` (rootfs results shown with an environment badge).

### 4.9 Rename (M2)

1. If `renameProvider.prepareProvider`: `prepareRename` -> `Range` / `{range, placeholder}` /
   `{defaultBehavior}` / null (null or error -> "Cannot rename here").
2. Inline field prefilled with placeholder; submit -> `rename {newName}` with timeout
   multiplier from `LspPolicy`.
3. `WorkspaceEdit` -> `EditPlan` (sec 5) -> preview listing files and edit counts; confirm
   -> apply. Stale version at apply time -> abort with message.

### 4.10 Code actions (M2)

- Lightbulb: `codeAction {range: caret line, context: {diagnostics: overlapping, only: absent,
  triggerKind: 2}}`; shown in the gutter if any non-disabled action. `editor.lightbulb.enabled`.
- Menu: explicit invoke with `triggerKind: 1` for the selection.
- Item with `edit` -> apply; without -> `codeAction/resolve` (if `resolveProvider`) then apply
  `edit` and/or execute `command` via `workspace/executeCommand` (the server may call back
  with `workspace/applyEdit`, lsp-client.md 2.5). `disabled.reason` shown greyed.
- `isPreferred` quick fix is the default action of the lightbulb tap.
- On save: `editor.codeActionsOnSave` kinds (`source.organizeImports`, `source.fixAll`) run
  before formatting, each with the willSaveWaitUntil timeout (lsp-client.md 5.3).

### 4.11 Formatting (M2)

- Document: `formatting {options: {tabSize, insertSpaces, trimTrailingWhitespace,
  insertFinalNewline, trimFinalNewlines}}` from the ux-overhaul Pillar 5 editor keys for the
  language. Range: `rangeFormatting` for the selection. On-type: `onTypeFormatting` after a
  `firstTriggerCharacter`/`moreTriggerCharacter` when `editor.formatOnType`.
- Owner: Single owner (1.1). `editor.formatOnSave` -> lsp-client.md 5.3.
  `editor.formatOnPaste` -> range formatting of the pasted range.
- Edits applied as one undo unit; VersionBound strict.

### 4.12 Inlay hints (M4)

`inlayHint {range: visible range}`; results cached per line bucket; `inlayHint/resolve` on
tap for tooltip/location; `textEdits` applied on double-tap (accept hint). Rendered as
inline inserted text (ADR-B layer). `editor.inlayHints.enabled` `on`/`off`/`onUnlessPressed`
(touch: hidden while a key-row modifier is held). ShiftAdjusted.

### 4.13 Semantic tokens + TextMate merge (M4)

Requests: `semanticTokens/full` on open; then `full/delta {previousResultId}` after each
flush when the server supports delta, else `full`; `semanticTokens/range` for the visible
range first on open of a large doc (> `LspPolicy.SEMANTIC_RANGE_FIRST_LINES` lines) so the
screen colours before the full result. `serverCancelSupport` honoured.

Decoding: the relative 5-int encoding (deltaLine, deltaStart, length, tokenType,
modifiersBitset) against the server's `legend`, into per-line runs
`SemanticSpan(start, end, role)` with line-relative UTF-16 offsets - the same shape as
`DocumentHighlighter`'s `StyledSpan`, so no conversion is needed at render time.

Role mapping: new `SemanticRules` table beside `ScopeRules` (app `syntax/`), `(tokenType,
modifiers) -> SyntaxRole?`, single source for that mapping, same rule as `ScopeRules` ("the
single source; nothing else may translate a scope to a color"). `null` = no override (keep
TextMate). Theme `semanticTokenColors` and `editor.semanticTokenColorCustomizations` resolve
through it. Whether roles beyond today's 21 are added is arch.md open question 7.

Merge in the renderer:
- `TextMateHighlighter.highlight(...)` gains `overlay: SemanticOverlay?` (per doc: version,
  `lineSpans: Array<List<SemanticSpan>?>`). `DocumentHighlighter.annotate` adds, per line in
  `[from, to]`, the TextMate `StyledSpan`s and then the overlay spans for that line, so a
  semantic colour replaces the TextMate colour on the same range while TextMate still
  colours everything the server does not classify (`augmentsSyntaxTokens: true`).
  Later-added `SpanStyle` winning must be pinned by a renderer test (O2).
- Only colour is overlaid; bold/italic from modifiers follow the theme rule.
- Staleness: ShiftAdjusted per line - lines above the first changed line keep overlay spans,
  lines inside the edit have none until the next result, lines after it are re-indexed by
  `lineDelta`. This mirrors how `DocumentHighlighter.setContent` keeps lines above the first
  change, so both layers invalidate the same region.
- `editor.semanticHighlighting.enabled`: `true`, `false`, `configuredByTheme` (theme JSON
  `semanticHighlighting` flag). Disabled -> overlay null, no requests.
- Memory: overlay is per open doc, freed on close; the `TextMateHighlighter` LRU
  (`MAX_CACHED_DOCUMENTS`) does not hold overlays.

### 4.14 Folding ranges (M4)

`foldingRange` after flush; replaces L0 marker/indent folding for the doc when available and
`editor.foldingStrategy == auto`; `indentation` keeps L0. `rangeLimit` from `LspPolicy`.
Collapsed state is kept by start line and survives shifting.

### 4.15 Selection range (M4)

`selectionRange {positions: [caret...]}` on "expand selection"; the returned parent chain is
cached for that version so repeated expand/shrink walks the chain without new requests.

### 4.16 Code lens (M4)

`codeLens` for the doc after flush; `codeLens/resolve` for lenses entering the visible range
without a `command`; tap executes the command (`workspace/executeCommand` or a client
command id known to the command registry, e.g. `editor.action.showReferences` maps to the
references peek). Between-line blocks. `editor.codeLens`. `workspace/codeLens/refresh` re-runs.

### 4.17 Document links (M4)

`documentLink` after flush; `documentLink/resolve` on tap if `target` absent; `file://`
targets go through `PathMapper`, `http(s)` targets through the `openUrl` confirm flow (arch.md
sec 9: full URL shown). Underline decoration; `editor.links`.

### 4.18 Extension access (`lspRequest` action, WASM `lsp.request`)

The sdk-reference `lspRequest` action and WASM `lsp.request{language, method, params}` call
`LspRequestGateway.request(ctx, method, params)`: capability `lsp.request` checked by the
caller (extension runtime), then routed to the highest-priority RUNNING session for the
language; position params default to caret. Only `textDocument/*` and `workspace/symbol`,
`workspace/executeCommand` methods are allowed; lifecycle methods (`initialize`, `shutdown`,
`exit`, `$/...`) are rejected. Results pass through `PathMapper` so extensions only see host
paths relative to the project.

## 5. Applying edits (`EditApplier`)

```kotlin
interface EditApplier {                        // app impl in WorkspaceViewModel's lsp bridge
    suspend fun apply(edit: WorkspaceEdit, label: String, expectVersions: Map<String, Int>?): ApplyResult
}
data class ApplyResult(val applied: Boolean, val failureReason: String? = null)
```
`EditPlan` normalises `changes` / `documentChanges` into ordered operations:
- `TextDocumentEdit` for an open tab -> edits applied to `EditorTab.content` via
  `WorkspaceViewModel.onContentChanged`, sorted descending by start so offsets stay valid,
  one undo unit per file (undo arrives with ADR-B); `textDocument.version` mismatch -> abort.
- `TextDocumentEdit` for a closed project file -> read, apply, `ProjectFiles.writeText`.
- `CreateFile` / `RenameFile` / `DeleteFile` -> `ProjectFiles.createFile` / `rename` /
  `delete` with their `overwrite` / `ignoreIfExists` options.
- Any target mapping to a read-only rootfs location or null -> whole edit refused
  (`applied: false`).
- `failureHandling: "abort"` (advertised): stop at the first failure; earlier operations
  stay applied and the user sees which file failed.

## 6. Threading

Per lsp-client.md sec 8. Pipelines run on the owning session's serial dispatcher for
encode/decode and on `Dispatchers.Default` for merge/filter/sort and semantic decode;
results are posted to `LspEditorState` flows (`StateFlow.update`), which the Compose
presenters collect on Main. Main-thread cost per response is the state swap only
(< 4 ms, arch.md sec 10).

## 7. Configuration used

From sdk-reference Settings keys: `editor.quickSuggestions`, `editor.quickSuggestionsDelay`,
`editor.suggestOnTriggerCharacters`, `editor.acceptSuggestionOnEnter`,
`editor.suggest.showSnippets`, `editor.snippetSuggestions`, `editor.wordBasedSuggestions`,
`editor.parameterHints.enabled`, `editor.hover.enabled`, `editor.hover.delay`,
`editor.inlayHints.enabled`, `editor.semanticHighlighting.enabled`, `editor.formatOnSave`,
`editor.formatOnType`, `editor.formatOnPaste`, `editor.defaultFormatter`,
`editor.codeLens`, `editor.lightbulb.enabled`, `editor.occurrencesHighlight`,
`editor.links`, `editor.folding`, `editor.foldingStrategy`,
`editor.diagnostics.minSeverity`, `editor.diagnostics.showInGutter`,
`editor.diagnostics.showSquiggles`, `editor.diagnostics.ignoreSources`,
`editor.semanticTokenColorCustomizations`, `lsp.enabled`, `lsp.requestTimeoutMs`,
`lsp.didChangeDebounceMs`, `editor.codeActionsOnSave`, `breadcrumbs.enabled`.

`LspPolicy` additions (declarative table, starting values to profile):

| Constant | Start value |
|---|---|
| `debounce(SIGNATURE_HELP / DOCUMENT_HIGHLIGHT / CODE_ACTION)` | 100 / 250 / 250 ms |
| `debounce(PULL_DIAGNOSTICS / VIEWPORT_FEATURES / SEMANTIC_TOKENS / STRUCTURE)` | 200 / 300 / 300 / 500 ms |
| `debounce(WORKSPACE_SYMBOL)` | 200 ms |
| `VIEWPORT_MARGIN_LINES` | 50 |
| `DELTA_HISTORY` | 32 deltas per doc |
| `FOLDING_RANGE_LIMIT` | 5000 |
| `SEMANTIC_RANGE_FIRST_LINES` | 2000 |
| `MAX_COMPLETION_ITEMS` | 500 kept after filter per source |

## 8. Integration points (existing code)

| Existing | Change |
|---|---|
| `app/.../workspace/WorkspaceViewModel.kt` (`EditorTab`, `openContent`, `onContentChanged`, `onSaveActiveTab`, `onTabClosed`) | constructs `LspWorkspaceBridge`, exposes `LspEditorState`; save hook; `EditApplier` impl. File is 641 lines today - the bridge and applier live in `workspace/lsp/`, not in the ViewModel (600-line rule) |
| `app/.../workspace/EditorPane.kt` (`rememberHighlightTransformation`, `LineWindow`) | passes the semantic overlay and visible range; popups and decorations wait on ADR-B |
| `app/.../syntax/TextMateHighlighter.kt`, `DocumentHighlighter.kt` | overlay parameter in `highlight` / `annotate` (4.13) |
| `app/.../syntax/ScopeRules.kt` | sibling `SemanticRules.kt` |
| `app/.../theme/SyntaxColors.kt` (`SyntaxRole`) | colours for any new roles (arch.md open question 7) |
| `sandbox-runtime/.../files/ProjectFileWatcher.kt` | feeds `didChangeWatchedFiles` for registered globs; it watches only expanded dirs today, so a recursive watch for registered patterns is needed (O3) |
| `sandbox-runtime/.../files/ProjectFiles.kt` | `EditApplier` closed-file edits and resource operations |

## 9. Testing

- Per pipeline, JVM tests against `FakeServer` (lsp-client.md sec 11): request shape per
  `ClientCapabilities`, routing policy (merge, first non-empty, single owner with
  `editor.defaultFormatter`), cancellation of superseded requests, VersionBound drop,
  ShiftAdjusted shifting with edit sequences above/inside/below ranges.
- `ClientCapabilitiesBuilder`: golden JSON per milestone and per UI-flag set; a missing ADR-B
  layer removes its capability.
- Semantic tokens: decode against recorded legends (pyright, gopls, clangd,
  rust-analyzer transcripts); delta application equals a fresh full; overlay merge test
  renders an `AnnotatedString` and asserts the semantic role wins where present and TextMate
  elsewhere (pins O2).
- Completion: fuzzy filter and sort fixtures; `isIncomplete` re-request; commit characters;
  `InsertReplaceEdit`; snippet insertion via the L0 engine.
- `EditPlan`: `changes` vs `documentChanges`, descending application, version mismatch
  abort, read-only target refusal, abort semantics mid-plan.
- Transcript replay of complete M2 sessions (open, type, complete, hover, rename, format,
  close) for the Python pack servers; M2 device exit criteria per arch.md sec 12.

## 10. Open issues

- O1 Completion/hover UI and all decorations depend on ADR-B; until then only Problems
  panel, Outline, navigation and formatting are demonstrable (arch.md risk table).
- O2 `AnnotatedString` precedence of overlapping `SpanStyle` colours must be verified by
  test; the ADR-B editor may replace `AnnotatedString` rendering altogether.
- O3 `ProjectFileWatcher` is FileObserver per expanded dir; recursive watching of a large
  tree has an inotify-watch cost to measure before advertising `didChangeWatchedFiles`
  broadly.
- O4 Undo units: "one undo unit" for multi-edit applies needs the ADR-B undo stack; today
  `onContentChanged` replaces the whole content.
- O5 Semantic role table vs extending the 21 `SyntaxRole`s (arch.md open question 7).

## Deviations

- D1, D2 resolved: `editor.codeActionsOnSave` and `breadcrumbs.enabled` are in sdk-reference;
  arch.md 5.2/6.3 now use `editor.quickSuggestions` / `quickSuggestionsDelay`.
