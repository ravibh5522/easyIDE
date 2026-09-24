package dev.easyide.ext.cli

import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * cli.md sec 4: `~/.easyide/config.json` then `./.easyide-ext.json`, later wins, flags win over
 * both. Paths only, never secrets; unknown keys warn.
 */
data class CliConfig(
    val publisher: String? = null,
    val key: String? = null,
    val keyId: String? = null,
    val indexRepo: String? = null,
    val engine: String? = null,
    val device: String? = null,
    val packageOut: String? = null,
) {
    private fun overlay(o: CliConfig) = CliConfig(
        o.publisher ?: publisher, o.key ?: key, o.keyId ?: keyId, o.indexRepo ?: indexRepo,
        o.engine ?: engine, o.device ?: device, o.packageOut ?: packageOut,
    )

    companion object {
        const val PROJECT_FILE = ".easyide-ext.json"
        private val KEYS = setOf("publisher", "key", "keyId", "indexRepo", "engine", "device", "packageOut")

        /** [dir] is the extension directory whose `.easyide-ext.json` applies. */
        fun load(ctx: CliContext, dir: File): CliConfig =
            read(ctx, File(ctx.home, ".easyide/config.json")).overlay(read(ctx, File(dir, PROJECT_FILE)))

        private fun read(ctx: CliContext, file: File): CliConfig {
            if (!file.isFile) return CliConfig()
            val text = String(file.readBytesOrFail(), Charsets.UTF_8)
            val obj = when (val r = JsonText.parseLenient(text)) {
                is JsonParse.Ok -> r.value as? JsonObject ?: usage("${file.path}: expected a JSON object")
                is JsonParse.Error -> usage("${file.path} line ${r.line}: ${r.message}")
            }
            obj.keys.filter { it !in KEYS }.forEach { ctx.out.diagnostic(cliWarning(CliCode.CONFIG_KEY, "unknown config key '$it' (ignored)", file.path)) }
            fun s(k: String) = obj[k]?.stringOrNull
            return CliConfig(s("publisher"), s("key"), s("keyId"), s("indexRepo"), s("engine"), s("device"), s("packageOut"))
        }
    }
}
