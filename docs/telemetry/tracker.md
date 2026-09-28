# Feature: Telemetry - Tracker

Status legend: not-started / in-progress / done / blocked

| Component | Status | Notes |
|---|---|---|
| `:telemetry` contracts + no-op | done | `TelemetryConsentTest` covers the consent flow |
| `:telemetry-firebase` adapter, FCM service, manifest defaults | done | verified to compile and assemble; not verified on a device |
| One mandatory consent: `TelemetryGate` (new: before setup; existing: after upgrade) + `privacy.telemetry.enabled` | done | Agree or Exit; setting stays for withdrawal |
| Events: `app_open`, `workspace_open` | done | |
| Events: command run, file open (language only), terminal open, git op, extension install, environment install, results | not-started | add at the choke points; names in `TelemetryNames` |
| Crash reporting (Crashlytics) | not-started | would need its own consent line and ADR update |
| Real package names registered in Firebase | done | `dev.easyide.app`, `dev.easyide.app.canary` (2026-09-28) |
| CI writes `google-services.json` from a secret | not-started | CI is currently blocked by billing; releases are built locally |
| Build flavor without Firebase | not-started | needed for a fully open build |
