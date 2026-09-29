package dev.easyide.sandbox.bootstrap

import java.io.File

/**
 * Puts `easyide-install-node` in every environment: the one way easyIDE installs Node.js.
 *
 * Ubuntu 24.04's `nodejs`/`npm` packages are Node 18 with npm 9.2, too old for what runs on them:
 * npm 9.2 dies with "Exit handler never called!" while running a package's postinstall (so
 * `npm install -g @anthropic-ai/claude-code` leaves a placeholder instead of the CLI), and
 * current CLIs want Node 20 or newer. The script installs the current LTS release from
 * nodejs.org into `/usr/local`, checking the tarball against the release's `SHASUMS256.txt`.
 * It does nothing when a new enough Node is already there, so it is safe to run from every
 * extension's install recipe and from the Node preset.
 */
internal object GuestNode {

    const val COMMAND = "easyide-install-node"
    private const val GUEST_PATH = "usr/local/bin/$COMMAND"
    private const val MIN_MAJOR = 20
    private const val LTS_LINE = "v22.x"

    fun ensure(rootfs: File) {
        val script = File(rootfs, GUEST_PATH)
        if (script.isFile && script.readText() == SCRIPT) return
        script.parentFile.mkdirs()
        script.writeText(SCRIPT)
        script.setExecutable(true, false)
    }

    val SCRIPT = """
        #!/bin/sh
        # easyIDE: install the current Node.js LTS from nodejs.org unless Node $MIN_MAJOR+ is already present.
        set -eu
        if command -v node >/dev/null 2>&1 && [ "${'$'}(node -p 'process.versions.node.split(".")[0]')" -ge $MIN_MAJOR ]; then exit 0; fi
        case "${'$'}(uname -m)" in
          aarch64|arm64) arch=arm64 ;;
          x86_64) arch=x64 ;;
          *) echo "easyide-install-node: unsupported CPU ${'$'}(uname -m)" >&2; exit 1 ;;
        esac
        if ! command -v curl >/dev/null 2>&1 || ! command -v xz >/dev/null 2>&1; then
          apt-get update
          DEBIAN_FRONTEND=noninteractive apt-get install -y curl ca-certificates xz-utils
        fi
        base=https://nodejs.org/dist/latest-$LTS_LINE
        tmp=${'$'}(mktemp -d)
        trap 'rm -rf "${'$'}tmp"' EXIT
        curl -fsSL "${'$'}base/SHASUMS256.txt" -o "${'$'}tmp/sums"
        line=${'$'}(grep "linux-${'$'}arch.tar.xz" "${'$'}tmp/sums" | head -n 1)
        [ -n "${'$'}line" ] || { echo "easyide-install-node: no linux-${'$'}arch build listed" >&2; exit 1; }
        sum=${'$'}{line%% *}
        name=${'$'}{line##* }
        curl -fsSL "${'$'}base/${'$'}name" -o "${'$'}tmp/node.tar.xz"
        echo "${'$'}sum  ${'$'}tmp/node.tar.xz" | sha256sum -c - >/dev/null
        tar -xJf "${'$'}tmp/node.tar.xz" -C /usr/local --strip-components=1 --exclude='*/CHANGELOG.md' --exclude='*/LICENSE' --exclude='*/README.md'
        node --version
    """.trimIndent() + "\n"
}
