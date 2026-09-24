package dev.easyide.app.extensions.registry

import dev.easyide.app.extensions.install.ExtensionStateStore

/**
 * TOFU publisher pins (registry-and-install.md sec 5), `pins: {registryId: {publisher: keyId}}`
 * in `state.json`. Browsing never pins: [pin] is called only after an install commits, with
 * the keyId [dev.easyide.extensions.registry.RegistryVerifier.trustEntry] accepted, so a
 * verified rotation re-pins to the new key. Uninstall keeps pins, so a reinstall still
 * detects a changed key; only [forget] (developer mode, with a warning) drops one.
 */
class TrustStore(private val state: ExtensionStateStore) {

    fun pinned(registryId: String, publisher: String): String? = state.read().pins[registryId]?.get(publisher)

    fun pins(): Map<String, Map<String, String>> = state.read().pins

    /** @throws java.io.IOException when `state.json` cannot be written. */
    fun pin(registryId: String, publisher: String, keyId: String) {
        state.update { s -> s.copy(pins = s.pins + (registryId to (s.pins[registryId].orEmpty() + (publisher to keyId)))) }
    }

    /** The user's explicit escape from a publisher left unusable by a revoked pinned key. */
    fun forget(registryId: String, publisher: String) {
        state.update { s ->
            val rest = s.pins[registryId].orEmpty() - publisher
            s.copy(pins = if (rest.isEmpty()) s.pins - registryId else s.pins + (registryId to rest))
        }
    }
}
