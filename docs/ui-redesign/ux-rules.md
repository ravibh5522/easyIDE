# UI Redesign - UI/UX Rules

Status: PROPOSED (2026-09-24). Part of [arch.md](arch.md). Identity: [identity.md](identity.md).
Kit: [kit.md](kit.md). Properties: [properties.md](properties.md).

Enforceable rules for every screen, panel, document and dialog, core or extension. Each rule
has an ID and a **check**: `test` (automated, fails the build), `lint` (script in CI),
`shot` (screenshot gallery diff), `dev` (on-device matrix), or `review` (PR checklist).
The kit makes the right thing the easy thing; these rules make the wrong thing fail.

## 1. Structure and layout (U-LAY)

| ID | Rule | Check |
|---|---|---|
| U-LAY-01 | Every screen is built from the shell: navigation surface, panels, main stage. No screen owns a private top bar, drawer or back stack. | review |
| U-LAY-02 | Anything that is a page opens as a **document** in the main stage; panels list, dialogs ask. | review |
| U-LAY-03 | On compact width the content column is edge to edge with a 16dp gutter; on wider windows page content is capped at 720dp and centred in the stage, lists and tables may use the full width. | shot |
| U-LAY-04 | Primary actions live in the bottom 30% on a phone or on the keyboard; the top 30% holds title and read-only state. | review |
| U-LAY-05 | List-detail is the default for wide windows (list in the panel, detail in the stage); on compact the same content is list first, detail pushed. | dev |
| U-LAY-06 | One scroll axis per region. No nested scrolling of the same direction; a horizontal scroller inside a vertical list is the only exception (tab strips, code). | review |
| U-LAY-07 | Spacing comes from the 4dp scale (`Kit.space`), never literals. Vertical rhythm inside a group: row height constant, separators inset to the text edge. | lint |
| U-LAY-08 | Maximum three levels of nesting visually: page, group, row. No group inside a group, no card inside a card. | review |

## 2. Components (U-CMP)

| ID | Rule | Check |
|---|---|---|
| U-CMP-01 | UI code outside `ui/kit` may not import raw Material widgets: Scaffold, TopAppBar, ListItem, Card, AlertDialog, TextButton, OutlinedTextField, FilterChip, AssistChip, Switch, DropdownMenu, Divider. | test (import ratchet) |
| U-CMP-02 | A component exists once. A second copy of a section header, badge, banner or empty state is a defect; extend the kit component instead. | review |
| U-CMP-03 | At most **one** filled primary action per screen or dialog. Secondary actions are ghost or outlined. | shot |
| U-CMP-04 | Destructive actions use the danger tone, are never the default focus, and name the object and the consequence ("Delete api-server? 214 files, not recoverable."). Irreversible ones need the object name typed. | review |
| U-CMP-05 | Toggles apply immediately with no Save button. Text/JSON editors apply on explicit save or on blur with visible dirty state. | review |
| U-CMP-06 | Every list row has one primary tap target (the row) plus at most two trailing actions. Overflow goes into a menu, never a third icon. | review |
| U-CMP-07 | Menus are for actions on an object; choosers for a value are a sheet (compact) or an inline picker. No menu inside a dialog. | review |
| U-CMP-08 | Dialogs are for one question. Multi-step flows are documents or full-screen sheets, not chained dialogs. | review |

## 3. Typography and text (U-TYP)

| ID | Rule | Check |
|---|---|---|
| U-TYP-01 | Text uses the kit roles (title, section, row, support, body, mono). No `fontSize` or `TextStyle(...)` literals outside `ui/theme` and `ui/kit`. | lint |
| U-TYP-02 | Code-like strings (paths, hashes, branches, versions, sizes, times, keys) are set in Geist Mono. | lint (regex) |
| U-TYP-03 | At most 3 sizes and 2 weights on one screen. Hierarchy from tone, then size, then weight. | shot |
| U-TYP-04 | Caps only for 11sp section headers and status tags, at most 14 characters. Never for buttons or titles. | lint |
| U-TYP-05 | Sentence case for everything else. No Title Case labels. | lint |
| U-TYP-06 | Layout survives 200% font scale: text rows use minimum heights, never fixed heights; no clipped or overlapping text (the phone status bar overlap seen on device is the reference failure). | dev |
| U-TYP-07 | Copy follows [identity.md](identity.md) section 9: terse, no exclamation marks, no "Oops", "Let's", "please", "successfully"; error copy names the object and the next step. | lint (strings.xml regex) |
| U-TYP-08 | Every user-visible string is a resource. No literals in composables. | lint |

## 4. Colour and surface (U-COL)

| ID | Rule | Check |
|---|---|---|
| U-COL-01 | Colours come from `ThemeTokens` through the kit (`Kit.colors`, `Tone`). No `Color(0x...)`, `Color.White/Black` or hex strings outside `ui/theme`. | lint |
| U-COL-02 | Surfaces separate by tone and 1dp hairline, not shadow. Elevation above 0 only on floating surfaces (menu, dialog, popup). | lint |
| U-COL-03 | No gradients, glow, blur or translucency effects, except the command palette scrim. | lint |
| U-COL-04 | The accent covers <= 5% of a screen's pixels and marks only focus, the current item, the primary action, caret and selection. | shot (pixel sample) |
| U-COL-05 | State is never conveyed by colour alone: add a glyph, a word or a shape. | review |
| U-COL-06 | Text contrast: body >= 4.5:1, large text and UI marks >= 3:1 in every built-in palette, in every accent swatch and in high-contrast (AAA 7:1). Custom accents are nudged to pass. | test |
| U-COL-07 | Every screen is checked in light and dark. | shot |

## 5. Interaction and touch (U-INT)

| ID | Rule | Check |
|---|---|---|
| U-INT-01 | Touch targets are at least 44dp in both dimensions (48dp preferred) regardless of density. A 32dp target is allowed only when a pointer is present. | test + dev (uiautomator bounds) |
| U-INT-02 | Every action reachable by gesture is reachable by a visible control and a command. | review |
| U-INT-03 | Long-press is never the only path to an action; it is a shortcut to a menu that also exists elsewhere. | review |
| U-INT-04 | Hover, focus and pressed states exist for every interactive kit component; keyboard focus is visible (accent 2dp ring) and tab order follows reading order. | shot + dev |
| U-INT-05 | Hardware keyboard: everything reachable without touch; standard shortcuts (see shell-model section 13) are commands with visible hints in menus and palette. | review |
| U-INT-06 | Text fields declare `KeyboardOptions` (type, IME action, capitalisation, autocorrect off for code and paths). | lint |
| U-INT-07 | Back behaves as in shell-model section 11 everywhere; a dialog or sheet always closes on Back before anything else. | test |
| U-INT-08 | Destructive or costly operations show progress and are cancellable; long work never blocks the UI thread and shows a determinate bar when the total is known. | review |

## 6. States (U-STA)

| ID | Rule | Check |
|---|---|---|
| U-STA-01 | Every data-driven view defines loading, empty, error and content states. | review |
| U-STA-02 | Loading shows nothing for 300ms, then the cursor sweep or a determinate bar; lists show a static skeleton (no shimmer). | shot |
| U-STA-03 | Empty states are ASCII art (max 5 lines) plus one sentence plus one command action. No centred icon over a headline. | lint + shot |
| U-STA-04 | Errors are inline with a code, one sentence and one action; a modal error is allowed only when the app cannot continue. | review |
| U-STA-05 | Offline is a state, not an error: say what is stale and what still works. | review |
| U-STA-06 | A restored screen (after process death) shows its last known content immediately, then refreshes. | dev |

## 7. Motion and haptics (U-MOT)

| ID | Rule | Check |
|---|---|---|
| U-MOT-01 | Durations from the motion table only (90/140/220ms, ceiling 320ms). | lint |
| U-MOT-02 | `rememberInfiniteTransition` and any looping animation appear only in `CursorBlink`; no animation runs at idle. | lint |
| U-MOT-03 | Nothing in the never-animate list of [identity.md](identity.md) section 7 animates. | review |
| U-MOT-04 | Reduce motion (system or app setting) yields 0ms everywhere and a solid cursor. | test |
| U-MOT-05 | Haptics come from the haptics table through the `Haptics` helper and honour `appearance.haptics`. | lint |

## 8. Accessibility (U-A11Y)

| ID | Rule | Check |
|---|---|---|
| U-A11Y-01 | Every interactive element has a role, a state and a name (text or content description from a string resource). Decorative elements are hidden from semantics. | dev (uiautomator: clickable nodes have text or desc) |
| U-A11Y-02 | Headings are marked (`heading()`), lists expose item counts, toggles expose checked state, dialogs trap focus and announce their title. | review |
| U-A11Y-03 | Content order is reading order in every size class; panels that appear as overlays take focus and return it on close. | dev |
| U-A11Y-04 | No information only in a tooltip or hover state. | review |
| U-A11Y-05 | Text scales to 200% and display size scales without loss of function; the touch floor and reflow rules hold. | dev |
| U-A11Y-06 | The motif system is decorative: cursor blink, crop corners and ticks are excluded from TalkBack, and the blink stops when the screen reader is on. | review |

## 9. Anti "generated" checks (U-AI)

These are the fifteen tests behind the identity. Each is pass/fail.

| ID | Rule | Pass/fail test |
|---|---|---|
| U-AI-01 | No raw Material widgets outside the kit | import ratchet (U-CMP-01) |
| U-AI-02 | At most one filled primary action per screen | screenshot scan finds <= 1 filled accent button |
| U-AI-03 | No gradients, glow or blur | grep `Brush.*Gradient`, `blur(` outside the allowlist |
| U-AI-04 | Accent <= 5% of pixels | sample the gallery screenshots |
| U-AI-05 | No resting shadow | grep `elevation`/`shadow` above 0 on non-floating surfaces |
| U-AI-06 | No icon-in-a-tinted-circle as a list leading | grep `CircleShape` container holding an `Icon` inside a row |
| U-AI-07 | No centred icon + headline empty state | every empty state uses `KitEmptyState` with art and a command action |
| U-AI-08 | Copy has none of `Oops`, `Something went wrong`, `Let's`, `!`, `please`, `successfully` | regex on `strings*.xml` (a `!` inside a code sample is allowlisted) |
| U-AI-09 | Sentence case except caps section headers | regex on button/title strings |
| U-AI-10 | Code-like strings in mono | regex for path/hash/version patterns vs style role |
| U-AI-11 | At most 3 sizes and 2 weights per screen | semantics scan of the gallery |
| U-AI-12 | No hardcoded colour, dp or sp outside `ui/theme` and `ui/kit` | grep |
| U-AI-13 | No looping animation outside `CursorBlink`, none over 320ms | grep + test on `Motion` |
| U-AI-14 | Density: Settings shows 9 or more rows on an 800dp-tall tablet without scrolling | `dev` screenshot + count |
| U-AI-15 | Motif check: each screen shows 1 to 3 motif instances | review against the per-surface table |

## 10. Performance (U-PERF)

| ID | Rule | Check |
|---|---|---|
| U-PERF-01 | Scrolling Settings and Extensions holds 60 fps on the reference phone; `dumpsys gfxinfo` jank under 5% over a scripted scroll. | dev |
| U-PERF-02 | Kit parameters are stable/immutable; no `Modifier.composed`; separators drawn with `drawBehind`, not divider composables. | review |
| U-PERF-03 | A screen composes its first frame from cached state, not from I/O; data loads off the main thread. | review |
| U-PERF-04 | Changing density, corners, scale or accent recomposes once and never re-lays out the editor text. | test |
| U-PERF-05 | Views that are not visible receive no updates and run no timers (extension views included). | test |

## 11. Extension UI (U-EXT)

| ID | Rule | Check |
|---|---|---|
| U-EXT-01 | Extension UI is declarative and rendered by the kit; no extension supplies pixels, colours or fonts. | validate |
| U-EXT-02 | Extension nav items: title <= 14 characters, monochrome 24-grid icon, at most 3 per pack. | validate |
| U-EXT-03 | An extension can never hide, reorder or restyle built-in items; the user can hide any extension item. | test |
| U-EXT-04 | View limits from [extension-ui.md](extension-ui.md) section 4.5 are enforced at runtime; a violation shows "view unavailable" and never crashes. | test |
| U-EXT-05 | Destructive actions declared by a pack carry a `confirm` block that names the object. | validate |

## 12. Enforcement

- **Import ratchet test** (JVM): scans `ui/screens/**`, `ui/components/**`, `ui/shell/**` for
  the banned imports of U-CMP-01 against an allowlist that only shrinks; a new violation
  fails the build. The allowlist starts with every current offender and each migration phase
  removes files from it.
- **Lint script** (`tools/ui-lint.sh`, run in CI): the grep-based rules (U-COL-01/02/03,
  U-TYP-01/04/05/07/08, U-MOT-01/02/05, U-AI-03/05/06/08/09/10/12/13).
- **Screenshot gallery**: one screen per kit component and per migrated screen at default
  properties, plus the matrix density x corners x font scale x light/dark on the gallery
  only. Tooling choice is in [kit.md](kit.md) section 7.
- **On-device matrix** (adb + uiautomator): sizes, font scale, night mode, hardware
  keyboard; asserts touch-target bounds, non-empty names, no truncation, scroll jank.
- **PR checklist** (added to the repo's review template): "Uses kit only", "States covered",
  "Copy follows voice", "Checked at 200% font and dark", "Motif count 1-3", "No new
  allowlist entries".
