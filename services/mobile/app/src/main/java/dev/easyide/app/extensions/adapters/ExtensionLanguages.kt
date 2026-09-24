package dev.easyide.app.extensions.adapters

import dev.easyide.extensions.ExtensionPolicy
import dev.easyide.extensions.contrib.GrammarContribution
import dev.easyide.extensions.contrib.LanguageConfigurationContribution
import dev.easyide.extensions.contrib.LanguageContribution
import dev.easyide.extensions.contrib.Owned
import java.util.regex.PatternSyntaxException

/**
 * Languages, grammars and language configurations contributed by enabled extensions
 * (EXT-20), as the lookups the highlighter and editor need. Immutable: a registry change
 * builds a new instance and the highlighter swaps it in.
 *
 * Detection precedence for a file (extension-runtime.md sec 7.3, minus
 * `files.associations`, which has no settings key yet): exact filename, then the longest
 * matching extension, then a filename pattern, then `firstLine`. Bundled grammars are the
 * fallback, applied by the caller when this returns null.
 */
class ExtensionLanguages(
    languages: List<Owned<LanguageContribution>>,
    grammars: List<Owned<GrammarContribution>>,
    configurations: List<Owned<LanguageConfigurationContribution>>,
) {
    private val langs = languages.map { it.value }
    private val patterns: List<Pair<Regex, String>> = langs.flatMap { l -> l.filenamePatterns.mapNotNull { p -> glob(p)?.let { it to l.id } } }
    private val firstLines: List<Pair<Regex, String>> = langs.mapNotNull { l -> l.firstLine?.let { f -> regex(f)?.let { it to l.id } } }

    /** Grammar per language id and per scope, first contributor wins (the registry already resolved scope conflicts). */
    private val grammarByLanguage: Map<String, GrammarContribution> = grammars.map { it.value }.filter { it.language != null }
        .groupBy { it.language!! }.mapValues { it.value.first() }
    private val grammarByScope: Map<String, GrammarContribution> = grammars.associate { it.value.scopeName to it.value }
    private val configByLanguage: Map<String, String> = configurations.associate { it.value.language to it.value.file.hostPath }

    /** Every scope an extension grammar provides; the highlighter drops these from its caches on change. */
    val scopes: Set<String> get() = grammarByScope.keys

    val isEmpty: Boolean get() = langs.isEmpty() && grammarByScope.isEmpty()

    fun languageFor(fileName: String, firstLine: String?): String? {
        val lower = fileName.lowercase()
        langs.firstOrNull { l -> l.filenames.any { it == fileName || it.lowercase() == lower } }?.let { return it.id }
        langs.flatMap { l -> l.extensions.filter { lower.endsWith(it.lowercase()) }.map { it.length to l.id } }
            .maxByOrNull { it.first }?.let { return it.second }
        patterns.firstOrNull { it.first.matches(fileName) }?.let { return it.second }
        val line = firstLine?.take(ExtensionPolicy.FIRST_LINE_MAX_CHARS) ?: return null
        return firstLines.firstOrNull { it.first.containsMatchIn(line) }?.second
    }

    /** The extension grammar scope for a file, or null to use the bundled index. */
    fun scopeFor(fileName: String, firstLine: String?): String? =
        languageFor(fileName, firstLine)?.let { grammarByLanguage[it]?.scopeName }

    /** Host path of an extension grammar file for [scope]; null when it is not an extension scope. */
    fun grammarFile(scope: String): String? = grammarByScope[scope]?.file?.hostPath

    /** Host path of the `language-configuration.json` an extension contributes for [languageId]. */
    fun configurationFile(languageId: String): String? = configByLanguage[languageId]

    companion object {
        val EMPTY = ExtensionLanguages(emptyList(), emptyList(), emptyList())

        /** VS Code filename glob on the basename: `*` and `?` within a name, `**` across. */
        internal fun glob(pattern: String): Regex? {
            val base = pattern.substringAfterLast('/').ifEmpty { return null }
            val sb = StringBuilder()
            base.forEach { c ->
                when (c) {
                    '*' -> sb.append("[^/]*")
                    '?' -> sb.append("[^/]")
                    else -> sb.append(Regex.escape(c.toString()))
                }
            }
            return regex(sb.toString())
        }

        /** Manifest regexes are untrusted input; one that does not compile is ignored (the validator warns). */
        private fun regex(pattern: String): Regex? =
            if (pattern.length > ExtensionPolicy.MAX_PATTERN_LENGTH) null
            else try { Regex(pattern) } catch (e: PatternSyntaxException) { null }
    }
}
