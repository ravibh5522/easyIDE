# 0031 - Telemetry: Firebase behind a provider-neutral module, one mandatory consent

Status: Accepted (2026-09-28)

## Context

The owner wants usage analytics (device info, installs, feature use and action results) and push
announcements, viewable in a dashboard, with the freedom to migrate provider later. easyIDE is AGPL-3.0
and a developer tool, so silent collection is not acceptable.

Licenses, checked 2026-09-28 from primary sources (Google Maven POMs, github.com/firebase/firebase-android-sdk):

- `firebase-messaging` 25.1.3: Apache-2.0.
- `firebase-analytics` / `play-services-measurement-api` 23.2.0: **Android Software Development Kit License
  (proprietary)**, not an open-source license.
- `com.google.gms:google-services` Gradle plugin 4.5.0 (google/play-services-plugins): Apache-2.0, releases current.
- Firebase BoM 34.19.0 is the latest on Google Maven.

## Decision

- **`:telemetry`** holds provider-neutral contracts (`Analytics`, `Push`, `PushNotifier`, `TelemetryNames`) and a
  no-op `Telemetry.NONE`. It depends on no provider.
- **`:telemetry-firebase`** is the only module that names Firebase. Migrating means writing a sibling module and
  changing one line in `AppContainer`.
- **One mandatory consent (owner decision, 2026-09-28).** A single gate covers analytics and push announcements
  (and any later Firebase feature: crash reports, performance, remote config). New installs see it before setup;
  existing installs see it on the first launch after upgrade. The only choices are Agree or Exit; Back does
  nothing. Until it is agreed, `privacy.telemetry.enabled` is false, the manifest keeps Analytics collection, the
  advertising ID and FCM auto-init off, and `TelemetryConsent` is the only code that enables them. Withdrawal
  stays available as that one setting (Settings > Privacy), which stops collection and deletes the FCM token.
- **Collected:** device details (maker, model, Android SDK, ABI, build channel), app version, feature names with
  success/failure, and app errors and crash logs. **Never collected by us:** name, phone number, contacts, accounts,
  installed or other apps, file or project names, code, typed commands, terminal output. The consent text says this
  in those words. Firebase itself assigns an app-instance id and derives approximate location from the connection's IP
  address (the address is not stored); we do not add any user identifier, and `setUserId` is not called.
- **`google-services.json` is not in git.** The Gradle plugin is applied only when the file exists, so forks and
  fresh checkouts build with the no-op provider. Release builds get the file from the maintainer's machine or a CI secret.

## Alternatives considered

- **Self-hosted (PostHog/Matomo/own backend).** Fully open and migratable, but `services/backend` is deliberately
  empty until a concrete need exists; running a server for analytics is that need created by us.
- **No push, analytics only.** Rejected: announcements were asked for and cost one service.
- **Optional, default-off toggles.** Built first, then replaced at the owner's request: they leave the dashboards nearly empty.
- **Firebase calls directly in `:app`.** Rejected: makes the stated migration a rewrite of every call site.

## Consequences

- The APK now contains proprietary Google binaries (Analytics/Play Services measurement). This does not change the
  app's own AGPL-3.0 licensing but rules out F-Droid's main repo for builds that include `:telemetry-firebase`. A
  build without `google-services.json` still links the SDK; excluding it entirely needs a build flavor (not done).
- A consent that cannot be declined is not "freely given" under GDPR-style regimes; if the app is offered in the EU/UK
  this needs legal review or a decline path. The owner accepted this trade-off.
- Analytics counts everyone who has agreed, i.e. every user who opens the app after this version. GitHub release download counts
  are the unconsented total; Firebase cannot see downloads, only opens.
- `dev.easyide.app` and `dev.easyide.app.canary` are registered as their own apps in Firebase project
  `easyide-a27e8` (2026-09-28), next to the original `com.easyide`; debug builds share `dev.easyide.app`, so
  their events land in the release app's stream (analytics stays off unless a developer opts in).
