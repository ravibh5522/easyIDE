plugins {
    alias(libs.plugins.android.library)
}

android {
    // Kept as the upstream package (com.termux.terminal) rather than renamed
    // into dev.easyide.* - see NOTICE.md. This is vendored Apache-2.0 code,
    // not project code, and keeping the namespace matches the Java package
    // declarations so future upstream syncs stay a low-diff copy.
    namespace = "com.termux.terminal"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
    }

    // ndk-build (Android.mk), not CMake: this is a single trivial .c file,
    // and it matches termux-app's own upstream build exactly rather than
    // reinventing an equivalent CMakeLists.txt.
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}
