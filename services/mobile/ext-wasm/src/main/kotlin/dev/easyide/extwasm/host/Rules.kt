package dev.easyide.extwasm.host

import dev.easyide.extwasm.WasmPolicy
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.URISyntaxException

/**
 * The capability rule of one host function (wasm-host.md sec 9.1). [denial] runs before the
 * handler: null = allowed, otherwise the reason sent back as E_CAPABILITY. A rule that needs
 * an argument to decide (a path, a key) fails E_ARGS through [badArgs] when it is missing.
 */
internal sealed interface Rule {
    fun denial(scope: HostCallScope, args: JsonObject): String?

    /** Always available (sdk-reference "Always available with no prompt"). */
    data object None : Rule {
        override fun denial(scope: HostCallScope, args: JsonObject): String? = null
    }

    data class Needs(val cap: String) : Rule {
        override fun denial(scope: HostCallScope, args: JsonObject): String? =
            if (scope.ext.capabilities.has(cap)) null else "$cap not granted"
    }

    /** Guest paths in [keys]: project read/write, plus `fs.outsideProject` outside `/workspace`. */
    data class Paths(val write: Boolean, val keys: List<String>) : Rule {
        override fun denial(scope: HostCallScope, args: JsonObject): String? {
            for (k in keys) {
                val path = GuestPaths.normalize(args.str(k)) ?: badArgs("\"$k\" must be an absolute guest path")
                GuestPaths.missingFor(scope.ext.capabilities, path, write)?.let { return "$it not granted for $path" }
            }
            return null
        }
    }

    /** `fs.watch{glob}`: relative globs are project-relative; absolute ones follow the path rule. */
    data object WatchGlob : Rule {
        override fun denial(scope: HostCallScope, args: JsonObject): String? {
            val glob = args.str("glob")
            val caps = scope.ext.capabilities
            if (!caps.has(Cap.FS_READ)) return "${Cap.FS_READ} not granted"
            if (!glob.startsWith("/")) return null
            val root = GuestPaths.normalize(glob.substringBefore('*')) ?: badArgs("\"glob\" is not a valid path")
            return if (GuestPaths.inProject(root) || caps.has(Cap.FS_OUTSIDE)) null else "${Cap.FS_OUTSIDE} not granted"
        }
    }

    /**
     * `config.get/set{key}`: own keys (`contributes.configuration`) always; others need
     * `ui.settings`; protected keys are never writable, whatever is granted.
     */
    data class SettingKey(val write: Boolean) : Rule {
        override fun denial(scope: HostCallScope, args: JsonObject): String? {
            val key = args.str("key")
            if (write && isProtected(key)) return "$key is protected and cannot be written by extensions"
            if (key in scope.ext.settingKeys) return null
            return if (scope.ext.capabilities.has(Cap.UI_SETTINGS)) null else "${Cap.UI_SETTINGS} not granted for $key"
        }

        private fun isProtected(key: String) =
            key in WasmPolicy.PROTECTED_KEYS || WasmPolicy.PROTECTED_KEY_PREFIXES.any { key.startsWith(it) }
    }

    /** `commands.execute`: the caller needs whatever the target command itself requires. */
    data object CommandTarget : Rule {
        override fun denial(scope: HostCallScope, args: JsonObject): String? {
            val need = scope.ports.commands.requiredCapability(args.str("command")) ?: return null
            return if (scope.ext.capabilities.has(need)) null else "$need not granted (required by ${args.str("command")})"
        }
    }

    /** `commands.register`: only ids declared in `contributes.commands` (threat-model B2 "S"). */
    data object DeclaredCommand : Rule {
        override fun denial(scope: HostCallScope, args: JsonObject): String? {
            val command = args.str("command")
            return if (command in scope.ext.commands) null else "command $command is not declared in contributes.commands"
        }
    }

    /** `providers.register`: kind in the v1 set and declared in `easyide.wasm.providers`. */
    data object DeclaredProvider : Rule {
        override fun denial(scope: HostCallScope, args: JsonObject): String? {
            val kind = args.str("kind")
            if (kind !in WasmPolicy.PROVIDER_KINDS) badArgs("provider kind $kind is not one of ${WasmPolicy.PROVIDER_KINDS}")
            return if (kind in scope.ext.providerKinds) null else "provider kind $kind is not declared in easyide.wasm.providers"
        }
    }

    /** `ui.setViewData{viewId}`: own views freely; own stages need `ui.stage`; nothing else. */
    data object ViewTarget : Rule {
        override fun denial(scope: HostCallScope, args: JsonObject): String? {
            val id = args.str("viewId")
            return when (id) {
                in scope.ext.views -> null
                in scope.ext.stages -> if (scope.ext.capabilities.has(Cap.UI_STAGE)) null else "${Cap.UI_STAGE} not granted"
                else -> "view $id is not contributed by this extension"
            }
        }
    }

    /** `net.fetch{url}`: https only, host must match a declared `network(...)` host (R-SEC-11). */
    data object NetworkUrl : Rule {
        override fun denial(scope: HostCallScope, args: JsonObject): String? {
            val url = parseUrl(args.str("url"))
            if (!url.scheme.equals("https", ignoreCase = true)) return "only https URLs are allowed"
            val host = url.host ?: badArgs("\"url\" has no host")
            return if (scope.ext.capabilities.network.matches(host)) null else "network($host) not granted"
        }
    }
}

internal fun parseUrl(s: String): URI = try {
    URI(s)
} catch (e: URISyntaxException) {
    badArgs("\"url\" is not a valid URL")
}
