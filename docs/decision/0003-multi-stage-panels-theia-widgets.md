# 0003 - Multi-stage panel system built on Theia's ApplicationShell + WidgetFactory

Status: Partially superseded by [0006](0006-native-ide-shell-before-theia.md)

> The `WidgetFactory` mechanism below remains the plan for adding PDF/docx/video
> renderers once Theia is mounted. What changed: native Compose panes, not Theia
> dock areas, own the IDE shell today, so that the app is usable before a Node
> userland exists. See 0006.

## Context

The product needs a VS Code-like layout of independently dockable panels — a left panel, a main/center stage, and a right panel — where each panel can host arbitrary content types (code editor, PDF viewer, document viewer, video player), not just source code. This needs to be extensible: a later "custom extensions" system should be able to register new content-type renderers that any stage can host, without forking the panel system itself.

Given Theia is already the chosen IDE engine ([0001](0001-ide-foundation-theia.md)), the question is whether to build this multi-stage system as custom Compose UI, or on top of Theia's own layout system.

## Decision

Build the stage system directly on Theia's existing **`ApplicationShell`** dock areas (`left`, `main`, `right`, `bottom`) and its **`WidgetFactory`**/`AbstractViewContribution` extension pattern, rather than building a custom Compose-based panel/docking system. Verified against Theia's official docs and source (research dated 2026-08-10):

- `ApplicationShell` ([source](https://github.com/eclipse-theia/theia/blob/master/packages/core/src/browser/shell/application-shell.ts)) already manages exactly the dock areas this product needs.
- Adding a new content-type widget (e.g. a PDF viewer) requires no core fork: implement a widget class (`ReactWidget`/`BaseWidget`, `@injectable()`), bind a `WidgetFactory`, and wire it up via an `AbstractViewContribution` — a documented, supported pattern ([theia-ide.org/docs/widgets](https://theia-ide.org/docs/widgets/)).
- This is the same mechanism third-party Theia adopters (Eclipse Che, Samsung, STMicroelectronics — see [0001](0001-ide-foundation-theia.md)'s research) use to add custom views, giving real production precedent at the scale this product needs.

**Renderer libraries chosen for the initial non-code content types**, each verified for license and maintenance status (2026-08-10):

| Content type | Library | License | Notes |
|---|---|---|---|
| PDF | [PDF.js](https://github.com/mozilla/pdf.js) (Mozilla) | Apache-2.0 | Pure JS/canvas, no server dependency, actively released (v6.2.108, Jul 2026) |
| Video | Native HTML5 `<video>` | N/A (browser built-in) | Sufficient for playback; avoids extra dependency weight |
| Word documents (.docx) | [mammoth.js](https://github.com/mwilliamson/mammoth.js) | BSD-2-Clause | Converts docx → HTML client-side, lightweight |
| Spreadsheets (.xlsx) | [SheetJS Community Edition](https://github.com/SheetJS/sheetjs) | Apache-2.0 | Use CE only — SheetJS also sells a separate "Pro" tier, don't conflate |

## Alternatives considered

- **Custom Compose-based multi-pane docking system**, independent of Theia — full native control and native touch-resize feel, but duplicates a docking/layout engine Theia already has, and would need its own extension API built from scratch for "later custom extensions" to hook into. Rejected: reinvents a solved problem and fragments the codebase into two extension systems (one for Theia widgets, one for native panels) instead of one.
- **iframe-per-content-type embedded directly in Compose**, bypassing Theia's shell entirely for non-code panels — simpler for a single content type, but loses Theia's docking/tabbing/persistence for those panels and still needs a separate extension mechanism. Rejected for the same fragmentation reason.

## Consequences

- "Stages" in product/UI language map directly onto Theia's `left`/`main`/`right`/`bottom` dock areas — [/docs/ui-shell/arch.md](../ui-shell/arch.md) uses this vocabulary consistently.
- The future "custom extensions" system (explicitly deferred, not building now) has a concrete, already-proven extension point to target: new `WidgetFactory` registrations. No separate extension API needs to be designed later.
- Native touch-resize gestures must be wired to trigger Theia's own dock-resize handles through the WebView bridge — this was already an open question in `ui-shell/arch.md` and remains one; this decision doesn't resolve it, just confirms which system's resize handles are being driven.
- All four renderer libraries are MIT/Apache/BSD-family — no copyleft obligations beyond what EPL-2.0 already requires for Theia itself (per [0001](0001-ide-foundation-theia.md)).
- Every future addition to this renderer list must go through the same license + maintenance verification before adoption — codified as a standing rule in `.claude/CLAUDE.md`, not just a one-time check.
