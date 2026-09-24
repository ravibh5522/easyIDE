#!/usr/bin/env bash
# ui-lint.sh - the greppable UI rules of docs/ui-redesign/ux-rules.md section 12.
#
# Usage (from anywhere; needs only bash, python3):
#   tools/ui-lint.sh                    fail (exit 1) on violations not in the baseline
#   tools/ui-lint.sh --all              list every violation, baseline or not (exit 0)
#   tools/ui-lint.sh --update-baseline  rewrite tools/ui-lint-baseline.txt from the tree
#
# Rules (id, rule, where):
#   U-COL-01  Color(0x..), Color.White/Black          all of app/src/main except ui/theme, ui/kit, ui/icons (a glyph is an opaque mask the tint replaces)
#   U-ICO-01  androidx.compose.material.icons import  ui/kit, ui/shell (icons come from the ui/icons resolver)
#   U-KIT-COL MaterialTheme.colorScheme read          ui/kit (kit reads ThemeTokens, not M3)
#   U-DP-01   raw N.dp literal (0.dp exempt; a named `val X = N.dp` is the token itself)  ui/kit, ui/shell
#   U-TYP-01  Kit.type., MaterialTheme.typography, TypeScale. (the one type set is Kit.text)  all except ui/theme, ui/kit
#   U-TYP-02  raw N.sp literal                        all except ui/theme, ui/kit/KitText.kt
#   U-DEN-01  raw dp control height: .height/.heightIn(min =)/defaultMinSize(minHeight =)/.size(N.dp)  ui/kit, ui/shell
#             (overlaps U-DP-01 on purpose so the density rule has its own id; a named `val X = N.dp` is exempt)
#   U-TYP-08  string literal in Text(...) copy        ui/screens, ui/components, ui/shell, ui/kit
#   U-MOT-01  tween(N), durationMillis = N literals   everything except ui/props/Motion.kt
#   U-MOT-02  rememberInfiniteTransition              everything except CursorBlock
#   U-AI-03   Brush gradients, blur(                  everything (command palette scrim excepted by baseline)
#   U-AI-05   elevation/shadowElevation above 0, .shadow(   ui/screens, ui/components, ui/shell (floating surfaces live in ui/kit)
#   U-AI-06   CircleShape (icon-in-a-tinted-circle)   ui/screens, ui/components, ui/shell
#   U-AI-08   Oops, Something went wrong, Let's, please, successfully, an exclamation mark   res/values*/strings*.xml
#   U-TYP-05  Title Case string (2..8 words, every later word capitalised, proper nouns excepted)   res/values*/strings*.xml
#
# The baseline (tools/ui-lint-baseline.txt) lists today's violations as
# "RULE<TAB>path<TAB>trimmed source line", one per occurrence. Matching ignores line numbers,
# so unrelated edits do not churn it; a violation is new when its (rule, path, text) occurs
# more often than the baseline allows. Fixing one leaves a stale entry: the script says so, and
# --update-baseline drops it. Never add lines to the baseline by hand to silence a new finding.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$root/services/mobile/app/src/main/java"
baseline="$root/tools/ui-lint-baseline.txt"

exec python3 - "$src" "$baseline" "${1:-}" <<'PY'
import os, re, sys
from collections import Counter

src, baseline_path, mode = sys.argv[1], sys.argv[2], sys.argv[3]
if mode not in ("", "--all", "--update-baseline"):
    sys.exit("usage: ui-lint.sh [--all | --update-baseline]")

UI = "dev/easyide/app/ui/"
THEME, KIT, SHELL, ICONS = UI + "theme/", UI + "kit/", UI + "shell/", UI + "icons/"
COPY_DIRS = (UI + "screens/", UI + "components/", SHELL, KIT)
MOTION_TABLE = UI + "props/Motion.kt"
KIT_TEXT = KIT + "KitText.kt"

def under(path, *dirs):
    return path.startswith(dirs)

# (id, compiled regex, applies-to predicate). Regexes run on the code with comments and
# string contents left intact only where the rule needs them.
COLOR = re.compile(r"\bColor\(\s*0[xX]|\bColor\.(White|Black)\b")
ICON_IMPORT = re.compile(r"^import androidx\.compose\.material\.icons\.")
KIT_SCHEME = re.compile(r"MaterialTheme\.colorScheme")
DP = re.compile(r"(?<![\w.])(\d+(\.\d+)?)\.dp\b")
NAMED_DP = re.compile(r"^\s*(private |internal )?(const )?val \w+(: Dp)? = [\d.]+\.dp\s*$")
DURATION = re.compile(r"\btween\(\s*\d|\b(durationMillis|delayMillis)\s*=\s*\d")
LOOP = re.compile(r"\brememberInfiniteTransition\b|\brepeatable\(|\binfiniteRepeatable\(")
FLASHY = re.compile(r"\bBrush\.\w*Gradient\(|\bblur\(")
OLD_TYPE = re.compile(r"(?<![\w.])Kit\.type\.|(?<![\w.])MaterialTheme\.typography\b|(?<![\w.])TypeScale\.")
RAW_SP = re.compile(r"(?<![\w.])\d+(\.\d+)?\.sp\b")
CONTROL_DP = re.compile(
    r"\.height\(\s*(\d+(\.\d+)?)\.dp\b|\.heightIn\(\s*min\s*=\s*(\d+(\.\d+)?)\.dp\b"
    r"|\bdefaultMinSize\(\s*minHeight\s*=\s*(\d+(\.\d+)?)\.dp\b|\.size\(\s*(\d+(\.\d+)?)\.dp\b")
TEXT_LIT = re.compile(r'\bText\(\s*(?:text\s*=\s*)?"((?:[^"\\]|\\.)*)"')
TEMPLATE = re.compile(r"\$\{[^}]*\}|\$\w+")

ELEVATION = re.compile(r"\b(shadowElevation|tonalElevation|elevation)\s*=\s*[1-9]|\.shadow\(")
CIRCLE = re.compile(r"\bCircleShape\b")
FORBIDDEN_WORDS = re.compile(r"\bOops\b|Something went wrong|\bLet(?:'|&apos;|\\')s\b|\bplease\b|\bsuccessfully\b|!(?=\s|$|\\?\")", re.I)
STRING = re.compile(r'<string name="([^"]+)"[^>]*>(.*?)</string>', re.S)
# Words a sentence-case string may still capitalise: names of products, languages, keys and acronyms.
PROPER = set("Git GitHub Linux Android JSON LSP SSH HTTPS HTTP PATH Markdown Python Rust Go Kotlin TypeScript JavaScript VS Code ANSI README URL ID UTF SAF APK Ctrl Alt Shift Enter Tab Esc Escape Settings OK Geist Mono Unicode Bash Zsh Ubuntu Debian Alpine Arch Fedora HEAD CPU RAM PDF SVG PNG WebAssembly Wasm Theia Room DataStore Gradle Claude AI SDK APT Apt Node Java Docker Home End Page Up Down Backspace Delete Insert Material Symbols".split())
RES_DIRS = ("values",)

found = []  # (rule, relpath, lineno, trimmed text)

def add(rule, rel, lineno, line):
    found.append((rule, rel, lineno, line.strip()))

for dirpath, _, files in os.walk(src):
    for name in files:
        if not name.endswith(".kt"):
            continue
        full = os.path.join(dirpath, name)
        rel = os.path.relpath(full, src).replace(os.sep, "/")
        with open(full, encoding="utf-8") as f:
            text = f.read()
        lines = text.split("\n")
        in_block = False
        for i, line in enumerate(lines, 1):
            s = line.strip()
            # KDoc/comments describe rules and legitimately name banned symbols.
            if in_block:
                in_block = "*/" not in s
                continue
            if s.startswith("/*"):
                in_block = "*/" not in s
                continue
            if s.startswith("//") or s.startswith("*"):
                continue
            if not under(rel, THEME, KIT, ICONS) and COLOR.search(line):
                add("U-COL-01", rel, i, line)
            if under(rel, KIT, SHELL) and ICON_IMPORT.match(s):
                add("U-ICO-01", rel, i, line)
            if under(rel, KIT) and KIT_SCHEME.search(line):
                add("U-KIT-COL", rel, i, line)
            if under(rel, KIT, SHELL) and not NAMED_DP.match(line) and any(float(m.group(1)) != 0 for m in DP.finditer(line)):
                add("U-DP-01", rel, i, line)
            if not under(rel, THEME, KIT) and OLD_TYPE.search(line):
                add("U-TYP-01", rel, i, line)
            if not under(rel, THEME) and rel != KIT_TEXT and RAW_SP.search(line):
                add("U-TYP-02", rel, i, line)
            if under(rel, KIT, SHELL) and not NAMED_DP.match(line) and any(
                    float(next(g for g in m.groups()[::2] if g)) != 0 for m in CONTROL_DP.finditer(line)):
                add("U-DEN-01", rel, i, line)
            if rel != MOTION_TABLE and not name.startswith("CursorBlock") and DURATION.search(line):
                add("U-MOT-01", rel, i, line)
            if not name.startswith("CursorBlock") and LOOP.search(line):
                add("U-MOT-02", rel, i, line)
            if FLASHY.search(line):
                add("U-AI-03", rel, i, line)
            if under(rel, UI + "screens/", UI + "components/", SHELL):
                if ELEVATION.search(line):
                    add("U-AI-05", rel, i, line)
                if CIRCLE.search(line) and not s.startswith("import "):
                    add("U-AI-06", rel, i, line)
        if under(rel, *COPY_DIRS):
            # Text( often breaks its argument onto the next line, so match on the whole file.
            for m in TEXT_LIT.finditer(text):
                if re.search(r"[A-Za-z]", TEMPLATE.sub("", m.group(1))):
                    lineno = text.count("\n", 0, m.end(1)) + 1
                    add("U-TYP-08", rel, lineno, lines[lineno - 1])

res_root = os.path.join(os.path.dirname(src), "res")
for name in sorted(os.listdir(res_root)) if os.path.isdir(res_root) else []:
    if not name.startswith("values"):
        continue
    for fname in sorted(os.listdir(os.path.join(res_root, name))):
        if not (fname.startswith("strings") and fname.endswith(".xml")):
            continue
        rel = f"res/{name}/{fname}"
        with open(os.path.join(res_root, name, fname), encoding="utf-8") as f:
            text = f.read()
        text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
        for m in STRING.finditer(text):
            key, body = m.group(1), m.group(2)
            if 'translatable="false"' in m.group(0)[: m.group(0).index(">")]:
                continue
            lineno = text.count("\n", 0, m.start()) + 1
            words = re.findall(r"[A-Za-z][A-Za-z'-]*", re.split(r"(?<=[.:?])\s+", body)[0])
            later = [w for w in words[1:] if w[0].isupper() and not w.isupper() and w not in PROPER and not re.fullmatch(r"[A-Z]{2,}s", w)]
            if FORBIDDEN_WORDS.search(body):
                found.append(("U-AI-08", rel, lineno, f"{key}: {body.strip()}"))
            if 2 <= len(words) <= 8 and words[0][0].isupper() and len(later) == len(words) - 1 and later:
                found.append(("U-TYP-05", rel, lineno, f"{key}: {body.strip()}"))

found.sort(key=lambda t: (t[0], t[1], t[2]))

if mode == "--update-baseline":
    with open(baseline_path, "w", encoding="utf-8") as f:
        for rule, rel, _, text in sorted(found, key=lambda t: (t[0], t[1], t[3])):
            f.write(f"{rule}\t{rel}\t{text}\n")
    print(f"ui-lint: baseline rewritten with {len(found)} entries")
    sys.exit(0)

allowed = Counter()
if os.path.exists(baseline_path):
    with open(baseline_path, encoding="utf-8") as f:
        for row in f:
            row = row.rstrip("\n")
            if row:
                allowed[tuple(row.split("\t", 2))] += 1

seen = Counter()
fresh = []
for rule, rel, lineno, text in found:
    key = (rule, rel, text)
    seen[key] += 1
    if mode == "--all" or seen[key] > allowed[key]:
        fresh.append((rule, rel, lineno, text))

for rule, rel, lineno, text in fresh:
    print(f"{rel}:{lineno}: {rule}: {text}")

stale = sum(max(0, n - seen[k]) for k, n in allowed.items())
if stale and mode == "":
    print(f"ui-lint: {stale} baseline entries no longer occur; run tools/ui-lint.sh --update-baseline", file=sys.stderr)
if mode == "--all":
    print(f"ui-lint: {len(found)} violations total ({sum(allowed.values())} in baseline)", file=sys.stderr)
    sys.exit(0)
if fresh:
    print(f"ui-lint: {len(fresh)} new violation(s); fix them (see ux-rules.md section 12)", file=sys.stderr)
    sys.exit(1)
PY
