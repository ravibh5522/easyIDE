# Source control and explorer, VS Code parity

Owner feedback (round 12): the explorer, the git file list, the graph and the right-click menus felt spare and hard-edged beside VS Code. This page records what was built to match, where it lives, and what is left out.

## Menu (`ui/kit/KitMenu*.kt`)

`KitMenu(expanded, onDismiss, items, modifier, at, anchor)` keeps its old signature; `anchor` (a window `IntRect`) is new. Entries: `Action` (icon, checked, disabled, danger, hint), `Submenu` (opens beside its row) and `Divider`.

- Surface: `radius.s` (6dp soft), 1dp `panelBorder`, rows at the `rowHeight` token (28dp dense), 13sp `body`, 8dp row padding inside a 4dp inset, hairline dividers, 180-320dp wide, 480dp tall before it scrolls.
- Fill: the focused or hovered row takes `listSelection` with `listSelectionText`; a disabled row is `textDisabled`; hints are right-aligned `caption` in the muted colour; a check or icon column exists only when some row has one.
- Placement (pure, `KitMenuLogic.kt`, tested): under the anchor or press point, flipped above at the bottom edge, clamped inside the window. A submenu opens on the right of its parent, on the left when the right would leave the window, and below the row when neither side fits.
- Keyboard (`MenuNav`, pure, tested): Up and Down (wrap, skip dividers and disabled rows), Home and End, Right into a submenu, Left or Escape out of it (Escape closes the menu from the top level), Enter or Space runs the focused row. Pointer hover shares the same focus.
- Submenus are nested popup windows; the root window owns the keys. Not seen on a device.

## Source control (`ui/screens/workspace/git`)

| Part | File | Notes |
|---|---|---|
| Commit split button | `CommitSplitButton.kt`, `ScmCommitBox.kt` | accent fill, check + label, hairline, chevron menu: Commit, Commit (amend), Commit and push, Commit and sync. The primary follows the last entry picked; picking runs it when it can. Push and sync are disabled with "Needs a remote" when there is no remote or HEAD is detached. |
| Changes and Staged | `ScmList.kt`, `ChangeRow.kt`, `ChangesTree.kt` | rows: icon, name tinted by status (modified, added, deleted, untracked, conflict), muted directory, hover or selected actions (open file, discard, stage), status letter. List or tree (compact folders, folders first) from the title-row toggle. Changes header: discard all, stage all. |
| Graph | `GraphLanes.kt`, `CommitGraphView.kt`, `RefChips.kt` | lane strokes per row with S-curves for merges and branch starts, in the lane palette; round nodes with a halo, merge commits as a ring with a dot, the checked-out commit larger. Subject 13sp regular, author muted, chips at the end (at most half the row): pills in the lane colour, dark text, cloud for remote, tag glyph for tags, ring for the current branch; a branch and its remote twin on one commit are one chip with the cloud; more than two fold into "+n". Header: fetch, refresh. |
| Commit menu | `CommitMenu.kt`, `CommitDialogs.kt`, `GitHistoryController.kt` | long press or right click, or the row's button when selected or hovered. Open changes; Checkout (submenu of the commit's branches); Checkout (detached); Create branch; Delete branch (submenu); Create tag; Cherry-pick; Compare with remote, merge base, or a picked ref (a file list, each row opens that file's diff); Copy hash; Copy message. |

Service operations (`sandbox-runtime/.../git`): `refsByCommit`, `createTag`, `checkoutDetached`, `cherryPick`, `mergeBase`, `changedBetween`; amend, delete branch and log already existed. Cherry-pick takes its author from the repository config (JGit's command has no author setter).

Commit and sync = commit, then pull with the configured strategy, then push (publish when the branch has no upstream); a failed pull stops before the push.

### Extension hooks (not built)

The app has no `scm/historyItem/context` contribution point, so `Add to chat` and `Explain changes` are not listed. When a pack contributes them they belong at the end of `commitMenuItems`, after a divider, through the same `MenuEntry` model the explorer uses (`explorer/context`).

## Explorer

- Toolbar: new file, new folder, refresh, collapse all, filter.
- Context menu (`fileMenuItems`): new file and folder for a folder, open to the side for a file, cut, copy, paste (dimmed with nothing on the clipboard), copy path, copy relative path, rename, delete, with the keys as hints. Left out: Reveal in Files and Open With (no counterpart on Android).
- Rows: names tinted by git status with the letter at the end, folders that hold a change tinted as modified, a dot on files with unsaved edits.
- Keys with a row focused (tap or long press it first): F2 rename, Delete, Ctrl+C, Ctrl+X, Ctrl+V into a focused folder.
- Not built: multi-select by long press then tap, drag and drop.

## Goldens

`GitUiGoldenTest` writes `app/src/test/screenshots/gitui/<name>-<config>-<dark|light>.png` at 1152dp dense, 411dp comfortable and 320dp at font scale 2: source control (fixture of 25 commits), changes as a tree, selected rows, the commit split menu, the commit menu with a submenu, the explorer and its menu. Menus are drawn inline (`KitMenuCascade`) because a popup window is not part of the captured root.
