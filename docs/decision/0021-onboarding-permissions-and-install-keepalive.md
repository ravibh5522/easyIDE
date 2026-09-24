# 0021 - Onboarding asks for battery exemption and notifications; installs run under the foreground service

Status: Accepted

## Context

The sandbox must survive the screen turning off (docs/sandbox-runtime/arch.md SS4, SS7), and the first Linux install is a several-minute download plus unpack that Android will kill within seconds of the app being backgrounded. Both need things only the user can grant, on system screens: exemption from battery optimisation, and (Android 13+) permission to show the foreground-service notification. Until now onboarding asked for neither, and `SandboxForegroundService` was never started by anything.

## Decision

1. **Onboarding is stepped and completes only at the end**: Welcome, Battery, Notifications (only on API 33+; the step is absent below that, not skipped), first environment, Done. Every step after Welcome can be skipped; `onboardingComplete` is written only when the user finishes from Done, so quitting half-way shows onboarding again. A user who skips the environment step gets an "Install Linux" prompt on Home (no environment exists) that opens the same install content.
2. **Battery**: request `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (one-tap system dialog) and declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the app manifest; fall back to the general battery-optimisation settings list when a vendor build lacks the dialog. State is read with `PowerManager.isIgnoringBatteryOptimizations` on every return to the foreground.
3. **Notifications**: `POST_NOTIFICATIONS` runtime request on API 33+ (declared by `:sandbox-runtime`, merged into the app). After a refusal the button opens the app's notification settings, since the system prompt may never appear again.
4. **Install keep-alive**: the environment install starts `SandboxForegroundService` for its duration (`InstallKeepAlive`, stopped in a `finally`), so backgrounding the app does not kill it. A refused start (Android 12+ background-start limits) is swallowed at that boundary and the install runs unprotected, as before. A partial download is kept, so a killed or cancelled install resumes.
5. **Structured install progress**: `LinuxEnvironment.install` and `RootfsProvisioner.provision` report `InstallEvent`s (download bytes/total/resumed prefix, extraction bytes of the archive, preset setup step k of n) beside the existing text lines, so the UI shows a real bar. Cancelling stops the download (partial kept) or the extraction, and a cancelled or failed extraction deletes the half-unpacked rootfs, because `isReady` only checks for `bin/sh` and a partial tree can contain one.

## Consequences

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is restricted by Google Play to apps whose core function needs it. A terminal-hosting IDE is a listed-style use, but this must be checked against the policy before a Play release; sideloaded and F-Droid style distribution is unaffected. Removing the request later means dropping onboarding step 2 and the manifest line; the rest is independent.
- The service notification text lives in `:sandbox-runtime` ("Sandbox running") and is not yet install-specific.
- Nothing here is device-verified; see the chainlog entry.
