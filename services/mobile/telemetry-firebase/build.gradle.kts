plugins {
    alias(libs.plugins.android.library)
}

// The only module that names Firebase. Replacing the provider means replacing this module and
// one line in AppContainer (decision 0031).
android {
    namespace = "dev.easyide.telemetry.firebase"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":telemetry"))
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)
    implementation(libs.firebase.config)
}
