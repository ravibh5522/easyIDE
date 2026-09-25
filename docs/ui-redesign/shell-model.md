# UI Redesign - Shell Model

Status: PROPOSED (2026-09-24). Part of [arch.md](arch.md). Decision record: [0025](../decision/0025-ui-shell-model.md).

This is the structural core of the redesign: how the app is laid out, where things open, and
what an extension may add. Everything visual (identity, kit, properties) sits on top of it.

## 1. The idea in one paragraph

The app is one **shell** with the same anatomy at every level. A **navigation surface** picks
a **container**; a container fills a **side panel**; picking something in it opens a
**document** in the **main stage**. The shell exists in two scopes: **app scope** (no project:
Home, Extensions, Settings, and anything an extension adds) and **workspace scope** (a project
is open). The two scopes use the same engine, the same panel and stage rules, and the same
extension contribution points. On a phone the surfaces collapse (bottom navigation, sliding
panels); on a tablet or desktop they dock (rail, resizable panels, split stage), exactly like
VS Code. Nothing about it is hardcoded: containers, views, document types and navigation
items are registries that built-in code and extensions both fill.

## 2. Anatomy

```
+-----+-----------------+------------------------------+-----------------+
| NAV | PRIMARY PANEL   | MAIN STAGE                   | SECONDARY PANEL |
|     | (left)          |  [tab][tab][tab]     [split] | (right)         |
| rail| container view  |  document                    | container view  |
|     |                 |                              |                 |
|     +-----------------+------------------------------+-----------------+
|     | BOTTOM PANEL: terminal / problems / output / ext views           |
+-----+------------------------------------------------------------------+
| STATUS STRIP (items from core and extensions)                          |
+------------------------------------------------------------------------+
   INPUT DOCK: floats above the software keyboard (section 9)
```

| Zone | Holds | Contributed via |
|---|---|---|
| Navigation surface (NAV) | destinations: one icon (+ label) per container or action | `navigation` items |
| Primary panel | the active container of the `sidebar` location | `viewsContainers.sidebar` |
| Main stage | editor groups; each group is tabs of documents | `documents` (types) |
| Secondary panel | the active container of the `secondarySidebar` location | `viewsContainers.secondarySidebar` |
| Bottom panel | the active container of the `panel` location | `viewsContainers.panel` |
| Status strip | small items, left and right | `statusBarItems` |
| Input dock | key rows, touch toolbar, selection tools | `keyRows`, `editor/touchToolbar` |

## 3. Entities

- **Container**: a named, switchable collection of views with an id, title, icon and a
  default **placement** (`sidebar`, `secondarySidebar`, `panel`). A user or a layout preset
  may move it to another allowed placement. Examples: Files, Search, Source control,
  Problems, Outline, Terminal, Extensions list, Settings categories. Navigation items are a
  separate list that points at containers (or commands); a container has no nav item of its
  own unless one is declared.
- **View**: one piece of UI inside a container (a tree, a list, a form). Built-in views are
  Compose code; extension views are declared with the native view schema
  ([extension-ui.md](extension-ui.md)) so they render with the same kit and identity as core.
- **Document**: anything opened in the main stage, identified by a URI (section 5). A file
  is a document. So is a settings page, an extension's detail page, a diff, a Markdown
  preview, a terminal, the welcome page, a chat.
- **Document type**: a registry entry that says how a URI scheme is rendered, titled,
  serialized for session restore, split, and whether it is dirty-aware.
- **Editor group**: an ordered list of document tabs plus the active one. The main stage is a
  tree of groups (row/column splits).
- **Navigation item**: `{id, title, icon, target, order, location, when, badge}` where
  target is a container id or a command id. The navigation surface renders the list.
- **Layout preset**: a named arrangement (which containers where, which panels open, sizes,
  group split) chosen per size class and posture, saved per project.

## 4. The rule that makes it VS Code-like

**Anything that is a page opens as a document in the main stage.** Panels list and select;
the stage shows. Specifically these are documents, not screens or dialogs:

| Document | URI | Notes |
|---|---|---|
| File (text editor) | `file:///workspace/src/a.kt` | as today |
| Settings page | `easyide://settings/editor` | one document per category, sub-page by fragment |
| Extension detail | `easyide://extension/easyide.python` | install, capabilities, inspector, log |
| Keyboard shortcuts | `easyide://keybindings` | |
| Language servers | `easyide://language-servers` | |
| Diagnostics | `easyide://diagnostics` | |
| Git diff | `git-diff:///src/a.kt?base=HEAD&head=worktree` | repository-relative path; `base` and `head` are a revision, `index`, `worktree` (head only) or `empty` (base only); side by side when the document is wide |
| Commit details | `git-commit://<sha>` | |
| Markdown / image preview | `preview:///workspace/README.md` | |
| Terminal (editor-area) | `terminal://<id>` | a terminal may also live in the bottom panel |
| Welcome | `easyide://welcome` | |
| Extension document | `ext://<extId>/<type>/<id>` | e.g. `ext://acme.docker/container/9f2c` |

What is **not** a document: transient input (dialogs, pickers, sheets, the command palette,
context menus), and the list-like content of a panel (file tree, change list, problems list).

### 4.1 Opening

- Single tap on a list item opens a **preview** tab (italic title, replaced by the next
  preview). Double tap, edit, or "Keep open" pins it. Same as VS Code.
- `openDocument(uri, {group: active|beside|new, preview: bool, focus: bool})` is the one
  entry point. Menus, palette, panels and extensions all call it, so behaviour is uniform.
- Opening a document already open focuses its tab (never duplicates), except types that
  declare `multiple: true` (terminals).
- "Open to the side" opens beside the active group and creates the split on demand. This is
  how git diff, preview and reference peek will land.

### 4.2 Closing and history

- Closing the active tab activates the most recently used tab in that group.
- Each group keeps a back/forward history, so paging through settings categories inside one
  tab (Settings -> Editor -> Fonts) is navigable with Back, as a browser would.
- Dirty documents ask before close (save / discard / cancel), unchanged from S1/S2.

## 5. Document URIs and the registry

```
DocumentType(
  scheme: String,                    // "file", "easyide", "git-diff", "ext"
  title(uri): String,                // tab title
  icon(uri): IconRef,
  render(uri, state): @Composable,   // the body
  multiple: Boolean = false,
  supportsSplit: Boolean = true,
  serialize(uri, state): Bytes?,     // null = restore by URI only
  dirtyState(uri): Flow<Boolean>?,
)
```

- The URI is the document's identity across process death: the session restore in
  `docs/ux-overhaul` (S6) persists `(group tree, tab URIs, active, scroll/cursor)` and each
  type restores itself from its URI.
- Built-in types register at startup; extension types register when the pack activates and
  are removed when it is disabled (open tabs of a vanished type show a "type unavailable"
  document with the URI and a one-tap "close all of this type").
- A type can declare **openers**: `openWith(uri)` chooses a type for a URI (Markdown file
  -> text editor or preview; `.png` -> image viewer). The user can override via
  `workbench.editorAssociations` (glob -> type id), so extensions can offer alternatives.

## 6. Navigation surface

### 6.1 Items

Built-in app-scope items: **Home**, **Extensions**, **Settings**. Built-in workspace-scope
items: **Files**, **Search**, **Source control**, **Problems**, **Terminal**, **Outline**.
Extensions contribute more (Docker, Agents, Chat, Database, Preview servers ...).

| Field | Meaning |
|---|---|
| `id`, `title`, `icon` | identity; icon is a glyph from the icon set or a pack-supplied SVG |
| `target` | a container id (opens its panel/stage) or a command id (runs it) |
| `order` | default sort key; user order overrides |
| `scope` | `app`, `workspace`, or `both` |
| `when` | context expression; item hidden when false (e.g. `workspaceContains:Dockerfile`) |
| `badge` | number or dot, from a view's state (unread, failing, running) |

### 6.2 Rendering by size class

| Size class | Surface | Rules |
|---|---|---|
| COMPACT (< 600dp) | **bottom navigation bar** | max 5 visible; extras go to a "More" sheet in the same order; labels always shown; 56dp; hides while the software keyboard is up (the input dock takes its place) |
| MEDIUM (600-840dp) | **navigation rail**, left | icons with optional labels (`shell.nav.labels`); overflow via a "..." button |
| EXPANDED (> 840dp) and desktop windows | rail, or a **labelled side panel** that can collapse to a rail | |
| Book posture | rail on the left half | |

### 6.3 User configuration

`shell.navigation.order` (list of ids), `shell.navigation.hidden` (list), `shell.navigation.pinned`
(ids that must stay in the visible five on compact). Long-press an item on a phone, or use
Settings -> Layout, to reorder and hide. An extension can add items but can never remove or
reorder built-in ones, and the user can hide any extension item. Item state is per scope.

## 7. Panels

- **Primary panel** (left): one container visible at a time; switching is done by the
  navigation surface. **Secondary panel** (right): same mechanism, its own container list.
  **Bottom panel**: tabs across the top for containers in the `panel` location.
- Panels are **resizable** (drag the divider, snap points, double tap resets), **collapsible**
  (tap the active nav item again, or Ctrl+B / Ctrl+J / Ctrl+Alt+B), and remember size and
  open state per project and per size class.
- A container can be **moved** between locations: drag its nav item onto a zone on tablets,
  or "Move to..." in its long-press menu on phones. Allowed moves are restricted by the
  view's declared `locations` (a wide table can refuse the narrow right panel).
- **Compact behaviour:** panels do not dock. The primary panel is a **modal side sheet**
  from the left (scrim, tap outside or Back closes, opening a document from it closes it
  automatically); the secondary panel is the same from the right; the bottom panel is a
  **bottom sheet** with three detents (peek 25%, half, full). Edge swipes open the side
  sheets once the gesture layer ships; buttons and the palette always work.

## 8. The main stage

- **Compact:** one group, one document visible. The tab strip is replaced by a **document
  switcher** (a title button with a count that opens a list of open documents, MRU first).
  Pinned and preview state are kept.
- **Medium:** up to two groups side by side or stacked (`Open to the side`).
- **Expanded:** up to four groups in a split tree; drag a tab to a group edge to split.
- **Book posture:** two groups, one per half of the hinge; **tabletop:** groups above,
  bottom panel below.
- Splits are per-project layout, persisted, and part of the layout preset.

## 9. Input dock (touch only)

The one bar that is not a panel. It is contextual chrome that sits directly above the
software keyboard while a text input has focus, and it replaces the compact bottom
navigation while it is shown.

| Focus | Dock content |
|---|---|
| Editor | the `editor/touchToolbar` items, then the language's key row |
| Terminal | shell keys (Ctrl, Esc, Tab, arrows, pipes) from `keyRows` |
| Any other text field | nothing (the OS keyboard only) |

- With a hardware keyboard attached the dock is hidden (a 24dp hint strip may show the
  current mode's shortcuts).
- Caret scrub bar and selection tools are dock content once the editor engine (PE1) allows
  it; until then the dock is exactly today's key rows and toolbar in one surface.

## 10. Scopes and how app scope uses the same engine

| Concept | App scope | Workspace scope |
|---|---|---|
| Navigation items | Home, Extensions, Settings, extension items | Files, Search, Git, Problems, Terminal, extension items |
| Primary panel | container list: projects, installed packs, settings categories | Files, Git, ... |
| Main stage | Home "Now" page, extension detail, settings page | files and every other document |
| Secondary panel | optional (e.g. agent context) | Outline, Chat, ... |
| Bottom panel | none by default | Terminal, Problems, Output |

So on a tablet, Settings is: rail + a panel listing categories (with search) + a stage
showing the selected category page. Extensions is: rail + a panel listing installed and
browsable packs + a stage showing the selected pack's page. Home is: rail + a panel listing
projects + a stage showing the project's "Now" page. On a phone each of those is
full-screen with the list first and the document as a push, Back returning to the list.
**Opening a project** swaps the scope: the same shell re-renders with workspace containers;
Back from the workspace root returns to app scope.

## 11. Back behaviour (all size classes, in order)

1. Close the topmost transient (dialog, sheet, menu, palette, IME).
2. Close an open compact overlay panel.
3. Step back in the active group's document history (if any).
4. Close the active preview/pinned tab? No: leave tabs alone; go to step 5.
5. Move focus to the navigation surface (compact) so the next Back leaves the scope.
6. Leave workspace scope for app scope; leave app scope's non-Home destination for Home;
   from Home, the system handles Back.
Unsaved documents are guarded at step 6 (save all / discard / cancel), as today.

## 12. Persistence and restore

Per project and size class: layout preset id, panel open state and sizes, container
locations, group split tree, tabs (URIs), active tab per group, and each document's state
blob. App scope persists the last destination and per-destination selection. Restore is
tolerant: a missing type or file degrades to a placeholder document, never a crash.

## 13. Keyboard, mouse, stylus

| Action | Keys |
|---|---|
| Toggle primary / secondary / bottom panel | Ctrl+B / Ctrl+Alt+B / Ctrl+J |
| Focus stage group 1..4 | Ctrl+1..4 |
| Next / previous tab | Ctrl+Tab / Ctrl+Shift+Tab (MRU) |
| Open to the side | Ctrl+Enter on a list item; Alt+click |
| Split editor | Ctrl+\ |
| Close tab / all | Ctrl+W / Ctrl+K W |
| Go to navigation item n | Ctrl+Alt+n |
| Layout preset picker | Ctrl+Alt+P |
Mouse: hover states, right-click context menus, drag tabs between groups, drag dividers,
middle-click closes a tab. All are commands, so user keybindings and extensions can rebind.

## 14. Mapping to today's code

| Today | Becomes |
|---|---|
| `WorkspaceStageState` (3 booleans + width constraints) | `ShellState`: panel visibility, container per location, sizes, group tree |
| `ActivityBar` in `WorkspaceChrome` | navigation surface renderer (rail / bottom bar) |
| `SidePanel { EXPLORER, SOURCE_CONTROL }` and the duplicated explorer column/overlay | one `PanelHost` with `Container` registry |
| `EditorTabBar` + one `EditorPane` | `StageHost` with editor groups; `EditorPane` becomes the `file` document type |
| Settings / Extensions / Diagnostics as nav destinations | documents of `easyide://` types plus their panel views |
| `TerminalDock` | bottom-panel container `terminal`, and `terminal://` document type |
| `TerminalKeyRow` + extension touch toolbar | input dock content |
| extension adapters (menus, status items, key rows, stages) | attach to the registries above (no adapter rewrite: same contribution points) |

## 15. Non-goals

- A freeform window manager or floating windows (presets and splits only).
- Webview-based panels (L4 stays deferred; extension views use the native view schema).
- Reproducing every VS Code affordance: no notebooks, no diff-merge editor in v1.

## 16. Open questions

1. Should the bottom navigation on compact also appear inside a workspace (Files, Search,
   Git, Terminal, extension items) or only in app scope? Proposed: yes, same component,
   workspace items, hidden while the keyboard is up.
2. Should terminals default to the bottom panel or to stage documents on expanded screens?
   Proposed: bottom panel (VS Code default), with "Move terminal to stage" as a command.
3. How many document types may a pack contribute before we require a review prompt?
   Proposed: no limit; capability `ui.document` is shown at install.
