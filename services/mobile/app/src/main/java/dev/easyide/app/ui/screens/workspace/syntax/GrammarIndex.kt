package dev.easyide.app.ui.screens.workspace.syntax

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * Maps a file to the TextMate scope that can colour it.
 *
 * `assets/grammars/index.json` is generated, not hand-written: grammar list and
 * licences come from the `tm-grammars` package, extension and filename mappings
 * from GitHub Linguist. Keeping it as data means adding a language is a
 * regenerate, not a code change.
 */
internal class GrammarIndex private constructor(
    private val scopeToFile: Map<String, String>,
    private val scopeToConfig: Map<String, String>,
    private val scopeToName: Map<String, String>,
    private val byExtension: Map<String, String>,
    private val byFilename: Map<String, String>,
) {

    fun assetFor(scopeName: String): String? = scopeToFile[scopeName]

    /** The grammar's `language-configuration` asset; absent where VS Code ships none. */
    fun configFor(scopeName: String): String? = scopeToConfig[scopeName]

    /**
     * The grammar's name (`python`, `typescript`), which matches VS Code's
     * language id for the common languages: the id `[lang]` settings blocks use.
     */
    fun languageIdFor(fileName: String): String? = scopeFor(fileName)?.let(scopeToName::get)

    /** Filename wins over extension - `Makefile` and `Dockerfile` carry no suffix. */
    fun scopeFor(fileName: String): String? {
        val lower = fileName.lowercase()
        byFilename[lower]?.let { return it }
        val dot = lower.lastIndexOf('.')
        if (dot < 0 || dot == lower.length - 1) return null
        return byExtension[lower.substring(dot + 1)]
    }

    companion object {
        const val DIRECTORY = "grammars"

        fun load(assets: AssetManager): GrammarIndex {
            val json = assets.open("$DIRECTORY/index.json").use { it.readBytes().decodeToString() }
            val root = JSONObject(json)

            val files = HashMap<String, String>()
            val configs = HashMap<String, String>()
            val names = HashMap<String, String>()
            val grammars = root.getJSONArray("grammars")
            for (i in 0 until grammars.length()) {
                val entry = grammars.getJSONObject(i)
                files[entry.getString("scope")] = entry.getString("file")
                names[entry.getString("scope")] = entry.getString("name")
                entry.optString("config").takeIf { it.isNotEmpty() }?.let { configs[entry.getString("scope")] = it }
            }

            return GrammarIndex(files, configs, names, root.readMap("byExtension"), root.readMap("byFilename"))
        }

        private fun JSONObject.readMap(key: String): Map<String, String> {
            val obj = getJSONObject(key)
            val out = HashMap<String, String>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.getString(k)
            }
            return out
        }
    }
}
