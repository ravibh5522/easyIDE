# UI Redesign - Extension UI Model

Status: ACCEPTED (2026-09-24); the SDK side and the renderer are built (R4, 2026-09-25, [decision 0027](../decision/0027-extension-view-schema.md)), see section 10. Part of [arch.md](arch.md). Builds on [shell-model.md](shell-model.md).

How an extension puts UI into the shell: a navigation item, a panel, a document in the main
stage, a status item. Everything is declarative and rendered by the app's own UI kit, so an
extension's screens carry the same identity, density and accessibility as core screens and
cannot look "off". This document is the requirement for the extension track; the manifest
schema itself lives in `docs/extension-sdk/sdk-reference.md` and is changed there.

## 1. Principles

1. **Extensions contribute data, the shell draws.** No extension draws pixels or ships
   views. A view is a tree of kit components ([section 4](#4-the-view-schema)) bound to data.
2. **Same slots as core.** Docker, agents and chat use the contribution points that Files,
   Git and Settings use. There is no second-class path.
3. **The user has the last word.** Any extension item can be hidden, moved or reordered by
   the user; extensions can never hide or reorder built-in items.
4. **Nothing runs until asked.** Contributing UI does not execute code. Data arrives from
   actions the user triggers or from an activated logic layer (WASM), gated by capabilities.
5. **Bounded.** Node counts, update rates and payload sizes have hard limits so a pack cannot
   stall the UI thread.

## 2. Contribution points

Existing points stay as they are (`commands`, `menus`, `keybindings`, `statusBarItems`,
`keyRows`, `editor/touchToolbar`, `views`, `viewsContainers`, `stages`, `viewData`). This
feature adds or extends:

| Point | Status | Purpose |
|---|---|---|
| `navigation` | NEW | items on the navigation surface (bottom bar / rail), app and/or workspace scope |
| `viewsContainers` | EXTENDED | `placement`: `sidebar`, `secondarySidebar`, `panel`; `scope`: `app`, `workspace`, `both` |
| `views[].schema` | EXTENDED | a view body is a view-schema file (or inline) instead of only host-built content |
| `documents` | NEW | document types opened in the main stage (`ext://<id>/<type>/<key>`) |
| `documentOpeners` | NEW | which type opens which file glob or URI scheme, with a default and alternatives |
| `layoutPresets` | NEW | named arrangements a pack recommends (locations, panels open, split) |
| `commands` action `openDocument` | NEW | open a URI in a group (`active`, `beside`, `new`) |
| `viewBadge` | NEW | a binding that supplies the number/dot on a navigation item |

### 2.1 `navigation`

```jsonc
"navigation": [{
  "id": "acme.docker.nav",            // globally unique: <extension id>.<name>
  "title": "Docker",
  "icon": "ext:./icons/docker.svg",   // glyph name from the icon set, or a pack SVG (monochrome, 24 grid)
  "target": { "container": "acme.docker.main" },   // or { "command": "acme.docker.open" }
  "scope": "both",                    // app | workspace | both
  "order": 300,                       // default sort key; user order overrides
  "when": "config.acme.docker.host != ''",
  "badge": { "view": "acme.docker.containers", "path": "runningCount" }
}]
```

Rules: `title` <= 14 characters (it is a label under a 24dp icon on a phone); the icon must be
a single-colour vector; `order` values below 100 are reserved for built-ins; at most 3
navigation items per extension (more are ignored with a log line); an item whose `target`
does not resolve is dropped.

### 2.2 `viewsContainers` and `views`

```jsonc
"viewsContainers": {
  "sidebar": [{ "id": "acme.docker.main", "title": "Docker", "icon": "ext:./icons/docker.svg", "scope": "both" }],
  "secondarySidebar": [{ "id": "acme.agent.context", "title": "Context", "icon": "context", "scope": "workspace" }],
  "panel": [{ "id": "acme.docker.logs", "title": "Docker logs", "icon": "ext:./icons/logs.svg", "scope": "workspace" }]
},
"views": {
  "acme.docker.main": [
    { "id": "acme.docker.containers", "name": "Containers", "schema": "./views/containers.json" },
    { "id": "acme.docker.images",     "name": "Images",     "schema": "./views/images.json" }
  ]
}
```

A container appears in the placement it declares; the user or a preset may move it to
another allowed placement (`locations` on the container limits the choices). On a phone all
three placements are overlay panels (side sheet, side sheet, bottom sheet).

### 2.3 `documents` and `documentOpeners`

```jsonc
"documents": [{
  "type": "acme.docker/container",
  "title": "{name}",                    // tab title from the document's state
  "icon": "ext:./icons/container.svg",
  "schema": "./docs/container.json",    // the body, a view schema bound to the document state
  "multiple": false,
  "supportsSplit": true,
  "state": { "provider": "acme.docker.containerState" }   // an action or WASM provider, keyed by the URI
}],
"documentOpeners": [{ "glob": "**/docker-compose*.yml", "type": "acme.docker/compose", "priority": "option" }]
```

`priority`: `default` (opens by default), `option` (offered in "Open with"), `builtin` (never
allowed, reserved for core). Users override with `workbench.editorAssociations`.

### 2.4 `layoutPresets`

```jsonc
"layoutPresets": [{
  "id": "acme.agent.review", "title": "Agent review",
  "sizeClass": ["expanded"],
  "containers": { "sidebar": "explorer", "secondarySidebar": "acme.agent.context", "panel": "terminal" },
  "panels": { "sidebar": true, "secondarySidebar": true, "panel": false },
  "stage": { "split": "row", "groups": 2 }
}]
```

Presets are offered in the layout picker; they never apply automatically.

## 3. Capabilities and prompts

| Capability | Grants | Shown at install as |
|---|---|---|
| `ui.contribute` | `navigation`, `viewsContainers`, `views`, `documents`, `layoutPresets` | "Adds screens and navigation items" |
| `ui.stage` (existing) | opening documents in the stage from actions | "Opens pages in the editor area" |
| data capabilities | as today: `sandbox.exec`, `net(hosts)`, `fs.project`, `secrets.read`, `lsp.request` | as today |

Provenance: an extension item shows its pack name in its long-press menu and in Settings ->
Layout. It is not marked in the main UI (a badge on every item would be noise), but the
Extensions page lists everything a pack contributes ("Contributions" section: N items, N
documents, N views) so nothing is hidden.

## 4. The view schema

A **view schema** is JSON (`viewSchema: 1`) describing a tree of kit components. It has no
code, no expressions beyond field paths and string templates, and no way to reach outside its
own data. Version 1 is fixed; new components need a schema version bump and an app release.

### 4.1 Components

| Group | Component | Key props |
|---|---|---|
| Layout | `column`, `row`, `section` (caps header + group), `group` (bordered list container), `tabs`, `split` | `gap`, `padding`, `weight`, `title` |
| Content | `text` (roles `title body caption mono`), `markdown`, `keyValue`, `tag` (tone), `statusDot`, `progress`, `icon`, `image` (pack asset), `code` (read-only, highlighted), `banner`, `emptyState` | `value`, `tone`, `role` |
| Data | `list` (virtualized, paged), `tree`, `table`, `sparkline`, `logStream`, `chat`, `terminal` (attach to a pty), `diff` | `bind`, `item`, `columns`, `follow` |
| Input | `button`, `iconButton`, `toggle`, `field`, `select`, `slider`, `search`, `form`, `composer` | `action`, `args`, `enabledWhen`, `validate` |
| Feedback | `toast` (via action), `confirm` (via action) | `title`, `body`, `destructive` |

Not available: custom drawing, arbitrary images from the network, HTML, animation, timers.

### 4.2 Binding and templates

- `bind: "containers"` selects a path in the view's data object; `item` is the row template.
- String templates use `{field}` and `{field|format}` (`bytes`, `duration`, `relative`,
  `count`). Missing fields render as empty, never as an error.
- `when: "state == 'running'"` uses the same expression grammar as extension when-clauses,
  evaluated against the current item and the shell's context keys.

### 4.3 Actions

Any interactive component takes `action` (a command id) plus `args`, or an inline action from
the existing vocabulary (`runInTerminal`, `sandboxExec`, `openDocument`, `sequence`,
`showInputBox`, `showQuickPick`, `setConfig`, `executeCommand`...). Results can be written
back to view data (`as: "containers"`), which re-renders the view. Destructive actions must
carry `confirm` (a kit dialog with the object's name and consequences).

### 4.4 Data sources

| Source | How | Limits |
|---|---|---|
| Action result | a `sandboxExec` or other action whose JSON stdout becomes the view data | one-shot or interval (>= 2 s), only while the view is visible |
| Logic layer | a WASM provider calls `setViewData(viewId, json)` | <= 10 updates/s per view, <= 256 KB per update |
| File watch | a path in the project or extension dir | debounced, read-only |
| Stream | `logStream` / `chat` bound to a process stdout or a provider stream | bounded ring buffer (default 5,000 lines / 2,000 messages) |

Views that are not on screen receive no updates and run no intervals (the shell tells the
provider `visible: false`), which is what keeps ten installed packs cheap.

### 4.5 Hard limits

Tree size <= 2,000 nodes rendered at once (lists and tables virtualize beyond that, up to
100,000 rows via paging); schema file <= 128 KB; nesting depth <= 12; one `composer` per
view; no more than 20 views and 10 documents per pack. Violations drop the view with a
visible "view unavailable: <reason>" state and a log line, never a crash.

## 5. Worked examples

### 5.1 Docker controller

- **Navigation:** item "Docker" (scope both), badge = number of running containers.
- **Container `acme.docker.main`** (placement `sidebar`): views *Containers* and *Images*.
- **Containers view** (`list` bound to `containers`): row = status dot (tone from `state`),
  name (title), image (mono caption), CPU/mem (tabular), trailing start/stop/restart icon
  buttons. Row tap -> `openDocument ext://acme.docker/container/{id}` (preview tab).
- **Document `acme.docker/container`:** header (name, state tag, actions), `tabs`:
  Logs (`logStream` following stdout of `docker logs -f`), Stats (`sparkline` x3),
  Inspect (`code` with JSON), Env (`table`).
- **Capabilities:** `sandbox.exec` (or `net` for a remote daemon), `ui.contribute`.
- On a phone: bottom-nav item -> full-screen Containers list -> tap pushes the container
  document; Back returns to the list.

### 5.2 AI agent controller

- **Navigation:** item "Agents", scope workspace, badge = sessions awaiting input.
- **Container `acme.agent.sessions`** (sidebar): list of sessions (title, model, state,
  changed-file count); "New session" button opens a `showQuickPick` of agent profiles.
- **Document `acme.agent/session`:** a `chat` component (streaming messages, tool-call cards
  that expand to show command/diff, approve/deny buttons for actions) and a `composer`
  (multiline, slash commands, attach file/selection from the editor).
- **Container `acme.agent.context`** (secondarySidebar): files the agent touched (tree with
  diff badges); tapping a file opens `git-diff://` beside the chat, which is the split-view
  and diff flow the shell already provides.
- **Capabilities:** `net(hosts)`, `secrets.read` (its API key, its own namespace),
  `fs.project(read|write)`, `sandbox.exec`, `ui.contribute`.

### 5.3 Chat (generic)

The same `chat` and `composer` components with a provider that answers from an HTTP
endpoint. It demonstrates that "chat UI" is a component, not a product: any pack can host a
conversation, and the user gets one consistent chat look, one accessibility behaviour and one
place to change chat appearance.

## 6. User configuration

Everything an extension adds is user-configurable in Settings -> Layout (a document, like
every other page):

| Setting key | Purpose |
|---|---|
| `shell.navigation.order`, `.hidden`, `.pinned` | order, hide, and pin (keep visible on compact) navigation items |
| `shell.containers.placement` | map container id -> placement override |
| `shell.containers.hidden` | hide a container everywhere |
| `shell.layout.preset` | active preset id (`auto` by size class) |
| `workbench.editorAssociations` | glob -> document type override |
| `shell.extensions.contribute` | per-extension on/off for UI contributions only (data stays) |

Precedence (low to high): built-in defaults, extension declared defaults, layout preset,
user settings, project layer (project may set only `shell.layout.preset` and
`workbench.editorAssociations`, so a cloned repo cannot rearrange the app).

## 7. Split view and diff

Split view and diff are built-in behaviours of the stage, available to extensions:

- `openDocument(uri, {group: "beside"})` creates or reuses the group to the right (or below
  in tabletop posture); on a phone it opens the document full-screen with a "Back to <prev>"
  affordance because there is one group.
- `git-diff://` and `git-commit://` are built-in document types. A pack can register a
  **diff provider** for its own scheme (`compare: { left, right }` in the type), so Docker
  compose vs running config or two agent checkpoints use the same side-by-side view with the
  same word-level highlighting, scroll sync and hunk navigation.
- Layout presets can pre-arrange the split (chat left, diff right).

## 8. Compatibility and hand-off

The extension track owns the SDK. This feature requires these changes there:

1. `sdk-reference.md`: add `navigation`, `documents`, `documentOpeners`, `layoutPresets`,
   `viewBadge`; extend `viewsContainers` (`placement`, `scope`, `locations`) and `views`
   (`schema`); add the `viewSchema: 1` reference and limits; add `ui.contribute`.
2. `easyide-ext validate`: check schemas against the component catalog, limits, icon rules
   (monochrome, 24 grid), unique ids, `title` length.
3. `easyide-ext test`: allow scenarios that assert a rendered view tree (component types and
   text), so packs can test their views without a device.
4. Runtime: the `:extensions` registry gains typed stores for the new points; the app adapter
   maps them onto the shell registries; WASM host gains `setViewData` and view events.
Existing packs keep working unchanged: their `views` without `schema` render as today, their
`viewsContainers.activitybar` maps to a `sidebar` container with a navigation item.

## 9. Open questions

1. Should `chat` and `terminal` components be extension-usable in v1 or after the logic layer
   is proven on device? Proposed: `chat` in v1 (needed by the agent example), `terminal`
   later.
2. Should packs be allowed to contribute an **app-scope** navigation item that opens a
   document instead of a container? Proposed: yes, via `target.command`.
3. How does a pack provide light/dark icon variants? Proposed: monochrome only, tinted by the
   shell, no variants.

## 10. As built (R4)

The proposals of section 9 were adopted: `chat` is a v1 component and `terminal` is **reserved** (the validator refuses it, the
renderer skips it); an app-scope navigation item may target a command; icons are monochrome, tinted by the shell, no variants.

What differs from or adds to the text above:

- `navigation`, `viewBadge`, `documents`, `documentOpeners` and `layoutPresets` are easyIDE-only keys, so they live under
  `easyide` in `package.json`; `viewsContainers` and `views` stay under `contributes`. Reference: [sdk-reference.md](../extension-sdk/sdk-reference.md);
  authoring: [views.md](../extension-sdk/views.md).
- Every id of these points is `<publisher>.<name>.<part>` (a document type `<publisher>.<name>/<name>`); the validator enforces what the shell
  already refused. Navigation titles are at most 14 characters, a pack has at most 3 navigation items (the rest are ignored with a warning).
- Events name a command, an inline action, or `open` (one of the pack's own documents); `args` reach the action as the new variable
  `${arg:name}`; `confirm`, `as`/`mode`/`parse` and `before`/`after` effects are the whole write-back model (views.md section 5).
- `openDocument` exists as an action too (own `ext://` documents, needs `ui.stage`); a view's `open` needs nothing beyond `ui.contribute`.
- Data sources: `easyide.viewData` (`kind: object`, `intervalSec >= 2`), a document type's `state.provider`, WASM `ui.setViewData` (10 a
  second, 256 KB). Views not on screen fetch nothing; a badge's view is fetched once when its item first shows. "File watch" and provider
  `visible: false` notifications are not built; `logStream` over an action polls (the last 200 lines, refreshed) instead of streaming.
- `composer` is a multi-line field and a send button (Ctrl+Enter sends); slash commands and attachments are not in v1. `code` is monospace
  without highlighting; `diff` is a coloured unified diff, not the split view (R6 owns that).
- The user's switch `shell.extensions.contribute` (`{ "<extension id>": false }`) hides one pack's screens and items without disabling it.
- The Extensions page's "Contributions" listing and the Layout page's provenance read the same registries, so nothing a pack adds is hidden.
