package dev.easyide.app.diagnostics

import java.io.File
import java.io.IOException

/**
 * Reads which distribution a rootfs holds from its `os-release` file, so Diagnostics can show
 * "Ubuntu 24.04.3 LTS" instead of only the preset name (which says what was asked for, not
 * what is on disk).
 */
object OsRelease {
    /** Per os-release(5) the file lives in `/etc`, or in `/usr/lib` when `/etc/os-release` is missing. */
    private val LOCATIONS = listOf("etc/os-release", "usr/lib/os-release")

    /** A real os-release file is well under 1 KiB; anything huge is not one and is not read. */
    private const val MAX_BYTES = 16 * 1024L

    /** `KEY=value` lines; `#` comments and blank lines skipped, one level of quoting removed. */
    fun parse(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') && '=' in it }
            .associate { line -> line.substringBefore('=').trim() to unquote(line.substringAfter('=').trim()) }

    /** `PRETTY_NAME`, else `NAME` plus `VERSION_ID`, else null when neither is usable. */
    fun displayName(text: String): String? {
        val fields = parse(text)
        fields["PRETTY_NAME"]?.takeIf { it.isNotBlank() }?.let { return it }
        val name = fields["NAME"]?.takeIf { it.isNotBlank() } ?: return null
        return listOfNotNull(name, fields["VERSION_ID"]?.takeIf { it.isNotBlank() }).joinToString(" ")
    }

    /**
     * The display name of the distribution in [rootfs], or null when it has no readable
     * os-release (not provisioned yet, or a custom rootfs without one). Reading follows the
     * file's own relative symlink (`/etc/os-release -> ../usr/lib/os-release` on Ubuntu).
     */
    fun read(rootfs: File): String? {
        for (relative in LOCATIONS) {
            val file = File(rootfs, relative)
            if (!file.isFile || file.length() > MAX_BYTES) continue
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (unreadable: IOException) {
                continue // a rootfs file we cannot read is the same as one that is not there
            }
            displayName(text)?.let { return it }
        }
        return null
    }

    private fun unquote(value: String): String {
        val quote = value.firstOrNull()
        if (value.length < 2 || (quote != '"' && quote != '\'') || value.last() != quote) return value
        val inner = value.substring(1, value.length - 1)
        return if (quote == '"') inner.replace(ESCAPED, "$1") else inner
    }

    /** The characters os-release(5) lets a double-quoted value escape. */
    private val ESCAPED = Regex("""\\(["\\$`])""")
}
