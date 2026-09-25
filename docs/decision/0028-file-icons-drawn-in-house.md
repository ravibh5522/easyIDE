# 0028 - File icons are drawn in-house, not vendored

Status: Accepted (2026-09-25); the Material Icon Theme is now the default and is vendored, see [0029](0029-material-icon-theme-default-and-icon-customisation.md). This pack remains as "EasyIDE Simple Icons".

## Context

The owner asked for a file icons extension that "supports all types of files". The app already
reads VS Code-shaped icon themes ([customization.md sec 9](../extension-sdk/lld/customization.md)), so the
work is the artwork and the pack. Mature open icon sets exist (Material Icon Theme, vscode-icons).
Redistributing artwork inside the APK needs its licence and trademark terms to be clear: icon sets
of this kind commonly mix a permissive licence on the set with logos of third-party products that
carry their own trademark terms, and the APK ships to devices with no way to recall a bad notice.

## Decision

`easyide.file-icons` uses only original artwork drawn for it: a page and a folder silhouette, 72
glyphs on an 8x8 box, and a 3x5 pixel font for two-letter monograms (`tools/file-icons/`). A
generator (`tools/build-file-icons.py`, stdlib Python) turns that data plus a documented, contrast
checked palette into the pack; the output is committed and the app never generates icons. No
third-party icon set, brand logo or vendored SVG is in the repository, so the pack is Apache-2.0
like the other first-party packs ([0015](0015-extension-sdk-licensing-apache.md)) and needs no entry in
the licences asset.

The licence terms of Material Icon Theme and vscode-icons were not read from their repositories in
this work (not a network-enabled session), so nothing from them is used, and no claim is made about
them here. Anyone proposing to vendor a set must verify the LICENSE file and the trademark notes for
each logo first, record the result here, and ship the notice file.

## Alternatives considered

- Vendor Material Icon Theme or vscode-icons - rejected: licence and per-logo trademark terms
  unverified, and their language logos are third-party marks; the drawn look also would not match the
  in-house palette rules.
- Monochrome glyphs tinted by a token - rejected: the owner wants recognisable per-language colour, and
  monochrome would not differ from the built-in glyphs.
- Text-only monograms - rejected: media, config and binary files are not readable as letters.

## Consequences

- A new language or format is a line of data, not a drawing session; a new glyph is one entry in
  `glyphs.json`. Distinct languages read by colour plus two letters, not by a logo people recognise.
- The palette is one table; `FileIconsPaletteTest` holds it to 3:1 (fills) and 4.5:1 (ink) on every
  built-in palette, and colours that cannot do both modes have a light twin.
- Icons need an SVG renderer: a small subset parser and rasteriser in the app, no library dependency.
