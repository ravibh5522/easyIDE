package dev.easyide.app.extensions.registry

import com.google.crypto.tink.subtle.Ed25519Verify
import dev.easyide.extensions.registry.Ed25519
import java.security.GeneralSecurityException

/**
 * [Ed25519] for the app on every supported API level: Tink's pure-Java verifier (decision
 * 0016 amendment), since the platform provider cannot be assumed below API 33. Verify only;
 * the app never signs.
 */
object TinkEd25519 : Ed25519 {
    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != Ed25519Verify.PUBLIC_KEY_LEN || signature.size != Ed25519Verify.SIGNATURE_LEN) return false
        return try {
            Ed25519Verify(publicKey).verify(signature, message)
            true
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
