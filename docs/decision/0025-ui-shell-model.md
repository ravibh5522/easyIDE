# 0025 - UI shell model: navigation surface, panels, and documents in a main stage

Status: Proposed (2026-09-24). Design: [docs/ui-redesign/shell-model.md](../ui-redesign/shell-model.md).

## Context

The app has two structures that do not compose. The workspace is a desktop-style rail plus an
explorer plus one editor plus a bottom terminal; Home, Settings, Extensions, Onboarding and
New Project are separate screens with private top bars and back stacks. Tested on a real
phone, the workspace layout is unusable at phone width (the explorer covers the editor, the
terminal collapses, status text overlaps) and nothing can open beside anything else: a
settings page cannot sit next to a file, a diff cannot open beside a chat.

The owner's direction (2026-09-24): follow VS Code. After a project opens, anything that
opens uses the side panel, the right side panel and the main stage. It must be configurable
through extensions and later support split view and git diff. On a phone, Home has bottom
navigation to switch between Home, Extensions, Settings and so on; on a tablet the same is a
side panel. Extensions must be able to add navigation items (a Docker controller, an AI agent
controller, chat).

## Decision

One **shell** with the same anatomy in two scopes (app, workspace): a **navigation surface**
(bottom bar on compact, rail on wider), a **primary panel**, a **main stage** of editor groups
that hosts **documents** identified by URI, a **secondary panel**, a **bottom panel**, a
status strip, and a touch-only **input dock** above the keyboard. **Anything that is a page
opens as a document in the main stage** (files, settings pages, extension pages, diffs,
previews, terminals, extension documents); panels list and select; transient input is a
dialog or sheet. **Navigation items, containers, views and document types are registries**
filled by core code and by extensions through declarative contribution points; the user can
hide, move and reorder any of it. Extension views use a declarative view schema rendered by
the app's own kit, so extension UI carries the app's identity.

## Alternatives considered

- **Dock as the primary layout** (one adaptive bottom dock) - excellent reach, but it does
  not say where pages and extension screens open; kept only as the input dock.
- **Swipeable deck of sheets** - horizontal swipes collide with caret, selection and code
  scrolling; cannot show editor and terminal together.
- **Command-first omnibar** - poor for browse-first touch use; the palette complements it.
- **Free-form canvas of cards** - a window manager to build and maintain; imprecise on touch;
  presets and splits give the same result.
- **Keep separate screens, restyle them** - leaves the composition problem and the
  extension-UI problem unsolved.

## Consequences

Easier: one place to open things (`openDocument`), split view and diff arrive as stage
features, extension screens inherit the shell, Settings/Extensions/Home rebuild on one
engine, session restore persists URIs.
Harder: a real engine to build and test (registries, groups, restore, back rules), and the
workspace re-hosting is the largest piece of the redesign (XL).
Forecloses: a bespoke per-screen navigation model; free-form floating windows; webview
panels as the way for extensions to draw (deferred, not rejected forever).
Requires: SDK additions (`navigation`, `documents`, `documentOpeners`, `layoutPresets`,
view schema, `ui.contribute`) owned by the extension track; a session registry (ADR-D) for
Home's Running section and document restore.
