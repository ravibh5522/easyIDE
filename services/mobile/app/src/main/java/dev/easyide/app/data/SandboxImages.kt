package dev.easyide.app.data

import dev.easyide.sandbox.download.Sha256
import dev.easyide.sandbox.model.RootfsArchive
import dev.easyide.sandbox.model.SandboxImage

/**
 * The sandbox presets offered when creating an environment.
 *
 * One declarative source: the runtime knows how to unpack a rootfs and run
 * setup commands, and knows nothing about which distros exist. Adding a preset
 * - including one backed by a rootfs we host ourselves with the packages
 * pre-baked - is an entry here and no code change anywhere else. See
 * docs/decision/0007-sandbox-image-catalog-and-custom-rootfs.md.
 *
 * Every preset below shares the one Ubuntu base tarball on purpose: the image
 * cache is keyed on the URL, so picking Node.js after Python is an apt install,
 * not a second 30 MB download.
 */
object SandboxImages {

    val CATALOG: List<SandboxImage> = listOf(
        SandboxImage(
            id = "ubuntu-24.04",
            label = "Ubuntu 24.04",
            description = "Bare Ubuntu base - apt, dpkg and sudo, nothing else. Fastest to set up.",
            rootfsByAbi = UBUNTU_2404,
        ),
        SandboxImage(
            id = "ubuntu-24.04-dev",
            label = "Ubuntu + build tools",
            description = "git, curl and a C/C++ toolchain. What most native builds need.",
            rootfsByAbi = UBUNTU_2404,
            setupCommands = setupFor("build-essential", "pkg-config"),
        ),
        SandboxImage(
            id = "ubuntu-24.04-node",
            label = "Ubuntu + Node.js",
            description = "Node.js and npm from the Ubuntu archive, plus git.",
            rootfsByAbi = UBUNTU_2404,
            setupCommands = setupFor("nodejs", "npm"),
        ),
        SandboxImage(
            id = "ubuntu-24.04-python",
            label = "Ubuntu + Python",
            description = "Python 3 with pip and venv, plus git.",
            rootfsByAbi = UBUNTU_2404,
            setupCommands = setupFor("python3", "python3-pip", "python3-venv"),
        ),
    )

    val DEFAULT: SandboxImage = CATALOG.first()

    /** Environments created before the catalog existed carry no id. */
    fun byId(id: String?): SandboxImage = CATALOG.find { it.id == id } ?: DEFAULT
}

private const val UBUNTU_BASE = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"

/**
 * Digests copied from the release's SHA256SUMS next to these tarballs
 * (https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS,
 * fetched 2026-09-24). Its detached SHA256SUMS.gpg verified as a good
 * signature from the "Ubuntu CD Image Automatic Signing Key (2012)", fingerprint
 * 8439 38DF 228D 22F7 B374 2BC0 D94A A3F0 EFE2 1092; each tarball was also
 * downloaded and hashed to the same value. Changing a URL here means taking
 * its line from that same file - the download is refused on any mismatch.
 */
private val UBUNTU_2404 = mapOf(
    "arm64-v8a" to RootfsArchive(
        url = "$UBUNTU_BASE/ubuntu-base-24.04.3-base-arm64.tar.gz",
        sha256 = Sha256.parse("7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"),
    ),
    "x86_64" to RootfsArchive(
        url = "$UBUNTU_BASE/ubuntu-base-24.04.3-base-amd64.tar.gz",
        sha256 = Sha256.parse("6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9"),
    ),
)

/**
 * Every preset wants the same baseline (git, curl, CA certificates) before its
 * own packages, so the shared prefix lives here rather than being repeated -
 * and forgotten - in each entry.
 */
private fun setupFor(vararg packages: String): List<String> = listOf(
    "apt-get update",
    "DEBIAN_FRONTEND=noninteractive apt-get install -y git curl ca-certificates ${packages.joinToString(" ")}",
)
