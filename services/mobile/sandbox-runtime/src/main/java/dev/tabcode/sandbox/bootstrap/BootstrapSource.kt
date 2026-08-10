package dev.tabcode.sandbox.bootstrap

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * Where a rootfs tarball comes from. Injected rather than hardcoded so the
 * download URL / asset name is a configuration decision, and so provisioning
 * can be tested from a local file with no network.
 */
interface BootstrapSource {
    /** Human-readable, shown in provisioning UI and error messages. */
    val label: String

    fun open(): InputStream
}

/** A tarball shipped inside the APK's assets. */
class AssetBootstrapSource(
    private val context: Context,
    private val assetName: String,
    override val label: String = assetName,
) : BootstrapSource {
    override fun open(): InputStream = context.assets.open(assetName)
}

/** A tarball already downloaded to disk. */
class FileBootstrapSource(
    private val file: File,
    override val label: String = file.name,
) : BootstrapSource {
    override fun open(): InputStream = file.inputStream()
}
