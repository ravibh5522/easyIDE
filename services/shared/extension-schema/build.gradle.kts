// Apache-2.0 SDK core (decision 0015): the manifest schema plus everything that decides whether
// a package is valid - JSON, schema validator, manifest parser, when-clause parser, capability
// rules, package layout, canonical JSON and signatures. Built as `:extension-schema` inside
// services/mobile and compiled from source by tools/easyide-ext, so the app and the CLI accept
// exactly the same packages. Nothing here may import app code (R-ENG-04).
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

// The schema file stays at the directory root, where non-Kotlin tools find it.
sourceSets {
    main {
        resources {
            srcDir(".")
            include("*.schema.json", "builtin-commands.json")
        }
    }
}

dependencies {
    // SettingsPort exposes StateFlow; JsonElement is the tree type of every public API.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
