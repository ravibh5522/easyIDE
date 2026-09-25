# 0026 - Identity ("Block"), UI kit as the only widget layer, and appearance properties

Status: Accepted (2026-09-24, owner sign-off). Design: [docs/ui-redesign/](../ui-redesign/arch.md).
Amends [0019](0019-visual-identity.md) (accent default, motif); keeps its palettes, fonts and
token model.

## Context

The editor screen is built from a dense chrome kit on editor tokens and reads as designed.
About 8,600 lines of other UI call stock Material widgets directly, with no shared
components, no rules for spacing, typography, copy or states, and no user-facing design
properties. The owner's verdict: those screens look AI-generated. Research on reference
products lists the tells: uniform rounded cards, gradient or violet-blue accents, unchosen
system fonts, identical padding, stock icons in circles, emoji, generic empty states,
"Oops" copy, uniform easing. ADR 0019's default accent (iris) is exactly the common tell.

## Decision

1. **Kit only.** A `ui/kit` package of about twenty primitives is the only place raw Material
   widgets may be used; a CI import ratchet fails any new use elsewhere. The kit reads the
   token model (ThemeTokens/editor colours) and a new `UiMetrics`.
2. **Identity "Block".** Primary motif: the block cursor (drawn, three states plus a cell-
   fill progress form: "the accent marks where you are"). Supporting: the prompt glyph and
   crop corners. Geist and Geist Mono are kept, with mono for code-like and measured
   strings. One accent covering at most 5% of pixels; no gradients, glows or resting
   shadows. A fixed copy voice. Custom icon set for identity glyphs, Material Symbols for
   generic verbs. Fifteen pass/fail anti-generated checks in the rules.
3. **Default accent** changes from iris to a warm signal orange (dark `#FF8A3D`, light
   `#B04600`, validated by the palette contrast tests); iris stays as a swatch.
   Owner accepted 2026-09-24.
4. **Appearance properties** (`appearance.*`, `shell.*`): accent, density, corners, UI scale,
   font pairing, chrome contrast, motif, cursor blink, reduce motion, haptics, icon style,
   handedness, layout preset, navigation position. Validated and clamped, layered
   (user, environment, project with restricted keys), resolved into immutable metrics via
   composition locals, shareable as chrome packs (`*.easyide-chrome.json`). Defaults equal
   today's constants so nothing shifts on upgrade.
5. **The cursor blink** is the single permitted idle loop; it needs a carve-out in the
   ux-overhaul "no idle animation" rule and stops under reduce motion or a screen reader.

## Alternatives considered

- **Restyle the Material theme only** - does not remove stock structure, density or copy.
- **Adopt a third-party design system** - trades our identity for another generic one and
  adds a dependency to maintain.
- **A heavier motif system** (Phosphor scanlines, Dot Matrix fonts, Field Unit hardware
  metaphors) - novelty costs legibility or requires a full illustration set.
- **No user properties** - contradicts the customization requirement and leaves density and
  scale fixed for users who need them (accessibility).

## Consequences

Easier: one place to change the look, user-customizable appearance, extension UI that
matches by construction, enforceable review rules, screenshot-tested components.
Harder: a migration of about 8,600 lines plus the workspace dialogs; a new lint and ratchet
to maintain; the kit must not grow into a second Material.
Requires: a screenshot-test spike (Roborazzi compatibility with the current AGP/Kotlin is
unverified), a draughtsperson pass for the custom icons, and the owner's call on the accent.
Persistence uses explicit string ids (R8 renames enums in release builds).
