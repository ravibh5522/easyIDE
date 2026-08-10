package dev.tabcode.sandbox.bootstrap

import android.content.Context
import android.os.Build
import dev.tabcode.sandbox.SandboxError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Locates the proot executables and its shared libraries.
 *
 * **The split here is forced by SELinux, and is the whole reason this class
 * exists.** On a real device (measured: Xiaomi Pad 6, Android 13, enforcing)
 * executing a binary from app-private storage is denied:
 *
 * ```
 * avc: granted { execute }          for name="proot"
 * avc: denied  { execute_no_trans } for path=".../files/run/bin/proot"
 *      scontext=u:r:untrusted_app:s0 tcontext=u:object_r:app_data_file:s0
 * ```
 *
 * `execute` (mapping a library) is allowed; `execute_no_trans` (exec'ing a
 * program) is not. So:
 *
 *  - **executables** - proot and its two loaders - ship in `jniLibs` and run
 *    from the native-library directory, which is `apk_data_file` and executable.
 *    They must be named `lib*.so` to survive packaging, hence `libproot.so`.
 *  - **shared libraries** stay assets and are copied into app storage, because
 *    `libtalloc.so.2` cannot be renamed (its SONAME is compiled in) and dlopen
 *    from app data is permitted.
 *
 * This also requires `android:extractNativeLibs="true"` plus legacy jniLibs
 * packaging, or the libraries are never written to disk as real files.
 */
class ProotInstaller(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {

    data class Installation(
        val prootBinary: File,
        val loader: File,
        val loader32: File,
        val libraryDir: File,
    )

    suspend fun ensureInstalled(targetDir: File): Result<Installation> = runCatching {
        withContext(ioDispatcher) {
            val abi = supportedAbi()
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)

            val proot = File(nativeDir, PROOT_LIB)
            if (!proot.canExecute()) {
                throw SandboxError.BackendUnavailable(
                    "proot",
                    "${proot.absolutePath} missing or not executable - check " +
                        "extractNativeLibs and jniLibs packaging",
                )
            }

            val libDir = File(targetDir, LIB_DIR).apply { mkdirs() }
            copyAsset(abi, LIBTALLOC, libDir)
            copyAsset(abi, LIBSHMEM, libDir)

            Installation(
                prootBinary = proot,
                loader = File(nativeDir, LOADER_LIB),
                loader32 = File(nativeDir, LOADER32_LIB),
                libraryDir = libDir,
            )
        }
    }

    /** proot must match the device ABI; a mismatch fails at exec unhelpfully. */
    private fun supportedAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }
            ?: throw SandboxError.BackendUnavailable(
                "proot",
                "no bundled binary for ${Build.SUPPORTED_ABIS.joinToString()}",
            )

    private fun copyAsset(abi: String, name: String, destDir: File): File {
        val dest = File(destDir, name)
        if (dest.isFile && dest.length() > 0) return dest
        context.assets.open("$ASSET_ROOT/$abi/$name").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }

    private companion object {
        const val ASSET_ROOT = "sandbox"
        const val LIB_DIR = "lib"

        /** jniLibs entries must be named lib*.so to be packaged and extracted. */
        const val PROOT_LIB = "libproot.so"
        const val LOADER_LIB = "libprootloader.so"
        const val LOADER32_LIB = "libprootloader32.so"

        const val LIBTALLOC = "libtalloc.so.2"
        const val LIBSHMEM = "libandroid-shmem.so"

        val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")
    }
}
