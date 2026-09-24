package dev.easyide.app.ui.shell

/**
 * The identity of a document in the main stage, and its identity across process death
 * (shell-model.md sections 4 and 5).
 *
 * Components are stored decoded and canonical: lower-case scheme and authority, `.`/`..`
 * resolved, empty segments and a trailing slash dropped, query parameters sorted. Two spellings
 * of one location therefore parse to equal values, and [toString] is the one canonical text
 * (percent-encoded the same way every time). Instances only come from [parse], [of] and the
 * factories, all of which return null for anything malformed, so an invalid URI cannot exist.
 *
 * The [fragment] is a sub-page inside a document (`easyide://settings/editor#fonts`), not part of
 * which document it is: [key] is the URI without it and is what the stage de-duplicates on.
 */
class DocumentUri private constructor(
    val scheme: String,
    val authority: String,
    val segments: List<String>,
    val query: Map<String, String>,
    val fragment: String?,
) {
    /** `/a/b`, or empty when there are no segments. */
    val path: String = if (segments.isEmpty()) "" else segments.joinToString("/", prefix = "/")

    private val text: String = buildString {
        append(scheme).append("://").append(authority)
        segments.forEach { append('/').append(UriCodec.encode(it, UriCodec.segment)) }
        if (query.isNotEmpty()) {
            query.entries.joinTo(this, "&", "?") {
                UriCodec.encode(it.key, UriCodec.queryPart) + "=" + UriCodec.encode(it.value, UriCodec.queryPart)
            }
        }
        if (fragment != null) append('#').append(UriCodec.encode(fragment, UriCodec.fragment))
    }

    /** Which document this is, ignoring the sub-page. */
    val key: DocumentUri get() = if (fragment == null) this else DocumentUri(scheme, authority, segments, query, null)

    /** Last path segment, else the authority: enough for a tab title when no type supplies one. */
    val name: String get() = segments.lastOrNull() ?: authority

    fun withFragment(sub: String?): DocumentUri =
        if (sub.isNullOrEmpty()) key else DocumentUri(scheme, authority, segments, query, sub)

    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is DocumentUri && other.text == text
    override fun hashCode(): Int = text.hashCode()

    companion object {
        private val SCHEME = Regex("[a-z][a-z0-9+.-]*")
        private val AUTHORITY = Regex("[a-z0-9._-]*")

        /** authority: true = required, false = forbidden; [segments] is how many path segments are allowed. */
        private class Shape(val authority: Boolean, val segments: IntRange)

        private val PATH_SHAPE = Shape(authority = false, segments = 1..Int.MAX_VALUE)
        private val NAME_SHAPE = Shape(authority = true, segments = 0..0)
        private val SHAPES = mapOf(
            "file" to PATH_SHAPE,
            "preview" to PATH_SHAPE,
            "git-diff" to PATH_SHAPE,
            "git-commit" to NAME_SHAPE,
            "terminal" to NAME_SHAPE,
            "ext" to Shape(authority = true, segments = 2..2),
        )

        /** `easyide://<page>[/<subject>]`. An unknown page is accepted: a newer app may write pages this one lacks. */
        private val EASYIDE_PAGES = mapOf(
            "settings" to 0..1,
            "extension" to 1..1,
            "project" to 1..1,
            "keybindings" to 0..0,
            "language-servers" to 0..0,
            "diagnostics" to 0..0,
            "welcome" to 0..0,
        )

        private fun shapeOf(scheme: String, authority: String): Shape? = when (scheme) {
            "easyide" -> Shape(authority = true, segments = EASYIDE_PAGES[authority] ?: 0..Int.MAX_VALUE)
            else -> SHAPES[scheme]
        }

        private fun checked(uri: DocumentUri?): DocumentUri = requireNotNull(uri) { "built-in URI is malformed" }

        /** The canonical URI for these parts, or null when they do not form a valid one. */
        fun of(
            scheme: String,
            authority: String = "",
            segments: List<String> = emptyList(),
            query: Map<String, String> = emptyMap(),
            fragment: String? = null,
        ): DocumentUri? {
            val lowerScheme = scheme.lowercase()
            val lowerAuthority = authority.lowercase()
            if (!SCHEME.matches(lowerScheme) || !AUTHORITY.matches(lowerAuthority)) return null
            val clean = resolveDots(segments) ?: return null
            val parts = clean + query.keys + query.values + listOfNotNull(fragment)
            if (parts.any { part -> part.any { it.isISOControl() } }) return null
            if (query.keys.any { it.isEmpty() }) return null
            val shape = shapeOf(lowerScheme, lowerAuthority)
            if (shape != null) {
                if ((lowerAuthority.isNotEmpty()) != shape.authority) return null
                if (clean.size !in shape.segments) return null
            }
            val sub = fragment?.takeIf { it.isNotEmpty() }
            return DocumentUri(lowerScheme, lowerAuthority, clean, query.toSortedMap(), sub)
        }

        /** Empty and `.` segments vanish, `..` removes the previous one; null when `..` would leave the root. */
        private fun resolveDots(raw: List<String>): List<String>? {
            val out = ArrayList<String>(raw.size)
            for (s in raw) {
                when {
                    s.isEmpty() || s == "." -> Unit
                    s == ".." -> if (out.isEmpty()) return null else out.removeAt(out.lastIndex)
                    '/' in s -> return null
                    else -> out += s
                }
            }
            return out
        }

        fun parse(text: String): DocumentUri? {
            if (text.any { it.isWhitespace() || it.isISOControl() }) return null
            val schemeEnd = text.indexOf("://")
            if (schemeEnd <= 0) return null
            val (beforeFragment, rawFragment) = text.substring(schemeEnd + 3).splitOnce('#')
            val (hierarchy, rawQuery) = beforeFragment.splitOnce('?')
            val slash = hierarchy.indexOf('/')
            val authority = if (slash < 0) hierarchy else hierarchy.substring(0, slash)
            val rawPath = if (slash < 0) "" else hierarchy.substring(slash)
            val segments = rawPath.split('/').map { UriCodec.decode(it) ?: return null }
            val query = parseQuery(rawQuery) ?: return null
            val fragment = rawFragment?.let { UriCodec.decode(it) ?: return null }
            return of(text.substring(0, schemeEnd), authority, segments, query, fragment)
        }

        private fun parseQuery(raw: String?): Map<String, String>? {
            val out = LinkedHashMap<String, String>()
            for (pair in raw.orEmpty().split('&').filter { it.isNotEmpty() }) {
                val (k, v) = pair.splitOnce('=')
                val key = UriCodec.decode(k) ?: return null
                val value = UriCodec.decode(v.orEmpty()) ?: return null
                if (out.put(key, value) != null) return null
            }
            return out
        }

        private fun String.splitOnce(c: Char): Pair<String, String?> {
            val i = indexOf(c)
            return if (i < 0) this to null else substring(0, i) to substring(i + 1)
        }

        private fun absolute(path: String): List<String>? = if (path.startsWith("/")) path.split('/') else null

        fun file(path: String): DocumentUri? = absolute(path)?.let { of("file", segments = it) }

        fun preview(path: String): DocumentUri? = absolute(path)?.let { of("preview", segments = it) }

        fun gitDiff(path: String, base: String, target: String): DocumentUri? =
            absolute(path)?.let { of("git-diff", segments = it, query = mapOf("base" to base, "target" to target)) }

        fun gitCommit(sha: String): DocumentUri? = of("git-commit", sha)

        fun terminal(id: String): DocumentUri? = of("terminal", id)

        /** `easyide://<page>[/<subject>][#<fragment>]`, e.g. `easyide("settings", "editor", "fonts")`. */
        fun easyide(page: String, subject: String? = null, fragment: String? = null): DocumentUri? =
            of("easyide", page, listOfNotNull(subject), fragment = fragment)

        fun extension(id: String, type: String, docId: String): DocumentUri? = of("ext", id, listOf(type, docId))

        // Declared last: they run the factories above, which read the tables declared earlier.
        val WELCOME = checked(easyide("welcome"))
        val KEYBINDINGS = checked(easyide("keybindings"))
        val LANGUAGE_SERVERS = checked(easyide("language-servers"))
        val DIAGNOSTICS = checked(easyide("diagnostics"))
    }
}
