# UI Redesign - Density, Rows and Alignment

Status: ACCEPTED (2026-09-25, owner feedback on the first device run: "UI is not responsive, items
not aligned, not compact - see how VS Code handles rows, sub rows, columns"). Part of
[arch.md](arch.md). Supersedes the row heights and paddings of [kit.md](kit.md) 3.1 and
[layout-spec.md](layout-spec.md) where they disagree. Rules it adds: [ux-rules.md](ux-rules.md) U-DEN-*.

## 1. What VS Code does (measured from the owner's screenshot, 1x)

| Element | VS Code | Note |
|---|---|---|
| Activity bar | 48 wide, 24 icons, 2 accent bar on the active one | icon only; labels are tooltips |
| Side bar header | 35 high, caps 11 title, actions right-aligned 16 icons | one line, no card |
| Section header ("Changes", "Graph") | 22 high, twistie 16 + caps 11 + count badge right | collapsible, sticky |
| Tree / list row | **22 high**, font 13, twistie 16, icon 16, label, then description inline in muted text, then badge / status letter right-aligned | never taller; indent 8 per level |
| Row anatomy | `[twistie][icon] label  description(muted, ellipsis)   ........ [actions on hover][badge]` | a "sub row" is another row indented one level, not a second text line |
| Editor tab | 35 high, 13 text, close 16 | |
| Panel tab (Terminal, Problems) | 30 high, caps 11 | |
| Status bar | 22 high, 12 text | items separated by padding, not dividers |
| Inputs | 26 high, 13 text, 1px border, 4 radius | |
| Buttons | 26 high (commit button 28), 13 text | |
| Cards | none: content sits on the panel background; sections are separated by header rows and hairlines | |
| Columns | fixed leading column (icon slot) so labels align; trailing column right-aligned; widths never depend on the text | |

## 2. Our tokens (dp; the tablet's dp is about 1.4x a desktop pixel in physical size, so
these read as VS Code sized)

| Token | Dense (default on MEDIUM/EXPANDED) | Comfortable (default on COMPACT phone) |
|---|---|---|
| list/tree row | 28 | 36 |
| section header row | 26 | 32 |
| toolbar icon button (visual / hit) | 28 / 32 | 32 / 44 |
| row icon | 16 | 18 |
| rail (activity) width | 52, icons 22, labels only on EXPANDED | bottom bar 56 |
| document tab | 34 | 40 |
| status strip | 24 | 28 |
| field / button height | 30 | 40 |
| body / label / caption sp | 13 / 12 / 11 | 14 / 13 / 12 |
| horizontal padding | 10 | 14 |
| row indent per level | 12 | 14 |

The 44dp touch floor stays for **isolated** controls on COMPACT (primary buttons, bottom-bar
cells, dialog actions). Dense **list rows and toolbar buttons** may be below it on MEDIUM/EXPANDED
because their hit box is padded by `Modifier.kitHitSlop` to 40dp and a hardware keyboard/pointer is
the expected companion at that size; the owner asked for VS Code density explicitly.

## 3. Rules

- **U-DEN-01 One line per row.** Title, then description inline in muted text with ellipsis, then a
  right-aligned trailing slot. A second line is allowed only for a row that is selected/expanded or
  in Comfortable density.
- **U-DEN-02 Fixed columns.** Leading slot fixed width (icon 16 in a 20 slot), trailing slot
  right-aligned, so titles align down the list. No widths derived from content.
- **U-DEN-03 No card per item.** A list is one group with hairline separators; sections are header
  rows (twistie, caps 11, count badge), collapsible.
- **U-DEN-04 Sub rows are indented rows** (tree indent tokens), not nested cards.
- **U-DEN-05 Chrome never wraps.** Labels in tabs, segmented controls, status items, panel headers
  are one line with ellipsis; the container scrolls or collapses instead.
- **U-DEN-06 Responsive by class.** Density and column count follow the width class; MEDIUM and up
  use two-column rows for settings and extension details (label left, control right); COMPACT stacks.
- **U-DEN-07 Insets.** Every banner, sheet and strip is inside the safe area; nothing draws under
  the status or navigation bar.
- **U-DEN-08 Baseline grid.** 4dp grid; icons and text share a vertical centre line in every row.

## 4. Acceptance

`tools/ui-device-check.sh` gains a row-height histogram and an alignment check (leading edge of
titles in one list within 1dp). Golden matrix on the gallery in Dense and Comfortable.
