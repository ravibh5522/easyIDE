# Feature: UI Redesign - Architecture

Status: PROPOSED (2026-09-24). Owner sign-off pending on the items in section 11.
Decision records: [0025 shell model](../decision/0025-ui-shell-model.md),
[0026 identity, kit and properties](../decision/0026-identity-ui-kit-and-properties.md).

## 1. Overview

**Verdict that started this** (owner, 2026-09-24, after using the app on a phone): the coding
screen is good; Home, Settings and Extensions "look AI-generated": no UI/UX rules, no
formatting discipline, no customizable properties, no distinctive layout.

**What the code and the device showed:**

- The coding screen has a system behind it: dense chrome on editor tokens, its own controls.
  About 8,600 lines of Home, Settings, Extensions, Onboarding and New Project call stock
  Material widgets directly (60 `TextButton`, 21 `OutlinedTextField`, 29 `AlertDialog`,
  12 `ListItem`, 8 `TopAppBar`, 8 `Scaffold`...) with a 419-line shared component folder.
- On a real phone the workspace layout is a desktop rail pasted onto a phone: the explorer
  covers the screen with no scrim, the terminal collapses to a sliver, the status bar text
  overlaps ("0 warnings 0Form..."), the toolbar is bare text, the empty editor is one grey
  line, and every card, chip and button is the same soft rounded box.
- Every "page" is its own screen with its own top bar, so nothing composes: settings and
  extension pages cannot sit next to a file, a diff cannot open beside a chat.

**The redesign** replaces all of that with one **shell** (navigation surface, side panels,
a main stage that opens documents), a small **UI kit** that is the only home of raw widgets,
a coherent **identity** (the "Block" identity), and **custom properties** the user can change.
Extensions plug into the same shell through the same contribution points, so a Docker
controller, an AI agent or a chat pack gets first-class navigation, panels and pages that
look native.

## 2. Goals and non-goals

### Goals

1. **One shell everywhere.** Same anatomy in app scope and workspace scope; bottom
   navigation on phones, rail and docked panels on tablets and desktop.
2. **Everything opens in the main stage** like VS Code: settings, extension pages, diffs,
   previews, files are documents in editor groups; panels list, the stage shows.
3. **Extension-configurable.** Extensions add navigation items, containers, views, document
   types and layout presets; the user can hide, move and reorder any of it.
4. **A kit, not widgets.** Screens are composed from about twenty kit primitives; a CI
   ratchet forbids raw Material widgets outside the kit.
5. **Distinct identity, checkable.** The Block identity, fifteen pass/fail anti-generated
   tests, a fixed copy voice.
6. **Custom properties.** Accent, density, corners, scale, font pairing, motif, haptics,
   layout preset, navigation position: validated, layered, shareable as chrome packs.
7. **Proven on device.** Every phase ends with an on-device check at the size and font-scale
   matrix, not only unit tests.

### Non-goals

- Redesigning the editor screen (it is the reference; it is only re-hosted in the shell).
- A free-form window manager or a canvas of floating cards (presets and splits only).
- Webview panels (still deferred); extension UI is declarative.
- The virtualised editor engine and the gesture layer (they wait for ADR 0018 PE1; the
  shell defines the seams).
- A new brand name or marketing site.

## 3. Principles

1. **Function first.** A distinctive interface is one where every decision has a reason a
   user can feel. Decoration that carries no meaning is removed.
2. **Same object, same place.** A thing (a setting, a file, a diff) has one URI and opens in
   one way everywhere.
3. **Thumb reach.** Frequent actions in the bottom 30%; read-only state at the top.
4. **The kit or nothing.** If a screen needs something the kit lacks, the kit grows.
5. **User has the last word.** Properties, layout and extension items are all overridable.
6. **Restraint.** One accent, one motif, three motif instances per screen, three type sizes
   per screen, one filled primary action per screen.

## 4. The design in brief

| Part | Summary | Spec |
|---|---|---|
| Shell | navigation surface + primary panel + main stage + secondary panel + bottom panel + input dock; two scopes (app, workspace) on one engine | [shell-model.md](shell-model.md) |
| Documents | everything that is a page is a document with a URI in an editor group; preview and pin; per-group history; open beside | shell-model.md section 4-5 |
| Navigation | bottom bar on compact (max 5, "More"), rail on wider; items contributed by core and extensions; user order/hide/pin | shell-model.md section 6 |
| Extension UI | `navigation`, `viewsContainers` placements, `documents`, view schema, capabilities, worked Docker / agent / chat examples | [extension-ui.md](extension-ui.md) |
| Layouts | per form factor: phone, landscape, tablet, foldable, desktop; sizes; input dock; gestures (phased) | [layout-spec.md](layout-spec.md) |
| Screens | Home "Now", project page, Extensions, Settings, onboarding, new project, workspace panels, dialogs, diagnostics | [screens.md](screens.md) |
| Identity | Block cursor motif, prompt glyph, crop corners, type and colour rules, icons, motion, haptics, copy voice, logo | [identity.md](identity.md) |
| Rules | numbered UI/UX rules with automated checks, fifteen anti-generated tests | [ux-rules.md](ux-rules.md) |
| Properties | `appearance.*` and `shell.*` keys, validation, scope safety, chrome packs, token file format | [properties.md](properties.md) |
| Kit | packages, twenty primitives, inventory of what is replaced, token refactors, testing, risks | [kit.md](kit.md) |

**What is unique, honestly.** The VS Code-like stage/panel structure is the owner's chosen
model, so the layout is familiar to developers by design. What is ours: (1) app scope uses
the same shell, so Home, Extensions and Settings are documents in a stage with a panel,
turning into a bottom-navigation app on a phone; (2) navigation items, containers and
documents are extension-configurable through a declarative view schema, so third-party
screens inherit the identity; (3) Home is a live "Now" page, not a project list; (4) the
Block identity and the user-customizable properties; (5) the input dock and the mono/prompt
language of a terminal-native tool.

## 5. Document map

| Document | Purpose |
|---|---|
| [arch.md](arch.md) | this file: vision, goals, decisions, roadmap, sequencing |
| [shell-model.md](shell-model.md) | the shell: anatomy, entities, documents, navigation, panels, stage, back, persistence |
| [extension-ui.md](extension-ui.md) | contribution points, view schema, capabilities, examples, hand-off to the SDK |
| [layout-spec.md](layout-spec.md) | per form factor layouts, sizes, input dock, gestures, motion, acceptance matrix |
| [screens.md](screens.md) | screen-by-screen specification |
| [identity.md](identity.md) | motifs, colour, type, icons, motion, haptics, voice, logo |
| [ux-rules.md](ux-rules.md) | enforceable rules and their checks |
| [properties.md](properties.md) | custom properties, tokens, chrome packs |
| [kit.md](kit.md) | kit specification, inventory, token refactors, tests, risks |
| [tracker.md](tracker.md) | status of every component |

## 6. Decisions

Two ADRs (Proposed): **0025** the shell model (navigation surface, panels, documents in a
main stage, extension-configurable; the earlier "Dock as primary layout" idea is reduced to
the input dock) and **0026** identity, kit and properties (Block motif, kit-only widgets,
`appearance.*` properties; supersedes the accent and motif parts of 0019).

Alternatives considered and rejected (from the layout brainstorm and the reference
research):

| Alternative | Why not |
|---|---|
| **Dock as the primary layout** (one adaptive bottom dock replacing rails and bars) | good reach, but it does not answer "where do pages and extension screens open"; it survives as the input dock |
| **Deck** (swipeable full-width sheets) | horizontal swipes collide with caret/selection gestures and code scrolling; no glance at editor and terminal together |
| **Omnibar / command-first** | poor for touch users who browse; the palette stays as a complement |
| **Stage canvas** (freely arranged cards) | becomes a window manager to maintain; imprecise dragging on touch; presets give the same benefit |
| **Keep stock Material, restyle the theme** | does not fix structure, density or copy; the verdict was about missing rules, not colours |
| **Another identity** (Phosphor, Ledger, Dot Matrix, Field Unit, Workbench) | Block borrows the useful parts of Phosphor and Field Unit without nostalgia or novelty costs |

## 7. Roadmap

Each phase is shippable and ends with the on-device check of layout-spec section 10.
Sizes: S = days, M = 1-2 weeks, L = 2-3 weeks, XL = 4+.

| Phase | Scope | Size | Exit criteria |
|---|---|---|---|
| **R0 Foundations** | ADRs accepted; import-ratchet test with today's allowlist; `tools/ui-lint.sh`; Roborazzi spike; `Appearance`, `UiMetrics`, token refactors 1-10 (defaults identical to today); kit primitives and a gallery | M | unit tests green; gallery goldens at 3 densities x 3 corners x light/dark; **no existing screen changed**; editor screenshot unchanged |
| **R1 App shell** | `ShellState`, registries (navigation, container, document), `NavSurface`, `PanelHost`, `StageHost` (single group), document URIs; rebuild **Home ("Now"), Extensions, Settings** as containers + documents on the kit; back behaviour; app-scope restore | L | phone: bottom nav + pushed documents; tablet: rail + panel + stage; ratchet allowlist drops `settings/**`, `extensions/**`, `home/**`; settings 60 fps; uiautomator matrix passes |
| **R2 Flows and dialogs** | onboarding, New project, Install Linux on the kit; all 22 non-workspace dialogs to `KitDialog`; banners and toasts | M | ratchet allowlist drops `onboarding/**`, `newproject/**`; baseline profile regenerated; copy lint green |
| **R3 Workspace shell** | re-host the workspace: editor groups (1 to 4), panels with the Files/Search/Git/Problems/Outline/Terminal containers, bottom navigation on compact, input dock, status strip, layout presets, foldable postures, persisted layout; stock workspace dialogs to the kit | XL | phone workspace usable one-handed; explorer no longer covers the editor; status bar text never overlaps; `ExtensionSlots` contract tests green; files <= 600 lines |
| **R4 Extension UI** | registries fed by the runtime: navigation, containers, documents, openers, presets; the view schema renderer with limits; sample packs (Docker, Agents, Chat) | L | a sample pack adds a bottom-nav item, a panel and a document that render with the kit; limits enforced; hidden/reordered by the user |
| **R5 Identity assets** | custom icon set (24 glyphs), app icon and splash, motion and haptics helpers, empty-state art, copy pass, motif per surface | M | the fifteen U-AI checks pass; on-device review by the owner |
| **R6 Split view, diff, gestures** | open beside, groups, `git-diff`/`git-commit` documents, diff providers for packs; gesture layer and caret scrub after PE1 | L | diff beside chat on a tablet; each gesture has a switch, a button and a command |

R4 needs the extension track to land the SDK changes listed in extension-ui.md section 8; R0
to R3 do not.

## 8. Sequencing with in-flight work

State on 2026-09-24: the local branch `ui-improvements` contains the merged extension SDK PR
(#3), the new Home and onboarding (an earlier, stock-widget version), and the Android regex
fix; it is unpushed. Four worktrees under `/home/ravi/Desktop/tab-code-wt/` hold **uncommitted**
work based on the older `main` (`e6772f9`) and each conflicts with `ui-improvements`:

| Worktree | What it holds | Redesign impact | Recommendation |
|---|---|---|---|
| `ui-layout` | pane layout store, fold/posture logic, compact switcher, tab chip, status bar | its UI rewrites the shell that R1/R3 replace | **salvage the pure logic** (splitter math, posture mapping, MRU) into `ui/shell`; discard its screen edits |
| `ui-editor` | undo/redo, find/replace, quick open, welcome view, file icons, pinch zoom | independent of the shell except its overlay/welcome UI | merge (rebased) before R1; its new UI goes on the ratchet allowlist and moves to the kit in R3 |
| `ui-reliab` | workspace session registry (ADR-D), restore, hot-exit, diagnostics | **required** by Home "Running" and by document restore | merge (rebased) before R1; diagnostics becomes a document in R1 |
| `ui-git` | git identity, credentials UI, remote ops, branch UI, diff view | diff view becomes the `git-diff` document type (R6); credentials become a Settings page | merge (rebased) before R1's Settings work (both edit `SettingsScreen`) |
| `ui-home` | stale: its work is already in `ui-improvements` | none | remove after confirmation |

Rule: R0 is additive and can start immediately in parallel; R1 starts after the three
merges (or after their pure logic is salvaged), so that the Settings and Extensions
migration does not fight open edits. New properties settings go in a **new** schema file, not
in `SettingsSchema.kt`, which all of these branches edit. The 600-line cap is already broken
by the four branches' additions to `WorkspaceViewModel` (about 673 lines combined): split it
while merging, not during the redesign.

## 9. Success metrics

| Metric | Target |
|---|---|
| Raw Material widget imports outside `ui/kit` | 0 (from about 200 call sites) |
| U-AI checks passing | 15 of 15 |
| Settings visible rows on an 800dp tablet without scrolling | 9 or more |
| Scroll jank on Settings and Extensions (reference phone) | under 5% |
| Touch targets under 44dp | 0 |
| Layouts checked at font scale 2.0 without clipping | all migrated screens |
| Time for a pack author to add a navigation item + panel + document | under 30 minutes with the CLI template |
| Owner review: "looks designed, not generated" | yes, on device |

## 10. Risks

| Risk | Mitigation |
|---|---|
| Scope: this rewrites about 8,600 lines plus the workspace shell | phases with exit criteria; R0 is additive; the ratchet shrinks per phase |
| Two systems coexist mid-migration (stock and kit) | `MaterialTheme` bridge maps stock widgets to the same tokens, so half-migrated screens still match colours and corners |
| Extension view schema limits what packs can build | components chosen from the Docker/agent/chat examples; version field allows growth; webview stays a later option |
| Document model complexity (URIs, restore, groups) | one entry point (`openDocument`), types register themselves, restore degrades to a placeholder |
| Phone workspace with a bottom bar plus input dock plus sheets | keyboard-aware: the dock replaces the bar while typing; verified on device per phase |
| Performance of recomposition on property change | properties are static locals changed rarely; metrics test; editor text never re-laid out |
| Identity motif reads as costume | one to three instances per screen; `appearance.motif = off` |
| Conflicts with the extension track over the SDK | contribution points defined here as requirements; the extension track owns the schema |

## 11. Open questions (owner)

1. **Default accent:** signal orange (recommended) or keep iris (0019).
2. **Block motif:** adopt (recommended) or a plainer identity.
3. **Bottom navigation inside a workspace** (Files, Search, Git, Terminal + extension items),
   hidden while typing: yes (proposed) or only in app scope.
4. **Terminals** default to the bottom panel (proposed) or to stage documents.
5. **In-flight worktrees:** confirm the salvage/merge plan of section 8 and the removal of
   `ui-home`.
6. **Extension items on the phone's bottom bar:** max five visible with "More" (proposed);
   should extension items ever be pinned above core items? Proposed: never.
7. **Hand-off to the extension track:** approve extension-ui.md section 8 as the SDK
   requirement.
