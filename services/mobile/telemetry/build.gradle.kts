plugins {
    alias(libs.plugins.android.library)
}

// Provider-neutral analytics and push contracts. Nothing here knows Firebase (decision 0031):
// a provider lives in its own module and :app picks one in a single line of AppContainer.
android {
    namespace = "dev.easyide.telemetry"
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
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
