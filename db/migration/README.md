# Backend DB Migrations

Time-ordered, reversible migrations for the `services/backend` database (once a backend exists — see [0002](../../docs/decision/) and `services/backend/README.md` for when this applies).

**Naming**: `<UTC-timestamp>_<snake_case_description>.up.sql` / `.down.sql`, e.g.:
```
20260810120000_create_projects_table.up.sql
20260810120000_create_projects_table.down.sql
```

**Rules**:
- Every `up` needs a matching `down` that actually reverses it — no one-way migrations.
- Never edit a migration that has shipped to any shared environment; write a new one instead.
- Timestamp prefix determines apply order — always generate it at authoring time (`date -u +%Y%m%d%H%M%S`), never hand-pick a number.
