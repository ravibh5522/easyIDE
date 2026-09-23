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

// The manifest schema is shared with the easyide-ext CLI (R-ENG-05); it is packaged from
// services/shared so there is exactly one copy.
sourceSets {
    main {
        resources {
            srcDir("../../shared/extension-schema")
            include("*.schema.json")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
