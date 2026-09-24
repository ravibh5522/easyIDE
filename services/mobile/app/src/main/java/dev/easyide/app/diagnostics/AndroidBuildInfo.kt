package dev.easyide.app.diagnostics

import android.content.Context
import android.os.Build
import android.system.Os
import androidx.core.content.pm.PackageInfoCompat

/**
 * The two Android reads behind [BuildInfo] and [Uname], kept out of the pure report code.
 * [readBuildInfo] is called once at startup; [readUname] is passed to [DiagnosticsCollector].
 */
fun readBuildInfo(context: Context): BuildInfo {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return BuildInfo(
        versionName = info.versionName.orEmpty(),
        versionCode = PackageInfoCompat.getLongVersionCode(info),
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
    )
}

fun readUname(): Uname = Os.uname().let { Uname(it.sysname, it.release, it.machine) }
