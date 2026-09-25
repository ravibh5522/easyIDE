#!/usr/bin/env bash
# Re-fetches the pinned Material Icon Theme vsix from Open VSX, verifies its sha256 and lays out
# the built-in pack easyide.material-icons. Upgrading = change VERSION and SHA256, run this.
# Icons no mapping references are dropped (with their definitions) to keep the APK small; the
# rest of the upstream files are copied unmodified.
set -euo pipefail

VERSION=5.38.1
SHA256=fa7515831a2d68b1f78bd02de40f96260bfe74efb03a238c2bde70265e04b696
URL="https://open-vsx.org/api/PKief/material-icon-theme/${VERSION}/file/PKief.material-icon-theme-${VERSION}.vsix"

root="$(cd "$(dirname "$0")/.." && pwd)"
pack="$root/services/mobile/app/src/main/assets/extensions/easyide.material-icons"
licenses="$root/services/mobile/app/src/main/assets/licenses"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

curl -fsSL -o "$work/mi.vsix" "$URL"
got="$(sha256sum "$work/mi.vsix" | cut -d' ' -f1)"
if [ "$got" != "$SHA256" ]; then
  echo "sha256 mismatch for $URL: expected $SHA256, got $got" >&2
  exit 1
fi
unzip -q "$work/mi.vsix" -d "$work/x"
src="$work/x/extension"
[ -f "$src/LICENSE.txt" ] || { echo "vsix has no LICENSE.txt" >&2; exit 1; }

rm -rf "$pack/dist" "$pack/icons"
mkdir -p "$pack/dist" "$pack/icons"
cp "$src/LICENSE.txt" "$pack/LICENSE.txt"
cp "$src/LICENSE.txt" "$licenses/MaterialIconTheme-MIT.txt"

python3 - "$src" "$pack" <<'PY'
import json, os, shutil, sys

src, pack = sys.argv[1], sys.argv[2]
theme = json.load(open(os.path.join(src, "dist", "material-icons.json")))
defs = theme["iconDefinitions"]
tables = ("fileNames", "fileExtensions", "languageIds", "folderNames", "folderNamesExpanded",
          "rootFolderNames", "rootFolderNamesExpanded")
singles = ("file", "folder", "folderExpanded", "rootFolder", "rootFolderExpanded")

def ids(section):
    out = {section[k] for k in singles if k in section}
    for t in tables:
        out.update(section.get(t, {}).values())
    return out

used = ids(theme)
for variant in ("light", "highContrast"):
    used |= ids(theme.get(variant, {}))
theme["iconDefinitions"] = {k: v for k, v in defs.items() if k in used}

kept = 0
for icon in theme["iconDefinitions"].values():
    rel = os.path.normpath(os.path.join(src, "dist", icon["iconPath"]))
    dest = os.path.join(pack, os.path.relpath(rel, src))
    shutil.copyfile(rel, dest)
    kept += 1
with open(os.path.join(pack, "dist", "material-icons.json"), "w") as f:
    json.dump(theme, f, separators=(",", ":"), sort_keys=False)
    f.write("\n")
print(f"kept {kept} of {len(defs)} icons")
PY
