package dev.easyide.app.ui.screens.settings

import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingGroup
import dev.easyide.app.data.settings.SettingsSnapshot

/**
 * A parsed search box: free text plus VS Code's filters `@modified`,
 * `@ext:<id>` and `@lang:<id>` (LLD sec 17). `@lang` also chooses the
 * `[lang]` block rows write into.
 */
data class SettingsFilter(val text: String, val modifiedOnly: Boolean, val extension: String?, val language: String?) {
    companion object {
        private const val MODIFIED = "@modified"
        private const val EXT = "@ext:"
        private const val LANG = "@lang:"

        fun parse(query: String): SettingsFilter {
            var modified = false
            var ext: String? = null
            var lang: String? = null
            val words = ArrayList<String>()
            for (token in query.trim().split(WHITESPACE).filter(String::isNotEmpty)) {
                when {
                    token == MODIFIED -> modified = true
                    token.startsWith(EXT) && token.length > EXT.length -> ext = token.removePrefix(EXT)
                    token.startsWith(LANG) && token.length > LANG.length -> lang = token.removePrefix(LANG)
                    else -> words += token
                }
            }
            return SettingsFilter(words.joinToString(" "), modified, ext, lang)
        }

        private val WHITESPACE = Regex("\\s+")
    }

    val isActive: Boolean get() = text.isNotEmpty() || modifiedOnly || extension != null || language != null
}

/** One titled block of rows; [owner] is null for built-in rows, which come first and carry no title. */
data class SettingsSection(val id: String, val title: String?, val owner: String?, val rows: List<Setting<*>>)

object SettingsSearch {

    /**
     * Settings that match [filter]. [textOf] resolves a setting's title and description (string
     * resources need a Context); [hidden] are keys another control already edits.
     */
    fun matching(
        settings: List<Setting<*>>,
        filter: SettingsFilter,
        snapshot: SettingsSnapshot,
        layer: LayerId,
        hidden: Set<String>,
        textOf: (Setting<*>) -> List<String>,
    ): List<Setting<*>> = settings.filter { s ->
        s.key !in hidden &&
            (filter.language == null || s.scope.languageOverridable) &&
            (filter.extension == null || (s as? Setting.Contributed)?.owner == filter.extension) &&
            (!filter.modifiedOnly || snapshot.isSetIn(s, layer, filter.language)) &&
            matches(s, filter.text, textOf)
    }

    /** Search results across pages: page order first, declared order within a page. */
    fun ranked(matches: List<Setting<*>>): List<Setting<*>> = matches.sortedBy { SettingsCategory.of(it).ordinal }

    /** The rows of one page: the built-in rows as one section, then contributed sections by `order` and title. */
    fun sections(rows: List<Setting<*>>): List<SettingsSection> {
        val builtIn = rows.filter { it.group is SettingGroup.BuiltIn }
        val contributed = rows.filterIsInstance<Setting.Contributed>()
            .groupBy { it.group as SettingGroup.Contributed }
            .entries
            .sortedWith(compareBy<Map.Entry<SettingGroup.Contributed, List<Setting.Contributed>>>({ it.key.order ?: Int.MAX_VALUE }, { it.key.title }, { it.key.owner }))
            .map { (group, list) ->
                SettingsSection(
                    id = "${group.owner}/${group.title}",
                    title = group.title,
                    owner = group.owner,
                    rows = list.sortedWith(compareBy({ it.order ?: Int.MAX_VALUE }, { it.key })),
                )
            }
        return listOfNotNull(builtIn.takeIf { it.isNotEmpty() }?.let { SettingsSection("builtin", null, null, it) }) + contributed
    }

    /** Title, description, keywords and key, as in VS Code's settings search. */
    private fun matches(s: Setting<*>, text: String, textOf: (Setting<*>) -> List<String>): Boolean {
        if (text.isEmpty()) return true
        val haystack = textOf(s) + s.keywords + s.key
        return text.split(' ').all { word -> haystack.any { it.contains(word, ignoreCase = true) } }
    }
}
