package dev.easyide.app.ui.screens.workspace.syntax

import android.content.res.AssetManager
import android.util.Log
import dev.easyide.app.ui.screens.workspace.edit.CharPair
import dev.easyide.app.ui.screens.workspace.edit.EnterRule
import dev.easyide.app.ui.screens.workspace.edit.IndentAction
import dev.easyide.app.ui.screens.workspace.edit.LanguageConfig
import dev.easyide.app.ui.screens.workspace.edit.compileJsRegex
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-language editing rules (brackets, comments, indentation) read from the
 * `language-configuration` assets that `tools/build-grammars.py` bundles next
 * to the grammars. Languages without one get [LanguageConfig.GENERIC].
 *
 * Parsed on first use per language and kept; each file is a few KB.
 */
object LanguageConfigs {

    private const val TAG = "LanguageConfigs"

    private var assets: AssetManager? = null
    private var index: GrammarIndex? = null
    private val cache = HashMap<String, LanguageConfig>()

    /** Shares the highlighter's index so the 44 KB file is parsed once. */
    @Synchronized
    internal fun init(assets: AssetManager, index: GrammarIndex) {
        this.assets = assets
        this.index = index
    }

    /**
     * Reads an asset on first use of a language; call off the main thread.
     * The highlighter loads its index lazily and hands it over via [init], so
     * ask it to do that first - outside this object's lock, since init takes
     * the highlighter's lock and then this one.
     */
    fun forFile(fileName: String): LanguageConfig {
        TextMateHighlighter.ensureIndexLoaded()
        return lookup(fileName)
    }

    /** Language id of [fileName] for per-language settings, or null when no grammar claims it; off the main thread. */
    fun languageIdFor(fileName: String): String? {
        TextMateHighlighter.ensureIndexLoaded()
        return synchronized(this) { index?.languageIdFor(fileName) }
    }

    @Synchronized
    private fun lookup(fileName: String): LanguageConfig {
        val scope = index?.scopeFor(fileName) ?: return LanguageConfig.GENERIC
        return cache.getOrPut(scope) { load(scope) }
    }

    private fun load(scope: String): LanguageConfig {
        val asset = index?.configFor(scope) ?: return LanguageConfig.GENERIC
        val manager = assets ?: return LanguageConfig.GENERIC
        return runCatching {
            val json = manager.open("${GrammarIndex.DIRECTORY}/$asset").use { it.readBytes().decodeToString() }
            parse(JSONObject(json))
        }.onFailure { Log.w(TAG, "language config $asset unreadable", it) }
            .getOrDefault(LanguageConfig.GENERIC)
    }

    private fun parse(root: JSONObject): LanguageConfig {
        val comments = root.optJSONObject("comments")
        val block = comments?.optJSONArray("blockComment")
        val indentation = root.optJSONObject("indentationRules")
        return LanguageConfig(
            // Newer configs (Makefile) spell it `{comment, noIndent}` instead of a bare string.
            lineComment = (comments?.optJSONObject("lineComment")?.optString("comment") ?: comments?.optString("lineComment"))
                ?.takeIf { it.isNotEmpty() },
            blockComment = block?.takeIf { it.length() == 2 }?.let { it.getString(0) to it.getString(1) },
            brackets = pairs(root.optJSONArray("brackets")),
            autoClosingPairs = pairs(root.optJSONArray("autoClosingPairs")),
            surroundingPairs = pairs(root.optJSONArray("surroundingPairs")),
            autoCloseBefore = root.optString("autoCloseBefore", LanguageConfig.DEFAULT_AUTO_CLOSE_BEFORE),
            increaseIndent = indentation?.optJSONObject("increaseIndentPattern")?.let(::regex),
            decreaseIndent = indentation?.optJSONObject("decreaseIndentPattern")?.let(::regex),
            onEnterRules = enterRules(root.optJSONArray("onEnterRules")),
        )
    }

    private fun pairs(array: JSONArray?): List<CharPair> {
        array ?: return emptyList()
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val notIn = o.optJSONArray("notIn")
            CharPair(
                open = o.getString("open"),
                close = o.getString("close"),
                notIn = notIn?.let { n -> (0 until n.length()).mapTo(HashSet()) { n.getString(it) } }.orEmpty(),
            )
        }.filter { it.open.isNotEmpty() }
    }

    private fun enterRules(array: JSONArray?): List<EnterRule> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val action = o.getJSONObject("action")
            // A rule whose regex this JVM cannot compile is dropped alone, not the whole config.
            EnterRule(
                beforeText = regex(o.getJSONObject("beforeText")) ?: return@mapNotNull null,
                afterText = o.optJSONObject("afterText")?.let { regex(it) ?: return@mapNotNull null },
                previousLineText = o.optJSONObject("previousLineText")?.let { regex(it) ?: return@mapNotNull null },
                indent = when (action.optString("indent")) {
                    "indent" -> IndentAction.INDENT
                    "indentOutdent" -> IndentAction.INDENT_OUTDENT
                    "outdent" -> IndentAction.OUTDENT
                    else -> IndentAction.NONE
                },
                appendText = action.optString("appendText"),
                removeText = action.optInt("removeText"),
            )
        }
    }

    private fun regex(o: JSONObject): Regex? = compileJsRegex(o.getString("pattern"), o.optString("flags"))
}
