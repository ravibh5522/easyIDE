# services/backend

Not required for the core product — the chosen architecture ([0002](../../docs/decision/0002-sandbox-backend-proot-default-chroot-optin.md)) runs everything on-device. This folder exists for whatever *does* need a server component later, e.g.:
- Account/sync service (optional cross-device project sync)
- Telemetry/crash reporting ingestion
- A future opt-in "cloud codespace" mode (the rejected-for-now real-Docker backend option from the sandbox design discussion) — if ever revisited, it lands here, not inside `services/mobile`

Keep empty with just this README until a concrete backend need exists — no speculative scaffolding.
