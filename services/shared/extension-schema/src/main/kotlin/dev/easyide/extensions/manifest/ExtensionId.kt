package dev.easyide.extensions.manifest

/**
 * `publisher.name`, always lower-cased: ids are compared case-insensitively (sdk-reference
 * naming rules) and appear in paths, pins and settings, so one canonical spelling avoids
 * two directories for one extension.
 */
@JvmInline
value class ExtensionId private constructor(val value: String) {
    val publisher: String get() = value.substringBefore('.')
    val name: String get() = value.substringAfter('.')

    override fun toString(): String = value

    companion object {
        /** `^[a-z0-9][a-z0-9-]{0,62}$`, checked after lower-casing. */
        val SEGMENT = Regex("[a-z0-9][a-z0-9-]{0,62}")

        fun of(publisher: String, name: String): ExtensionId? {
            val p = publisher.lowercase()
            val n = name.lowercase()
            if (!SEGMENT.matches(p) || !SEGMENT.matches(n)) return null
            return ExtensionId("$p.$n")
        }

        /** Parses `publisher.name` (e.g. from `extensions.disabled`); null when malformed. */
        fun parse(raw: String): ExtensionId? {
            val dot = raw.indexOf('.')
            if (dot < 0) return null
            return of(raw.substring(0, dot), raw.substring(dot + 1))
        }
    }
}
