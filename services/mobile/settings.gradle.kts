pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "easyIDE-mobile"

include(":app")
include(":sandbox-runtime")
include(":lsp")
include(":extensions")
include(":terminal-emulator")
include(":terminal-view")
include(":baselineprofile")
