plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

// Generates app/src/<variant>/generated/baselineProfiles by driving the app
// on a connected device: `./gradlew :app:generateBaselineProfile`. Nothing
// here ships; the app only carries the resulting profile text file.
android {
    namespace = "dev.easyide.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // BaselineProfileRule needs API 28+ (rooted) or 33+ (any device).
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
