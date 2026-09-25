#!/usr/bin/env python3
"""Generates the easyide.file-icons built-in pack from the data under tools/file-icons/.

    python3 tools/build-file-icons.py           write the pack into the APK assets
    python3 tools/build-file-icons.py --check   fail if the committed pack differs from the data
    python3 tools/build-file-icons.py --preview PATH.html   write a contact sheet for eyeballing

Data (source of truth): glyphs.json (8x8 base shapes), font3x5.json (monogram letters),
palette.json (colours), icons-*.txt (file icons and their associations), folders.txt.
Stdlib only; the output is committed and the app never runs this.
"""
import glob
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "tools", "file-icons")
PACK = os.path.join(ROOT, "services", "mobile", "app", "src", "main", "assets", "extensions", "easyide.file-icons")
THEME_ID = "easyide-file-icons"
THEME_PATH = "icons/easyide-file-icons.json"

# Editor, panel and raised surfaces of the built-in palettes (BuiltInPalettes.kt); the app-side
# FileIconsPaletteTest reads the real tokens, this copy only lets the generator fail early.
DARK_SURFACES = ["#0E1014", "#13161B", "#181C22", "#000000", "#0A0B0E"]
LIGHT_SURFACES = ["#FBFAF8", "#F3F2EE", "#ECEAE4", "#FFFFFF", "#F4F4F5"]
MIN_FILL_CONTRAST = 3.0
MIN_INK_CONTRAST = 4.5
MAX_SVG_BYTES = 1500
MAX_THEME_BYTES = 250 * 1024
MAX_TOTAL_SVG_BYTES = 1024 * 1024

STROKE = 'fill="none" stroke="{ink}" stroke-width="1.1" stroke-linecap="round" stroke-linejoin="round"'
PAGE = "M4 1h5.5L13 4.5V14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V2a1 1 0 0 1 1-1z"
FOLD = "M9.5 1v3.5H13z"
FOLDER_BACK = "M2 2h4l1.5 1.5H14a1 1 0 0 1 1 1V13a1 1 0 0 1-1 1H2a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z"
FOLDER_FRONT = "M1 6h14v7a1 1 0 0 1-1 1H2a1 1 0 0 1-1-1z"
FOLDER_OPEN_BACK = "M2 2h4l1.5 1.5H13a1 1 0 0 1 1 1V6H3.5L1 12.4V3a1 1 0 0 1 1-1z"
FOLDER_OPEN_FRONT = "M3.6 6.5H15.6L13.3 13.4a1 1 0 0 1-.95.6H1.5z"


PROBLEMS = []


def fail(message):
    """Data problems are collected so one run lists every one of them."""
    PROBLEMS.append(message)


def num(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return s or "0"


def luminance(hex_color):
    c = [int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    lin = [x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4 for x in c]
    return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]


def contrast(a, b):
    la, lb = sorted((luminance(a), luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def load_json(name):
    with open(os.path.join(DATA, name), encoding="ascii") as f:
        return json.load(f)


class Palette:
    def __init__(self):
        raw = load_json("palette.json")
        self.ink_light, self.ink_dark = raw["ink_light"], raw["ink_dark"]
        self.colors = raw["colors"]
        for name, c in self.colors.items():
            self.check(name, "dark", c["dark"], DARK_SURFACES)
            self.check(name, "light", c.get("light", c["dark"]), LIGHT_SURFACES)

    def fill(self, color, mode):
        c = self.colors[color]
        return c["light"] if mode == "light" and "light" in c else c["dark"]

    def has_light(self, color):
        return "light" in self.colors[color]

    def ink(self, fill):
        return self.ink_light if contrast(self.ink_light, fill) >= contrast(self.ink_dark, fill) else self.ink_dark

    def check(self, name, mode, fill, surfaces):
        worst = min(contrast(fill, s) for s in surfaces)
        if worst < MIN_FILL_CONTRAST:
            sys.exit("palette %s/%s: %s reaches %.2f:1 on a %s surface, need %.1f" % (name, mode, fill, worst, mode, MIN_FILL_CONTRAST))
        ratio = contrast(self.ink(fill), fill)
        if ratio < MIN_INK_CONTRAST:
            sys.exit("palette %s/%s: ink on %s reaches %.2f:1, need %.1f" % (name, mode, fill, ratio, MIN_INK_CONTRAST))


class Glyphs:
    def __init__(self):
        self.shapes = {k: v for k, v in load_json("glyphs.json").items() if not k.startswith("_")}
        self.font = load_json("font3x5.json")["rows"]

    def body(self, content, ink):
        """SVG elements of a glyph or monogram on the 8x8 box, painted in [ink]."""
        kind, _, value = content.partition(":")
        if kind == "t":
            return self.text(value, ink)
        if value == "-":
            return ""
        return self.shape(self.shapes[value], ink)

    def shape(self, tokens, ink):
        groups = {"s": [], "so": [], "f": [], "fo": []}
        for tok in tokens:
            k, _, d = tok.partition(":")
            groups[k].append('<path d="%s"/>' % d)
        out = ""
        if groups["s"]:
            out += "<g %s>%s</g>" % (STROKE.format(ink=ink), "".join(groups["s"]))
        if groups["so"]:
            out += '<g %s opacity=".6">%s</g>' % (STROKE.format(ink=ink), "".join(groups["so"]))
        if groups["f"]:
            out += '<g fill="%s">%s</g>' % (ink, "".join(groups["f"]))
        if groups["fo"]:
            out += '<g fill="%s" opacity=".55">%s</g>' % (ink, "".join(groups["fo"]))
        return out

    def text(self, letters, ink):
        width = 4 * len(letters) - 1
        x0 = (8 - width) / 2
        d = ""
        for n, ch in enumerate(letters):
            rows = self.font[ch]
            for y, row in enumerate(rows):
                x = 0
                while x < 3:
                    if row[x] == "1":
                        run = 1
                        while x + run < 3 and row[x + run] == "1":
                            run += 1
                        d += "M%s %sh%dv1h-%dz" % (num(x0 + 4 * n + x), 2 + y, run, run)
                        x += run
                    else:
                        x += 1
        return '<path fill="%s" d="%s"/>' % (ink, d)


def wrap(inner):
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">%s</svg>' % inner


def page_svg(glyphs, fill, ink, content):
    body = glyphs.body(content, ink)
    place = '<g transform="translate(4 6)">%s</g>' % body if body else ""
    return wrap('<path fill="%s" d="%s"/><path fill="#fff" fill-opacity=".35" d="%s"/>%s' % (fill, PAGE, FOLD, place))


def folder_svg(glyphs, fill, ink, content, opened):
    """The back panel is the fill darkened; the front panel is the plain fill, so the glyph keeps the ink contrast."""
    back, front = (FOLDER_OPEN_BACK, FOLDER_OPEN_FRONT) if opened else (FOLDER_BACK, FOLDER_FRONT)
    body = glyphs.body(content, ink)
    dx = 0.4 if opened else 0
    place = '<g transform="translate(%s 6.9) scale(.75)">%s</g>' % (num(5 + dx), body) if body else ""
    return wrap('<path fill="%s" d="%s"/><path fill="#000" fill-opacity=".3" d="%s"/><path fill="%s" d="%s"/>%s' % (fill, back, back, fill, front, place))


def parse_lines(path):
    for raw in open(path, encoding="ascii"):
        line = raw.strip()
        if line and not line.startswith("#"):
            yield line.split()


def parse_icons(path):
    icons = []
    for tok in parse_lines(path):
        icon = {"id": tok[0], "color": tok[1], "content": tok[2], "e": [], "n": [], "l": []}
        for extra in tok[3:]:
            key, _, vals = extra.partition("=")
            icon[key] = [v for v in vals.split(",") if v]
        icons.append(icon)
    return icons


def parse_folders():
    return [{"id": t[0], "color": t[1], "content": t[2], "n": [v for v in (t[3][2:].split(",") if len(t) > 3 else []) if v]}
            for t in parse_lines(os.path.join(DATA, "folders.txt"))]


def build():
    palette, glyphs = Palette(), Glyphs()
    icons = [i for f in sorted(glob.glob(os.path.join(DATA, "icons-*.txt"))) for i in parse_icons(f)]
    folders = parse_folders()
    files, defs, light_defs = {}, {}, {}
    seen_look, seen_id = {}, set()

    def add(icon_id, svg, light=False):
        if len(svg.encode()) > MAX_SVG_BYTES:
            sys.exit("%s is %d bytes, limit %d" % (icon_id, len(svg), MAX_SVG_BYTES))
        files["icons/svg/%s.svg" % icon_id] = svg
        (light_defs if light else defs)[icon_id] = {"iconPath": "./svg/%s.svg" % icon_id}

    alias = {}  # icon id -> the id whose SVG it shares (same colour and glyph)
    for i in icons:
        if i["id"] in seen_id:
            fail("duplicate icon id " + i["id"])
        seen_id.add(i["id"])
        look = (i["color"], i["content"])
        if look in seen_look:
            alias[i["id"]] = seen_look[look]
            continue
        seen_look[look] = i["id"]
        for mode in ("dark", "light") if palette.has_light(i["color"]) else ("dark",):
            fill = palette.fill(i["color"], mode)
            svg = page_svg(glyphs, fill, palette.ink(fill), i["content"])
            add(i["id"] if mode == "dark" else i["id"] + "-light", svg, mode == "light")
    for icon_id, owner in alias.items():
        defs[icon_id] = defs[owner]
        if owner + "-light" in light_defs:
            light_defs[icon_id + "-light"] = light_defs[owner + "-light"]
    for f in folders:
        for opened in (False, True):
            name = "folder-%s%s" % (f["id"], "-open" if opened else "")
            for mode in ("dark", "light") if palette.has_light(f["color"]) else ("dark",):
                fill = palette.fill(f["color"], mode)
                add(name if mode == "dark" else name + "-light", folder_svg(glyphs, fill, palette.ink(fill), f["content"], opened), mode == "light")

    theme = {"iconDefinitions": {}, "fileExtensions": {}, "fileNames": {}, "languageIds": {}, "folderNames": {}, "folderNamesExpanded": {}}
    light = {"fileExtensions": {}, "fileNames": {}, "languageIds": {}, "folderNames": {}, "folderNamesExpanded": {}}

    def put(table, key, icon_id, has_light):
        if key in theme[table]:
            return fail("%s '%s' maps to both %s and %s" % (table, key, theme[table][key], icon_id))
        theme[table][key] = icon_id
        if has_light:
            light[table][key] = icon_id + "-light"

    for i in icons:
        has_light = palette.has_light(i["color"])
        for table, key in (("fileExtensions", "e"), ("fileNames", "n")):
            for v in i[key]:
                put(table, v.lower(), i["id"], has_light)
        for v in i["l"]:
            put("languageIds", v, i["id"], has_light)
    for f in folders:
        has_light = palette.has_light(f["color"])
        for n in f["n"]:
            put("folderNames", n.lower(), "folder-" + f["id"], has_light)
            put("folderNamesExpanded", n.lower(), "folder-%s-open" % f["id"], has_light)

    if PROBLEMS:
        sys.exit("\n".join(PROBLEMS))
    theme["iconDefinitions"] = {**defs, **light_defs}
    ordered = {"iconDefinitions": dict(sorted(theme["iconDefinitions"].items())),
               "file": "file", "folder": "folder-plain", "folderExpanded": "folder-plain-open",
               "rootFolder": "folder-root", "rootFolderExpanded": "folder-root-open"}
    for table in ("fileExtensions", "fileNames", "languageIds", "folderNames", "folderNamesExpanded"):
        ordered[table] = dict(sorted(theme[table].items()))
    light_out = {}
    for table, m in light.items():
        if m:
            light_out[table] = dict(sorted(m.items()))
    ordered["light"] = light_out
    theme_text = json.dumps(ordered, indent=1, ensure_ascii=True) + "\n"
    if len(theme_text.encode()) > MAX_THEME_BYTES:
        sys.exit("theme JSON is %d bytes, limit %d" % (len(theme_text.encode()), MAX_THEME_BYTES))
    total = sum(len(v.encode()) for v in files.values())
    if total > MAX_TOTAL_SVG_BYTES:
        sys.exit("SVGs total %d bytes, limit %d" % (total, MAX_TOTAL_SVG_BYTES))

    files[THEME_PATH] = theme_text
    files["package.json"] = json.dumps(manifest(), indent=2) + "\n"
    files["README.md"] = readme(icons, folders, ordered, files)
    return files, icons, folders, ordered, palette, glyphs


def manifest():
    return {
        "name": "file-icons", "publisher": "easyide", "version": "1.0.0", "displayName": "EasyIDE Simple Icons",
        "description": "Coloured file and folder icons for source, config, data, media, archives, binaries, keys and 3D files, drawn in-house. The simple alternative to the default Material Icon Theme; pick it in Settings.",
        "license": "Apache-2.0", "engines": {"easyide": "^0.3.0"}, "categories": ["Themes"],
        "contributes": {"iconThemes": [{"id": THEME_ID, "label": "EasyIDE Simple Icons", "path": "./" + THEME_PATH}]},
    }


def readme(icons, folders, theme, files):
    file_icons = len(icons)
    variants = sum(1 for k in files if k.startswith("icons/svg/"))
    lines = [
        "# easyide.file-icons", "",
        "Generated by `tools/build-file-icons.py` from `tools/file-icons/`; edit the data, not this folder.", "",
        "## Coverage", "",
        "| Table | Entries |", "|---|---|",
        "| File extensions | %d |" % len(theme["fileExtensions"]),
        "| Exact file names | %d |" % len(theme["fileNames"]),
        "| Language ids | %d |" % len(theme["languageIds"]),
        "| Folder names (each with an open variant) | %d |" % len(theme["folderNames"]),
        "| File icons | %d |" % file_icons,
        "| Folder kinds | %d |" % len(folders),
        "| SVG files (dark and light variants) | %d |" % variants, "",
        "Defaults: `file` (generic page), `folder-plain` / `folder-plain-open`, `folder-root` / `folder-root-open`.", "",
        "## Icon list", "", "| Icon | Colour | Extensions | Names | Language ids |", "|---|---|---|---|---|",
    ]
    for i in icons:
        lines.append("| %s | %s | %d | %d | %d |" % (i["id"], i["color"], len(i["e"]), len(i["n"]), len(i["l"])))
    lines += ["", "## Folder kinds", ""] + ["- `%s` (%s): %s" % (f["id"], f["color"], ", ".join(f["n"][:8]) or "generic") for f in folders]
    lines += ["", "## Licence", "",
              "Apache-2.0, like the other first-party packs (decision 0015). Every glyph is drawn in-house for this pack: pages, folders,",
              "the 8x8 glyph set and the 3x5 pixel letters are original artwork. No third-party icon set or brand logo is vendored",
              "(decision 0028), so no third-party notice is required.", ""]
    return "\n".join(lines)


def write_all(files):
    for rel, text in files.items():
        path = os.path.join(PACK, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="ascii", newline="\n") as f:
            f.write(text)
    known = set(files)
    for dirpath, _, names in os.walk(PACK):
        for n in names:
            rel = os.path.relpath(os.path.join(dirpath, n), PACK).replace(os.sep, "/")
            if rel not in known:
                os.remove(os.path.join(dirpath, n))


def check(files):
    stale = []
    for rel, text in files.items():
        path = os.path.join(PACK, rel)
        if not os.path.isfile(path) or open(path, encoding="ascii").read() != text:
            stale.append(rel)
    on_disk = {os.path.relpath(os.path.join(d, n), PACK).replace(os.sep, "/") for d, _, ns in os.walk(PACK) for n in ns}
    stale += sorted(on_disk - set(files))
    if stale:
        sys.exit("file-icons pack is stale, run tools/build-file-icons.py: " + ", ".join(stale[:10]))


def preview(path, files, icons, folders):
    cells = []
    for i in icons:
        if "icons/svg/%s.svg" % i["id"] in files:
            cells.append((i["id"], files["icons/svg/%s.svg" % i["id"]]))
    for f in folders:
        cells.append(("folder-" + f["id"], files["icons/svg/folder-%s.svg" % f["id"]]))
        cells.append(("folder-%s-open" % f["id"], files["icons/svg/folder-%s-open.svg" % f["id"]]))
    rows = ""
    for bg, fg in (("#0E1014", "#d5dae1"), ("#FBFAF8", "#222")):
        rows += '<div style="background:%s;color:%s;padding:8px;display:flex;flex-wrap:wrap;gap:6px">' % (bg, fg)
        for name, svg in cells:
            light = name + "-light"
            if bg == "#FBFAF8" and "icons/svg/%s.svg" % light in files:
                svg = files["icons/svg/%s.svg" % light]
            rows += '<div style="width:88px;font:9px monospace;text-align:center"><div style="display:flex;gap:4px;justify-content:center;align-items:center;height:48px">%s<span style="display:inline-block;width:16px;height:16px">%s</span></div>%s</div>' % (
                svg.replace("<svg ", '<svg width="48" height="48" ', 1), svg.replace("<svg ", '<svg width="16" height="16" ', 1), name)
        rows += "</div>"
    with open(path, "w") as f:
        f.write("<!doctype html><meta charset=utf-8><body style='margin:0'>" + rows)


def main(argv):
    files, icons, folders, theme, _, _ = build()
    if "--check" in argv:
        check(files)
    elif "--preview" in argv:
        preview(argv[argv.index("--preview") + 1], files, icons, folders)
    else:
        write_all(files)
        print("icons %d, folders %d, ext %d, names %d, langs %d, folder names %d, svgs %d" % (
            len(icons), len(folders), len(theme["fileExtensions"]), len(theme["fileNames"]), len(theme["languageIds"]),
            len(theme["folderNames"]), sum(1 for k in files if k.startswith("icons/svg/"))))


if __name__ == "__main__":
    main(sys.argv[1:])
