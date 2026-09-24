// Apache-2.0 SDK core (decision 0015): the manifest schema plus everything that decides whether
// a package is valid or runs it headlessly - JSON, schema validator, manifest parser, when-clause
// parser and evaluator, capability rules, the L1 action runner, package layout, WASM static
// validation and metering, canonical JSON, signatures and registry verification. Built as `:extension-schema` inside
// services/mobile and compiled from source by tools/easyide-ext, so the app and the CLI accept
// exactly the same packages. Nothing here may import app code (R-ENG-04).
plugins {
    alias(libs.plugins.kotlin.jvm)
    // TestSupport and FakeHost, shared with :extensions tests.
    `java-test-fixtures`
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
    // WASM static validation parses modules; the interpreter (chicory runtime) stays in :ext-wasm.
    api(libs.chicory.wasm)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.junit)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    systemProperty("easyide.fixtures", file("src/testFixtures/fixtures").absolutePath)
}
