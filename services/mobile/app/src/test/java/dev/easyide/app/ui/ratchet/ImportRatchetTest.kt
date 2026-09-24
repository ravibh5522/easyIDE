package dev.easyide.app.ui.ratchet

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-CMP-01 / U-AI-01: raw Material 3 widgets are banned outside `ui/kit`. Files that still
 * import them are listed in `ui-ratchet-allowlist.txt` (`<count> <path under src/main/java>`);
 * the allowance may only shrink. A file over its allowance, a new offender, or a file under its
 * allowance without the allowlist being lowered all fail, so every migration must also edit
 * the allowlist and the count can never drift back up.
 *
 * `ui-ratchet-banned.txt` holds one regex fragment per line (`\*` bans `material3.*` so a
 * wildcard cannot hide a widget). Regenerate the allowlist from the current tree, from
 * `services/mobile`, with this one line (only ever to lower it):
 *
 * ```
 * P=$(paste -sd'|' app/src/test/resources/ui-ratchet-banned.txt); (cd app/src/main/java && grep -rcE "^import androidx\.compose\.material3\.($P)( |$)" --include=*.kt . | grep -v ':0$' | grep -v 'easyide/app/ui/kit/' | sed -E 's#^(\./)?([^:]+):([0-9]+)$#\3 \2#' | LC_ALL=C sort -k2) > app/src/test/resources/ui-ratchet-allowlist.txt
 * ```
 */
class ImportRatchetTest {

    private val banned: Regex = run {
        val names = resource("ui-ratchet-banned.txt").lines().filter { it.isNotBlank() }
        Regex("""^import androidx\.compose\.material3\.(${names.joinToString("|")})(\s+as\s+\w+)?\s*$""")
    }

    private val allowed: Map<String, Int> = resource("ui-ratchet-allowlist.txt")
        .lines().filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { line ->
            val (count, path) = line.trim().split(Regex("\\s+"), limit = 2)
            path to count.toInt()
        }

    private val actual: Map<String, Int> by lazy { scan(sourceRoot()) }

    @Test fun `no file imports more banned widgets than its allowance`() {
        val over = actual.filter { (path, n) -> n > (allowed[path] ?: 0) }
            .map { (path, n) -> "$path: $n banned imports, allowance ${allowed[path] ?: 0}" }
        assertTrue(
            "Raw Material widgets outside ui/kit (use the kit, do not raise the allowlist):\n" +
                over.joinToString("\n"),
            over.isEmpty(),
        )
    }

    @Test fun `the allowlist is lowered whenever a file improves`() {
        val stale = allowed.filter { (path, n) -> (actual[path] ?: 0) < n }
            .map { (path, n) -> "$path: allowance $n, now ${actual[path] ?: 0}" }
        assertTrue(
            "Lower or delete these lines in ui-ratchet-allowlist.txt (the ratchet only shrinks):\n" +
                stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    private fun scan(root: File): Map<String, Int> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .filterNot { (path, _) -> path.startsWith(KIT_DIR) }
            .associate { (path, file) -> path to file.useLines { l -> l.count { banned.matches(it.trim()) } } }
            .filterValues { it > 0 }

    // Gradle runs unit tests with the module directory (app) as the working dir; walking up
    // also covers an IDE launching from the repo root or services/mobile.
    private fun sourceRoot(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        val found = generateSequence(start) { it.parentFile }
            .flatMap { sequenceOf(File(it, MAIN_SOURCES), File(it, "app/$MAIN_SOURCES")) }
            .firstOrNull { it.isDirectory }
        return checkNotNull(found) { "no $MAIN_SOURCES at or above $start" }
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing test resource $name" }
            .bufferedReader().use { it.readText() }

    private companion object {
        const val MAIN_SOURCES = "src/main/java"
        const val KIT_DIR = "dev/easyide/app/ui/kit/"
    }
}
