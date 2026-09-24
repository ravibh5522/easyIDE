// Pure JVM on purpose: the same host runs in JVM unit tests, on ART and inside
// tools/easyide-ext. This is the only module allowed to see Chicory (decision 0014,
// docs/extension-sdk/hld.md module rules).
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Fake ports + fixture access shared with :ext-wasm-device-test (src/testFixtures/kotlin).
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

// Build tooling that turns src/testFixtures/wat/*.wat into .wasm test fixtures.
// Readable text fixtures instead of committed binaries; wabt never ships.
val watc: SourceSet by sourceSets.creating

val compileWatFixtures by tasks.registering(JavaExec::class) {
    val input = layout.projectDirectory.dir("src/testFixtures/wat")
    val output = layout.buildDirectory.dir("generated/wasm-fixtures")
    inputs.dir(input)
    outputs.dir(output)
    classpath = watc.runtimeClasspath
    mainClass.set("dev.easyide.extwasm.watc.WatCompilerKt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(input.asFile.absolutePath, output.get().asFile.absolutePath)
    })
}

sourceSets.test {
    resources.srcDir(compileWatFixtures)
}

// Lets :ext-wasm-device-test ship the same fixtures as androidTest assets.
val wasmFixtures by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(wasmFixtures.name, compileWatFixtures)
}

dependencies {
    implementation(libs.chicory.runtime)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesApi(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    "watcImplementation"(libs.chicory.wabt)
}
