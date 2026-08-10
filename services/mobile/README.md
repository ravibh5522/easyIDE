# services/mobile

The Android tablet IDE app — Kotlin + Jetpack Compose shell, WebView-hosted Theia frontend, foreground Service managing the sandbox process tree. See [/docs/sandbox-runtime/arch.md](../../docs/sandbox-runtime/arch.md) for the runtime architecture, [/docs/ui-shell/arch.md](../../docs/ui-shell/arch.md) for screens and navigation, and [/docs/design-system/arch.md](../../docs/design-system/arch.md) for theming.

## Build

```
./gradlew :app:assembleDebug
```

**Requires a full JDK (17+), not a JRE.** `javac` must be on the toolchain — a JRE-only install fails with `Toolchain installation ... does not provide the required capabilities: [JAVA_COMPILER]`. If the system Java is a JRE, point Gradle at a JDK for the invocation:

```
JAVA_HOME=/path/to/jdk ./gradlew :app:assembleDebug
```

`local.properties` holds the machine-specific `sdk.dir` and is gitignored — recreate it if cloning fresh.

Verified building against: Gradle 9.7.0, AGP 9.3.1, Kotlin 2.4.10, compileSdk/targetSdk 37, minSdk 26.

Note: AGP 9.x has built-in Kotlin support — do **not** add the `org.jetbrains.kotlin.android` plugin, and use the `compilerOptions {}` DSL rather than the removed `kotlinOptions {}` block.

## Current state

The app builds and packages. Implemented: navigation with animated transitions, six-theme Material 3 system with persisted preference, responsive layouts driven by window size classes, reduce-motion compliance, and a real environment/project data layer with sharing (see [decision 0005](../../docs/decision/0005-sandbox-environment-sharing-model.md)).

Not implemented: anything that actually executes a sandbox. The launchers build argv but nothing runs it, provisioning has no bootstrap tarball, and Workspace stages render placeholders rather than Theia.

**Verification is compile-only.** The local Android SDK has no system image installed, so the emulator cannot boot and nothing here has been rendered or executed. Treat runtime behaviour as unproven — see the "Verification status" sections in `/docs/*/tracker.md`.

## Module layout

```
services/mobile/
  app/                 -- Compose UI, ViewModels, navigation, theming
  sandbox-runtime/      -- environment/project model, persistence, proot/chroot launchers, bootstrap extraction, foreground Service
```

`bridge/` (WebView <-> Kotlin JS bridge) is still planned but has nothing to bridge to yet.

Dependency direction is one-way: `app` depends on `sandbox-runtime`, never the reverse. UI preferences live in `app`; sandbox state lives in `sandbox-runtime`, each with its own DataStore, so neither layer owns the other's persistence.
