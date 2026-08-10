package dev.easyide.sandbox.model

/**
 * A selectable sandbox preset: which rootfs tarball to unpack, and what to run
 * inside it afterwards to turn a bare distro into a working toolchain.
 *
 * Presets deliberately share a base tarball where they can. The image cache is
 * keyed on the URL's file name, so "Node.js" and "Python" built on the same
 * Ubuntu base cost one download between them and differ only in
 * [setupCommands] - which matters a lot on a tablet's data plan.
 *
 * A privately hosted rootfs with the packages already baked in is the same
 * record with a different [urlByAbi] and no setup commands; nothing else in the
 * runtime changes. See
 * docs/decision/0007-sandbox-image-catalog-and-custom-rootfs.md.
 */
data class SandboxImage(
    val id: String,
    val label: String,
    val description: String,
    /** Tarball URL per Android ABI ("arm64-v8a", "x86_64"). */
    val urlByAbi: Map<String, String>,
    /** Run in the guest, in order, once the rootfs is unpacked. */
    val setupCommands: List<String> = emptyList(),
) {
    /** The first ABI the device reports that this image ships a rootfs for. */
    fun urlFor(supportedAbis: List<String>): String? =
        supportedAbis.firstNotNullOfOrNull { urlByAbi[it] }
}
