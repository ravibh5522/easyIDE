package dev.easyide.sandbox.download

import java.io.File
import java.security.MessageDigest

/**
 * A SHA-256 digest, always held as 64 lowercase hex characters.
 *
 * A dedicated type rather than a `String` so a malformed digest is rejected
 * once, where it enters (a catalog entry, a registry index), and every
 * comparison after that is a plain equality on canonical values - no
 * case-folding or length checks scattered across callers.
 */
@JvmInline
value class Sha256 private constructor(val hex: String) {

    override fun toString(): String = hex

    companion object {
        private const val ALGORITHM = "SHA-256"
        private const val HEX_LENGTH = 64
        private const val HASH_BUFFER = 64 * 1024
        private val HEX = Regex("^[0-9a-fA-F]{$HEX_LENGTH}$")

        /**
         * @throws IllegalArgumentException if [hex] is not exactly 64 hex
         *   characters. Upper case is accepted and normalised, because
         *   published checksum files are not consistent about case.
         */
        fun parse(hex: String): Sha256 {
            require(HEX.matches(hex)) { "Not a SHA-256 hex digest: '$hex'" }
            return Sha256(hex.lowercase())
        }

        /** Wraps the raw 32-byte output of a finished [MessageDigest]. */
        fun fromDigest(bytes: ByteArray): Sha256 =
            parse(bytes.joinToString(separator = "") { "%02x".format(it) })

        fun newDigest(): MessageDigest = MessageDigest.getInstance(ALGORITHM)

        /** Hashes a file in fixed-size chunks; never loads it whole. */
        fun of(file: File): Sha256 = fromDigest(digestOf(file).digest())

        /**
         * A digest already fed with [file]'s bytes, left open so a caller can
         * keep appending - how a resumed download continues one hash over the
         * old prefix and the new bytes without reading the result twice.
         */
        fun digestOf(file: File): MessageDigest {
            val digest = newDigest()
            file.inputStream().use { input ->
                val buffer = ByteArray(HASH_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest
        }
    }
}
