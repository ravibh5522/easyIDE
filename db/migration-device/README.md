# On-Device DB Migrations

Migrations for the Android app's local persistent state.

## Current status: no Room migrations yet

The app does **not** currently use Room. Sandbox environments and projects are persisted as a single JSON snapshot via DataStore (`SandboxStore` / `PersistedStateJson` in `services/mobile/sandbox-runtime`), and UI preferences via DataStore Preferences.

Why, and when that changes, is recorded in [decision 0005](../../docs/decision/0005-sandbox-environment-sharing-model.md#persistence-note-revisit-trigger) — short version: Kotlin 2.4.10 had no matching KSP release, and the dataset is tens of rows read and written whole. Switch to Room when the data grows past a few hundred rows, a screen needs indexed/partial queries, or row-level write atomicity is required.

Schema evolution today is handled by the `version` field in the JSON snapshot, with unknown enum values decoding to safe defaults so a downgrade degrades rather than wiping state.

## Convention once Room lands

Room migrations are Kotlin classes, not raw SQL files — one class per version bump, named `Migration_<from>_<to>.kt`, e.g. `Migration_1_2.kt`. Keep them here (mirrored into the Room `databaseBuilder(...).addMigrations(...)` call in `services/mobile`) so schema history is visible without digging through app source.

**Rules**:
- Never change a migration once it has shipped in a released app version — devices in the wild have already applied it. Add a new one instead.
- Every schema change needs a migration once the app has shipped once — `fallbackToDestructiveMigration()` is only acceptable pre-release.
- Pair each migration file with a one-line note on what changed and why, so this folder doubles as a schema changelog.
