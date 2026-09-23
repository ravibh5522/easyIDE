plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

// Single source of truth for what a build calls itself. CI supplies the build
// number and commit; a local build gets 0/"local" and still works offline.
val baseVersion = "0.1.0"
val buildNumber = (System.getenv("EASYIDE_BUILD_NUMBER") ?: "0").toInt()
val gitSha = System.getenv("EASYIDE_GIT_SHA")?.take(7) ?: "local"

// Play and every Android package manager compare versionCode, not versionName,
// so it has to rise monotonically across published builds. CI's run number is
// the only counter that does that without state.
val appVersionCode = 1 + buildNumber

android {
    namespace = "dev.easyide.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.easyide.app"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = baseVersion
    }

    signingConfigs {
        // Populated only when CI (or a local release build) exports the
        // keystore env vars. Without them `release` falls back to the debug
        // key below, so an unsigned-capable machine can still assemble.
        create("publish") {
            val keystore = System.getenv("EASYIDE_KEYSTORE_FILE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("EASYIDE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EASYIDE_KEY_ALIAS")
                keyPassword = System.getenv("EASYIDE_KEY_PASSWORD")
            }
        }
    }

    val publishSigning =
        if (System.getenv("EASYIDE_KEYSTORE_FILE") != null) {
            signingConfigs.getByName("publish")
        } else {
            signingConfigs.getByName("debug")
        }

    buildTypes {
        // Local development. Keeps the plain applicationId so an existing
        // sideloaded install keeps its sandbox and projects.
        debug {
            versionNameSuffix = "-debug"
        }

        // Published on every push to main. Suffixed so it installs alongside a
        // stable build instead of replacing it -- the whole point of a canary
        // is running it against the same device as the thing it might break.
        create("canary") {
            initWith(getByName("release"))
            applicationIdSuffix = ".canary"
            versionNameSuffix = "-canary.$buildNumber+$gitSha"
            // Canary must ship the same R8 output as release, or it cannot
            // catch a missing keep rule before stable does.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            isDebuggable = false
            signingConfig = publishSigning
            // Library modules only declare debug/release.
            matchingFallbacks += listOf("release")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = publishSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // proot is exec'd, not dlopen'd, so it must exist as a real file in
            // the native-library directory. Without legacy packaging the .so
            // stays inside the APK and there is nothing to execute.
            useLegacyPackaging = true
        }
    }
}

// Release and canary run the same code, so one profile in src/main serves
// both instead of a per-variant copy that can drift.
baselineProfile {
    mergeIntoMain = true
}

dependencies {
    implementation(project(":sandbox-runtime"))
    implementation(project(":terminal-view"))
    baselineProfile(project(":baselineprofile"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    // Installs the baseline profile shipped in the APK on sideloaded and
    // non-Play installs, where no cloud profile ever arrives.
    implementation(libs.androidx.profileinstaller)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlin.textmate.core)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
