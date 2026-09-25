# 0029 - Material Icon Theme is the default icon theme; icons are customised by extensions and settings

Status: Accepted (2026-09-25). Supersedes the "nothing vendored" part of [0028](0028-file-icons-drawn-in-house.md).

## Context

The owner wants the icons VS Code shows (the Python snake for `.py`, the Docker whale, per-name folders)
and wants icons customisable through extensions as in VS Code. 0028 declined to vendor a set because its
licence had not been read. It has now been read from primary sources on 2026-09-25.

## Verified licence and maintenance

- Material Icon Theme (`PKief.material-icon-theme`): repository `github.com/material-extensions/vscode-material-icon-theme`,
  licence MIT, "Copyright (c) 2025 Material Extensions"; active (last push 2026-09-25, about 2985 stars, not
  archived). The Open VSX package 5.38.1 carries the same MIT `LICENSE.txt`, kept in the pack and in
  `assets/licenses/MaterialIconTheme-MIT.txt`.
- vscode-icons (`github.com/vscode-icons/vscode-icons`): MIT, "Copyright (c) 2016 Roberto Huertas". Not vendored;
  installable by the user through the .vsix path below.
- Trademarks: the language and tool logos inside both sets remain trademarks of their owners. They are used
  only as file-type indicators (nominative use), as in VS Code; `NOTICE.md` and the pack README say so.

## Decision

1. Vendor Material Icon Theme 5.38.1 as the built-in pack `easyide.material-icons`, from the pinned Open VSX
   vsix (sha256 in the pack README), extracted and pruned of unreferenced icons by `tools/vendor-material-icons.sh`;
   the files are never hand-edited. 1191 SVGs, 1.3 MB raw, about 0.4 MB compressed in the APK.
2. `workbench.iconTheme` defaults to `material-icon-theme`. The in-house pack stays installed as "EasyIDE Simple
   Icons"; "Built-in icons" (empty id) stays. An explicit choice is kept; a profile that never chose moves.
3. Rendering: extend the in-house SVG renderer (gradients, `use`, `clipPath`, `style` attributes, view box
   origin) rather than adopt AndroidSVG. The vendored set needs 62 linear gradients, one radial, 12 `use`,
   4 clip paths and 11 style attributes, about 200 added lines over `android.graphics` shaders and clips,
   and `MaterialIconsRenderTest` fails if the set ever uses a construct the renderer ignores. This adds no
   dependency, keeps the "an icon file reaches nothing outside itself" property (no external references,
   no CSS), and needs no licence review. AndroidSVG (Apache-2.0) was considered and not adopted, so its
   maintenance status was not evaluated.
4. Customisation through extensions: any VS Code icon theme `.vsix` (or unpacked folder) installs through
   Extensions > Install from file; `VsCodeIconThemeAdapter` rewrites only the manifest and keeps only
   `iconThemes`. `../` in theme icon paths resolves inside the package (Material uses `./../icons/`); a path
   leaving the package stays refused. Font-based themes install but cannot be drawn: the app warns and says
   so in Settings instead of failing silently.
5. Customisation through settings: `easyide.icons.fileAssociations` and `easyide.icons.folderAssociations`, applied
   in front of the theme's mappings, checked against the active theme's icon ids in Edit as JSON.

## Alternatives considered

- Keep only the in-house pack: rejected, it does not give the recognisable logos the owner asked for.
- AndroidSVG: see 3.
- Fetch the theme at first run: rejected, needs network and a trust decision for something the APK can carry.
- Full VS Code fidelity for `highContrast` and `rootFolderNames`: not read by the app; the sections stay in the
  vendored file untouched.

## Consequences

- Upgrades are one command; the diff of the vendored folder is the upstream diff.
- First start after each APK update unpacks about 1200 more small files (built-in packs are copied per install).
- Open VSX browse is still not built (extension-sdk tracker); the picked-file path is what works today.
- The APK now carries third-party trademarks as file-type icons; a removal request from an owner is a
  one-line pruning rule in the vendor script.
