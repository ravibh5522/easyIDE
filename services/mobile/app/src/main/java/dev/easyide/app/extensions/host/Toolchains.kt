package dev.easyide.app.extensions.host

/**
 * The interpreters and compilers a Run action may need, and how to install each into the Ubuntu
 * guest. The single table behind "run it and install what is missing": a new language is one line.
 */
object Toolchains {
    private const val APT = "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y"
    private const val BUILD = "$APT build-essential"

    private val installByTool: Map<String, String> = mapOf(
        "python3" to "$APT python3",
        "node" to "easyide-install-node",
        "npx" to "easyide-install-node",
        "gcc" to BUILD,
        "g++" to BUILD,
        "make" to BUILD,
        "go" to "$APT golang-go",
        "rustc" to "$APT rustc cargo",
        "cargo" to "$APT rustc cargo",
        "java" to "$APT default-jdk",
        "javac" to "$APT default-jdk",
        "ruby" to "$APT ruby",
        "php" to "$APT php-cli",
        "lua" to "$APT lua5.4",
        "perl" to "$APT perl",
    )

    /** The first known tool a shell line runs (`cd '/w' && 'python3' 'a.py'` -> python3), or null for none. */
    fun toolIn(commandLine: String): String? =
        commandLine.split(' ').map { it.trim('\'', '"') }.firstOrNull { it in installByTool }

    fun installFor(tool: String): String? = installByTool[tool]
}
