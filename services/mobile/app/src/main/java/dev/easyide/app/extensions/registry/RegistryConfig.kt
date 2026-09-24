package dev.easyide.app.extensions.registry

import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * One `extensions.registries` entry (sdk-reference "Settings keys"): a static https base URL
 * and the registry's ed25519 root public key, pinned here rather than fetched.
 */
class RegistryConfig(val id: String, url: String, val rootKey: ByteArray) {
    /** Always ends in `/`, so file names append directly. */
    val baseUrl: String = if (url.endsWith("/")) url else "$url/"

    fun fileUrl(name: String): String = baseUrl + name

    override fun equals(other: Any?) = other is RegistryConfig && id == other.id && baseUrl == other.baseUrl && rootKey.contentEquals(other.rootKey)
    override fun hashCode() = id.hashCode() * 31 + baseUrl.hashCode()
    override fun toString() = "RegistryConfig($id, $baseUrl)"

    companion object {
        /** A slug: it names a directory under `extensions/registry/` and appears in `state.json`. */
        val ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    }
}

/** The usable entries of `extensions.registries`, and one line per entry that is not. */
data class RegistryConfigs(val registries: List<RegistryConfig>, val problems: List<String>) {
    companion object {
        val NONE = RegistryConfigs(emptyList(), emptyList())

        fun parse(value: JsonElement): RegistryConfigs {
            val array = value as? JsonArray ?: return RegistryConfigs(emptyList(), listOf("extensions.registries must be an array"))
            val ok = ArrayList<RegistryConfig>()
            val problems = ArrayList<String>()
            array.forEachIndexed { i, e ->
                when (val r = entry(e as? JsonObject)) {
                    is Either.Ok -> if (ok.any { it.id == r.value.id }) problems += "registries[$i]: duplicate id '${r.value.id}'" else ok += r.value
                    is Either.Bad -> problems += "registries[$i]: ${r.reason}"
                }
            }
            return RegistryConfigs(ok, problems)
        }

        private fun entry(o: JsonObject?): Either {
            if (o == null) return Either.Bad("must be an object {id, url, rootKey}")
            val id = o["id"]?.stringOrNull ?: return Either.Bad("id is required")
            if (!RegistryConfig.ID.matches(id)) return Either.Bad("id '$id' must be lowercase letters, digits and '-'")
            val url = o["url"]?.stringOrNull ?: return Either.Bad("$id: url is required")
            if (!url.startsWith(RegistryPolicy.SCHEME) || url.length <= RegistryPolicy.SCHEME.length) return Either.Bad("$id: url must be https")
            val key = o["rootKey"]?.stringOrNull?.let { try { Base64.getDecoder().decode(it) } catch (e: IllegalArgumentException) { null } }
                ?.takeIf { it.size == ROOT_KEY_BYTES } ?: return Either.Bad("$id: rootKey must be base64 of a 32-byte ed25519 public key")
            return Either.Ok(RegistryConfig(id, url, key))
        }

        private const val ROOT_KEY_BYTES = 32
    }

    private sealed interface Either {
        data class Ok(val value: RegistryConfig) : Either
        data class Bad(val reason: String) : Either
    }
}
