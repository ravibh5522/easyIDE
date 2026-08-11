package dev.easyide.sandbox.git

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Host -> access token, encrypted with an Android Keystore key.
 *
 * The key never leaves the Keystore (and on most devices never leaves secure
 * hardware), so the file on disk is useless on its own. Written with the
 * platform primitives rather than `androidx.security:security-crypto`, which is
 * deprecated and would add a dependency for ~60 lines of AES-GCM.
 *
 * Tokens are deliberately **not** written into the sandbox rootfs, into a remote
 * URL, or into `.git/config` - see [GitRemote] for how they reach git instead.
 */
class GitCredentials(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    fun tokenFor(host: String): String? = readAll()[host.lowercase()]

    fun store(host: String, token: String) {
        writeAll(readAll() + (host.lowercase() to token))
    }

    fun forget(host: String) {
        writeAll(readAll() - host.lowercase())
    }

    fun hosts(): Set<String> = readAll().keys

    /**
     * Tokens are keyed by host so a URL resolves without the caller knowing
     * which forge it points at.
     */
    fun tokenForUrl(url: String): String? = hostOf(url)?.let { tokenFor(it) }

    private fun readAll(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val raw = file.readBytes()
            if (raw.size <= IV_LENGTH) return emptyMap()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH))
            }
            cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH)
                .decodeToString()
                .lineSequence()
                .filter { it.contains('\t') }
                .associate { it.substringBefore('\t') to it.substringAfter('\t') }
        }.getOrDefault(emptyMap())
    }

    private fun writeAll(entries: Map<String, String>) {
        val plain = entries.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        file.writeBytes(cipher.iv + cipher.doFinal(plain.encodeToByteArray()))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        private const val FILE_NAME = "git-credentials.bin"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "easyide.git.credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128

        /** `https://github.com/user/repo.git` -> `github.com`. */
        fun hostOf(url: String): String? = runCatching {
            java.net.URI(url.trim()).host?.lowercase()
        }.getOrNull()
    }
}
