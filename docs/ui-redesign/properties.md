# UI Redesign - Custom Properties

Status: PROPOSED (2026-09-24). Part of [arch.md](arch.md). Decision record: [0026](../decision/0026-identity-ui-kit-and-properties.md).
Depends on the layered settings system (user < environment < project, per-language) and the
token model of [0019](../decision/0019-visual-identity.md).

"Custom properties" are the design values a user (or a pack) can change, the way CSS custom
properties let a stylesheet change a design without touching components. Components never
hardcode a value that appears here; they read it through the kit, so one change reaches every
screen, including extension views.

## 1. Three levels

| Level | What | Who edits | Where |
|---|---|---|---|
| **Properties** (about 18) | coarse, named knobs: accent, density, corners, scale, font pairing, motif, haptics, layout preset... | the user, in Settings -> Appearance | `appearance.*` and `shell.*` keys |
| **Tokens** (about 80 colours, plus space/radius/stroke/control/type/motion scales) | the fine values a property expands into | expert users, packs | `workbench.colorCustomizations`, `appearance.tokens`, chrome packs |
| **Internal constants** | structural values (4dp grid base, touch floor 44dp, 320ms motion ceiling) | nobody at runtime | code |

Properties are what Settings shows. Tokens are what a chrome pack or a designer edits.
Internal constants are the guard rails: no property or token can violate them (a touch target
cannot drop below 44dp; nothing animates longer than 320ms).

## 2. Property schema

Keys follow the existing settings conventions (typed, scoped, validated, per-key reset).
Scope letters: G global, E environment, P project (see section 5 for what P and E may set).

| Key | Type / values | Default | Scope | Effect | Consumed by |
|---|---|---|---|---|---|
| `appearance.themeMode` | system, light, dark, amoled, dynamic, highContrast | system | G | base palette | `themeTokensFor` (exists) |
| `appearance.accent` | `theme` or `wallpaper` or `#RRGGBB` | theme | G,E,P | overrides the accent token family | `AccentDerivation` |
| `appearance.density` | compact, comfortable, spacious | comfortable | G | spacing 0.85 / 1 / 1.2 (4dp snapped); row heights 40 / 48 / 56 (compact width); tab heights 32 / 36 / 44; panel default widths 260 / 280 / 300 | `Metrics` |
| `appearance.corners` | sharp, soft, round | soft | G | radii sharp 0/2/2/4; soft 4/6/10/16; round 8/12/18/28 | `easyIdeShapes(metrics)`, kit |
| `appearance.uiScale` | 0.85 to 1.30, step 0.05 | 1.0 | G | scales dp and sp together | root `LocalDensity` |
| `appearance.fontPairing` | geist, monoChrome, system | geist | G | UI family: Geist + Geist Mono / Geist Mono for chrome / platform sans + mono | `EasyIdeFonts`, typography |
| `appearance.chromeContrast` | soft, normal, high | normal | G | hairline alpha and muted/faint text mix; independent of the `highContrast` mode | `Palette.tune` |
| `appearance.motif` | off, subtle, full | subtle | G | supporting motifs (identity 2.2) | kit (`LocalFeel`) |
| `appearance.cursorBlink` | bool | true | G | the blink loop; forced off by reduce motion or a screen reader | `CursorBlink` |
| `appearance.reduceMotion` | system, on, off | system | G | all durations 0 | `LocalMotion` |
| `appearance.haptics` | off, subtle, full | subtle | G | haptics table | `Haptics` helper |
| `appearance.iconStyle` | ei, material | ei | G | custom versus Material glyph set (resolver falls back per glyph) | icon resolver |
| `appearance.handedness` | right, left | right | G | mirrors sheets, dialog button order and the compact navigation side hint | shell, kit |
| `appearance.projectGlyph` | 1-2 characters | derived monogram | P | project mark on Home and tabs | Home, tree |
| `appearance.chromeTheme` | chrome pack id | empty | G | applies a pack as defaults *under* the user's own values | pack loader |
| `appearance.tokens` | object of dotted token keys | empty | G | expert override, ranges in 4 | `Metrics`, `Motion` |
| `shell.layout.preset` | auto, focus, classic, workbench, terminalFirst, or a pack preset id | auto | G,P | default arrangement per size class and posture | shell |
| `shell.navigation.position` | auto, left, right, bottom | auto | G | navigation surface placement (auto = bottom on compact, rail otherwise) | shell |
| `shell.navigation.labels` | auto, always, never | auto | G | labels under nav icons | shell |

Extension-related layout keys (`shell.navigation.order`, `.hidden`, `.pinned`,
`shell.containers.*`) are in [extension-ui.md](extension-ui.md) section 6.

## 3. Validation

- An invalid or out-of-range value falls back to the default and is listed in the settings
  JSON editor as an invalid entry, exactly like invalid theme customizations today.
- Hex accepts `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA` through the existing `parseHexColor`;
  alpha is dropped.
- **Contrast guard:** an accent below 4.5:1 on the panel colour or 3:1 on the editor
  background is nudged in lightness until it passes; the UI shows the adjusted colour with a
  one-line note ("adjusted for contrast"). The guard applies in every scope and to chrome
  packs.
- **Hue warning:** an accent within 20 degrees of a signal hue (success, modified, error,
  info, conflict) warns and does not block.
- `appearance.uiScale` and `fontScale` clamp to their range; `appearance.density` never
  reduces a touch target below 44dp.

## 4. Expert tokens: `appearance.tokens`

A flat object of dotted keys. Unknown keys are ignored and listed, never an error.

| Token group | Range | Example |
|---|---|---|
| `space.*` | 0 to 48 dp | `space.m: 10` |
| `radius.*` | 0 to 28 dp | `radius.s: 2` |
| `stroke.*` | 0.5 to 2 dp | `stroke.hairline: 1` |
| `control.*` | 24 to 64 dp | `control.row: 30` |
| `motion.duration.*` | 0 to 600 ms (clamped to 320 for anything but the cursor) | `motion.duration.press: 90` |
| `type.size.*` | 10 to 28 sp | `type.size.body: 14` |

Colour tokens are edited through `workbench.colorCustomizations` (exists) with names from
`ColorToken` (mechanical mapping: `EDITOR_BACKGROUND` is `editor.background`,
`ACTIVITY_BAR_ACTIVE_BORDER` is `activity.bar.active.border`) or any key in
`ThemeColorMap.BY_VSCODE_KEY`.

## 5. Scope safety

A cloned repository can carry `.easyide/settings.json`; an extension can ship defaults. Neither
may restyle the whole app.

| Scope | May set |
|---|---|
| Project (P) | `appearance.accent`, `appearance.projectGlyph`, `shell.layout.preset`, `workbench.editorAssociations` |
| Environment (E) | `appearance.accent` only, as a **label colour** for the environment. It is decoration, not a boundary, and copy must never call it isolation. |
| Extension defaults | `configurationDefaults` for `appearance.*` are ignored; a pack that wants to restyle ships a **chrome pack** the user applies |
| User (G) | everything |

The contrast guard applies to every scope.

## 6. Resolution into Compose

```
SettingsStore.resolve(appearance.*, env, project)   // layers: user < environment < project
    -> Appearance (immutable, clamped, validated)
        -> Metrics(space, radius, stroke, control)     // density x corners x tokens
        -> Motion(reduce, blink, durations, easings)
        -> Feel(haptics, motif, iconStyle, handedness)
        -> ThemeTokens.withAccent(...) and withOverrides(...)
EasyIdeTheme(appearance, ...) provides
    LocalMetrics, LocalMotion, LocalFeel, LocalEditorColors, MaterialTheme (bridge)
    LocalDensity(density * uiScale, fontScale * uiScale)
    easyIdeShapes(metrics), easyIdeTypography(fontScale, pairing)   // functions, not constants
Kit components read only through one accessor:  Kit.space.m, Kit.radius.s, Kit.colors, Kit.type
```

- **Back-compat.** `Spacing`, `Radius`, `ControlSize` stay as the comfortable/soft base
  tables inside `Metrics`, so existing call sites keep compiling. A unit test asserts that
  the default `Appearance` yields exactly today's constants, so the editor screen is
  pixel-unchanged until it is deliberately changed.
- **Cheap changes.** Metrics, motion and feel are small immutable objects held in static
  composition locals: they change rarely and recompose the tree once when they do (intended).
  The editor text is not re-laid out by an accent, corner or density change.
- **Persistence.** Property values are stored by explicit string id, never by enum `name` or
  ordinal (R8 renames both in release builds).

### 6.1 Accent derivation

`ThemeTokens.withAccent(accent)` re-derives the whole accent family through the same
tint/alpha rules as the built-in palettes:

| Token | Derivation |
|---|---|
| `ACCENT` | the chosen colour (contrast-guarded) |
| `ON_ACCENT` | whichever of background or foreground contrasts better (AA) |
| `FOCUS_BORDER`, `TAB_ACTIVE_BORDER`, `ACTIVITY_BAR_ACTIVE_BORDER`, `CURSOR` | the accent |
| `SELECTION` | accent at the emphasis selection alpha (22%) |
| `LIST_ACTIVE_SELECTION` | accent at the list-selection alpha |
Neutrals, syntax colours and the terminal ANSI palette are never touched by the accent; the
terminal cursor follows the accent unless a theme or customization sets it. Under DYNAMIC,
`accent = wallpaper` keeps today's behaviour.

### 6.2 Precedence (low to high)

built-in `Palette` < contributed colour theme (`workbench.colorTheme`) < `appearance.accent`
(when not `theme`) < `appearance.chromeContrast` < `workbench.colorCustomizations` and its
`[Theme]` block < forced `highContrast` mode.
An explicit per-token entry beats a coarse knob; a theme's own accent is replaced only when
the user chose one; the accent never overrides syntax or terminal colours.

## 7. Settings UI: Appearance

Appearance is a document (`easyide://settings/appearance`). Top of the page is a **live
preview** of a Home card, a list group, a dialog and an editor snippet that updates as
properties change. Below: swatch row (accent), segmented controls (density, corners,
motif, haptics, handedness), sliders (UI scale), font pairing chooser, toggles (cursor
blink, reduce motion), then "Layout" (preset, navigation position and labels). Each row has
the modified dot and a per-row reset; a "Reset appearance" action is at the bottom; "Edit as
JSON" opens the same layer editor as other settings. Search finds every property by name and
keyword.

## 8. Chrome packs

A **chrome pack** is a shareable bundle of properties and tokens: a JSON file
`*.easyide-chrome.json`, or an extension contributing it (`contributes.chromeThemes:
[{id, label, path}]`, no capabilities, global scope).

- **Apply:** `appearance.chromeTheme = <id>` applies the pack as a layer *below* the user's
  own values, so user-set keys always win.
- **Export:** writes only the non-default resolved values and never secrets; also included
  in the existing settings profile export.
- **Import:** shows a before/after preview and the list of ignored keys, then applies.
- **Validate:** `easyide-ext validate` reports unknown keys, out-of-range values and
  contrast failures.

### 8.1 Token file format

Designer-readable, modelled on the W3C design-token shape (`$value` / `$type`):

```json
{ "$schema": "https://easyide.dev/schema/chrome-theme-1.json",
  "$name": "Paper Terminal", "$extends": "builtin:graphite-dark", "$version": 1,
  "appearance": { "density": "compact", "corners": "sharp", "motif": "full" },
  "color": {
    "accent":            { "$value": "#3CC9C0", "$type": "color" },
    "editor.background": { "$value": "#0B0D10" },
    "selection":         { "$value": "alpha({accent}, .22)" } },
  "syntax": { "keyword": "#B18CFF", "comment": "#5A6270" },
  "space":  { "m": 10 }, "radius": { "s": 2 }, "stroke": { "hairline": 1 },
  "motion": { "duration": { "press": 90, "standard": 140 } } }
```

Mapping to existing code: `color.*` goes through `ThemeColorMap` and `ThemeCustomizer`;
`syntax.*` uses `SyntaxRole` names; `space`, `radius`, `stroke`, `control`, `motion`, `type`
become `appearance.tokens`; `appearance` is the table in section 2. Values may be `#RGB`
to `#RRGGBBAA`, a reference `{accent}`, or `alpha({accent}, .22)`. Units are implicit per
category (dp, sp, ms).

## 9. Tests

- `Appearance` defaults produce exactly today's `Spacing/Radius/ControlSize` values.
- Metrics: density scales are monotone, the touch floor never drops below 44dp, corner radius
  is clamped to half the control height, uiScale and fontScale clamp.
- Codec: unknown keys ignored, string ids stable, round trip, invalid values fall back.
- Accent: 200 random hues keep ON_ACCENT/ACCENT and the focus ring at AA (AAA in high
  contrast); the contrast guard nudges a failing accent; hue warning triggers.
- Typography: line height is at least 1.15 of the size for every role at every scale.
- Scope safety: a project layer that sets a forbidden key is ignored and reported.

## 10. Open questions

1. Is `comfortable` (today's numbers) the right default, or should the default be denser
   given the editor screen's density? Proposed: keep, so nothing shifts on upgrade.
2. Should font pairing ever allow user-supplied fonts? Proposed: not in v1; a bundled font
   needs the licence and maintenance check the repo rules require.
3. Should `appearance.handedness` also swap the side of the navigation rail on tablets?
   Proposed: no; it only affects sheets, dialog buttons and the compact bar's "More" side.
