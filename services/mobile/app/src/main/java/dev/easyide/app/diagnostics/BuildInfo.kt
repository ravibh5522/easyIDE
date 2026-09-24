package dev.easyide.app.diagnostics

/**
 * What identifies this build and device in a crash report or a diagnostics screen. Filled once
 * by the caller from `PackageInfo` and `android.os.Build` ([readBuildInfo]); a plain data class
 * so the report code stays free of `android.*` and testable on the JVM.
 *
 * [versionName] carries the build channel as the Gradle build types set it:
 * `0.1.0` (release), `0.1.0-debug`, `0.1.0-canary.42+abc1234`.
 */
data class BuildInfo(
    val versionName: String,
    val versionCode: Long,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val abis: List<String>,
) {
    /** The part of [versionName] after the first `-` (`debug`, `canary.42+abc1234`), or [RELEASE_CHANNEL]. */
    val channel: String
        get() = versionName.substringAfter('-', "").ifEmpty { RELEASE_CHANNEL }

    companion object {
        const val RELEASE_CHANNEL = "release"
    }
}

/** `uname(2)` fields of the running kernel, which is what the sandbox's guest sees too. */
data class Uname(val sysname: String, val release: String, val machine: String)
