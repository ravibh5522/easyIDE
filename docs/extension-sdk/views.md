# Extension SDK - Views, documents and navigation

How a pack puts screens into the shell. The model and the reasons are in
[ui-redesign/extension-ui.md](../ui-redesign/extension-ui.md) and [decision 0027](../decision/0027-extension-view-schema.md);
this is the author's reference. Manifest fields are also in [sdk-reference.md](sdk-reference.md). Three working packs to copy from:
`easyide.sample-docker`, `easyide.sample-agents` and `easyide.sample-chat` under
`services/mobile/app/src/main/assets/extension-samples/` (install them from Extensions, **+**, **Install a sample pack**).

## 1. The pieces

| Point | Where in `package.json` | Purpose |
|---|---|---|
| `viewsContainers.sidebar`, `.secondarySidebar`, `.panel` | `contributes` | a switchable group of views in a panel; `scope` (`app`, `workspace`, `both`), `locations` (where the user may move it) |
| `views` | `contributes` | a view of a container; `schema` points at a view file |
| `navigation` | `easyide` | an item on the bottom bar or rail: `target.container` or `target.command`, `scope`, `order`, `when`, `badge` |
| `viewBadge` | `easyide` | the badge of a navigation item, spelled apart from it: `{nav, view, path, kind}` |
| `documents` | `easyide` | a document type opened in the main stage: `ext://<extension id>/<name>/<key>`, with `schema`, `title`, `multiple`, `supportsSplit`, `state.provider` |
| `documentOpeners` | `easyide` | which type opens which file glob: `priority` `default` or `option` (`builtin` is core's and is refused) |
| `layoutPresets` | `easyide` | a named arrangement offered in the layout picker, never applied by itself |
| capability `ui.contribute` | `easyide.capabilities` | required by all of the above; shown at install as "Adds screens and navigation items" |

Rules the validator enforces (`easyide-ext validate`, and again at install): ids are `<publisher>.<name>.<part>` (a document type
is `<publisher>.<name>/<name>`); a navigation title is at most 14 characters; at most 3 navigation items (more are ignored with a
warning), 10 containers, 20 schema views, 10 documents, 10 presets, 20 openers per pack; every `target`, `badge.view` and opener
`type` must name something the pack declares; `order` below 100 is reserved for built-ins (the shell raises it). A bare
`activitybar` or `panel` container and a view without `schema` need no capability and keep working: the container becomes a sidebar
container with a navigation item, and the view shows what `easyide.viewData` (or `ui.setViewData`) supplies as a plain list.
An environment-scoped pack (anything with `sandboxExec`) only contributes while its environment is the open one; put
`"scope": "workspace"` on its containers and navigation. A pack with no process actions is installed globally and works from Home.

## 2. A view file

```json
{
  "viewSchema": 1,
  "state": { "containers": [], "query": "" },
  "root": { "type": "column", "children": [ ... ] }
}
```

`viewSchema` must be `1`. `state` is the view's initial data (an object). `root` is a component. A file is at most 128 KB, 12 levels
deep, 2,000 components; a string is at most 4,096 characters. Any error rejects the file (and the pack); a component the app
cannot draw is skipped at run time, never a crash.

Every component has `type`, and may have `id` (a stable key), `when` (a when-clause) and `weight` (its share of free space in a
row or a filling column). An unknown component or property is an error, so a typo is caught by `validate`.

## 3. Components

| Group | Component | Properties (required first) |
|---|---|---|
| Layout | `column` | `children`; `gap`, `padding` (`none xs s m l`), `align` |
| | `row` | `children`; `gap`, `padding`, `align` (`start center end stretch spread`); an event: the row is tappable |
| | `section` | `title`, `children` (caps header, grouped) |
| | `group` | `children` (a bordered list container) |
| | `tabs` / `tab` | `tabs.children` are `tab` (`title`, `children`); `bind` keeps the selected index in the data |
| | `split` | exactly two `children`; `axis` (`row`, `column`), `ratio` |
| Content | `text` | `value`; `role` (`title body caption mono`), `tone`, `maxLines` |
| | `markdown`, `code`, `diff` | `value` (`code`: `language`; `diff`: a unified diff, coloured by line) |
| | `keyValue` | `label`, `value`; `mono` |
| | `tag` | `value`; `tone` |
| | `statusDot` | `label` (spoken); `tone` |
| | `progress` | `value` (0 to 1; empty is indeterminate); `label`, `tone` |
| | `icon` | `name`; `tone`, `label` |
| | `image` | `src` (png, webp, jpg in the pack, at most 512 KB), `label`; `height` |
| | `banner` | `value`; `tone` |
| | `emptyState` | `message`; `actionLabel` and an event |
| Data | `list` | `bind`, `item` (a row template); `key`, `empty`, `query` + `filterFields` (a live filter) |
| | `tree` | `bind`, `item`; `key`, `childrenField` |
| | `table` | `bind`, `columns` (`title`, `field`; `format`, `mono`, `weight`); `key`, `empty` |
| | `sparkline` | `bind` (numbers), `label`; `tone` |
| | `logStream` | `bind` (an array of lines or a string); `follow` |
| | `chat` | `bind` (messages `{id, role, text, state?, tool?}`); `follow` |
| | `terminal` | reserved: refused by `validate` |
| Input | `button` | `label`, an event; `style` (`primary secondary ghost danger`), `icon`, `enabledWhen` |
| | `iconButton` | `icon`, `label` (its name for a screen reader), an event; `tone`, `enabledWhen` |
| | `toggle` | `label`, `bind` (a boolean); an event on change |
| | `field` | `bind`; `label`, `hint`, `mono`, `multiline`, `validate` (a regex); an event on submit |
| | `select` | `bind`, and one of `options` (`"a"` or `{label, value}`) or `optionsFrom` (a path); an event on change |
| | `slider` | `label`, `bind`; `min`, `max`, `step`; an event on change |
| | `search` | `bind`; `hint`; an event on submit |
| | `form` | `children`; `submitLabel` and an event |
| | `composer` | `bind`, an event; `hint`, `sendLabel`. One per view |

A `list`, `chat`, `logStream`, `table` or `tree` that is a direct part of the view's body (the root, or reached through
`column`, `tabs`, `tab`, `split` only) scrolls and fills the space; anywhere else it shows at most 100 rows inline. `progress`,
`sparkline` and `statusDot` need no more than a value, and their `label` is what a screen reader says. Text that is not on
screen (an `iconButton`'s name, an `image`'s description) is required, not optional.

## 4. Data and templates

A view draws from one data object: its `state`, then whatever writes to it. `bind: "containers"` reads the path `containers`;
paths are object keys and array indexes joined with dots (`env.0.name`). Inside a `list`, `tree` or `table` row the current item
is searched first and the view's data second, so `{name}` is the row's name.

Text properties are templates: `{field}` and `{field|format}` with the formats `bytes`, `duration` (seconds), `relative` (an epoch
in milliseconds) and `count`. `{{` and `}}` are literal braces. A missing field is empty, not an error. This is not the action
language: `${...}` is plain text here.

`when` uses the when-clause grammar on the current item's fields first and the shell's context keys second (`state == running`,
`windowSizeClass == compact`, `!error`). A false `when` removes the component.

Data arrives from:

| Source | How |
|---|---|
| an action | `easyide.viewData` `{ "<view id>": { "kind": "object", "from": <sandboxExec>, "intervalSec": 5 } }`: the JSON on standard output is merged into the data by top-level key (`list` and `tree` write the array to `items`, for schema-less views). `intervalSec` is at least 2. |
| a document's provider | `documents[].state.provider`: a command called with `{uri, key}`; its JSON result is merged into the document's data. `state.intervalSec` refreshes it. `{key}` is also in the data. |
| a WASM provider | `ui.setViewData{viewId, items}`: an object is merged; at most 10 updates a second and 256 KB each. |
| the user | typing in a `field` or `composer`, and the `before` / `after` effects below |

Views that are not on screen fetch nothing. A view named by a navigation badge is fetched once when its item first shows, so
the badge has a number before the view was ever opened. A source that keeps failing is logged once in the Extension Log.

## 5. Events

An interactive component takes one event (click, change or submit, by component) written as:

| Property | Meaning |
|---|---|
| `action` | a command id of the pack (or a built-in), or an inline action object of the [action vocabulary](sdk-reference.md#action-vocabulary) |
| `open` | instead of `action`: a `ext://<own extension id>/<type>/<key>` document, a template (`ext://easyide.sample-docker/container/{id}`) |
| `group` | with `open`: `active`, `beside` or `new` |
| `args` | JSON with template strings, resolved where the event fires; the action reads it as `${arg:name}` or `${arg:a.b}` |
| `confirm` | `{title, body?, destructive?}`: a dialog first. A `button` with `style` `danger` and an `iconButton` with `tone` `danger` must carry it |
| `as`, `mode`, `parse` | write the result back: `as` is the data path (`"."` merges an object), `mode` `set` (default) or `append`, `parse` `json` (default), `text` or `lines` for a `sandboxExec` result |
| `before`, `after` | local effects `{op: set \| append \| clear, path, value?}` run before the call and after it, whatever happened |

A `sandboxExec` result is `{exitCode, stdout, stderr}`; a non-zero exit is reported to the user as a failure (the last line of
standard error) and writes nothing back. An event that is already running is not started twice. The value of a control is
`{value}` in `args` and effects (a toggle's boolean, a field's text), and the bound path names the same value (`{draft}`).

```json
{
  "type": "composer", "bind": "draft", "action": "acme.agent.send", "args": { "id": "{key}", "text": "{draft}" },
  "before": [
    { "op": "append", "path": "messages", "value": { "role": "user", "text": "{draft}" } },
    { "op": "set", "path": "busy", "value": true },
    { "op": "clear", "path": "draft" }
  ],
  "as": "messages", "mode": "append",
  "after": [{ "op": "set", "path": "busy", "value": false }]
}
```

## 6. Limits

| Limit | Value | Where checked |
|---|---|---|
| view file | 128 KB, depth 12, 2,000 components, 4,096 characters per string, 200 entries per literal list | validate and install |
| a pack | 20 schema views, 10 documents, 10 containers, 10 presets, 20 openers, 3 navigation items | validate and install |
| drawn at once | 2,000 components (a list counts the rows a screen shows) | render: the view says "view unavailable: reason" |
| rows | 100,000 per list, tree or table; 100 when inline | render |
| chat and log | 2,000 messages, 5,000 lines (the newest are kept) | render |
| provider updates | 10 a second per view, 256 KB each | update |
| data intervals | at least 2 seconds | validate |

## 7. Icons

An icon is a token from the icon set (`play`, `stop`, `refresh`, `add`, `delete`, `edit`, `check`, `close`, `send`, `copy`,
`info`, `warning`, `star`, `chat` and the navigation glyphs) or an SVG in the pack. A pack SVG is one colour (tinted by the shell,
no dark variant), on a `0 0 24 24` viewBox, at most 16 KB, with no `image`, `script`, gradient, filter, `style` or external
reference. Shapes drawn: `path`, `circle`, `ellipse`, `rect`, `line`, `polyline`, `polygon`.

## 8. Testing without a device

`easyide-ext validate` checks everything above, against the view file that has the problem (`E_VIEW_*`, `E_UI_*`, `E_ICON_RULE`).
`easyide-ext test` renders views in scenarios:

```json
{ "name": "containers", "steps": [], "expect": { "views": [
  { "view": "acme.docker.containers", "data": { "containers": [ { "id": "a1", "name": "web", "state": "running" } ] },
    "types": ["list", "iconButton"], "texts": ["web", "Stop web"], "absent": ["Start web"], "rows": 1 },
  { "view": "acme.docker/container", "data": { "name": "web", "logs": ["ready"] }, "texts": ["ready"] } ] } }
```

`view` is a view id or a document type; `data` is merged over the view's `state`; the tree is the one the app draws (rows of lists,
tables and trees included). Commands still run against fakes as before (`fakes.sandboxExec`, `expect.calls`).

## 9. Not in view schema 1

`terminal`; `composer` slash commands and attachments; images from the network; custom drawing, animation and timers; a live
`docker logs -f` from an action (an action returns when its command has finished; a WASM provider can push updates); a
provider being told a view is hidden (the shell simply stops the fetches it runs itself).
