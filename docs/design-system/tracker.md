# Feature: Design System — Tracker

Status legend: `not-started` / `in-progress` / `done` / `blocked`

## Components

| Component | Status | Notes |
|---|---|---|
| `Color.kt` — seed color + `ColorScheme` token definitions | in-progress | Placeholder seed color in place; real brand palette still open (see arch.md open questions) |
| `Theme.kt` — theme selection (System/Light/Dark/Dynamic/AMOLED/High-contrast) | in-progress | All six modes resolve to a `ColorScheme`; AMOLED/high-contrast overrides are first-pass, need on-device QA |
| `Type.kt` — typography scale | done | Material 3 defaults, no customization until a concrete need exists |
| `Shape.kt` — corner-radius/shape tokens | done | Material 3 defaults, no customization until a concrete need exists |
| Material Symbols icon integration | in-progress | Using `material-icons-extended`; no custom icon set yet |
| Custom icon module (logo, sandbox-backend indicators) | not-started | Launcher icon is a placeholder vector |
| Window size class-driven adaptive layout | done | `ui/foundation/WindowSize.kt`; applied on Home (grid columns), Workspace (stage visibility/widths), and form max-widths |
| Animation: reduce-motion system-setting compliance | done | `LocalMotionEnabled` + `motionSpec()`; looping empty-state pulse and nav transitions both suppress when disabled |
| Theia theme-preference sync (native theme -> Theia theme) | not-started | Depends on Theia backend integration existing at all (sandbox-runtime) |
| AMOLED Black surface-token overrides | not-started | Needs on-device visual QA |
| High-contrast theme WCAG validation | not-started | Validate AA/AAA once built |

## Open decisions blocking full implementation

- Seed color / brand palette (see arch.md) — use a placeholder to avoid blocking scaffolding, revisit before any public release.
