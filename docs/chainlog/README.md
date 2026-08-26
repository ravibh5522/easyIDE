# Chainlog

An append-only, weekly-bucketed log of changes made by AI assistants in this repo — one file per ISO week, newest entry appended at the bottom.

**Filename**: `YYYY-Www.md` (ISO week, e.g. `2026-W33.md`). Get the current bucket with `date +%G-W%V`.

**Entry format**:
```
## YYYY-MM-DD HH:MM

- What changed (files/areas touched)
- Why (one line — link a decision doc if this traces back to one)
```

This is a mechanical record, not a design doc — if a change involved a real tradeoff or a decision worth remembering, write it up under [/docs/decision/](../decision/) and just link it here.
