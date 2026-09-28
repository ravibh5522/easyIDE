# Feature: Telemetry - Architecture

## Overview

Consent-gated usage analytics and push announcements, so the maintainer can see installs, device mix, feature use and
action results, and message users about releases. Provider-neutral so it can move off Firebase.

## Decisions

- [0031](../decision/0031-telemetry-firebase-behind-neutral-module.md): Firebase behind a neutral module; opt-in; license findings.

## Architecture

- `services/mobile/telemetry` (`:telemetry`): `Analytics`, `Push`, `Telemetry`, `PushNotifier`, `TelemetryNames` (every
  event, param and property name lives there, no literals at call sites).
- `services/mobile/telemetry-firebase`: `FirebaseTelemetry.create(context)` returns adapters, or `Telemetry.NONE`
  when no `FirebaseApp` exists (no `google-services.json` at build time). `EasyFirebaseMessagingService` maps a
  foreground notification message to `PushNotifier`. The manifest disables collection until runtime.
- `:app`: `AppContainer.telemetry` is the single choice of provider. `TelemetryConsent` observes
  `privacy.telemetry.enabled` (`PrivacySettingsSchema`, one setting for everything) and is the only code that
  enables a provider: on analytics-on it sets device user properties and logs `app_open`; on push-on it creates the
  channel and subscribes to topics `all` and `channel_<release|canary|debug>`. Call sites log unconditionally; a
  disabled provider drops the event. `TelemetryGate` is the mandatory agreement (Agree / Exit, Back disabled), shown before setup on a new install and on
  the first launch after upgrade; its answered flag lives in `UiPreferences`, so withdrawing in Settings does not re-show it.
- Events wired: `app_open` (with device properties), `workspace_open`.

## Viewing the data

Firebase console, project `easyide-a27e8`: Analytics dashboards (first_open = installs, users by device model, OS,
country, engagement, custom events). Downloads are not visible to Firebase; use
`gh api repos/ravibh5522/easyIDE/releases --jq '.[] | {tag: .tag_name, dl: [.assets[].download_count]}'`.
Notifications: Firebase console > Messaging, target topic `all` or `channel_release`.

## Open questions

- Debug builds report into the release app's stream; a `.debug` applicationId suffix would split them but changes where existing sideloaded installs upgrade.
- BigQuery export needs the Blaze plan.
- A build flavor without the Firebase module, for a fully open build.
