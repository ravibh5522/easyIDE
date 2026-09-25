# Extension SDK - Authoring icon themes

An icon theme gives files and folders their icons in the explorer, quick open, Recent, Search, source
control rows and the icon picker preview. The format is VS Code's `iconThemes` contribution; this page
lists what easyIDE reads, how a VS Code icon theme installs, how users override single icons in settings.json,
and how the built-in packs are made: `easyide.material-icons` (the default, a vendored copy of the Material
Icon Theme) and `easyide.file-icons` ("EasyIDE Simple Icons", drawn in-house).

## Manifest and theme file

```json
"contributes": { "iconThemes": [ { "id": "my-icons", "label": "My Icons", "path": "./icons/theme.json" } ] }
```

The theme file (JSON with comments allowed) holds `iconDefinitions` (`id` -> `{ "iconPath": "./svg/x.svg" }`,
relative to the theme file; `../` may step up to a sibling folder, as in `./../icons/x.svg`, but not out of
the package), the defaults `file`, `folder`, `folderExpanded`,
`rootFolder`, `rootFolderExpanded`, and the tables `fileNames`, `fileExtensions`, `languageIds`,
`folderNames`, `folderNamesExpanded`, plus an optional `light` section with the same tables. Font icons
(`fonts`, `fontCharacter`) are not supported: those definitions are dropped, the manifest check warns, and
a theme made only of font icons shows a note under the picker in Settings and leaves the built-in glyphs
in place. `manifest validate` checks every `iconPath` exists.

Lookup for a file, first hit wins, all case-insensitive: `fileNames` > `fileExtensions` (longest suffix
first: `a.test.tsx` tries `test.tsx` then `tsx`) > `languageIds` (the id of the app's grammar or pack
language) > `file`. Folders: `folderNames` (`folderNamesExpanded` when open) > `folder`/`folderExpanded`.
On a light editor base the `light` section is asked first at every step, then the root section, so a
light section only needs the entries whose icon differs; repeat the same key you override.

## Images

PNG and SVG. Icons are drawn at the row's pixel size (a 16dp icon on a 2.5x screen is rasterised at 40px)
in their own colours, cached per path and size, off the main thread. The SVG subset: `svg` with `viewBox`
(origin honoured, fitted and centred), `g`, `path`, `rect`, `circle`, `ellipse`, `line`, `polygon`,
`polyline`, `use` (`#id` of the same file, with `x`/`y`), `linearGradient`/`radialGradient` fills (stops,
`xlink:href` inheritance, `gradientUnits`, `gradientTransform`) and `clipPath` of shapes; attributes `fill`,
`stroke`, `stroke-width`, `stroke-linecap`, `stroke-linejoin`, `fill-rule`, `opacity`, `fill-opacity`,
`stroke-opacity`, `transform` (`translate`, `scale`, `rotate`, `matrix`) and `clip-path`, inherited through `g`
and also read from a `style` attribute. Colours are `#rgb`, `#rrggbb`, `#rrggbbaa`, `none`, `white`, `black`.
Filters, masks, text, `<style>` sheets, gradient strokes and images inside SVG are not drawn. Keep each file
under 256 KB (the app refuses larger). `MaterialIconsRenderTest` reports any element the vendored set uses
that the renderer would ignore, so the subset and the pack cannot drift apart.

## Installing a VS Code icon theme

Extensions > Install from file accepts a `.vsix` (or its unpacked folder) that contributes `iconThemes`, for
example from Open VSX (`PKief.material-icon-theme`, `vscode-icons-team.vscode-icons`,
`Catppuccin.catppuccin-vsc-icons`). Only the icon themes are taken: the manifest is rewritten to an easyIDE
one (lower-case id, `engines.easyide`, `contributes.iconThemes` only) and everything else the extension
declares (commands, colour themes, settings, code) is dropped; the result then goes through the ordinary
package checks. The theme appears in Settings > Appearance > File icon theme and applies live. There is no
Open VSX browse yet (extension-sdk tracker), so the file is picked by hand. Themes drawn with an icon font
install but cannot be shown; see above.

## Your own associations

Two settings in settings.json (Appearance, profile layers, merged key by key) go in front of the active
theme's own mappings; they name icon ids of the active theme:

```json
"easyide.icons.fileAssociations": { "*.foo": "python", "Jenkinsfile": "docker", "docker-compose*.yml": "docker" },
"easyide.icons.folderAssociations": { "api": "folder-api" }
```

File keys are case-insensitive: an exact name (`Jenkinsfile`, `.env`), a suffix without a glob character
(`foo` or `.foo` matches `a.foo` and `a.b.foo`), or a glob with `*` and `?` over the whole name. Order:
exact name, then globs in file order, then suffixes longest first, then the theme. Folder keys are exact
names; an open folder uses `<id>-open` when the theme defines it, else the same icon. The icon is used in
light and dark alike. An id the active theme does not define is ignored (the theme's own icon shows) and is
reported as a warning in Edit as JSON. The ids of a theme are the keys of its `iconDefinitions`; with the
default theme they are the file names in `easyide.material-icons/icons`.

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

The Material Icon Theme (`workbench.iconTheme` = `material-icon-theme`) is selected by default; this pack is
the selectable "EasyIDE Simple Icons" (`easyide-file-icons`). "Built-in icons" in Settings stores an empty id,
and disabling a pack in Extensions falls back to the built-in glyphs. Someone who picked a theme explicitly
keeps it; only a profile that never chose one moves to the Material Icon Theme.

## The vendored Material Icon Theme

`easyide.material-icons` is `PKief.material-icon-theme` 5.38.1, MIT, taken unmodified from the Open VSX vsix
by `tools/vendor-material-icons.sh` (pinned URL and sha256; it also drops the icons no mapping references).
Never edit `dist/` or `icons/` by hand; to upgrade, change `VERSION` and `SHA256` in the script and run it.
Decision and licence notes: [ADR 0029](../decision/0029-material-icon-theme-default-and-icon-customisation.md).
