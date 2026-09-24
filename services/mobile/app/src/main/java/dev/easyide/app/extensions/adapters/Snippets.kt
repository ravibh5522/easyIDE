package dev.easyide.app.extensions.adapters

import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.SnippetContribution
import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One snippet from a VS Code snippet file. [languages] null means "every language" (a
 * global file without `scope`); [body] is the TextMate body, expanded at insertion.
 */
data class Snippet(
    val name: String,
    val prefixes: List<String>,
    val body: String,
    val description: String?,
    val languages: Set<String>?,
    val owner: Owner,
)

/** Parses the VS Code snippet file shape `{name: {prefix, body, description?, scope?}}` (JSONC). */
object SnippetFile {

    /** Snippets in [text]; entries without a body are skipped. Null when the file is not a JSON object. */
    fun parse(text: String, fileLanguage: String?, owner: Owner): List<Snippet>? {
        val root = (JsonText.parseLenient(text) as? JsonParse.Ok)?.value as? JsonObject ?: return null
        return root.mapNotNull { (name, value) ->
            val o = value as? JsonObject ?: return@mapNotNull null
            val body = lines(o[BODY])?.joinToString("\n") ?: return@mapNotNull null
            val scope = o[SCOPE]?.stringOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            Snippet(
                name = name,
                prefixes = lines(o[PREFIX]).orEmpty(),
                body = body,
                description = o[DESCRIPTION]?.let { lines(it)?.joinToString("\n") },
                languages = fileLanguage?.let(::setOf) ?: scope,
                owner = owner,
            )
        }
    }

    /** A string or an array of strings (VS Code accepts both for prefix, body and description). */
    private fun lines(e: JsonElement?): List<String>? = when (e) {
        is JsonArray -> e.map { it.stringOrNull ?: return null }
        null -> null
        else -> e.stringOrNull?.let(::listOf)
    }

    private const val PREFIX = "prefix"
    private const val BODY = "body"
    private const val DESCRIPTION = "description"
    private const val SCOPE = "scope"
}

/**
 * Snippets of the enabled extensions, grouped for lookup by language. Files are read by
 * [load] (I/O, off the main thread); [forLanguage] and [named] are pure lookups.
 */
class SnippetCatalog(private val byFile: List<Snippet>) {

    fun forLanguage(languageId: String?): List<Snippet> =
        byFile.filter { s -> s.languages == null || (languageId != null && languageId in s.languages) }

    /** The snippet [name] for [languageId]; a language-specific one beats a global one. */
    fun named(name: String, languageId: String?): Snippet? =
        forLanguage(languageId).sortedBy { if (it.languages == null) 1 else 0 }.firstOrNull { it.name == name }

    companion object {
        val EMPTY = SnippetCatalog(emptyList())

        /**
         * Reads every contributed file with [read] (null for an unreadable or oversized
         * file, reported through [onUnreadable]).
         */
        fun load(
            contributions: List<Owned<SnippetContribution>>,
            read: (String) -> String?,
            onUnreadable: (Owned<SnippetContribution>, String) -> Unit,
        ): SnippetCatalog = SnippetCatalog(contributions.flatMap { c ->
            val text = read(c.value.file.hostPath)
            val parsed = text?.let { SnippetFile.parse(it, c.value.language, c.owner) }
            if (parsed == null) onUnreadable(c, "snippet file ${c.value.file.path} is unreadable or not a JSON object")
            parsed.orEmpty()
        })
    }
}
