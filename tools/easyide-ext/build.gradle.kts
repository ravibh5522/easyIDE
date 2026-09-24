// easyide-ext: the extension author CLI (docs/extension-sdk/lld/cli.md), Apache-2.0 (decision 0015).
// Standalone build: tools/ never ships inside a service. The SDK core is compiled from
// services/shared/extension-schema sources, so validate/package accept exactly what the app does.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val shared = layout.projectDirectory.dir("../../services/shared/extension-schema")

// The schema and built-in command list sit at the root of the shared directory; copy those data files into resources.
val schemaResources by tasks.registering(Copy::class) {
    from(shared) { include("*.schema.json", "builtin-commands.json") }
    into(layout.buildDirectory.dir("generated/schema"))
}

// The wasm-rust template vendors the easyide-guest crate; copied at build time so there is one source.
val guestCrate by tasks.registering(Copy::class) {
    from(shared.dir("../guest-rust")) { include("Cargo.toml", "README.md", "LICENSE", "src/lib.rs") }
    into(layout.buildDirectory.dir("generated/guest/templates/wasm-rust/guest/easyide-guest"))
}

// The wasm-assemblyscript template vendors the AssemblyScript bindings the same way.
val guestAs by tasks.registering(Copy::class) {
    from(shared.dir("../guest-as/assembly")) { include("easyide.ts") }
    into(layout.buildDirectory.dir("generated/guest-as/templates/wasm-assemblyscript/guest/assembly"))
}

// Templates are shared with the app's "Create extension"; only templates/ goes into the jar.
val templateResources by tasks.registering(Copy::class) {
    from(shared.dir("../extension-templates")) { include("templates/**") }
    into(layout.buildDirectory.dir("generated/templates"))
}

sourceSets.main {
    kotlin.srcDir(shared.dir("src/main/kotlin"))
    resources.srcDir(schemaResources)
    resources.srcDir(templateResources)
    resources.srcDir(guestCrate.map { layout.buildDirectory.dir("generated/guest").get() })
    resources.srcDir(guestAs.map { layout.buildDirectory.dir("generated/guest-as").get() })
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Parser only, for the shared WASM static check; the interpreter never ships in the CLI.
    implementation(libs.chicory.wasm)
    testImplementation(libs.junit)
    // Compiles .wat test fixtures into modules.
    testImplementation(libs.chicory.wabt)
}

application {
    mainClass.set("dev.easyide.ext.cli.MainKt")
    applicationName = "easyide-ext"
}

// One runnable jar (cli.md sec 2: dependencies merged with a plain jar, no shadow plugin).
tasks.jar {
    manifest { attributes("Main-Class" to "dev.easyide.ext.cli.MainKt", "Implementation-Version" to project.version) }
    archiveFileName.set("easyide-ext-${project.version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
    }
}

tasks.test {
    // The jar under test is the one users run; tests also call commands in-process.
    dependsOn(tasks.jar)
    systemProperty("easyide.ext.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
