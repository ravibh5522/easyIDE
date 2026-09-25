# Extension SDK - Authoring icon themes

An icon theme gives files and folders their icons in the explorer, quick open, Recent, Search, source
control rows and the icon picker preview. The format is VS Code's `iconThemes` contribution; this page
lists what easyIDE reads and how the built-in `easyide.file-icons` pack is made, which is the model to copy.

## Manifest and theme file

```json
"contributes": { "iconThemes": [ { "id": "my-icons", "label": "My Icons", "path": "./icons/theme.json" } ] }
```

The theme file (JSON with comments allowed) holds `iconDefinitions` (`id` -> `{ "iconPath": "./svg/x.svg" }`,
relative to the theme file, inside the package, no `..`), the defaults `file`, `folder`, `folderExpanded`,
`rootFolder`, `rootFolderExpanded`, and the tables `fileNames`, `fileExtensions`, `languageIds`,
`folderNames`, `folderNamesExpanded`, plus an optional `light` section with the same tables. Font icons
(`fonts`, `fontCharacter`) are ignored. `manifest validate` checks every `iconPath` exists.

Lookup for a file, first hit wins, all case-insensitive: `fileNames` > `fileExtensions` (longest suffix
first: `a.test.tsx` tries `test.tsx` then `tsx`) > `languageIds` (the id of the app's grammar or pack
language) > `file`. Folders: `folderNames` (`folderNamesExpanded` when open) > `folder`/`folderExpanded`.
On a light editor base the `light` section is asked first at every step, then the root section, so a
light section only needs the entries whose icon differs; repeat the same key you override.

## Images

PNG and SVG. Icons are drawn at the row's pixel size (a 16dp icon on a 2.5x screen is rasterised at 40px)
in their own colours, cached per path and size, off the main thread. The SVG subset: `svg` with `viewBox`,
`g`, `path`, `rect`, `circle`, `ellipse`, `line`, `polygon`, `polyline`; attributes `fill`, `stroke`,
`stroke-width`, `stroke-linecap`, `stroke-linejoin`, `fill-rule`, `opacity`, `fill-opacity`,
`stroke-opacity`, `transform` (`translate`, `scale`, `rotate`, `matrix`), inherited through `g`. Colours are
`#rgb`, `#rrggbb`, `#rrggbbaa`, `none`, `white`, `black`. Gradients, filters, text, CSS, `use` and images
inside SVG are not drawn. Keep each file under 256 KB (the app refuses larger); the built-in pack keeps
each under 1.5 KB.

## Adding an icon to the built-in pack

The pack is generated: edit the data under `tools/file-icons/`, run `python3 tools/build-file-icons.py`, and
commit the data and the output (`--check` fails when they differ; `--preview sheet.html` writes a contact
sheet for both surfaces).

- A file type: one line in `icons-*.txt`: `id color t:XX|g:glyph [e=exts] [n=names] [l=languageIds]`.
  `t:` is a two-letter monogram from the 3x5 font (`font3x5.json`); `g:` is an 8x8 glyph from `glyphs.json`.
  A key claimed by two icons, or two icons with the same colour and content, is an error, or shares one SVG.
- A glyph: add tokens to `glyphs.json` on the 0..8 box (`s:` stroked path, `f:` filled, `so:`/`fo:` at
  partial opacity), then look at it in the preview.
- A folder: a line in `folders.txt`; the open variant is generated.
- A colour: `palette.json`; a colour that cannot reach 3:1 on both dark and light surfaces takes a `light`
  value. The generator and `FileIconsPaletteTest` reject anything below 3:1 (fill) or 4.5:1 (ink).
- Budgets enforced by the generator and `FileIconsThemeTest`: theme JSON under 250 KB, each SVG under
  1.5 KB, all SVGs under 1 MB.

The pack is selected by default (`workbench.iconTheme` = `easyide-file-icons`); "Built-in icons" in Settings
stores an empty id, and disabling the pack in Extensions falls back to the built-in glyphs.
