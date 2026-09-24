#!/usr/bin/env python3
"""codemod-type-roles.py - move every text style onto the one semantic set `Kit.text`.

Usage:
  tools/codemod-type-roles.py [ROOT] [--dry-run] [--report FILE]
    ROOT      source root to rewrite (default: services/mobile/app/src/main/java of this repo)
    --dry-run count and report, write nothing but the report
    --report  where the AMBIGUOUS / RAW-SP / ... list goes (default tools/codemod-type-roles-report.txt)

Skips ui/theme/ and ui/kit/KitText.kt. Idempotent: converted code has no old symbols left, so a
second run rewrites nothing. What it cannot decide is left untouched and written to the report.

Mapping (Kit.type.X, MaterialTheme.typography.X and, when `val typography = MaterialTheme.typography`
is in the file, bare typography.X; the ROLE dict below is the implementation):
  displayLarge displayMedium displaySmall headlineLarge headlineMedium headlineSmall titleLarge -> Kit.text.display
  titleMedium                          -> Kit.text.heading
  titleSmall labelLarge                -> Kit.text.title
  bodyLarge bodyMedium                 -> Kit.text.body
  bodySmall labelMedium                -> Kit.text.caption
  labelSmall sectionHeader             -> Kit.text.label
  X.kitMono()                          -> Kit.text.monoSmall (labelSmall labelMedium bodySmall)
                                          Kit.text.mono      (bodyMedium bodyLarge titleSmall labelLarge)
  X.copy(fontFamily = EasyIdeFonts.mono, ...) -> same mono role by the same size rule, the
                                          fontFamily argument dropped (other copy arguments kept)
Report only (never rewritten):
  AMBIGUOUS  the style is followed by .copy(fontSize/fontWeight/lineHeight/letterSpacing/other
             fontFamily) or sits in a call that passes fontSize=/fontWeight=; also X.kitMono() /
             mono copy on a role with no mono size (headline*, titleMedium, sectionHeader ...)
  UNSURE     bare `typography.X` with no visible alias, or Kit.type.<unknown>
  RAW-SP     a raw N.sp literal
  TYPESCALE  TypeScale.X (sizes, not styles)
  LEFTOVER   `.kitMono()` / `fontFamily = EasyIdeFonts.mono` still there, bare `Kit.type` / `MaterialTheme.typography`

Imports (only in files that were rewritten): drop ui.theme.sectionHeader, ui.kit.kitMono,
material3.MaterialTheme, EasyIdeFonts, TypeScale, FontWeight when no longer named in the file;
add ui.kit.Kit when Kit.text is used and Kit is not imported (outside package ui.kit).
"""
import argparse
import bisect
import os
import re
import sys
from collections import Counter

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(REPO, "services/mobile/app/src/main/java")
UI = "dev/easyide/app/ui/"
SKIP = (UI + "theme/", UI + "kit/KitText.kt")

ROLE = {
    **dict.fromkeys(("displayLarge", "displayMedium", "displaySmall", "headlineLarge",
                     "headlineMedium", "headlineSmall", "titleLarge"), "display"),
    "titleMedium": "heading",
    **dict.fromkeys(("titleSmall", "labelLarge"), "title"),
    **dict.fromkeys(("bodyLarge", "bodyMedium"), "body"),
    **dict.fromkeys(("bodySmall", "labelMedium"), "caption"),
    **dict.fromkeys(("labelSmall", "sectionHeader"), "label"),
}
MONO = {
    **dict.fromkeys(("labelSmall", "labelMedium", "bodySmall"), "monoSmall"),
    **dict.fromkeys(("bodyMedium", "bodyLarge", "titleSmall", "labelLarge"), "mono"),
}
SIZE_ARGS = {"fontSize", "fontWeight", "lineHeight", "letterSpacing"}
CALL_ARGS = {"fontSize", "fontWeight"}

REF = re.compile(r"(?<![\w.])(Kit\.type|MaterialTheme\.typography|typography)\.(\w+)")
ALIAS = re.compile(r"\bval typography\s*=\s*MaterialTheme\.typography\b")
NAKED = re.compile(r"(?<![\w.])(Kit\.type|MaterialTheme\.typography)\b(?!\.\w)")
RAW_SP = re.compile(r"(?<![\w.])\d+(\.\d+)?\.sp\b")
IMPORT = re.compile(r"^import (\S+)[ \t]*\n", re.M)
KIT_IMPORT = "dev.easyide.app.ui.kit.Kit"
DROPPABLE = {
    "dev.easyide.app.ui.theme.sectionHeader": "sectionHeader",
    "dev.easyide.app.ui.kit.kitMono": "kitMono",
    "androidx.compose.material3.MaterialTheme": "MaterialTheme",
    "dev.easyide.app.ui.theme.EasyIdeFonts": "EasyIdeFonts",
    "dev.easyide.app.ui.theme.TypeScale": "TypeScale",
    "androidx.compose.ui.text.font.FontWeight": "FontWeight",
}


def code_chars(text, lo, hi):
    """Yield (index, char) outside strings, char literals and comments."""
    i = lo
    while i < hi:
        c = text[i]
        two = text[i:i + 2]
        if two == "//":
            i = text.find("\n", i)
            i = hi if i < 0 else i
        elif two == "/*":
            i = text.find("*/", i + 2)
            i = hi if i < 0 else i + 2
        elif text.startswith('"""', i):
            i = text.find('"""', i + 3)
            i = hi if i < 0 else i + 3
        elif c in "\"'":
            i += 1
            while i < hi and text[i] != c:
                i += 2 if text[i] == "\\" else 1
            i += 1
        else:
            yield i, c
            i += 1


def paren_pairs(text):
    stack, pairs = [], {}
    for i, c in code_chars(text, 0, len(text)):
        if c == "(":
            stack.append(i)
        elif c == ")" and stack:
            pairs[stack.pop()] = i
    return pairs


def split_args(text, op, cl):
    """Top-level argument spans (start, end) between the parens at op..cl, whitespace trimmed."""
    cuts, depth = [op], 0
    for i, c in code_chars(text, op + 1, cl):
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            cuts.append(i)
    cuts.append(cl)
    spans = []
    for a, b in zip(cuts, cuts[1:]):
        seg = text[a + 1:b]
        if seg.strip():
            lead = len(seg) - len(seg.lstrip())
            spans.append((a + 1 + lead, a + 1 + len(seg.rstrip())))
    return spans


def arg_name(text, span):
    m = re.match(r"(\w+)\s*=(?!=)", text[span[0]:span[1]])
    return m.group(1) if m else None


def scan(text, alias):
    """-> (edits [(start, end, new)], stats [(label, target)], pending [(kind, pos, suggestion)])."""
    pairs = paren_pairs(text)
    opens = sorted(pairs)
    edits, stats, pending = [], [], []
    code = {i for i, _ in code_chars(text, 0, len(text))}  # skip strings and comments
    for m in REF.finditer(text):
        if m.start() not in code:
            continue
        recv, role = m.groups()
        if recv == "typography" and not alias:
            pending.append(("UNSURE", m.start(), "bare typography.%s: receiver unknown" % role))
            continue
        if role not in ROLE:
            pending.append(("UNSURE", m.start(), "%s.%s has no mapping" % (recv, role)))
            continue
        end = m.end()
        kitmono = text.startswith(".kitMono()", end)
        end += len(".kitMono()") if kitmono else 0
        target, cut, why = ROLE[role], None, None
        wants_mono = kitmono

        if text.startswith(".copy(", end):
            op = end + len(".copy")
            spans = split_args(text, op, pairs[op]) if op in pairs else []
            names = [arg_name(text, s) for s in spans]
            if SIZE_ARGS & set(names):
                why = "copy() sets " + "/".join(sorted(SIZE_ARGS & set(names)))
            for s, n in zip(spans, names):
                if n != "fontFamily":
                    continue
                if re.sub(r"\s+", "", text[s[0]:s[1]].split("=", 1)[1]) == "EasyIdeFonts.mono":
                    wants_mono, cut = True, (spans, s, op)
                else:
                    why = "copy() sets a fontFamily other than EasyIdeFonts.mono"
        if wants_mono:
            if role in MONO:
                target = MONO[role]
            else:
                why = why or "%s has no mono role (size role would be %s)" % (role, ROLE[role])

        # a Text(..., style = Kit.type.X, fontSize = ...) pins a size the role would drop
        p = bisect.bisect_left(opens, m.start()) - 1
        while p >= 0 and pairs[opens[p]] < m.start():
            p -= 1
        if p >= 0:
            outer = opens[p]
            names = {arg_name(text, s) for s in split_args(text, outer, pairs[outer])}
            if CALL_ARGS & names:
                why = why or "enclosing call passes " + "/".join(sorted(CALL_ARGS & names))
        if why:
            hint = "Kit.text.%s (%s)" % (target if wants_mono else ROLE[role], why)
            pending.append(("AMBIGUOUS", m.start(), hint))
            continue

        edits.append((m.start(), end, "Kit.text." + target))
        if cut:
            spans, s, op = cut
            if len(spans) == 1:
                edits.append((op - len(".copy"), pairs[op] + 1, ""))
            else:
                i = spans.index(s)
                a, b = (s[0], spans[i + 1][0]) if i == 0 else (spans[i - 1][1], s[1])
                edits.append((a, b, ""))
        label = "%s.%s%s" % (recv, role, ".kitMono()" if kitmono else "")
        stats.append((label, "Kit.text." + target))
    return edits, stats, pending


def apply(text, edits):
    out, last = [], 0
    for a, b, new in sorted(edits):
        if a < last:
            continue
        out += [text[last:a], new]
        last = b
    out.append(text[last:])
    return "".join(out)


def fix_imports(text):
    body = IMPORT.sub("", text)
    for imp, name in DROPPABLE.items():
        if re.search(r"^import %s[ \t]*\n" % re.escape(imp), text, re.M) and not re.search(r"\b%s\b" % name, body):
            text = re.sub(r"^import %s[ \t]*\n" % re.escape(imp), "", text, flags=re.M)
    pkg = re.search(r"^package (\S+)", text, re.M)
    have = {m.group(1) for m in IMPORT.finditer(text)}
    if (re.search(r"(?<![\w.])Kit\.text\b", text) and KIT_IMPORT not in have
            and "dev.easyide.app.ui.kit.*" not in have and pkg and pkg.group(1) != "dev.easyide.app.ui.kit"):
        line = "import %s\n" % KIT_IMPORT
        found = list(IMPORT.finditer(text))
        after = next((f for f in found if f.group(1) > KIT_IMPORT), None)
        if after:
            at = after.start()
        elif found:
            at = found[-1].end()
        else:
            at = text.index("\n", pkg.start()) + 1
            line = "\n" + line
        text = text[:at] + line + text[at:]
    return text


def drop_unused_alias(text):
    """`val typography = MaterialTheme.typography` with no reader left."""
    m = ALIAS.search(text)
    if m and len(re.findall(r"(?<![\w.])typography\b", text)) == 1:
        a = text.rfind("\n", 0, m.start()) + 1
        b = text.find("\n", m.end()) + 1
        text = text[:a] + text[b:]
    return text


def line_reports(text):
    """Line-based findings on the final text: (kind, lineno, message)."""
    out, in_block = [], False
    for n, line in enumerate(text.split("\n"), 1):
        s = line.strip()
        if in_block:
            in_block = "*/" not in s
            continue
        if s.startswith("/*"):
            in_block = "*/" not in s
            continue
        if s.startswith(("//", "*")) or s.startswith("import "):
            continue
        if RAW_SP.search(line):
            out.append(("RAW-SP", n, s))
        if re.search(r"(?<![\w.])TypeScale\.", line):
            out.append(("TYPESCALE", n, s))
        if ".kitMono()" in line and "fun TextStyle.kitMono" not in line:
            out.append(("LEFTOVER", n, s))
        if re.search(r"fontFamily\s*=\s*EasyIdeFonts\.mono", line):
            out.append(("LEFTOVER", n, s))
        if NAKED.search(line):
            out.append(("LEFTOVER", n, s))
    return out


def main():
    ap = argparse.ArgumentParser(description="rewrite text styles onto Kit.text roles")
    ap.add_argument("root", nargs="?", default=SRC)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--report", default=os.path.join(REPO, "tools/codemod-type-roles-report.txt"))
    args = ap.parse_args()
    root = os.path.abspath(args.root)

    counts, changed, entries = Counter(), 0, []
    for dirpath, _, files in sorted(os.walk(root)):
        for name in sorted(files):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if not name.endswith(".kt") or rel.startswith(SKIP):
                continue
            with open(full, encoding="utf-8") as f:
                orig = f.read()
            alias = bool(ALIAS.search(orig))
            edits, stats, _ = scan(orig, alias)
            text = orig
            if edits:
                text = fix_imports(apply(orig, edits))
                text = drop_unused_alias(text) if alias else text
            counts.update(stats)
            if text != orig:
                changed += 1
                if not args.dry_run:
                    with open(full, "w", encoding="utf-8") as f:
                        f.write(text)
            left = text  # what a real run leaves, so a dry run reports the same lines
            _, _, pending = scan(left, alias)
            seen = set()
            for kind, pos, hint in pending:
                n = left.count("\n", 0, pos) + 1
                seen.add(n)
                entries.append((kind, rel, n, left.split("\n")[n - 1].strip(), hint))
            for kind, n, src in line_reports(left):
                if n not in seen:
                    seen.add(n)
                    entries.append((kind, rel, n, src, ""))

    order = {"AMBIGUOUS": 0, "UNSURE": 1, "LEFTOVER": 2, "TYPESCALE": 3, "RAW-SP": 4}
    entries.sort(key=lambda e: (order[e[0]], e[1], e[2]))
    os.makedirs(os.path.dirname(os.path.abspath(args.report)), exist_ok=True)
    with open(args.report, "w", encoding="utf-8") as f:
        for kind, rel, n, src, hint in entries:
            f.write("%s %s:%d: %s%s\n" % (kind, rel, n, src, "  => " + hint if hint else ""))

    tag = " (dry run, nothing written)" if args.dry_run else ""
    print("codemod-type-roles%s: root %s" % (tag, root))
    for (label, target), n in sorted(counts.items()):
        print("  %4d  %s -> %s" % (n, label, target))
    kinds = Counter(e[0] for e in entries)
    print("rewrites: %d   files changed: %d" % (sum(counts.values()), changed))
    print("report: " + ("  ".join("%s %d" % (k, kinds[k]) for k in order if kinds[k]) or "empty") + "  -> " + args.report)


if __name__ == "__main__":
    sys.exit(main())
