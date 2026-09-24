#!/usr/bin/env bash
# ui-device-check.sh - on-device accessibility check of the screen currently in the foreground.
#
# What it does, per font scale (default 1.0, 1.3, 2.0):
#   1. sets the system font_scale over adb and waits for the app to re-lay out,
#   2. dumps the UI hierarchy with uiautomator (one node per line, so a finding can name a line),
#   3. asserts every clickable node is at least MIN_DP x MIN_DP dp and has non-empty text or
#      content-desc (its own, or a descendant's, since a merged Compose row carries its label in a child).
# Findings print as `<dump-file>:<line>: <message>`, one per problem, so an editor can jump to them.
# The original font_scale is restored on exit, including on Ctrl-C and on failure (trap).
#
# Open the screen first (for the kit gallery: Settings > Diagnostics > Developer tools > Kit gallery),
# then run:
#   tools/ui-device-check.sh                      # the only connected device
#   tools/ui-device-check.sh -s emulator-5554     # a specific device
#   tools/ui-device-check.sh -o /tmp/ui-check     # keep the dumps there (default: a temp dir, printed)
#   tools/ui-device-check.sh -m 48 1.0 2.0        # 48dp floor, only these font scales
# Exit status: 0 clean, 1 findings, 2 setup problem (no device, no dump).
# Scrolled-off rows are not in the dump: scroll and run again to check the rest of a long page.

set -u

MIN_DP=44
SERIAL=""
OUT=""
while getopts "s:o:m:h" opt; do
  case "$opt" in
    s) SERIAL="$OPTARG" ;;
    o) OUT="$OPTARG" ;;
    m) MIN_DP="$OPTARG" ;;
    h) sed -n '2,22p' "$0"; exit 0 ;;
    *) exit 2 ;;
  esac
done
shift $((OPTIND - 1))
SCALES=("$@")
[ ${#SCALES[@]} -eq 0 ] && SCALES=(1.0 1.3 2.0)

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
DENSITY="$(adb_ shell wm density | tr -d '\r' | grep -o '[0-9]\+' | tail -1)"
[ -n "$DENSITY" ] || { echo "ui-device-check: cannot read the display density" >&2; exit 2; }

# Reads a split dump (one <node per line) and prints findings. Bounds are "[l,t][r,b]" in px.
check_dump() {
  awk -v file="$1" -v density="$DENSITY" -v min_dp="$MIN_DP" '
    function attr(line, name,    m) {
      if (match(line, " " name "=\"[^\"]*\"")) {
        m = substr(line, RSTART + length(name) + 3, RLENGTH - length(name) - 4)
        return m
      }
      return ""
    }
    /<node / {
      n++
      raw[n] = $0
      lineno[n] = NR
      b = attr($0, "bounds")
      gsub(/[\[\]]/, " ", b); gsub(/,/, " ", b)
      split(b, p, " ")
      l[n] = p[1]; t[n] = p[2]; r[n] = p[3]; bt[n] = p[4]
      label[n] = (attr($0, "text") != "" || attr($0, "content-desc") != "")
    }
    END {
      floor_px = min_dp * density / 160
      bad = 0
      for (i = 1; i <= n; i++) {
        if (attr(raw[i], "clickable") != "true") continue
        what = attr(raw[i], "resource-id"); if (what == "") what = attr(raw[i], "class")
        w = r[i] - l[i]; h = bt[i] - t[i]
        if (w + 0.5 < floor_px || h + 0.5 < floor_px) {
          printf "%s:%d: target %.0fx%.0fdp is under %sdp (%s)\n", file, lineno[i], w * 160 / density, h * 160 / density, min_dp, what
          bad++
        }
        found = label[i]
        for (j = 1; j <= n && !found; j++)
          if (j != i && label[j] && l[j] >= l[i] && t[j] >= t[i] && r[j] <= r[i] && bt[j] <= bt[i]) found = 1
        if (!found) {
          printf "%s:%d: clickable node has no text or content-desc (%s)\n", file, lineno[i], what
          bad++
        }
      }
      printf "%s: %d nodes, %d findings\n", file, n, bad
      exit (bad > 0)
    }
  ' "$2"
}

FAILED=0
for scale in "${SCALES[@]}"; do
  adb_ shell settings put system font_scale "$scale" >/dev/null
  sleep 2
  dump="$OUT/ui-fs${scale}.xml"
  adb_ shell uiautomator dump /sdcard/ui-device-check.xml >/dev/null 2>&1
  raw="$(adb_ shell cat /sdcard/ui-device-check.xml | tr -d '\r')"
  [ -n "$raw" ] || { echo "ui-device-check: uiautomator produced no dump at font_scale $scale" >&2; exit 2; }
  printf '%s' "$raw" | sed 's/<node /\n<node /g' > "$dump"
  echo "== font_scale $scale (density $DENSITY, floor ${MIN_DP}dp)"
  check_dump "$dump" "$dump" || FAILED=1
done

[ "$FAILED" -eq 0 ] && echo "ui-device-check: clean" || echo "ui-device-check: findings above; dumps in $OUT"
exit "$FAILED"
