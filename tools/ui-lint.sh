#!/usr/bin/env bash
# ui-lint.sh - the greppable UI rules of docs/ui-redesign/ux-rules.md section 12.
#
# Usage (from anywhere; needs only bash, python3):
#   tools/ui-lint.sh                    fail (exit 1) on violations not in the baseline
#   tools/ui-lint.sh --all              list every violation, baseline or not (exit 0)
#   tools/ui-lint.sh --update-baseline  rewrite tools/ui-lint-baseline.txt from the tree
#
# Rules (id, rule, where):
#   U-COL-01  Color(0x..), Color.White/Black          all of app/src/main except ui/theme, ui/kit
#   U-KIT-COL MaterialTheme.colorScheme read          ui/kit (kit reads ThemeTokens, not M3)
#   U-DP-01   raw N.dp literal (0.dp exempt)          ui/kit, ui/shell
#   U-TYP-08  string literal in Text(...) copy        ui/screens, ui/components, ui/shell, ui/kit
#   U-MOT-01  tween(N), durationMillis = N literals   everything except ui/props/Motion.kt
#   U-MOT-02  rememberInfiniteTransition              everything except CursorBlink
#   U-AI-03   Brush gradients, blur(                  everything (command palette scrim excepted by baseline)
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
THEME, KIT, SHELL = UI + "theme/", UI + "kit/", UI + "shell/"
COPY_DIRS = (UI + "screens/", UI + "components/", SHELL, KIT)
MOTION_TABLE = UI + "props/Motion.kt"

def under(path, *dirs):
    return path.startswith(dirs)

# (id, compiled regex, applies-to predicate). Regexes run on the code with comments and
# string contents left intact only where the rule needs them.
COLOR = re.compile(r"\bColor\(\s*0[xX]|\bColor\.(White|Black)\b")
KIT_SCHEME = re.compile(r"MaterialTheme\.colorScheme")
DP = re.compile(r"(?<![\w.])(\d+(\.\d+)?)\.dp\b")
DURATION = re.compile(r"\btween\(\s*\d|\b(durationMillis|delayMillis)\s*=\s*\d")
LOOP = re.compile(r"\brememberInfiniteTransition\b|\brepeatable\(|\binfiniteRepeatable\(")
FLASHY = re.compile(r"\bBrush\.\w*Gradient\(|\bblur\(")
TEXT_LIT = re.compile(r'\bText\(\s*(?:text\s*=\s*)?"((?:[^"\\]|\\.)*)"')
TEMPLATE = re.compile(r"\$\{[^}]*\}|\$\w+")

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
            if not under(rel, THEME, KIT) and COLOR.search(line):
                add("U-COL-01", rel, i, line)
            if under(rel, KIT) and KIT_SCHEME.search(line):
                add("U-KIT-COL", rel, i, line)
            if under(rel, KIT, SHELL) and any(float(m.group(1)) != 0 for m in DP.finditer(line)):
                add("U-DP-01", rel, i, line)
            if rel != MOTION_TABLE and DURATION.search(line):
                add("U-MOT-01", rel, i, line)
            if not name.startswith("CursorBlink") and LOOP.search(line):
                add("U-MOT-02", rel, i, line)
            if FLASHY.search(line):
                add("U-AI-03", rel, i, line)
        if under(rel, *COPY_DIRS):
            # Text( often breaks its argument onto the next line, so match on the whole file.
            for m in TEXT_LIT.finditer(text):
                if re.search(r"[A-Za-z]", TEMPLATE.sub("", m.group(1))):
                    lineno = text.count("\n", 0, m.end(1)) + 1
                    add("U-TYP-08", rel, lineno, lines[lineno - 1])

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
