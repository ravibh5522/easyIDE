# infra

CI and release automation. Deployment/IaC config would live here too, but there is no
server component yet ([services/backend](../services/backend/) is deliberately empty), so
there is nothing to deploy.

The workflows themselves live in [.github/workflows/](../.github/workflows/) because that
is the only path GitHub Actions reads. This file is the operator documentation for them.

## Build channels

| Channel | Trigger | applicationId | Installs alongside stable? |
|---|---|---|---|
| `debug` | local `./gradlew :app:assembleDebug` | `dev.easyide.app` | no -- same id as stable |
| `canary` | every push to `main`, or manual dispatch | `dev.easyide.app.canary` | **yes** |
| `release` | pushing a `v*` tag | `dev.easyide.app` | n/a -- it is stable |

Canary is deliberately id-suffixed and labelled **easyIDE Canary**, so you can run the tip
of `main` on the same tablet as a build you rely on. That is the entire reason it exists;
a canary you have to uninstall your working build to try is not a canary.

`release` and `debug` share an applicationId but not a signing key, so you must uninstall
one before installing the other.

## Versioning

Set in one place at the top of [services/mobile/app/build.gradle.kts](../services/mobile/app/build.gradle.kts):

```
versionCode = 1 + EASYIDE_BUILD_NUMBER      # CI run number; must rise monotonically
versionName = 0.1.0                          # release
              0.1.0-canary.<run>+<sha7>      # canary
              0.1.0-debug                    # local
```

A local build with no environment set gets `versionCode = 1` and `+local`, so the build
works offline with no CI involvement.

## Release signing

Both workflows read four secrets. **If they are not set the build still succeeds, but the
APK is debug-signed** -- the workflow says so in its log and in the release notes. A
debug-signed APK cannot be upgraded to a properly signed one; users must uninstall first.
So set these before telling anyone to install a build.

| Secret | Value |
|---|---|
| `EASYIDE_KEYSTORE_BASE64` | `base64 -w0 easyide.jks` |
| `EASYIDE_KEYSTORE_PASSWORD` | keystore password |
| `EASYIDE_KEY_ALIAS` | key alias |
| `EASYIDE_KEY_PASSWORD` | key password |

Creating the keystore (do this once, keep the file and passwords somewhere you will not
lose them -- losing the key means no existing install can ever be upgraded again):

```
keytool -genkeypair -v -keystore easyide.jks -alias easyide \
        -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 easyide.jks   # paste into the EASYIDE_KEYSTORE_BASE64 secret
gh secret set EASYIDE_KEYSTORE_BASE64 < /dev/stdin
```

`*.jks`, `*.keystore` and `keystore.properties` are gitignored. Never commit the key.

## Cutting a stable release

```
git tag v0.2.0
git push origin v0.2.0
```

The tag triggers [release.yml](../.github/workflows/release.yml), which builds, signs,
attaches `LICENSE` and `NOTICE.md`, and publishes a GitHub Release with a SHA-256.

## Licence obligations on every published build

Publishing an APK distributes **PRoot (GPL-2.0-or-later)** and **talloc
(LGPL-3.0-or-later)**, which are bundled in it. Both workflows therefore attach
[NOTICE.md](../NOTICE.md) to every release, which carries the attribution and the written
offer of source. Do not remove that step, and do not publish an APK by hand without it.
See [decision 0008](../docs/decision/0008-noncommercial-source-available-licensing.md).

## Known gaps

- **No test or lint gate.** Nothing blocks a broken build from becoming a canary. There is
  no test suite yet for a workflow to run.
- **The NDK is installed per run** (`sdkmanager --install ndk;<pinned>`), which costs a few
  minutes each time. Worth caching once builds get frequent.
- **No PR build.** Only `main` and tags build; a pull request gets no APK.
