pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // One version table for the app and the CLI.
    versionCatalogs {
        create("libs") { from(files("../../services/mobile/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "easyide-ext"
