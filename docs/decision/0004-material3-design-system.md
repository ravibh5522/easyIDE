# 0004 - Material 3 as the native Compose design system foundation

Status: Accepted

## Context

The native Compose layer (Home, Workspace chrome, Settings, onboarding — see [/docs/ui-shell/arch.md](../ui-shell/arch.md)) needs a responsive UI with good color combinations, multiple themes, background colors, animation, and icons. Rather than designing a bespoke design system from scratch, Jetpack Compose ships an official, actively maintained design system built for exactly this.

## Decision

Adopt **Material 3** (`androidx.compose.material3`) as the foundation for the native Compose UI: `ColorScheme`-based theming, dynamic color (Android 12+ wallpaper-derived palettes via `dynamicColorScheme`), and Material's motion/animation specs as the default, with a small curated set of built-in themes layered on top (see [/docs/design-system/arch.md](../design-system/arch.md) for the actual palette/theme list and icon set).

## Alternatives considered

- **Bespoke custom design system** (hand-rolled color tokens, custom animation curves, custom icon set) — maximum control and differentiation, but is a large, ongoing design+engineering investment to reach the accessibility (contrast ratios, dark-mode correctness) and polish Material 3 provides out of the box. Rejected: not justified for a developer tool where the win is in editor/terminal UX, not novel visual branding.
- **Wrapping Theia's own web-based theming** for the native Compose chrome too (i.e., render Compose screens as web content matching Theia's CSS theme) — would unify theming across the WebView and native layers, but native Compose screens (Home, Settings, onboarding) gain nothing from being web content and lose native performance/accessibility integration (TalkBack, system font scaling) for no benefit. Rejected — Theia's own theming stays scoped to the WebView content; native screens use Material 3 independently, kept visually consistent by hand (shared color tokens where sensible) rather than by shared rendering technology.

## Consequences

- Multiple themes, dark mode, and dynamic color are Material 3 features to configure, not to build — significant scope reduction versus a bespoke system.
- Two independent theming surfaces now exist (native Compose via Material 3, Theia/WebView via its own theme system) that must be kept visually coherent by deliberate color-token alignment, not automatically — tracked as an open item in [/docs/design-system/tracker.md](../design-system/tracker.md).
- Icon set follows from this choice: **Material Symbols** (Google, Apache-2.0, matches Material 3 natively) is the default icon set — see design-system docs for specifics.
