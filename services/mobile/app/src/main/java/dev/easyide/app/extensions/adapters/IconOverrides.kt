package dev.easyide.app.extensions.adapters

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The user's icon associations from settings (`easyide.icons.fileAssociations` and
 * `easyide.icons.folderAssociations`), asked before the active theme's own mappings. Values are
 * icon ids of the active theme; an id it does not define is skipped at lookup and reported by
 * [unknownIds], so the tree never shows a blank icon for a typo.
 *
 * File keys, case-insensitive: an exact name (`Jenkinsfile`), a name suffix without dot (`foo`
 * matches `a.foo`, `a.b.foo`), or a glob (`*.foo`, `docker-compose*.yml`) with `*` and `?`.
 * Precedence: exact name, then glob in file order, then suffix, longest first. Folder keys are
 * exact names; an expanded folder uses `<id>-open` when the theme has it, else the same icon.
 */
class IconOverrides private constructor(
    private val files: Map<String, String>,
    private val globs: List<Pair<Regex, String>>,
    private val folders: Map<String, String>,
    /** Every association as written: (setting key, name, icon id), for [unknownIds]. */
    private val written: List<Triple<String, String, String>>,
) {
    fun fileIcon(fileName: String): String? {
        val name = fileName.lowercase()
        files[name]?.let { return it }
        globs.firstOrNull { it.first.matches(name) }?.let { return it.second }
        return IconTheme.extensionsOf(name).firstNotNullOfOrNull { files[it] }
    }

    /** The icon id for [folderName], or null when it has no association. */
    fun folderIcon(folderName: String): String? = folders[folderName.lowercase()]

    /** Every (setting key, name, icon id) whose icon id is not in [known]. */
    fun unknownIds(known: Set<String>): List<Triple<String, String, String>> = written.filter { it.third !in known }

    companion object {
        const val FILE_KEY = "easyide.icons.fileAssociations"
        const val FOLDER_KEY = "easyide.icons.folderAssociations"
        val NONE = of(JsonObject(emptyMap()), JsonObject(emptyMap()))

        fun of(files: JsonElement, folders: JsonElement): IconOverrides {
            val exact = LinkedHashMap<String, String>()
            val globs = ArrayList<Pair<Regex, String>>()
            val fileEntries = strings(files)
            for ((key, id) in fileEntries) {
                val k = key.lowercase()
                if (k.any { it == '*' || it == '?' }) globs += glob(k) to id else exact[k] = id
            }
            // `.foo` names the dotfile `.foo` and, as in a theme's fileExtensions, the suffix `foo`.
            for ((key, id) in fileEntries) {
                val k = key.lowercase()
                if (k.startsWith(".") && k.length > 1 && k.none { it == '*' || it == '?' }) exact.putIfAbsent(k.drop(1), id)
            }
            val folderEntries = strings(folders)
            val written = fileEntries.map { Triple(FILE_KEY, it.first, it.second) } + folderEntries.map { Triple(FOLDER_KEY, it.first, it.second) }
            return IconOverrides(exact, globs, folderEntries.associate { (k, v) -> k.lowercase() to v }, written)
        }

        /** The string-valued members of an object setting; other members are the schema check's business. */
        private fun strings(e: JsonElement): List<Pair<String, String>> =
            (e as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }

        private fun glob(pattern: String): Regex = Regex(
            pattern.map { c -> when (c) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(c.toString()) } }.joinToString(""),
        )
    }
}
