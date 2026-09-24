# 0027 - Extension views: a fixed declarative view schema (`viewSchema: 1`) drawn by the app's kit

Status: Accepted (2026-09-25, implemented in R4). Design: [extension-ui.md](../ui-redesign/extension-ui.md); authoring:
[views.md](../extension-sdk/views.md). Builds on [0025](0025-ui-shell-model.md) and [0009](0009-extension-platform-tiers.md).

## Context

The shell (0025) gives extensions the same slots as core: navigation items, containers, documents. What fills a container or a
document body is open. Docker, an agent controller and a chat must be possible, on a phone and on a tablet, with the app's own
identity, density and accessibility, without giving a pack a place to run code on the UI thread or to draw pixels
(threat-model M-5 and the 0009 tiers: declarative first, WASM for logic, no webviews).

## Decision

1. **A view is data.** A `viewSchema: 1` JSON file describes a tree of components from a closed catalog (layout, content, data,
   input; `terminal` reserved). The app draws it with kit primitives only. There is no code in it and no expression language:
   `{field|format}` templates over the view's own data, `when` clauses in the existing grammar, and nothing that reaches outside
   the data object.
2. **One data object per view, fed three ways.** An action's captured JSON (`easyide.viewData`, or a document type's
   `state.provider`), a WASM provider's `ui.setViewData`, and local edits (typed text, effects). A provider update merges by
   top-level key, so typed input survives a refresh. Sources run only while the view is on screen.
3. **Events reuse the action vocabulary.** A control names a command (or writes an inline action, or `open`s one of the pack's
   documents) and passes `args`, which the action reads as `${arg:name}`. The result is written back where `as` says, with
   `before` and `after` local effects around the call. A destructive control must carry `confirm`; the validator refuses it
   otherwise.
4. **Hard limits, checked twice.** Static limits at parse time (file 128 KB, depth 12, 2,000 nodes, 4,096-character strings,
   200-entry literal lists, 20 views and 10 documents per pack); rendered limits at plan time (2,000 nodes drawn, lists
   virtualized to 100,000 rows, chat 2,000 messages, log 5,000 lines); rate limits at update time (10 provider updates per
   second per view, 256 KB each, intervals of at least 2 s). A view over a limit is drawn as "view unavailable: reason" and
   logged. It never crashes and never blocks another view.
5. **A pure planning step in the SDK core.** `ViewPlan` (Apache-2.0, `:extension-schema`) turns a view and its data into the tree
   the renderer draws. The app's Compose code draws that tree and nothing else, and `easyide-ext test` asserts on the same tree,
   so a pack tests its screens without a device.
6. **Capability `ui.contribute`** grants navigation, the shell's container forms, schema views, documents and layout presets.
   Bare `activitybar` / `panel` containers and schema-less views keep working with no new capability (extension-ui.md section 8).
   Extension ids of these points are `<publisher>.<name>.<part>`; the shell already enforces it, and the validator now says so.
7. **Icons are monochrome, tinted by the shell** (24 grid, one paint colour, no raster, script, gradient or effect); no light/dark
   variants (extension-ui.md section 9.3).

Answers to the open questions of extension-ui.md section 9: `chat` is a v1 component and `terminal` is reserved (a pack that
names it is refused, and the renderer skips it); an app-scope navigation item may target a command (`target.command`), which is
how a pack opens a document from the bar; icons are monochrome only.

## Alternatives considered

- **Webview panels (L4)** - a second UI stack with its own identity, accessibility and security surface; deferred by 0025.
- **A tree-data provider like VS Code's** - fits a tree, not a chat, a form or a log; the provider still needs somewhere to run.
- **A small expression language in view files** - one more thing to sandbox and to validate on a phone; templates plus
  when-clauses cover the examples and every extension point stays greppable.
- **Component versions per component** - a single `viewSchema` version is easier for authors and for an app to refuse cleanly.

## Consequences

Packs get one consistent look, one accessibility behaviour and one place to change chat or list appearance, and the app can ship a
sample per surface (Docker, Agents, Chat) that is only files. A new component is a schema version bump and an app release, which
is slow on purpose. Layout is fixed to what the catalog offers (no custom drawing, animation, timers or network images).
Streaming is bounded by what a provider can push (10 updates per second); an action returns when its command has finished.
The SDK gains one variable (`${arg:}`) and one action (`openDocument`, restricted to the pack's own `ext://` documents); both
are additive within API 0.3.
