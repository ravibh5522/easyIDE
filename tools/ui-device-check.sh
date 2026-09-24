#!/usr/bin/env bash
# ui-device-check.sh - on-device layout check of the screen currently in the foreground.
#
# What it does, per font scale (default 1.0, 1.3, 2.0):
#   1. sets the system font_scale over adb and waits for the app to re-lay out,
#   2. dumps the UI hierarchy with uiautomator (one node per line, so a finding can name a line),
#   3. analyses the dump (embedded python3, stdlib only):
#      a. touch floor: every clickable node is at least the floor in both dimensions and has
#         non-empty text or content-desc (its own, or a descendant's). The floor depends on the
#         window width class: 44dp under 600dp wide, 40dp from 600dp up. On wide windows dense rows
#         (inside a scrollable) and toolbar/tab-strip buttons are exempt when they are at least 28dp
#         in both dimensions. -m N forces one floor for every width.
#      b. row-height histogram of all clickable nodes (dp, 2dp buckets) with p50/p90; WARNS (never
#         fails) when more than 15% are taller than 40dp on a window >= 600dp wide.
#      c. title alignment: in every scrollable container with >= 3 stacked text rows, the left edge of
#         each row's first text must agree within 1dp. A misaligned container FAILS.
# Findings print as `<dump-file>:<line>: <message>`, one per problem, so an editor can jump to them.
# The original font_scale is restored on exit, including on Ctrl-C and on failure (trap).
#
# Open the screen first (for the kit gallery: Settings > Diagnostics > Developer tools > Kit gallery),
# then run:
#   tools/ui-device-check.sh                      # the only connected device
#   tools/ui-device-check.sh -s emulator-5554     # a specific device
#   tools/ui-device-check.sh -o /tmp/ui-check     # keep the dumps there (default: a temp dir, printed)
#   tools/ui-device-check.sh -m 48 1.0 2.0        # force a 48dp floor, only these font scales
#   tools/ui-device-check.sh --no-align --no-histogram   # only the touch floor
#   tools/ui-device-check.sh --dump F.xml --density 400 [--width-dp 720]   # analyse a saved dump, no device
# Exit status: 0 clean (warnings allowed), 1 findings, 2 setup problem (no device, no dump, bad args).
# Scrolled-off rows are not in the dump: scroll and run again to check the rest of a long page.

set -u

MIN_DP=""
SERIAL=""
OUT=""
DUMP=""
DENSITY=""
WIDTH_DP=""
ALIGN=1
HIST=1
need() { [ "$1" -ge 2 ] || { echo "ui-device-check: $2 needs a value" >&2; exit 2; }; }
while [ $# -gt 0 ]; do
  case "$1" in
    -s) need $# "$1"; SERIAL="$2"; shift 2 ;;
    -o) need $# "$1"; OUT="$2"; shift 2 ;;
    -m) need $# "$1"; MIN_DP="$2"; shift 2 ;;
    --dump) need $# "$1"; DUMP="$2"; shift 2 ;;
    --density) need $# "$1"; DENSITY="$2"; shift 2 ;;
    --width-dp) need $# "$1"; WIDTH_DP="$2"; shift 2 ;;
    --no-align) ALIGN=0; shift ;;
    --no-histogram) HIST=0; shift ;;
    -h|--help) sed -n '2,/^$/p' "$0"; exit 0 ;;
    --) shift; break ;;
    -*) echo "ui-device-check: unknown option $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
SCALES=("$@")
[ ${#SCALES[@]} -eq 0 ] && SCALES=(1.0 1.3 2.0)

# analyse FILE: prints findings, histogram and alignment report; exit 0 clean, 1 findings, 2 unreadable.
analyse() {
  python3 - "$1" "$DENSITY" "$MIN_DP" "$WIDTH_DP" "$ALIGN" "$HIST" <<'PY'
import math, re, sys
import xml.etree.ElementTree as ET

file, density, min_dp, width_dp, do_align, do_hist = sys.argv[1:7]
density = float(density)
do_align, do_hist = do_align == "1", do_hist == "1"

WIDE_DP = 600          # width class boundary
FLOOR_COMPACT = 44     # touch floor below WIDE_DP
FLOOR_WIDE = 40        # touch floor from WIDE_DP up
DENSE_DP = 28          # dense rows / toolbar buttons are exempt above this size on wide windows
TALL_DP = 40           # a "tall" clickable for the histogram warning
TALL_SHARE = 0.15
ALIGN_TOL_DP = 1.0
MIN_ALIGN_ROWS = 3
BAR_MAX = 40
CHROME = re.compile(r"toolbar|tab[_ ]?(strip|row|layout|bar)", re.I)


def dp(px):
    return px * 160.0 / density


class Node:
    def __init__(self, el, line, depth, parent):
        a = el.attrib
        self.line, self.depth, self.parent, self.kids = line, depth, parent, []
        self.l, self.t, self.r, self.b = (int(v) for v in re.findall(r"-?\d+", a.get("bounds", "")) or [0, 0, 0, 0])
        self.w, self.h = self.r - self.l, self.b - self.t
        self.clickable = a.get("clickable") == "true"
        self.scrollable = a.get("scrollable") == "true"
        self.text = a.get("text", "").strip()
        self.desc = a.get("content-desc", "").strip()
        self.rid, self.cls = a.get("resource-id", ""), a.get("class", "")

    def what(self):
        return self.rid or self.cls

    def walk(self):
        yield self
        for k in self.kids:
            yield from k.walk()

    def ancestors(self):
        n = self.parent
        while n:
            yield n
            n = n.parent


try:
    # One <node per line so findings carry a line number; a no-op on an already split dump.
    text = re.sub(r"(?<=\S)<node ", "\n<node ", open(file, encoding="utf-8").read())
    root = ET.fromstring(text.encode("utf-8"))
except (OSError, ET.ParseError) as e:
    print(f"ui-device-check: cannot read {file}: {e}", file=sys.stderr)
    sys.exit(2)

starts = [i + 1 for i, s in enumerate(text.split("\n")) if s.lstrip().startswith("<node ")]
nodes, tops = [], []


def build(el, parent, depth):
    for child in el:
        if child.tag != "node":
            continue
        n = Node(child, starts[len(nodes)], depth, parent)
        nodes.append(n)
        (parent.kids if parent else tops).append(n)
        build(child, n, depth + 1)


build(root, None, 0)
if not nodes:
    print(f"ui-device-check: {file} has no nodes", file=sys.stderr)
    sys.exit(2)

width = float(width_dp) if width_dp else dp(max(n.r for n in nodes))
wide = width >= WIDE_DP
floor = float(min_dp) if min_dp else (FLOOR_WIDE if wide else FLOOR_COMPACT)
clickable = [n for n in nodes if n.clickable]
bad = 0


def exempt(n):
    if not wide or dp(n.w) < DENSE_DP or dp(n.h) < DENSE_DP:
        return False
    chain = [n, *n.ancestors()]
    return any(a.scrollable for a in chain[1:]) or any(CHROME.search(a.what()) for a in chain)


def labelled(n):
    return any(k.text or k.desc for k in n.walk())


print(f"window {width:.0f}dp wide ({'wide' if wide else 'compact'}), touch floor {floor:g}dp")
for n in clickable:
    if not exempt(n) and (dp(n.w) + 0.2 < floor or dp(n.h) + 0.2 < floor):
        print(f"{file}:{n.line}: target {dp(n.w):.0f}x{dp(n.h):.0f}dp is under {floor:g}dp ({n.what()})")
        bad += 1
    if not labelled(n):
        print(f"{file}:{n.line}: clickable node has no text or content-desc ({n.what()})")
        bad += 1

if do_hist:
    heights = sorted(dp(n.h) for n in clickable if n.w > 0 and n.h > 0)
    if heights:
        buckets = {}
        for h in heights:
            b = int(h / 2 + 0.5) * 2
            buckets[b] = buckets.get(b, 0) + 1
        peak = max(buckets.values())
        print(f"row-height histogram ({len(heights)} clickable nodes, dp, 2dp buckets)")
        for b in sorted(buckets):
            print(f"  {b:4d}dp {buckets[b]:4d} {'#' * max(1, buckets[b] * BAR_MAX // peak)}")
        pct = lambda q: heights[max(0, math.ceil(q * len(heights)) - 1)]
        dominant = min(buckets, key=lambda b: (-buckets[b], b))
        print(f"row-heights: dominant={dominant}dp p50={pct(0.5):.0f} p90={pct(0.9):.0f}")
        tall = sum(h > TALL_DP for h in heights) / len(heights)
        if wide and tall > TALL_SHARE:
            print(f"{file}: WARN row-heights: {tall:.0%} of clickable nodes are taller than {TALL_DP}dp "
                  f"on a {width:.0f}dp-wide window (dense targets 28dp rows, 26 headers, 34 tabs)")
    else:
        print("row-heights: no clickable nodes with bounds")


def rows_of(cont):
    """Outermost clickable descendants inside cont, chosen at the depth with the most stacked text rows."""
    by_depth = {}

    def walk(n):
        for k in n.kids:
            if k.scrollable:
                continue  # a nested scroller owns its own rows
            if k.clickable and k.w > 0 and k.h > 0 and k.l >= cont.l and k.r <= cont.r and k.t >= cont.t and k.b <= cont.b:
                by_depth.setdefault(k.depth, []).append(k)
            else:
                walk(k)

    walk(cont)
    best = []
    for group in by_depth.values():
        stacked, last = [], None
        for k in sorted(group, key=lambda k: (k.t, k.l)):
            if last is None or k.t >= last.b - 1:  # side-by-side chips overlap vertically: not rows
                stacked.append(k)
                last = k
        rows = [(k, next((c for c in k.walk() if c.text), None)) for k in stacked]
        rows = [(k, c) for k, c in rows if c]
        if len(rows) > len(best):
            best = rows
    return best


if do_align:
    checked = failed = 0
    for cont in (n for n in nodes if n.scrollable):
        rows = rows_of(cont)
        if len(rows) < MIN_ALIGN_ROWS:
            continue
        checked += 1
        edges = [dp(c.l) for _, c in rows]
        lo, hi = min(edges), max(edges)
        where = f"{cont.what()} [{cont.l},{cont.t}][{cont.r},{cont.b}]"
        if hi - lo <= ALIGN_TOL_DP:
            print(f"align PASS {where}: {len(rows)} rows, left {lo:.1f}..{hi:.1f}dp")
            continue
        failed += 1
        med = sorted(edges)[len(edges) // 2]
        off = ", ".join(f"'{c.text}'@{dp(c.l):.1f}dp" for _, c in rows if abs(dp(c.l) - med) > ALIGN_TOL_DP)
        print(f"{file}:{cont.line}: align FAIL {where}: {len(rows)} rows, left {lo:.1f}..{hi:.1f}dp, off: {off}")
    print(f"align: {checked} containers checked, {failed} failed")
    bad += failed

print(f"{file}: {len(nodes)} nodes, {bad} findings")
sys.exit(1 if bad else 0)
PY
}

if [ -n "$DUMP" ]; then
  [ -n "$DENSITY" ] || { echo "ui-device-check: --dump needs --density N" >&2; exit 2; }
  analyse "$DUMP"
  exit $?
fi

adb_() { if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi; }

command -v adb >/dev/null || { echo "ui-device-check: adb not found" >&2; exit 2; }
[ "$(adb_ get-state 2>/dev/null)" = "device" ] || { echo "ui-device-check: no single device connected (use -s)" >&2; exit 2; }
[ -n "$OUT" ] || OUT="$(mktemp -d)"
mkdir -p "$OUT"

# "null" means the setting was never written; restoring must delete it, not write the word.
ORIGINAL_SCALE="$(adb_ shell settings get system font_scale | tr -d '\r')"
restore() {
  if [ "$ORIGINAL_SCALE" = "null" ] || [ -z "$ORIGINAL_SCALE" ]; then
    adb_ shell settings delete system font_scale >/dev/null 2>&1
  else
    adb_ shell settings put system font_scale "$ORIGINAL_SCALE" >/dev/null 2>&1
  fi
  adb_ shell rm -f /sdcard/ui-device-check.xml >/dev/null 2>&1
}
trap restore EXIT INT TERM

# `wm density` prints "Physical density: N" and, when overridden, "Override density: M"; the last one wins.
if [ -z "$DENSITY" ]; then
  DENSITY="$(adb_ shell wm density | tr -d '\r' | grep -o '[0-9]\+' | tail -1)"
fi
[ -n "$DENSITY" ] || { echo "ui-device-check: cannot read the display density" >&2; exit 2; }

FAILED=0
for scale in "${SCALES[@]}"; do
  adb_ shell settings put system font_scale "$scale" >/dev/null
  sleep 2
  dump="$OUT/ui-fs${scale}.xml"
  adb_ shell uiautomator dump /sdcard/ui-device-check.xml >/dev/null 2>&1
  raw="$(adb_ shell cat /sdcard/ui-device-check.xml | tr -d '\r')"
  [ -n "$raw" ] || { echo "ui-device-check: uiautomator produced no dump at font_scale $scale" >&2; exit 2; }
  printf '%s' "$raw" | sed 's/<node /\n<node /g' > "$dump"
  echo "== font_scale $scale (density $DENSITY)"
  analyse "$dump"
  rc=$?
  [ "$rc" -eq 2 ] && exit 2
  [ "$rc" -ne 0 ] && FAILED=1
done

[ "$FAILED" -eq 0 ] && echo "ui-device-check: clean" || echo "ui-device-check: findings above; dumps in $OUT"
exit "$FAILED"
