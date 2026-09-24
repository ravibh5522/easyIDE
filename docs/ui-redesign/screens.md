# UI Redesign - Screens

Status: PROPOSED (2026-09-24). Part of [arch.md](arch.md). Structure: [shell-model.md](shell-model.md).
Layout numbers: [layout-spec.md](layout-spec.md). Look and copy: [identity.md](identity.md).
Every screen follows [ux-rules.md](ux-rules.md) and is built only from the [kit](kit.md).

Each screen is described once for all size classes. "Panel" is the primary panel, "stage" is
the main stage; on a phone the panel is the first screen and the stage content is pushed.

## 1. Screen map

| Scope | Destination | Panel (list) | Stage (document) |
|---|---|---|---|
| App | **Home** | resume + running + environment + projects | project page (`easyide://project/<id>`) |
| App | **Extensions** | Installed / Browse tabs, search | extension page (`easyide://extension/<id>`) |
| App | **Settings** | category list with search | settings page (`easyide://settings/<category>`) |
| App | extension items (Docker, Agents, Chat ...) | pack's container | pack's documents |
| Workspace | **Files** | tree | files and every other document |
| Workspace | **Search** | query + results | file at the match |
| Workspace | **Source control** | changes, commit box | diff, commit details |
| Workspace | **Problems** | grouped diagnostics | file at the problem |
| Workspace | **Terminal** | (bottom panel) sessions | `terminal://` document optional |
| Workspace | **Outline**, extension items | symbol tree; pack container | |
| Flow | Onboarding, New project, Install Linux | own full-screen flow (compact) / sheet (wide) | |

## 2. Home (the "Now" page)

Purpose: a live index of what is going on, not a marketing page or a bare list.

```
+--------------------------------+
| > easyIDE                 [+]  |   [+] = new project / import / clone menu
|--------------------------------|
| > RESUME                       |
| +----------------------------+ |
| | my-app   main*  3 changed  | |   hero: the last project, mono branch, dirty count,
| | main.py  edited 2m ago     | |   last file; crop corners if selected on wide
| |               [ Continue ] | |   the one filled primary action on the page
| +----------------------------+ |
| > RUNNING                      |
|  sh 1       ubuntu       [x]   |   state dot + mono name + detail + stop/attach
|  pyright    ready  212 MB [x]  |
|  install    python  [###__]    |   cell-fill progress
| > ENVIRONMENT                  |
|  base   proot   4.1 GB   ok    |
| > PROJECTS          [search][=]|
|  my-app     ~/proj  main*  3m  |   rows, hairline separated, mono path/branch, tabular time
|  api-server  ...               |
+--------------------------------+
```

| Section | Content | Data source | Empty state |
|---|---|---|---|
| Resume | last opened project, branch, dirty count, last file, "Continue" (opens with the saved session) | project store, JGit summary (cached, off main), session snapshot | first run: "no projects yet" + "> New project" |
| Running | terminals, language servers (with RSS), installs, agent sessions, tasks; stop or attach | workspace session registry (ADR-D), LSP manager statuses, install progress, extension providers | section hidden when empty |
| Environment | each environment: backend, size, disk, state; "Install Linux" when none | environment manager | "no environment. > Install Linux" |
| Projects | all projects: mono path, branch, dirty, relative time; search; sort recent/name; long-press or right-click menu (rename, duplicate, delete, change environment, open location) | project store | as Resume |

- **Actions:** "+" opens New project / Import folder / Clone (a small menu). No FAB.
- **Wide layout:** Projects is the panel; the stage shows the selected project's page; Resume
  and Running sit at the top of the stage when nothing is selected.
- **States:** loading = static two-bar skeletons (after 300ms); errors inline per section;
  offline is silent (nothing on Home needs the network).
- **Extension hooks:** an extension can add a Running provider (Docker containers up) and a
  Resume action; both render through kit rows.

## 3. Project page (document)

Header: monogram tile (hash-tinted, no gradient), name, mono path. Actions: **Open**
(primary), Open with terminal, "..." menu. Below: Details rows (environment, git branch,
language, last opened, created), Recent files (mono, relative time), last commits (mini
graph, 8 rows), Sessions (running items for this project). On a phone this page is what a tap
on a project row pushes; Back returns to the list.

## 4. Extensions

Panel (list):
```
| Extensions               [+]   |    [+] = install from folder / create extension
| Installed | Browse             |    KitTabs underline
| [search                      ] |
| easyide.python           1.0.0 |    mono id + tabular version
|   Python language server   [on]|    support text, toggle
|   built in                     |    tag
| acme.docker              0.4.2 |
|   Docker controller        [on]|    ext badge: adds Docker
```
- Rows: mono `publisher.name`, version chip, one-line description, enable toggle at the right;
  state tags: `built in`, `disabled`, `crashed`, `needs approval`, `update`. No card per row.
- Banners (kit): safe mode ("Extensions are off for this session. Exit safe mode"), revoked
  packs, pending approvals.
- **Browse** tab: registry results (search, filter by category/language), each row with
  Install; an offline banner when the cached index is stale.

Extension page (document), tabs (`KitTabs`): **Details** (description, publisher, license),
**Contributions** (navigation items, containers, documents, commands, languages, themes: N of
each, expandable), **Capabilities** (what it can do, what was approved, revoke), **Log**
(recent lines, copy), **Versions** (installed, retained, "Roll back to x"). Header actions:
enable/disable, uninstall (confirm, names the pack), update.

Install flow (sheet on compact, dialog on wide): pack name and publisher, version, size,
the **capability list in plain language** ("Runs commands in your sandbox", "Adds screens
and navigation items"), the setup commands verbatim, then Install / Cancel; progress as a cell-
fill bar; result toast `ok installed python 1.0.0`. Copy never claims isolation.

## 5. Settings

Panel: search at the top ("Search settings"), then categories: **Appearance, Editor,
Terminal, Files, Git, Sandbox, Keyboard, Language servers, Extensions, Layout, Diagnostics,
Advanced**. Selecting one opens its page in the stage; on a phone, the list is the screen and
the page is pushed.

Page anatomy:
- Top of every page: the **layer control** (User | Environment | Project, `KitTabs`
  segmented) when the category has layered keys, and an "Edit as JSON" action (opens the
  layer editor as a document beside).
- Groups by `KitSection`; each setting is a **44dp row**: label (sans) on the left, value on
  the right (mono, muted) or an inline control (toggle, stepper, segmented, swatch row).
  Modified dot when the value differs from the default; per-row reset; "Overridden by
  Project" note when a higher layer wins.
- No card per setting; a dense page shows 9 or more rows on an 800dp tablet without scrolling.
- Search results are a flat list of rows across categories with the category as the support
  text; tapping jumps to the row and highlights it.

**Appearance page** has the live preview and the property controls of
[properties.md](properties.md) section 7. **Layout page** holds preset, navigation position
and labels, and the reorder/hide lists for navigation items and containers (drag handles;
extension items show their pack name as support text). **Language servers** and
**Keyboard** are pages (not dialogs): a list of servers with state, memory and overrides; a
searchable shortcuts table with conflict flags and "Add binding".

## 6. Onboarding

Full-screen flow (no shell chrome), one step per screen, `KitStepper` at the top ("1/4" in
mono). The step card carries crop corners; the step title types itself once at 60ms per
character (skipped under reduce motion).

| Step | Content | Skippable |
|---|---|---|
| Welcome | one sentence of what easyIDE is, the mark with a blinking block, "Continue" | no |
| Battery | why the sandbox needs to keep running; opens the system exemption screen; shows current state on return | yes |
| Notifications | (Android 13+) why a foreground service notification exists; runtime permission | yes |
| Environment | preset chooser (`KitRow` selectable), download + extract with a cell-fill bar, cancel, retry/resume, actionable errors; checksum verified line | yes (Home then shows "> Install Linux") |
| Done | "Ready." and "> New project" / "> Import folder" | - |
Complete only at the end; leaving early resumes at the same step.

## 7. New project

Compact: full-screen page; wide: a sheet. Fields (all `KitField`/`KitRow`): name; storage
(app storage or a chosen folder, with the mirror explanation); environment (reuse one, with
its state, or create); template/preset; Create (the one primary action, disabled until the
name and environment are valid). Errors inline; the environment step can start an install.

## 8. Workspace panels and documents

| Item | Type | Spec |
|---|---|---|
| Files | panel | tree, 40dp rows, file-type glyphs (custom set, tinted from tokens), git state glyphs (added/modified/untracked) at the right edge as small marks, accent-tinted open folder, selection = block marker; header actions: new file/folder/refresh; context menu on long-press and right-click |
| Search | panel | field + include/exclude, results grouped by file with mono line numbers, "open to the side" |
| Source control | panel | branch chip, changes grouped (Staged, Changes, Untracked), commit box, sync; row tap opens a diff document beside |
| Problems | panel (bottom) | grouped by file, severity filter, tap jumps |
| Terminal | bottom panel | session tabs, kit input dock keys, latched modifiers |
| Outline | secondary panel | symbol tree from the language server |
| File | document | the editor (unchanged behaviour); tab title mono; dirty dot; preview tab in italics |
| Welcome / empty editor | document | the mark watermark, recent files, a shortcut cheat sheet generated from the live keymap, "> New file" |
| Diff | document | side by side at wide sizes, unified on compact, hunk navigation, stage/unstage/discard hunk |
| Preview | document | Markdown/image preview, refreshes on change |
| Project page, settings pages, extension pages | documents | as sections 3-5 |

The editor screen itself is **not redesigned**: it is the reference the rest catches up to.
Its chrome is re-hosted in the shell (tab strip / switcher, status strip, input dock) and its
stock dialogs and menus move onto `KitDialog`/`KitMenu`.

## 9. Dialogs, sheets, banners, toasts

| Pattern | Use | Spec |
|---|---|---|
| Confirm | delete, discard, uninstall | title = the question naming the object; consequence line; ghost Cancel left, Danger action right; irreversible = type the name |
| Unsaved changes | leaving or closing dirty documents | Save all / Discard / Cancel; lists up to 5 file names |
| Capability / trust prompt | install, project trust | plain-language capability list; exact argv and env for trust; Allow / Not now / Never |
| Text input | rename, new file, clone URL | one `KitField`, validation inline, primary disabled until valid |
| Sheet | pickers on compact, install flow, panels | bottom sheet with detents; handle; Back closes |
| Banner | safe mode, trust pending, offline registry, restored session | tone, one sentence, one action, dismissible when informational |
| Toast | result of an action | bottom-left, `ok`/`warn`/`err` tag + sentence, 3s (6s with action), one at a time |

## 10. Diagnostics (document)

Sections: Backend (proot or chroot, version, architecture), Rootfs (version, checksum state),
Storage (per environment, cleanup action with confirm), Language servers (state, memory),
Extensions (crash journal), Logs (ring buffer viewer with copy and export), Crash reports
(last report, share). Read-mostly, mono values, "Copy diagnostics" as the primary action.

## 11. Acceptance per screen

A screen is complete when: it uses kit primitives only (import ratchet green); it defines
loading, empty and error states; it passes the on-device matrix of layout-spec section 10;
its screenshot golden exists in light and dark at default properties; its copy passes the
strings lint; and it shows one to three motif instances.
