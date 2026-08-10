package dev.tabcode.app.data

import dev.tabcode.sandbox.model.SandboxImage

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
            urlByAbi = UBUNTU_2404,
        ),
        SandboxImage(
            id = "ubuntu-24.04-dev",
            label = "Ubuntu + build tools",
            description = "git, curl and a C/C++ toolchain. What most native builds need.",
            urlByAbi = UBUNTU_2404,
            setupCommands = setupFor("build-essential", "pkg-config"),
        ),
        SandboxImage(
            id = "ubuntu-24.04-node",
            label = "Ubuntu + Node.js",
            description = "Node.js and npm from the Ubuntu archive, plus git.",
            urlByAbi = UBUNTU_2404,
            setupCommands = setupFor("nodejs", "npm"),
        ),
        SandboxImage(
            id = "ubuntu-24.04-python",
            label = "Ubuntu + Python",
            description = "Python 3 with pip and venv, plus git.",
            urlByAbi = UBUNTU_2404,
            setupCommands = setupFor("python3", "python3-pip", "python3-venv"),
        ),
    )

    val DEFAULT: SandboxImage = CATALOG.first()

    /** Environments created before the catalog existed carry no id. */
    fun byId(id: String?): SandboxImage = CATALOG.find { it.id == id } ?: DEFAULT
}

private const val UBUNTU_BASE = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"

private val UBUNTU_2404 = mapOf(
    "arm64-v8a" to "$UBUNTU_BASE/ubuntu-base-24.04.3-base-arm64.tar.gz",
    "x86_64" to "$UBUNTU_BASE/ubuntu-base-24.04.3-base-amd64.tar.gz",
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
