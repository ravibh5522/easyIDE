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

    fun tokenFor(host: String): String? = readAll()[host.lowercase()]?.token

    /** The username to present with [host]'s token; [GitCredentialsFormat.DEFAULT_USERNAME] when none was given. */
    fun usernameFor(host: String): String =
        readAll()[host.lowercase()]?.username ?: GitCredentialsFormat.DEFAULT_USERNAME

    /**
     * Saves [token] for [host]. Returns false, storing nothing, when a value
     * cannot be represented (blank token, control characters).
     */
    fun store(host: String, token: String, username: String = GitCredentialsFormat.DEFAULT_USERNAME): Boolean {
        val user = username.trim().ifEmpty { GitCredentialsFormat.DEFAULT_USERNAME }
        val key = host.trim().lowercase()
        if (!GitCredentialsFormat.isStorable(key, token, user)) return false
        writeAll(readAll() + (key to GitCredential(key, user, token)))
        return true
    }

    fun forget(host: String) {
        writeAll(readAll() - host.lowercase())
    }

    fun hosts(): Set<String> = readAll().keys

    /**
     * Host and username of every stored token - never the tokens, so a screen
     * listing them cannot leak one by accident.
     */
    fun entries(): List<GitCredentialEntry> =
        readAll().values.map { GitCredentialEntry(it.host, it.username) }.sortedBy { it.host }

    /**
     * Tokens are keyed by host so a URL resolves without the caller knowing
     * which forge it points at.
     */
    fun tokenForUrl(url: String): String? = hostOf(url)?.let { tokenFor(it) }

    fun usernameForUrl(url: String): String =
        hostOf(url)?.let { usernameFor(it) } ?: GitCredentialsFormat.DEFAULT_USERNAME

    private fun readAll(): Map<String, GitCredential> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val raw = file.readBytes()
            if (raw.size <= IV_LENGTH) return emptyMap()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH))
            }
            GitCredentialsFormat.decode(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH).decodeToString())
        }.getOrDefault(emptyMap())
    }

    private fun writeAll(entries: Map<String, GitCredential>) {
        val plain = GitCredentialsFormat.encode(entries.values)
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
