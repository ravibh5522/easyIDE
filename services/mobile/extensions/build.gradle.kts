// Pure JVM on purpose: no Android types, so the logic is unit-testable off-device
// and reusable by tools/easyide-ext. See docs/extension-sdk/hld.md module rules.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Target 17 bytecode like the Android modules, built by whatever JDK runs Gradle
// (a toolchain would demand a locally installed JDK 17).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":extension-schema"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
