# UI Redesign - Layout Spec

Status: PROPOSED (2026-09-24). Part of [arch.md](arch.md). Structure: [shell-model.md](shell-model.md).
Identity and rules: [identity.md](identity.md), [ux-rules.md](ux-rules.md).

Concrete layouts, sizes and behaviours per form factor. Wireframes are schematic; numbers
are dp and are the defaults (they scale with `appearance.density` and `appearance.uiScale`,
see [properties.md](properties.md)).

## 1. Size classes and postures

The shell reads `WindowSize` (already in `ui/foundation/WindowSize.kt`) plus, once added,
`WindowInfoTracker` for folds.

| Class | Width | Typical | Navigation | Panels | Stage groups |
|---|---|---|---|---|---|
| COMPACT | < 600 | phone portrait, small split | bottom bar | overlays (side sheets, bottom sheet) | 1 |
| MEDIUM | 600-840 | phone landscape, small tablet, 1/2 split | rail | one docked side panel at a time | up to 2 |
| EXPANDED | > 840 | tablet, desktop window | rail (labels optional) | left + right + bottom docked | up to 4 |
| Height COMPACT | < 480 | phone landscape | as width class, but bottom panel is an overlay | | |

Postures (foldables): **flat** = by width. **Book** (hinge vertical): two panes, left = panel
area, right = stage; the hinge is the divider. **Tabletop** (hinge horizontal): stage above,
bottom panel + input dock below. A posture change re-applies the saved preset for that
posture; it never loses open documents.

## 2. Reach map (phone, one hand)

```
 +--------------------+  y 0
 |  READ ZONE         |  title, tabs/switcher, diagnostics count,
 |  (top 30%)         |  document content. No primary action here.
 |                    |
 +--------------------+  y 30%
 |  WORK ZONE         |  the document (editor text, list, page)
 |                    |
 +--------------------+  y 70%
 |  THUMB ZONE        |  navigation bar / input dock / sheet handles /
 |  (bottom 30%)      |  primary buttons in dialogs and sheets
 +--------------------+  y 100%
```

Rules: primary actions are in the thumb zone or reachable by the keyboard; the top holds
title and read-only state; destructive actions are never on the far edge of the thumb zone
in a dialog (cancel left, confirm right, on the side away from the bottom-left grip for
right-handed use; the `appearance.handedness` setting mirrors sheets and dialog buttons).

## 3. App scope (no project open)

### 3.1 Phone portrait (COMPACT)

```
 Home                     Extensions               Settings
+--------------------+   +--------------------+   +--------------------+
| > easyIDE     [+]  |   | Extensions   [+]   |   | Settings     [/]   |
|--------------------|   | Installed | Browse |   |--------------------|
| RESUME             |   | [search          ] |   | [search settings ] |
| my-app  main*  3   |   |--------------------|   | > Editor           |
| RUNNING            |   | Python        1.0  |   |   Terminal         |
|  sh  ubuntu   [x]  |   |  built in  on [o]  |   |   Git              |
|  pyright  ok       |   | TypeScript    1.0  |   |   Sandbox          |
| ENVIRONMENT        |   |  built in  on [o]  |   |   Keys             |
|  base   4.1G   ok  |   |  ...               |   |   Extensions       |
| PROJECTS           |   |                    |   |   Layout           |
|  my-app  api  ...  |   |                    |   |                    |
+--------------------+   +--------------------+   +--------------------+
| Home  Ext  Docker  ... |  (bottom navigation, 56dp, 3-5 items, 'More' for the rest)
+------------------------+
```

- Home is the "Now" page ([screens.md](screens.md)): resume, running items, environment
  health, then projects. The list of projects is the panel of the Home destination; on a
  phone it is the lower half of the same scroll.
- Tapping a row pushes a document full-screen (extension detail, settings category,
  project detail); Back pops to the list. The bottom bar stays unless a text field with the
  software keyboard is focused.

### 3.2 Tablet (MEDIUM / EXPANDED)

```
+----+--------------------+-------------------------------------------+
| H  | PROJECTS      [+]  | my-app                       [Open] [...] |
| E  | [search        ]   |-------------------------------------------|
| S  |--------------------|  RESUME       main*  3 changed            |
|    | > my-app    3m     |  RUNNING      sh ubuntu | pyright ok      |
|    |   api-server  1d   |  ENVIRONMENT  base  proot  4.1G  ok       |
|    |   notes      5d    |  RECENT FILES  a.kt  b.kt  README.md      |
|    |                    |  git graph (last 8 commits)               |
+----+--------------------+-------------------------------------------+
  rail  primary panel(list)   main stage (selected project's page)
```

Same engine as the workspace: rail chooses a container; the panel lists; the stage shows.
Extensions and Settings follow the identical pattern (list of packs / list of categories in
the panel; detail page in the stage). On MEDIUM the panel collapses to an overlay when the
stage needs the width (portrait tablet), opened from the rail.

## 4. Workspace scope

### 4.1 Phone portrait

```
 editing (keyboard down)        keyboard up                 files sheet open
+--------------------+       +--------------------+       +------+-------------+
| main.py v   3 tabs |       | main.py v   ! 2    |       |Files |  main.py    |
|--------------------|       |--------------------|       | src/ | (dimmed,    |
|  1 def main():     |       |  1 def main():     |       | a.kt | scrim)      |
|  2    print("hi")  |       |  2    print("hi")  |       | b.kt |             |
|  3                 |       |  3                 |       |      |             |
|                    |       |--------------------|       |      |             |
|--------------------|       | Tab < > ^ v ( ) [ ]|  <- input dock (key row,
| ! 0  main.py  py   |  <-status strip 24dp      |       touch toolbar above)
|Files Search Git Term|      | [ software keyboard ]      |
+--------------------+       +--------------------+       +------+-------------+
```

- **Document switcher** (top-left, "main.py v 3 tabs"): opens a list of open documents (MRU
  first, preview in italics, dirty dot). Long-press = close others / close all.
- **Status strip**: one line: diagnostics, file, language, extension items (tap = the
  item's action). Overflow "..." if crowded.
- **Bottom navigation** (workspace items: Files, Search, Git, Terminal + extension items).
  Tapping Files opens the primary panel as a side sheet; Terminal opens the bottom panel as a
  sheet; Git opens the primary panel with the Git container. The bar hides while the
  software keyboard is up; the **input dock** takes that position directly above the
  keyboard.
- **Bottom panel sheet** has three detents (peek 25%, half, full); dragging the handle moves
  between them; peek shows the last lines of the terminal.
- **Secondary panel** (Outline, Chat, Context) is a side sheet from the right, opened from
  the "More" item or an extension item; on a phone it is used rarely, by design.

### 4.2 Phone landscape (MEDIUM width, COMPACT height)

```
+----+--------------------------------------------------------------+
| F  | main.py v                                        ! 0  py     |
| S  |--------------------------------------------------------------|
| G  |  1 def main():                                               |
| T  |  2     print("hi")                                           |
| .. |--------------------------------------------------------------|
|    | Tab < > ^ v ( ) [ ] { }  (input dock; keyboard shown below)  |
+----+--------------------------------------------------------------+
 rail                     stage (one group)
```

Rail on the left (bottom navigation is not used in landscape). Panels open as overlays
because the height is too small to dock a bottom panel. Text area is maximised; the
status strip merges into the top line.

### 4.3 Tablet landscape (EXPANDED)

```
+----+--------------+------------------------------------+----------------+
| F  | FILES        | main.py | README.md | + [split]    | OUTLINE        |
| S  |  src/        |------------------------------------|  main()        |
| G  |   a.kt       |  1 def main():                     |  helper()      |
| T  |   b.kt       |  2    print("hi")                  |                |
| A  |              |  3                                 |                |
| ...|              |------------------------------------|                |
|    |              | TERMINAL | PROBLEMS | OUTPUT       |                |
|    |              | $ python main.py                   |                |
+----+--------------+------------------------------------+----------------+
| ! 0  main  Ln 3 Col 1  py  UTF-8  env base ok                  claude . |
+-----------------------------------------------------------------------+
```

Everything docked and resizable: primary 280 (200-45%), secondary 300 (240-40%), bottom
35% (25%-70%) of stage height. The stage holds up to four groups. Status strip 24dp full
width. Rail 56dp.

### 4.4 Tablet portrait (MEDIUM/EXPANDED narrow)

Rail + stage; one docked panel at a time (primary **or** secondary); bottom panel docked
below the stage. Opening the other side panel replaces the docked one (it does not stack).

### 4.5 Foldable

```
 Book (flat unfolded, hinge vertical)      Tabletop (hinge horizontal)
+--------------+--------------+           +-----------------------------+
| PANELS       | STAGE        |           | STAGE                       |
| (primary +   | main.py      |           |                             |
|  bottom      |              |           |=========== hinge ===========|
|  stacked)    |              |           | TERMINAL + input dock       |
+--------------+--------------+           +-----------------------------+
```

The hinge is never crossed by a tab strip or a text line: panes snap to it. Presets `book`
and `tabletop` are built-in; the user can save their own per posture.

### 4.6 Desktop mode / external display

EXPANDED layout; hardware keyboard and mouse assumed: input dock hidden, hover states on,
context menus on right click, tabs draggable, dividers draggable, window resizing re-flows
size classes live.

## 5. Sizes

| Element | Compact | Medium | Expanded |
|---|---|---|---|
| Navigation surface | bottom bar 56 | rail 56 (labels 72) | rail 56 (labels 72) |
| Status strip | 24 | 24 | 24 |
| Document switcher / tab strip | 40 | 36 | 36 |
| Primary panel | sheet 88% width (max 360) | 280 docked | 280 docked (200 - 45%) |
| Secondary panel | sheet 88% width (max 360) | 300 docked | 300 docked (240 - 40%) |
| Bottom panel | sheet: peek 25% / half / full | 35% docked | 35% docked (25 - 70%) |
| Input dock | 44 (keys) + 44 (touch toolbar) | 44 | hidden with hardware keyboard |
| List row | 48 | 44 | 40 |
| Touch floor (any target) | 44 minimum, 48 preferred | 44 | 32 with mouse pointer, 44 with touch |

## 6. Input dock (touch)

- Appears when a text input owns focus and a software keyboard is shown; sits directly on
  top of the keyboard and moves with it (IME insets, no animation lag: it is part of the
  IME-aware container, not a separate overlay).
- Two rows at most: **tools** (contributed `editor/touchToolbar` items, language actions
  such as Comment, Format, Rename, Definition) and **keys** (the active `keyRows`: symbols
  or shell keys). Overflow scrolls horizontally; the first key row cell is a row switcher.
- Hidden when a hardware keyboard is attached (`Configuration.keyboard`), with a 24dp hint
  strip optional (`terminal.accessoryBar`: auto | always | never; the same setting governs
  the editor dock).
- Terminal focus: keys row only, with sticky Ctrl/Alt (latched state shown with an
  accent-filled key and a haptic tick).
- Later (needs the editor engine, ADR 0018 PE1): a **caret scrub bar** row (drag to move
  the caret, faster with drag distance, vertical drag moves by line) and selection tools
  (word, line, block, extend). Until then the dock is exactly today's key rows and toolbar in
  one surface.

## 7. Gestures (phase 5, each behind a switch and each with a button)

| Gesture | Action | Equivalent (always present) | Conflict handling |
|---|---|---|---|
| Edge swipe from left / right | open primary / secondary panel | nav item; Ctrl+B | `systemGestureExclusion` <= 200dp; right edge preferred when system back gesture is on the left |
| Swipe on the tab strip / switcher | previous / next document | switcher list; Ctrl+Tab | never inside the text area |
| Two-finger swipe (editor) | previous / next document | same | disabled while a selection handle is active |
| Drag the dock scrub bar | move caret | arrow keys | dock only |
| Drag a line number | select / move lines | select via keyboard | gutter only |
| Pinch (editor / terminal) | font size | Ctrl+= / settings | already planned |
| Long-press a tab | close others / move to group | palette | |
Rules: every gesture is a registered command (extensions may bind), each has a Settings ->
Gestures switch, none is the only way to reach anything, and TalkBack users get the buttons.

## 8. Motion for layout changes

| Change | Motion |
|---|---|
| Panel open/close (sheet) | 220ms slide + scrim fade, enter easing; instant under reduce motion |
| Panel dock/undock, resize | follows the finger; snaps with spring damping 0.9 and a CLOCK_TICK haptic |
| Document open | no transition inside a group; new group splits in 220ms |
| Navigation item change | content cross-fade 140ms, no slide |
| Keyboard show/hide | dock follows IME insets exactly |
| Theme / density / corner change | instant, one recomposition |

## 9. Density effects on layout

`appearance.density` compact / comfortable / spacious scales spacing 0.85 / 1 / 1.2 (snapped
to the 4dp grid), list rows 40 / 48 / 56 on compact width, tab and switcher heights, and the
panel default widths (260 / 280 / 300). The touch floor never drops below 44dp for any
touch target; density only tightens padding around content.

## 10. Acceptance matrix

A layout change is done when it has been checked (uiautomator dump for structure and bounds,
screenshot for look) on: phone portrait 393dp; phone landscape; 7-inch tablet portrait;
10-inch tablet landscape; 1/3 and 1/2 split-screen; font scale 1.0, 1.3, 2.0; light and dark;
with and without a hardware keyboard; and, when a foldable is available, both postures.
