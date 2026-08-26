# Feature: Design System — Architecture

Covers the visual system for the native Compose layer: themes, color, icons, imagery, animation, and responsive layout. Built on Material 3 per [decision 0004](../decision/0004-material3-design-system.md). Scoped to native Compose screens (Home, Workspace chrome, Settings, onboarding) — Theia's own WebView content has its own separate theme system (VS Code-style themes), coordinated with this one only at the color-token level (see "Cross-surface coherence" below). See [tracker.md](tracker.md) for status.

## Themes

A curated, user-selectable set — not an unbounded custom-theme editor for v1 (that's a natural future-extension surface, not a current requirement):

| Theme | Source |
|---|---|
| **System default** | Follows Android's light/dark system setting |
| **Light** | Material 3 `lightColorScheme()` from the app's seed color |
| **Dark** | Material 3 `darkColorScheme()` from the app's seed color |
| **Dynamic (Material You)** | Android 12+ only — `dynamicLightColorScheme()`/`dynamicDarkColorScheme()`, derived from the user's wallpaper via `dynamicColorScheme` |
| **AMOLED Black** | A dark-theme variant with true-black (`#000000`) surface tokens instead of Material's default dark-gray surfaces — meaningful battery savings on OLED tablet panels, common request for dev tools used for long sessions |
| **High contrast** | Accessibility variant — boosted contrast ratios beyond Material 3 defaults, for low-vision users |

All theme definitions live as **`ColorScheme` token sets**, not scattered hex literals — one `Theme.kt`/`Color.kt` pair is the single source of truth. Any component reading a raw color value instead of `MaterialTheme.colorScheme.*` is a bug (maps directly to the "no hardcoding" coding standard in `.claude/CLAUDE.md`).

## Color system

- **Seed-color-driven**: Material 3's tonal palette generation takes one seed color and derives the full `ColorScheme` (primary/secondary/tertiary/surface/error tones, each with on-color pairs) — avoids hand-picking dozens of individually-inconsistent colors.
- **Semantic tokens only in component code**: components reference `colorScheme.primary`, `colorScheme.surfaceVariant`, etc. — never a literal hex value — so a theme switch or seed-color rebrand doesn't require touching component code.
- **Status/signal colors** (git-dirty indicator, error/warning banners, sandbox-backend indicator for proot vs. chroot per [decision 0002](../decision/0002-sandbox-backend-proot-default-chroot-optin.md)) get their own named semantic tokens (e.g. `colorScheme.error`, a custom `LocalExtendedColors.gitDirty`) rather than inline colors, so they adapt correctly across all six themes automatically.

## Icons

**Material Symbols** (Google, Apache-2.0) as the default icon set — matches Material 3 natively, both "Outlined" (default/inactive state) and "Filled" (selected/active state) variants used consistently for the same toggle-style meaning across the app (e.g. a stage-visibility toggle, bookmark, star).

Custom icons (app logo, sandbox-backend indicators, Claude Code-specific iconography) live as vector drawables in one dedicated icons module — never inline path data duplicated per screen — so they themselves pick up `colorScheme` tints rather than being baked-in-color raster assets.

## Imagery

- **Prefer vector/illustration assets over raster photos** for in-app imagery (empty states, onboarding illustrations) — vectors re-tint correctly across all six themes and scale losslessly across tablet screen densities; raster photography looks inconsistent against a switchable dark/AMOLED/high-contrast theme and bloats APK size.
- **Project thumbnails** (if added to the Home project-list screen) are the one legitimate raster-image use case — user-supplied or auto-generated (e.g. a snapshot of the file tree), not stock photography.
- No stock photography in default UI chrome — if a "welcome" or empty-state illustration is wanted, use a themed SVG illustration set (single source, re-colored via theme tokens) rather than bitmap art.

## Animation

Compose's animation APIs (`animateColorAsState`, `AnimatedVisibility`, `Crossfade`, shared-element transitions for navigation) drive all native-layer motion, following Material's motion guidelines: motion communicates state change (a stage opening, a tab switching, a theme changing) rather than being decorative.

**Accessibility requirement, not optional**: respect Android's system "remove animations" setting (`ValueAnimator.areAnimatorsEnabled()` / animator duration scale) — all animation code must check this and degrade to instant transitions when the user has disabled system animations, not just when they haven't explicitly configured this app.

Theia's own WebView content has its own CSS-driven animation (tab switching, panel resize) — out of scope here, governed by Theia's theme, not this design system.

## Responsive design

Tablets vary widely in size and get used in split-screen/multi-window Android modes, so layout must be width-class-driven, not device-specific:

- Use Compose's **window size class APIs** (`androidx.compose.material3.adaptive` / `androidx.window`) to branch layout by `Compact`/`Medium`/`Expanded` width, not hardcoded device checks.
- **Expanded width** (typical full-screen tablet): Home shows a multi-column project grid; Workspace shows left+main+right stages simultaneously.
- **Medium/Compact width** (split-screen multitasking, smaller tablets, foldables in some postures): Home falls back to a single-column list; Workspace collapses side stages behind toggle affordances rather than showing three columns that would each become too narrow to use.
- Orientation changes (rotation) must not lose state — Workspace's open tabs/stage visibility survive rotation via standard Compose state-hoisting/`rememberSaveable`, not full screen re-init.

## Cross-surface coherence (native Compose <-> Theia WebView)

Two independent theming systems exist (per [decision 0004](../decision/0004-material3-design-system.md)'s consequence) — kept visually coherent by aligning color *tokens*, not by sharing rendering technology:
- When the user picks a native theme (e.g. "Dark"), the app should set Theia's own theme to its closest equivalent (Theia ships light/dark/high-contrast VS Code-style themes) via Theia's theme-switching API/preference, so switching one switches the other.
- Exact seed-color-to-Theia-theme mapping is an implementation detail to finalize once Theia integration starts (tracked in tracker.md), not a structural open question — the mechanism (Theia has a settable theme preference) is already confirmed to exist.

## Open questions

- Exact seed color(s) / brand palette — not yet chosen; needs either a stakeholder decision or a placeholder default (e.g. a single blue/teal seed matching typical dev-tool conventions) to unblock initial scaffolding, swappable later without touching component code since everything reads from `colorScheme` tokens.
- AMOLED Black theme's exact surface-token overrides need real on-device visual QA (Material 3's tonal system doesn't have a built-in "pure black" mode — this is a manual override layer).
- High-contrast theme's exact contrast ratios should be validated against WCAG AA/AAA targets once built, not just asserted.
