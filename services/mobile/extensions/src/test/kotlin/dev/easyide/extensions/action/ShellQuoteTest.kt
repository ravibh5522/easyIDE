package dev.easyide.extensions.action

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Property test (extension-runtime.md sec 12): any string, quoted, is exactly one word to a
 * real POSIX shell. Runs `sh -c "printf %s <quoted>"` on the host and compares bytes.
 */
class ShellQuoteTest {
    private val sh = File("/bin/sh")

    private fun roundTrip(s: String): String {
        val p = ProcessBuilder(sh.path, "-c", "printf %s " + ShellQuote.quote(s)).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor(10, TimeUnit.SECONDS)) { "sh did not exit" }
        return out
    }

    @Test fun `hostile strings survive as one word`() {
        assumeTrue("needs a POSIX sh", sh.canExecute())
        listOf(
            "plain", "", "it's", "''", "a b", "\$(touch /tmp/pwned)", "`id`", "\${HOME}", "*.py", "a;b", "a&&b|c", "\"q\"",
            "line1\nline2", "tab\there", "back\\slash", "-n", "\u00fcnic\u00f8de \u2603", "'\\''", "!!", "~root", "#comment",
        ).forEach { assertEquals(it, it, roundTrip(it)) }
    }

    @Test fun `random strings survive as one word`() {
        assumeTrue("needs a POSIX sh", sh.canExecute())
        val alphabet = "ab '\"\\\$`;&|<>()*?[]{}~#!\n\t=%^-_".toCharArray() + charArrayOf('\u00e9', '\u4e2d')
        val rnd = Random(20260924)
        repeat(60) {
            val s = String(CharArray(rnd.nextInt(1, 24)) { alphabet[rnd.nextInt(alphabet.size)] })
            assertEquals(s, roundTrip(s))
        }
    }

    @Test fun `quoting is a single-quote wrap with embedded quotes escaped`() {
        assertEquals("'a'\\''b'", ShellQuote.quote("a'b"))
        assertEquals("''", ShellQuote.quote(""))
    }
}
