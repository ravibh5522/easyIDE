package dev.tabcode.sandbox

import android.os.Build
import java.io.File

/**
 * Best-effort root check used only to decide whether to *offer* the chroot
 * backend. It is not a security control: a false negative just means the user
 * gets the safer proot default, and a false positive is harmless because the
 * chroot bootstrap simply fails without real root.
 * See docs/decision/0002-sandbox-backend-proot-default-chroot-optin.md.
 */
class RootDetector(
    private val suCandidates: List<String> = DEFAULT_SU_PATHS,
    private val buildTags: String? = Build.TAGS,
) {

    fun isRootLikelyAvailable(): Boolean =
        hasTestKeysBuild() || hasSuBinary()

    private fun hasTestKeysBuild(): Boolean =
        buildTags?.contains(TEST_KEYS) == true

    private fun hasSuBinary(): Boolean =
        suCandidates.any { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }

    private companion object {
        const val TEST_KEYS = "test-keys"

        val DEFAULT_SU_PATHS = listOf(
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/vendor/bin/su",
            "/su/bin/su",
            "/data/adb/magisk",
        )
    }
}
