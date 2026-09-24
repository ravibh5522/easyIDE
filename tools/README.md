# tools

Suggested addition (not in the original spec): dev-only scripts that support building/testing the repo but never ship inside `services/*` — codegen, local environment setup, release/signing scripts, the Termux/proot bootstrap-tarball builder used by `services/mobile`.

Kept as a top-level sibling of `services/`, not nested inside it, because these scripts operate *on* the services (build them, generate code into them, package their release artifacts) rather than being one — mixing "things that ship" with "things that build the things that ship" makes both harder to reason about as the repo grows.

## Contents

| Path | What | License |
|---|---|---|
| `build-grammars.py` | Builds the bundled TextMate grammar assets for `services/mobile` | PolyForm NC (repo default) |
| [`ui-device-check.sh`](#ui-device-checksh) | On-device layout check: touch floor, row-height histogram, title alignment, at font scales 1.0/1.3/2.0 (adb + uiautomator) | PolyForm NC (repo default) |
| [`easyide-ext/`](easyide-ext/) | Extension author CLI: init, validate, package, keygen, sign, verify | **Apache-2.0** ([0015](../docs/decision/0015-extension-sdk-licensing-apache.md)) |

## ui-device-check.sh

Checks the screen in the foreground on a connected device (or a saved uiautomator dump) against the density spec in [`docs/ui-redesign/density.md`](../docs/ui-redesign/density.md). Bash plus an embedded python3 (stdlib only) analysis; needs `adb` only when it talks to a device.

```
tools/ui-device-check.sh [-s SERIAL] [-o DIR] [-m DP] [--no-align] [--no-histogram] [FONT_SCALE...]
tools/ui-device-check.sh --dump FILE.xml --density N [--width-dp N] [-m DP] [--no-align] [--no-histogram]
```

Per font scale (default 1.0 1.3 2.0) the script sets `font_scale`, dumps the hierarchy, and restores the original scale on exit. Density comes from `adb shell wm density`; the window width in dp is the widest node right edge (`--width-dp` overrides). Scrolled-off rows are not in a dump: scroll and rerun.

| Check | Rule | Result |
|---|---|---|
| Touch floor | Every clickable node is at least 44dp wide and tall on windows < 600dp wide, 40dp from 600dp up. On wide windows, dense rows (clickable inside a scrollable) and toolbar / tab-strip buttons (node or ancestor id/class matches `toolbar` or `tab_strip`/`tab_row`/`tab_layout`/`tab_bar`) are exempt when >= 28dp in both dimensions. `-m N` forces one floor for every width. | finding |
| Label | Every clickable node has text or content-desc, its own or a descendant's. | finding |
| Row-height histogram | Heights of all clickable nodes with non-zero bounds, bucketed to the nearest 2dp, with `row-heights: dominant=<b>dp p50=<n> p90=<n>`. | WARN only when > 15% are taller than 40dp on a window >= 600dp wide (dense targets: 28dp rows, 26 section headers, 34 tabs) |
| Title alignment | In each scrollable container, take the outermost clickable descendants that are stacked vertically inside it (at the depth with the most such rows); the left edge of each row's first non-empty text must agree within 1dp. Containers with fewer than 3 text rows are skipped. Prints PASS/FAIL per container with the min/max left edge and the off rows. | finding on FAIL |

`--no-align` and `--no-histogram` switch those two checks off. Findings print as `<dump>:<line>: <message>`.

Exit codes: `0` clean (warnings allowed), `1` findings (touch floor, label or alignment), `2` setup problem (no adb / device / dump, bad arguments, unparsable dump).

Self-test with the fixtures (a 720dp-wide tablet at 400dpi), no device needed:

```
tools/ui-device-check.sh --dump tools/testdata/ui-dump-dense.xml --density 400   # exit 0, no warning
tools/ui-device-check.sh --dump tools/testdata/ui-dump-airy.xml  --density 400   # exit 1: align FAIL + row-heights WARN
```
