# UI Redesign - Identity

Status: PROPOSED (2026-09-24). Part of [arch.md](arch.md). Decision record: [0026](../decision/0026-identity-ui-kit-and-properties.md).
Extends [0019](../decision/0019-visual-identity.md) (palette, fonts, tokens). Rules that enforce
this document: [ux-rules.md](ux-rules.md).

## 1. Position

easyIDE is a **terminal-native instrument**: a serious tool for makers that happens to live on
a touch screen. The interface should feel like a well-made piece of equipment (a synth, a
lab instrument, a good terminal), not like a generated app. It earns that through a small
number of consistent, functional decisions, not through decoration. The working name for the
identity is **Block**, after its primary motif.

What "generated" means, and what we do instead, is codified as fifteen checkable rules in
[ux-rules.md](ux-rules.md) section 9. The short version: no stock widgets, no gradients,
no cards-in-cards, no icon-in-a-circle, no title-case chat copy, one accent, one motif,
hierarchy from type and tone.

## 2. Motifs

### 2.1 Primary: the block cursor

The block cursor is the object every user stares at (editor, terminal), it is already an
accent-coloured token (`CURSOR`), and no other tool owns it. It carries one idea across the
whole app: **the accent marks where you are**.

| State | Look | Meaning | Used for |
|---|---|---|---|
| Solid | filled accent block, 0.6em wide | focus / active / current | active nav item marker, focused field caret, selected row marker |
| Hollow | 1dp accent outline of the same block | available / inactive | inactive nav item hint on wide rail, a step not yet reached |
| Blinking | solid, hard on/off, 530ms/530ms | working / waiting for input | loading, a running task, an active prompt |
| Cell fill | a row of small blocks, filled left to right | determinate progress | installs, downloads, indexing |

The block is **drawn** (a rectangle with the accent colour), not typed as a character: the
bundled Geist Mono has no block-cursor glyph (U+25AE), only the full block (U+2588) and
half block (U+258C). Drawn, it also scales with density and font scale.

### 2.2 Supporting motifs

1. **Prompt glyph.** A right angle bracket (`>`, U+203A is present in Geist Mono) leads
   section headers ("> RECENT") and marks the active row of a list or panel. It replaces
   bullets, chevron-in-circle affordances and leading icon chips. Text colour; accent only on
   the active row.
2. **Crop corners.** Four short L-shaped marks (6dp) at the corners of a container mean
   "selected / hero". Used on the selected project card, the chosen theme swatch and the
   onboarding step card. They replace elevation and tinted-fill selection. At most one
   element per view carries them.
3. **Rules and ticks.** 1dp hairlines separate; on Home and the onboarding hero a hairline
   may carry a tick scale (a small tick every 8dp) like a ruler. Nowhere else.

`appearance.motif` = `off | subtle | full`: `off` draws none of the supporting motifs and
keeps only the functional cursor states; `subtle` (default) draws prompt glyphs and crop
corners; `full` adds ticks and the ASCII empty-state art.

### 2.3 Where motifs appear

| Surface | Motif use |
|---|---|
| Home | header "> easyIDE" with a cursor that blinks at most 6 times then goes solid; section headers use the prompt glyph; selected project card has crop corners |
| Settings | active category has the solid block marker; section headers 11sp caps; modified-value dot; no card per setting |
| Extensions | mono publisher/name, version in mono, cell-fill bar for installs, hairline separators |
| Onboarding | step counter "1/4" in mono; the step card has crop corners; the step title is typed once at 60ms per character (skipped with reduce motion) |
| Workspace chrome | block marker on the active nav item and active tab (2dp accent bar), status strip in mono, focus = accent |
| Dialogs and sheets | none (they must be calm); destructive confirmations use text, not colour blocks |
| Loading | blinking cursor sweeping 8 cells (90ms step) after a 300ms delay |
| Errors | 2dp left rule in the error colour, a short mono code, one sentence, one action |

A screen shows **one to three** motif instances. Zero reads as bland, more than three reads as
costume.

## 4. Colour

Neutrals and syntax are as ADR 0019 (graphite dark, paper light, AMOLED, high contrast).
This document changes only how the accent is used and proposes its default.

1. **One accent, used for meaning.** It marks focus, the current item, the primary action,
   the caret and selection. It covers **5% of the pixels or less** on any screen.
2. **Signal colours are reserved** for state: success, warning, error, info, git states.
   The accent must stay at least 20 degrees of hue away from them (warn, do not block).
3. **No gradients, glows, blur or resting shadows.** Separation is tone plus hairline.
   Shadows exist only on things that float (menus, dialogs).
4. **Default accent (owner decision, section 12):** ADR 0019 chose iris (violet-blue) which
   reads as the generic "AI product" accent (research: purple/blue accents are the most
   common generated tell). Recommendation: a warm **signal orange** by default, keeping
   iris as a swatch. Candidate values: dark `#FF8A3D`, light `#B04600`; both are validated
   by the existing WCAG palette test before adoption. The Ember Night pack on the phone
   already shows this direction working.
5. **Swatches** offered in Settings: orange, iris `#7C8CFF`, violet `#B18CFF`, teal
   `#3CC9C0`, sky `#5CB8FF`, rose `#FF7AA8`, mono (foreground ink), plus "from theme",
   "from wallpaper" and a hex field. Custom values pass the contrast guard (an accent below
   4.5:1 on the panel or 3:1 on the editor background is nudged in lightness and the UI says so).

## 5. Typography

Two families, deliberately paired (both bundled, OFL-1.1, per 0019): **Geist** for prose and
titles, **Geist Mono** for anything that is code-like or measured.

| Role | Family | Size / weight | Rule |
|---|---|---|---|
| Screen title | Geist | 20 / SemiBold | one per screen |
| Section header | Geist Mono caps, tracking 0.4sp (Geist caps 0.6sp) | 11 / Medium | caps only here and for status tags, max 14 characters |
| Row title | Geist | 13 / Medium | sentence case |
| Row support | Geist | 12 / Regular, muted tone | max 2 lines |
| Body | Geist | 13 / Regular | |
| Code-like strings | Geist Mono | 12-13 / Regular | paths, branches, hashes, versions, byte and time values with units, keybinding chips, env pills, toast tags, settings values, step counters, ASCII art |
| Display (Home only) | Geist | 28 / SemiBold | |

- **Never set a paragraph in mono.** Never caps a button.
- At most **3 sizes and 2 weights per screen**. Hierarchy comes from tone (text, muted,
  faint) before size, and from size before weight.
- **Numerals:** Geist has `tnum` and is used with it for counts, versions and times. Geist
  Mono is monospaced, so its digits are tabular by construction; it has no `tnum` and no
  `zero` feature (verified in the bundled fonts), so slashed zero cannot be requested by a
  feature flag. Whether its default zero is distinguishable from O is checked visually.
- **Glyph coverage of Geist Mono (verified):** box drawing (U+2500 range), full and half
  block, shades, single angle quote (U+203A), arrows, bullet and middle dot, ellipsis,
  right-pointing triangle are present. **Not present:** U+25AE (block cursor), U+2713
  (check mark). Use drawn shapes and icons for those; ASCII art may use box drawing.

## 6. Iconography

- **Keep Material Symbols** (Apache-2.0, already bundled) for generic verbs: close, add,
  search, more, back, check, edit, delete, copy, share, refresh, info, warning, chevrons.
- **Custom-draw** the glyphs that carry identity, as one vector set in one module:

| Group | Glyphs |
|---|---|
| Core | prompt (`>_`), block cursor, command palette, settings as sliders (no gear), swatch, keycap, density (three ruled lines), dock (rectangle with edge bar) |
| Work | project (folder with corner tick), new project, terminal, language server (braces + dot), agent (chevron + spark) |
| Git | branch, commit, diff, stash, remote/sync |
| System | sandbox (bracketed box), environment (stacked layers), root mode (`#`), extension pack (brick), install, theme (half-filled square) |

- **Style rules:** 24dp grid, 20dp live area, 1.5dp stroke, butt/square terminals, mitre
  joins, 45-degree chamfers of 2dp instead of round corners, optical centring, one tint
  colour, no two-tone. Active state is one block filled (the cursor motif), not an
  outlined/filled swap.
- **Packs** supply monochrome SVG (24 grid, no gradients, no embedded raster); the shell
  converts it to a vector and tints it. Colour icons from packs are rejected by `validate`.

## 7. Motion

| Use | Duration | Easing |
|---|---|---|
| Press feedback | 90ms | linear |
| Standard state change | 140ms | enter (0.2, 0, 0, 1) |
| Pane / navigation | 220ms | enter; exit (0.3, 0, 1, 1) |
| Ceiling for anything | 320ms | |

- Overshoot only on pane snap (spring damping 0.9).
- **Cursor blink** is a hard step (no fade): 530ms on, 530ms off; resets to solid for 600ms
  on any input. It is the only permitted loop, allowed on the focused caret and the loading
  sweep, and for at most six cycles on Home and empty-state headers.
- **Never animate:** caret movement, scrolling, terminal output, list reorder or data
  reflow, status or error colours, theme switches (instant), anything at idle, staggered
  list entrances.
- Reduce motion (system setting or `appearance.reduceMotion`) sets every duration to 0 and
  keeps the cursor solid.

## 8. Haptics

| Event | Android constant | Level |
|---|---|---|
| Accessory key tap | KEYBOARD_TAP | full |
| Modifier latch | VIRTUAL_KEY | full |
| Pane snap, segmented step | CLOCK_TICK | full |
| Drag pick-up | GESTURE_THRESHOLD_ACTIVATE | full |
| Toggle | CONTEXT_CLICK | subtle |
| Long-press menu | LONG_PRESS | subtle |
| Success (commit, install finished) | CONFIRM | subtle |
| Rejected or blocked action | REJECT | subtle |
`appearance.haptics` = off | subtle | full. **No UI sounds**; the terminal bell is its own setting.

## 9. Copy voice

Terse, sentence case, no first person, names the object, gives the next step. No exclamation
marks, no "Oops", no emoji, no "please", no "successfully".

| Do | Do not |
|---|---|
| Couldn't clone: host unreachable. Retry | Oops! Something went wrong |
| No projects yet. > New project | It looks like you don't have any projects yet! |
| Delete "api-server"? 214 files, not recoverable. | Are you sure you want to delete this? |
| Committed 3 files to main | Success! Your changes have been committed |
| Install python 1.4? Needs network and 84 MB. | Let's get you set up! |
| Sandbox stopped. Restart | Uh oh, the sandbox crashed |
| Waiting for the language server... | Hang tight while we work our magic |
| Discard 4 changes (button) | Yes, I'm sure |
| Theme not found: "night" | Invalid input |
| Saved | Your settings have been saved successfully! |
Irreversible actions require typing the object's name. Never claim isolation for the sandbox
in any string (repo rule).

## 10. Patterns for states

| State | Pattern |
|---|---|
| Empty | at most five lines of box-drawing/ASCII art in a muted token, one sentence, one command-style action ("> New project"). No illustration, no big icon, no centred hero |
| Loading | nothing for 300ms; then a cursor sweeping eight cells, or a determinate cell-fill bar; lists show a static two-bar skeleton without shimmer |
| Error | inline, 2dp left rule in the error colour, mono code (`E_NET`), one sentence, one action; modal only when the app cannot continue |
| Toast | bottom-left, mono tag (`ok`, `warn`, `err`) + sentence, 3s (6s with an action), one at a time, dismissable without swiping |
| Dialog | small radius, hairline, overlay tone, title 15/Medium, no icon at the top; destructive: ghost cancel left, danger text right; on compact a dialog becomes a bottom sheet |

ASCII empty state example (drawn in a muted token):
```
 +----------+
 | >        |
 |   [_]    |
 +----------+    no projects yet
 > New project
```

## 11. Logo and app icon

- The current purple `</` is generic. New mark: a 45-degree chevron with square terminals
  (the prompt) followed by a solid block in the accent, on one 8-unit grid.
- Adaptive icon: graphite background (`#0E1014`), the mark in the foreground layer, a
  monochrome layer for themed icons (Android 13+). No gradient, no glow.
- The same mark is the favicon, the splash (the block blinks once), and the watermark of the
  empty editor.

## 12. Open decisions for the owner

1. **Default accent**: signal orange (recommended) or keep iris.
2. **Primary motif**: the block cursor (recommended) versus a plainer identity with no
   motif system. The motif needs the "no idle loop" rule of ux-overhaul to carve out the
   cursor blink.
3. **Custom icon set**: needs a draughtsperson pass; the 24 glyph list is the brief. Until
   then Material Symbols fill in (the resolver falls back per glyph).
4. **Per-environment accent** as a label colour is proposed as a convenience; it must never
   be described as isolation.
