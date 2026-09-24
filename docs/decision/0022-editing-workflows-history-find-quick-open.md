# 0022 - Editing workflows: patch history at the workspace's content choke point, decoration-painted find, one picker shell

Status: Accepted (2026-09-24)

## Context

The editor had no undo/redo of its own, no find, no go to line and no file finder
([ux-overhaul/arch.md](../ux-overhaul/arch.md) Pillar 6 Editor + Files). They have to work on
the text field we ship now and carry over unchanged to the line-virtualised editor that
[0018](0018-editor-engine-and-decorations.md) sets as the next engine (PE1), so each was built
as a pure model with a thin adapter. Two facts about the current code shaped the choices:

1. **Buffers change from four places, and only one reports selections.** Typing goes through
   `BasicTextField.onValueChange` (with old and new selection); language-server results
   (format, rename apply, snippet insert, completion edits) and extension actions call
   `WorkspaceViewModel.onContentChanged` / `replaceContent` with text only. Compose's own
   `UndoManager` sees only the first, lives inside one field instance (a tab switch or a
   recomposition that re-keys the field loses it) and cannot be told about the others.
2. **The engine will change, the workspace will not.** Anything stored inside the text field
   dies with it.

## Decision

1. **History is a per-document patch stack owned by the workspace** (`EditHistory` in `edit/`,
   one per open path in `EditHistories`, surfaced through `EditorSession`). Each step is
   `(start, removed, inserted, selectionBefore, selectionAfter)`, not a snapshot, so memory
   follows what was edited (cap: 2M retained characters and 1,000 steps per document, both named
   constants). Undo and redo restore the selection.
2. **Everything that can change a buffer reaches the history.** The text field reports typed
   edits with exact selections (`EditorSession.onUserEdit`); every other change is caught at
   `WorkspaceViewModel.onContentChanged` / `replaceContent`, the single choke point, and recorded
   as one step with the caret carried across it. An edit whose "before" is not the text the
   history last saw (a buffer replaced behind its back, as a file reload would) drops the
   history and restarts from that text rather than applying patches to text they were not made
   against. So auto-close, auto-indent, format, rename apply and snippet insert are each one
   undo step by construction, with no per-feature integration.
3. **Coalescing is a function of the edit alone**: a run of single typed characters, or of
   backspaces, or of forward deletes, merges while the caret has not moved, the pause stays under
   1.5 s and, for insertions, the run does not start a new word after a non-word character.
   Newlines, pastes, completions, edits over a selection and multi-character programmatic edits
   are their own sealed steps. Undo/redo seal the step below, so typing after an undo never
   merges into older text.
4. **Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y are workspace commands** (`edit.undo`, `edit.redo`,
   outside the terminal, where they are shell control bytes). The root key handler consumes
   them, so Compose's built-in undo never runs on the editor. A focused find, palette or
   go-to-line field is exempt (it keeps its own undo).
5. **Find highlights through the decoration layer** already reserved for it
   (`DecorationLayer.SearchMatches`), so a search costs a draw, not a text relayout. The search
   engine (`TextSearch`) is pure: literal or regex, case, whole word; matches capped at 10,000;
   and every regex runs against a `CharSequence` that throws once 500 ms have passed, because
   `java.util.regex` has no timeout and a user pattern such as `(a+)+$` can backtrack for
   hours. Replace-one and replace-all go through `EditorSession.replaceText`, one undo step each.
6. **Quick open indexes off the main thread** (`FileIndexer` over `ProjectFiles.list`), skips
   `.git` always, honours every `.gitignore` in the tree with git's own rules (an ignored
   directory is never entered; deeper files override shallower; `!` re-includes), and the
   `explorer.hideHiddenFiles` setting. Ranking (`PathMatcher`) is fuzzy with a tightest-window
   alignment, file-name and word-start preference, and a recency bonus that never outranks a
   clearly better match. `PickerOverlay` is the one shell (focus, arrows, Enter, Escape, 48 dp
   rows) under the command palette, quick open and go to line; `>`, `@` and `#` reuse the
   palette's existing prefix routing to the language servers' pickers.
7. **The new command ids are not in `CommandIds.ALL`.** `ALL` is held equal to the shared
   `builtin-commands.json` and to `BuiltInCommandTable` by tests, and both live with the
   extension SDK. They are in `CommandIds.EDITING`, and `CommandIds.KNOWN` (`ALL + EDITING`) is
   what keybinding resolution accepts, so users can rebind them. Until the extension side adds
   them to those two lists, a pack manifest cannot name them (no touch-toolbar undo button from a
   pack).

## Alternatives considered

- **Keep Compose's `UndoManager`.** Free, but it is per field instance, cannot see language
  server or extension edits, restores whatever selection Compose recorded, and disappears with
  the engine.
- **Snapshot history (a copy of the text per step).** Simplest to reason about; a 256 KB buffer
  at 1,000 steps is 500 MB. Patches keep the same guarantees at edit size.
- **Record in the text field only, and ask each feature to call history.** Every present and
  future feature would have to remember to; the choke point makes the omission impossible.
- **Find as `SpanStyle`s in the highlighter.** Rejected in 0018 for the same reason: a whole
  document relayout per keystroke of the query.
- **A regex engine with linear-time guarantees (RE2/J).** Would remove the timeout, but adds a
  dependency (license and maintenance to verify per the project rules) and drops lookarounds and
  backreferences users expect from a code editor's find. The deadline guard costs nothing and
  keeps the JDK engine's semantics.

## Consequences

- PE1 reuses `EditHistory`, `TextSearch`, `PathMatcher`, `FindController` and `EditorSession`
  as they are; its input handling calls `EditorSession.onUserEdit` and honours `reveal`,
  `focusTicks` and `generation` (which drops a stale IME composition after undo).
- History is in memory: it does not survive process death, and closing a tab discards it. Hot
  exit ([ux-overhaul/tracker.md](../ux-overhaul/tracker.md) "Session restore + hot-exit") can
  serialise the stack later; steps are plain strings and offsets.
- A step whose patch exceeds the character cap on its own is still kept (the newest step is never
  dropped); the buffer limit (256 KB) bounds it.
- An empty regex match is skipped (nothing to highlight or replace), which differs from editors
  that show a caret-position match for `^`.
- Not run on a device: gestures, IME behaviour after undo, and find bar layout on real widths.
