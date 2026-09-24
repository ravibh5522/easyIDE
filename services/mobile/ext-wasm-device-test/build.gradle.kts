// Instrumented tests for :ext-wasm on ART (decision 0014 spike conditions and the
// WasmHost device suite). Ships nothing: the library has no main code; only its
// androidTest APK is installed, by `connectedReleaseAndroidTest`.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.easyide.extwasm.devicetest"
    compileSdk = 37

    // The debug test APK is debuggable, and debuggable ART keeps code out of the JIT, which
    // made the interpreter measure ~25x slower than it ships. Same rule as androidx.benchmark.
    testBuildType = "release"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The .wasm fixtures :ext-wasm compiles from its .wat sources, as androidTest assets.
val wasmFixtures by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

abstract class CopyWasmFixtures : DefaultTask() {
    @get:InputFiles
    abstract val fixtures: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(fixtures)
            into(outputDir)
        }
    }
}

val copyWasmFixtures by tasks.registering(CopyWasmFixtures::class) {
    fixtures.from(wasmFixtures)
    outputDir.set(layout.buildDirectory.dir("generated/wasm-fixture-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(
            copyWasmFixtures,
            CopyWasmFixtures::outputDir,
        )
    }
}

dependencies {
    wasmFixtures(project(path = ":ext-wasm", configuration = "wasmFixtures"))
    androidTestImplementation(project(":ext-wasm"))
    androidTestImplementation(testFixtures(project(":ext-wasm")))
    androidTestImplementation(libs.chicory.runtime)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
