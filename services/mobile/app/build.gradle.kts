plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

// Single source of truth for what a build calls itself. CI supplies the build
// number and commit; a local build gets 0/"local" and still works offline.
val baseVersion = "0.1.0"
val buildNumber = (System.getenv("EASYIDE_BUILD_NUMBER") ?: "0").toInt()
val gitSha = System.getenv("EASYIDE_GIT_SHA")?.take(7) ?: "local"

// Play and every Android package manager compare versionCode, not versionName,
// so it has to rise monotonically across published builds. CI's run number is
// the only counter that does that without state.
val appVersionCode = 1 + buildNumber

android {
    namespace = "dev.easyide.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.easyide.app"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = baseVersion
    }

    signingConfigs {
        // Populated only when CI (or a local release build) exports the
        // keystore env vars. Without them `release` falls back to the debug
        // key below, so an unsigned-capable machine can still assemble.
        create("publish") {
            val keystore = System.getenv("EASYIDE_KEYSTORE_FILE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("EASYIDE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EASYIDE_KEY_ALIAS")
                keyPassword = System.getenv("EASYIDE_KEY_PASSWORD")
            }
        }
    }

    val publishSigning =
        if (System.getenv("EASYIDE_KEYSTORE_FILE") != null) {
            signingConfigs.getByName("publish")
        } else {
            signingConfigs.getByName("debug")
        }

    buildTypes {
        // Local development. Keeps the plain applicationId so an existing
        // sideloaded install keeps its sandbox and projects.
        debug {
            versionNameSuffix = "-debug"
        }

        // Published on every push to main. Suffixed so it installs alongside a
        // stable build instead of replacing it -- the whole point of a canary
        // is running it against the same device as the thing it might break.
        create("canary") {
            initWith(getByName("release"))
            applicationIdSuffix = ".canary"
            versionNameSuffix = "-canary.$buildNumber+$gitSha"
            // Canary must ship the same R8 output as release, or it cannot
            // catch a missing keep rule before stable does.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            isDebuggable = false
            signingConfig = publishSigning
            // Library modules only declare debug/release.
            matchingFallbacks += listOf("release")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = publishSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Developer tools (the kit gallery) exist only in debug and canary: release compiles the
    // no-op twin in src/release instead, so the route and its strings are absent from the R8 output.
    sourceSets {
        for (name in listOf("debug", "canary")) {
            getByName(name) {
                kotlin.directories.add("src/devtools/java")
                res.directories.add("src/devtools/res")
            }
        }
    }

    // Robolectric needs merged resources and manifest to load R.font.* (Geist) and themes.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs {
            // proot is exec'd, not dlopen'd, so it must exist as a real file in
            // the native-library directory. Without legacy packaging the .so
            // stays inside the APK and there is nothing to execute.
            useLegacyPackaging = true
        }
    }
}

// Release and canary run the same code, so one profile in src/main serves
// both instead of a per-variant copy that can drift.
baselineProfile {
    mergeIntoMain = true
}

// The declarative extension templates, shared with `easyide-ext init`, as APK assets under
// authoring-templates/ for "Create extension". One copy in git (services/shared); the WASM
// templates need a toolchain and build-time guest bindings, so they stay CLI-only.
abstract class CopyAuthoringTemplates : DefaultTask() {
    @get:InputDirectory
    abstract val templates: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(templates) { exclude("wasm-*/**") }
            into(outputDir.dir("authoring-templates"))
        }
    }
}

val copyAuthoringTemplates by tasks.registering(CopyAuthoringTemplates::class) {
    templates.set(layout.projectDirectory.dir("../../shared/extension-templates/templates"))
    outputDir.set(layout.buildDirectory.dir("generated/authoring-template-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyAuthoringTemplates, CopyAuthoringTemplates::outputDir)
    }
}

dependencies {
    implementation(project(":sandbox-runtime"))
    implementation(project(":extensions"))
    implementation(project(":terminal-view"))
    implementation(project(":lsp"))
    // L2 extension logic (decision 0014); the only route to Chicory is through this module.
    implementation(project(":ext-wasm"))
    baselineProfile(project(":baselineprofile"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    // Installs the baseline profile shipped in the APK on sideloaded and
    // non-Play installs, where no cloud profile ever arrives.
    implementation(libs.androidx.profileinstaller)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlin.textmate.core)
    // Tree API only (JsonElement), no serialization plugin - decision 0013.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.documentfile)
    // Registry signature verification (Ed25519Verify only); decision 0016 amendment.
    implementation(libs.tink.android)
    debugImplementation(libs.androidx.ui.tooling)
    // Hosts the compose test rule's activity under Robolectric; test-only, debug variant only.
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.jgit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(testFixtures(project(":ext-wasm")))
    testImplementation(testFixtures(project(":extension-schema")))
}

// WASM port tests drive the real host with :ext-wasm's compiled `.wat` fixtures (proxy.wasm).
tasks.withType<Test>().configureEach {
    // Robolectric's SDK 36+ shared-memory shadow reaches into JDK internals (FileDescriptor).
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    dependsOn(":ext-wasm:compileWatFixtures")
    systemProperty("easyide.wasmFixtures", rootProject.file("ext-wasm/build/generated/wasm-fixtures").absolutePath)
}
