package dev.easyide.app.session

import java.security.MessageDigest

/** SHA-256 of text as UTF-8, lower-case hex; how a backup remembers what it was edited from. */
object Hashes {
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
