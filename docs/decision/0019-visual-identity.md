# 0019 - Visual identity: graphite neutrals, iris accent, Geist type, one token system

Status: Proposed (accent default and motif amended by [0026](0026-identity-ui-kit-and-properties.md), also Proposed) (implemented in `ui/theme/`; the accent hue awaits owner confirmation)

## Context

ux-overhaul root cause 3 ("no visual identity"): native screens used Material 3's baseline purple `#6750A4` with only `primary` overridden, the workspace was a hex-for-hex copy of VS Code Dark+, the two palettes were defined separately (`Color.kt`, `Theme.kt`, `EditorColors.kt`), AMOLED and high contrast were partial overrides, the terminal was fixed black, stock Roboto/monospace everywhere and shapes/spacing were per-file literals. This is ADR-A in [ux-overhaul/arch.md](../ux-overhaul/arch.md) Pillar 3. It is hard to reverse because every surface, the settings theme picker and the future VS Code theme import ([extension-sdk/lld/customization.md](../extension-sdk/lld/customization.md) sec 8) are written against the token names.

## Decision

1. **One token system.** `ThemeTokens` (the arch doc's "EasyIdeColors") holds one colour per `ColorToken` (~80: surfaces, content, accent/focus, status, git, 8 lanes, decorations, terminal fg/bg/cursor + ANSI-16) plus a `SyntaxColors` per `SyntaxRole`. Material's `ColorScheme` (`toColorScheme`) and the workspace `EditorColors` (`toEditorColors`) are both projections of it, applied together by `EasyIdeTheme`. Built-in themes are small `Palette`s (neutral ramp, accent, signals, lanes, ANSI, syntax, tint alphas); one exhaustive derivation table (`Palette.toTokens`) turns a palette into tokens.
2. **Palettes.** Dark: cool graphite `#0E1014` editor < `#13161B` panel < `#181C22` raised < `#1F242C` overlay, hairline `#262C35`. Light: warm paper `#FBFAF8` / `#F3F2EE` / `#ECEAE4`, hairline `#E4E2DC`. AMOLED: graphite with the surface family at true black. High contrast: real dark (on black) and light (on white) sets for chrome, editor and terminal, chosen by the system night setting. Syntax is a custom low-saturation palette tuned to these neutrals, not Dark+. Status bar and rail are surface-coloured; separation is tonal.
3. **Accent: iris** `#7C8CFF` (dark/AMOLED), `#4355D6` (light), `#A6B1FF` / `#2A37A8` (high contrast). Used only for focus, the active tab bar, rail indicator, cursor, selection (22% alpha), tree pill (tint), primary action and progress. Under DYNAMIC the wallpaper primary replaces only the accent; neutrals and syntax stay fixed so contrast never depends on a wallpaper.
4. **Semantic tokens**: `gitAdded #4ADE80`, `gitModified #FBBF24`, `gitDeleted #F87171`, `gitConflict #F472B6`, `info #60A5FA` (dark values; light/HC have darker/brighter counterparts), untracked = added. An 8-colour categorical lane palette replaces syntax-colour borrowing in the commit graph.
5. **Type**: Geist (UI; 400/500/600) and Geist Mono (editor, terminal, markdown code; 400/700), dense IDE scale 11/12/13/15/20/28sp mapped onto Material roles, 11sp caps section headers with +0.6sp tracking, tabular figures in the status bar.
6. **Layout tokens**: 4dp `Spacing`, `Radius` 4/6/10/16 (Material `Shapes` built from it), `Elevation` (shadow only for floating layers), `Stroke` (hairline, 2dp accent bar), `IconSize`, `ControlSize`.
7. **VS Code theme seam**: `ThemeColorMap.BY_VSCODE_KEY` is the only VS Code workbench key -> `ColorToken` table (later entry wins between keys that feed one token; `DERIVED` fills gutter/tab/terminal etc. from the colour a theme did set). `VsCodeThemeMapper.map(VsCodeColorTheme, base)` is pure: colours, `tokenColors` collapsed onto roles through `ScopeRules.prefixesFor` (longest selector, later rule wins ties, per customization.md 8.2), validated `semanticTokenColors`, plus unmapped keys and invalid entries for `validate`. Reading theme files is the next wave.

### Why iris over mint

Measured with WCAG 2.x relative luminance (now enforced by `BuiltInPaletteContrastTest`):

| | on `#0E1014` | on `#FBFAF8` | notes |
|---|---|---|---|
| iris `#7C8CFF` | 6.39 | 2.85 | clears AA as text on every dark surface (5.24 on overlay) |
| mint `#3DDBB5` | 10.88 | 1.68 | near-unusable on paper without a very dark variant |
| iris-600 `#4355D6` (light) | 3.18 | 5.74 | the light-theme accent |
| mint-dark `#007A5E` (light) | 3.58 | 5.10 | |

Both work in dark, but mint (hue ~165) sits next to `gitAdded`/success green (~142): an accent-coloured focus mark and an "added" file would read alike in the tree and source control. Iris is the one hue no semantic token uses, keeps a single hue family across light/dark, and `#0E1014` on iris (onAccent) is 6.4:1.

### Fonts (verified 2026-09-24 from primary sources)

| Font | Source | Version | License | Bundled |
|---|---|---|---|---|
| Geist | [vercel/geist-font](https://github.com/vercel/geist-font) release `v1.7.2` (2026-06-01), `Geist/ttf/` | font version 1.800 | OFL-1.1 (repo `OFL.txt`, GitHub license API; no Reserved Font Name declared) | Regular, Medium, SemiBold: 236 KB |
| Geist Mono | same release, `GeistMono/ttf/` | font version 1.700 | OFL-1.1 | Regular, Bold: 173 KB |

Release zip SHA-256 `7fc800d2ac6b92844895196e5041aca55d814c15db70c44f79b3b83ab82b04e2`. Maintenance: last release 2026-06-01, last push 2026-07-14, not archived. Bundle processing: TrueType hinting stripped with fontTools `pyftsubset --unicodes='*' --no-hinting` (all glyphs and layout features kept; Android does not use TT hinting at tablet densities), 680 KB -> 409 KB on disk, ~200 KB compressed in the release APK. The OFL text ships at `assets/licenses/Geist-OFL.txt` and in each font's name table. Files: `app/src/main/res/font/geist_{regular,medium,semibold,mono_regular,mono_bold}.ttf`.

## Alternatives considered

- **Mint `#3DDBB5` accent** - collides with the added/success green; needs a separate dark variant for light theme (1.68:1 on paper).
- **User-chosen accent with a curated default** - deferred, not rejected: `Palette.accent` is one field, so an `appearance.accentColor` setting is a small change once the settings schema wave wants it.
- **Keep seed-generated M3 schemes and override a few roles** - the source of the purple look; tonal palettes from one seed cannot express graphite neutrals plus one accent.
- **Inter (rsms/inter v4.1, OFL-1.1) + JetBrains Mono (v2.304, OFL-1.1)** - both fine licenses, but the last releases are Nov 2024 and Jan 2023; Geist ships sans and mono from one actively maintained repo with one license and matching metrics.
- **Variable fonts** - one file per family, but weight axes on API 26-27 and Compose `FontVariation` add risk for ~100 KB saved; static instances are predictable.
- **Latin-only subsetting** - would save ~40% more but drops Cyrillic/box drawing that the terminal and editor need.
- **Separate chrome and editor palettes kept in step by convention** - what existed; it drifted within a week.

## Consequences

- Chrome and editor cannot disagree; a theme is a `Palette` (built in) or a `MappedTheme` (imported) and every surface follows.
- `ColorToken` names are now a contract for the theme import, `ThemeColorMap`, `validate` output and customization keys; renaming one is a breaking change for extension themes.
- Contrast is a test, not a review item: any palette edit that drops a text pair below AA (AAA for high contrast) fails `BuiltInPaletteContrastTest`.
- The terminal palette is written into Termux's process-wide default scheme (`TerminalTheme`), and existing emulators are reset once per palette change; a program's OSC 4 colour overrides are lost at that moment (only on a theme change).
- APK +~200 KB compressed for fonts.
- The window/splash colours in `res/values*/colors.xml` must equal the editor surfaces; `ThemeTokensTest` checks this.
- Not done here (other Pillar 3 items): motion changes, file-type icons, living status bar content, welcome view, resizable panes.
