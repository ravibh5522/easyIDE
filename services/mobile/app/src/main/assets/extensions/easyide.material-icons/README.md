# easyide.material-icons

Vendored copy of the Material Icon Theme, so file and folder rows show the real language and tool logos (Python, Docker, Git, ...) as they do in VS Code.

| | |
|---|---|
| Upstream | https://github.com/material-extensions/vscode-material-icon-theme (PKief.material-icon-theme) |
| Pinned version | 5.38.1 |
| Source | https://open-vsx.org/api/PKief/material-icon-theme/5.38.1/file/PKief.material-icon-theme-5.38.1.vsix |
| sha256 of the vsix | `fa7515831a2d68b1f78bd02de40f96260bfe74efb03a238c2bde70265e04b696` |
| Licence | MIT, Copyright (c) 2025 Material Extensions (`LICENSE.txt`, also `assets/licenses/MaterialIconTheme-MIT.txt`) |

The language and tool logos inside the icons remain trademarks of their owners; they are used as file-type indicators only, as in VS Code.

`dist/material-icons.json` and `icons/*.svg` are extracted by `tools/vendor-material-icons.sh`, which checks the hash above and drops the icons (and their definitions) that no mapping references. Do not edit them by hand; to upgrade, change `VERSION` and `SHA256` in the script and run it.

`package.json` is the only file written here by hand: the easyIDE manifest that contributes the theme.
