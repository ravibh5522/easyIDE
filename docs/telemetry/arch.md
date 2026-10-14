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
  foreground notification message to `PushNotifier`, and a data message (an announcement, `PushMessage.fromData`) to
  `PushNotifier` plus `PushInbox`. The manifest disables collection until runtime.
- `:app`: `AppContainer.telemetry` is the single choice of provider. `TelemetryConsent` observes
  `privacy.telemetry.enabled` (`PrivacySettingsSchema`, one setting for everything) and is the only code that
  enables a provider: on analytics-on it sets device user properties and logs `app_open`; on push-on it creates the
  channel and subscribes to topics `all`, `channel_<release|canary|debug>` and `model_<Build.MODEL>` (characters a topic
  disallows become `_`). Call sites log unconditionally; a
  disabled provider drops the event. `TelemetryGate` is the mandatory agreement (Agree / Exit, Back disabled), shown before setup on a new install and on
  the first launch after upgrade; its answered flag lives in `UiPreferences`, so withdrawing in Settings does not re-show it.
- Also behind the neutral contracts: `CrashReporter` (Crashlytics; the app's own `CrashHandler` still writes its local
  report and chains to Crashlytics' handler), `Performance` (automatic startup/screen traces plus `startTrace`), and
  `RemoteConfig` (`refresh()` after consent; reads return the caller's default until a remote value exists). All are
  switched on by the same consent and off in the manifest until then.
- Events wired: `app_open` (with device properties), `workspace_open`.
- Announcements: a data message with `title`, `body`, optional `link` (https; opened on tap and by the action button),
  `action` (button label) and `image` (https; downloaded once by `PushInbox`, max 1 MiB, shown as the notification's large
  icon and beside the dialog text). `PushInbox` keeps the last one (SharedPreferences + image file) until the app shows it
  once as `AnnouncementDialog` over any screen except first-run setup; any answer clears it. Sent with
  `tools/push-announce.py` (FCM HTTP v1 via `google-auth`, Apache-2.0, verified on PyPI 2026-10-14: 2.62.0 released
  2026-10-12). Builds before this ignore data messages.

## Firebase console map

| Console area | What it needs from us |
|---|---|
| Analytics: Dashboard, Realtime, Events, Audiences, Latest Release | nothing; fills once users agree |
| Analytics: Events Config, Custom Definitions | register our event params (`version_name`, `version_code`) and user properties (`build_channel`, `device_maker`, `device_model`, `os_sdk`, `cpu_abi`) as custom definitions to report on them |
| Analytics: DebugView | `adb shell setprop debug.firebase.analytics.app dev.easyide.app` (`adb shell setprop debug.firebase.analytics.app .none.` to stop) |
| Crashlytics, Release Monitoring | done in the app (`CrashReporter`); Release Monitoring reads Crashlytics + Analytics per version |
| Performance | done in the app (`Performance`), automatic traces only |
| Remote Config, A/B Testing | SDK wired; no parameters exist yet. Add a key when an experiment needs one, read it with `telemetry.remoteConfig.bool/string(key, default)`, then create the A/B test in the console |
| Messaging | done; target topic `all`, `channel_release` / `channel_canary` or `model_<model>`. Console sends are notification messages (system-drawn, no in-app copy); `tools/push-announce.py` sends announcements |
| App Distribution | console/CLI only: upload the release APK from `gh release download` to tester groups; no SDK |
| Test Lab | console/gcloud only: run a Robo test on the release APK |
| Dynamic Links | shut down by Google (2025-08-25); not used |

## Viewing the data

Firebase console, project `easyide-a27e8`: Analytics dashboards (first_open = installs, users by device model, OS,
country, engagement, custom events). Downloads are not visible to Firebase; use
`gh api repos/ravibh5522/easyIDE/releases --jq '.[] | {tag: .tag_name, dl: [.assets[].download_count]}'`.
Notifications: Firebase console > Messaging, target topic `all` or `channel_release`.

## Open questions

- Debug builds report into the release app's stream; a `.debug` applicationId suffix would split them but changes where existing sideloaded installs upgrade.
- BigQuery export needs the Blaze plan.
- A build flavor without the Firebase module, for a fully open build.
