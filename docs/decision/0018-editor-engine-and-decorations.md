# 0018 - Editor engine: keep the text field, add engine-neutral decoration layers now, build our own line-virtualised editor next

Status: Accepted (2026-09-24)

## Context

This is ux-overhaul **ADR-B** ([ux-overhaul/arch.md](../ux-overhaul/arch.md), PE1). Two
needs press on the editor at the same time:

1. **Large-file typing cost.** The editable surface (`workspace/EditorPane.kt`) is one
   Compose `BasicTextField` holding the whole document, coloured through a
   `VisualTransformation`. Measured on the Pad 6 ([chainlog 2026-W33](../chainlog/2026-W33.md),
   2026-08-11 11:40): the same 4-word typing burst costs **507 ms in a 14-line file and
   5,131 ms in a 4,203-line file**. Tokenising is already O(changed lines) + O(viewport)
   (`DocumentHighlighter`), so the residue is Compose text layout of the whole buffer, which
   only a line-virtualised editing surface removes. Colour spans are already limited to a
   viewport window, so span count is not the lever (measured in the same log: coalescing
   spans removed 8%).
2. **LSP UI needs decoration layers now.** Extension SDK M0/PLT-05 and every M2 language
   feature ([extension-sdk/arch.md 7.9](../extension-sdk/arch.md#79-ui-integration-via-adr-b-decoration-layers))
   need squiggles, background ranges, gutter icons, inline text, between-line blocks and
   caret-anchored popups. Waiting for a new engine would block M2 for weeks
   (extension-sdk/arch.md risk "ADR-B editor engine slips").

What a decoration must not do on the current engine: go into the `AnnotatedString`. Any
change to the field's transformed text or spans re-lays-out the whole buffer - the exact
cost in (1) - which is why the matching-bracket box was already drawn behind the text from
the `TextLayoutResult` instead of as a span.

JVM measurements of the new decoration model (desktop JDK 21, Intel Core 5 210H; the Pad 6
ran TextMate ~3x slower than this class of machine per chainlog W33, so expect ~3x here):

| Operation | Size | Cost |
|---|---|---|
| One keystroke mid-file: diff + shift all decorations | 256 KB buffer (the edit limit), 5,000 diagnostics | 1.07 ms |
| One keystroke at the top: diff + shift all | 256 KB, 4,096 search matches | 0.96 ms |
| Of which: prefix/suffix diff alone | 256 KB | 0.67 ms |
| Range query for a 750-line paint window | 4,096 matches | 5 us |

So keeping decorations in their own model costs about 1 ms per keystroke at the largest
editable size, against hundreds of ms of text layout per keystroke that the engine costs
today. The decoration layer is not the bottleneck and will not become one.

Editor libraries, verified 2026-09-24 from each repository (GitHub API license field and
source):

| Library | License | Maintenance | Engine | Verdict |
|---|---|---|---|---|
| [sora-editor](https://github.com/Rosemoe/sora-editor) | LGPL-2.1 (LICENSE file) | active, pushed 2026-09-19 | custom View, virtualised, TextMate + LSP | rejected: LGPL relinking obligations in an APK conflict with the commercial licence ([0008](0008-noncommercial-source-available-licensing.md); already rejected in [extensions/arch.md](../extensions/arch.md) and [0010](0010-textmate-highlighting-bundled.md)) |
| [AmrDeveloper/CodeView](https://github.com/AmrDeveloper/CodeView) | MIT | last push 2023-12-30 | `AppCompatMultiAutoCompleteTextView` subclass | rejected: unmaintained, and an `EditText` with regex spans over the whole document; no gutter decorations, no popups anchored to offsets |
| [markusressel/KodeEditor](https://github.com/markusressel/KodeEditor) | MIT | active, pushed 2026-08-31, 93 stars | `AppCompatEditText` subclass in a zoom layout | rejected: same whole-document `EditText` model, no decoration API; small user base |
| [Qawaz/compose-code-editor](https://github.com/Qawaz/compose-code-editor) | MIT | last push 2024-04-25 | a Compose `TextField` with prettify spans | rejected: the same engine we already have, with a weaker highlighter |

An `EditText` engine (Android `DynamicLayout`) does reflow incrementally per paragraph, so it
might type faster on large files than `BasicTextField`; that was **not measured** (no device
was available to this work). It was rejected anyway: it keeps highlighting as whole-document
`Spannable` spans (the pattern W33 moved away from), and every decoration, popup and
gesture would have to be rebuilt as View code inside a Compose shell.

## Decision

1. **Keep `BasicTextField` as the editing engine for now, and ship decorations as a layer
   that does not depend on it**:
   - a pure-Kotlin `DecorationModel` per document (`workspace/decor/`): UTF-16 offset ranges
     in typed layers (diagnostics, document highlights, search matches, inlay hints, gutter
     markers, code lenses), each with an edit policy (drop on overlap, or clip), a paint
     priority, and per-source entries. Every write names the text its offsets refer to, and
     the model shifts what it holds onto that text from the prefix/suffix diff;
   - painters that run only in the **draw phase** from the existing `TextLayoutResult`
     (squiggle wave paths, background ranges via `getPathForRange`, gutter glyphs), culled to
     the visible line window, with the text itself in its own graphics layer. A decoration
     change therefore invalidates drawing only: no recomposition of the field and no text
     relayout;
   - an `EditorGeometry` port (offset -> caret/range/line rectangle in viewport coordinates)
     and one `EditorPopup` composable positioned inside the viewport, dismissed by Escape or
     Back, drawn in the editor's own window so the text field keeps focus and the soft
     keyboard stays up.
2. **The next engine (PE1) is our own line-virtualised Compose editor**, not a library. It
   must implement the same seams - `EditorGeometry`, the `DecorationInputs` paint contract,
   the `overlay` slot - so the decoration model and every LSP presenter carry over unchanged.
3. **Deferred to PE1, and rendered differently until then:**
   - *inline inserted text* (inlay hints inside a line): shown as **end-of-line ghost text**.
     Inserting text into the field would need a non-identity `OffsetMapping` whose text
     changes with every hint refresh - a whole-document relayout each time;
   - *between-line blocks* (code lens): shown as a **gutter glyph**; a gutter tap reports the
     line so the presenter can list that line's lenses in an `EditorPopup`.

## Alternatives considered

- **Write the line-virtualised editor first, decorations after.** The right engine, but weeks
  of work (IME connection, selection across lines, scrolling, accessibility) before any
  squiggle; it would block M2 on the riskiest item. Doing decorations first behind engine-
  neutral seams loses nothing: the model and geometry port are what PE1 needs anyway.
- **Decorations as `SpanStyle`s in the highlighter's `AnnotatedString`.** Simplest to write.
  Rejected: every diagnostic refresh, caret-driven highlight or find keystroke would re-lay-out
  the whole document, re-creating the measured typing cost for every decoration change.
- **Adopt a View-based editor library** (sora-editor, CodeView, KodeEditor). See the table:
  the only capable one is LGPL; the permissive ones are `EditText` wrappers with no decoration
  or popup model and, for two of three, no recent maintenance.
- **Show inlay hints inline now via `VisualTransformation` + `OffsetMapping`.** Works
  visually, but each hint refresh changes the transformed text (full relayout), and caret
  mapping around inserted text must be hand-maintained for a surface PE1 replaces.
- **Anchor popups with Compose `Popup` windows.** A focusable popup window takes focus from
  the text field and hides the soft keyboard mid-completion; a non-focusable one never sees
  Escape. An in-window overlay keeps focus where typing happens.

## Consequences

- LSP presenters can be built now against a stable API: `DecorationModel.set(layer, source,
  items, text)`, `DecorationRegistry.model(path)` (owned by `WorkspaceViewModel`),
  `EditorPane(decorations, onGutterTap, overlay)`, `EditorGeometry.caretRect/rangeRect`,
  `EditorPopup`. Results computed for an older document version must be shifted by the
  producer first (`:lsp` `DecorationShifter`, lsp-features.md 3.3); the model only moves
  decorations it already holds.
- `ClientCapabilitiesBuilder` UI flags (lsp-features.md sec 2): underline, background range,
  gutter icon and caret popup are available; inline text is available **as end-of-line
  only**; between-line blocks are **not** available (code lens goes through the gutter).
- Large-file typing cost is unchanged by this decision; it is fixed only by PE1.
- Decorations are painted only on the editable surface. Read-only tabs (the virtualised
  `LazyColumn` viewer) show none, which matches their purpose (oversized or binary files).
- Ghost text past the longest line can be cut off at the right edge of the horizontal
  scroll range; PE1's per-line layout removes that limit.
- Instrumented checks (test-plan.md "Decoration layers", tier A: underline, background,
  gutter icon, popup rendered from a test provider) need a device and are not yet run.
