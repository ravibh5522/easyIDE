package dev.easyide.sandbox.extensions

/**
 * An extension id (`publisher.name`) in canonical, lower-case form.
 *
 * Extension ids become directory names and guest mount points, and they come
 * from untrusted package manifests. Accepting only the sdk-reference grammar
 * makes `..`, `/`, empty and absolute names unrepresentable, so a path built
 * from an [ExtensionId] cannot leave its parent directory - no canonicalise-
 * and-compare needed at each use. Ids compare case-insensitively
 * (sdk-reference Package layout), so the canonical form is lower case and two
 * spellings of one id share one directory.
 */
@JvmInline
value class ExtensionId private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        /**
         * sdk-reference: `name` and `publisher` are each `^[a-z0-9][a-z0-9-]{0,62}$`,
         * compared case-insensitively. Upper case is spelled out rather than
         * using IGNORE_CASE, which on the JVM also folds non-ASCII letters
         * (the Kelvin sign matches `k`) and would let them into a path.
         */
        private const val SEGMENT = "[a-zA-Z0-9][a-zA-Z0-9-]{0,62}"
        private val PATTERN = Regex("^$SEGMENT\\.$SEGMENT$")

        fun parseOrNull(raw: String): ExtensionId? =
            if (PATTERN.matches(raw)) ExtensionId(raw.lowercase()) else null

        /** @throws IllegalArgumentException if [raw] is not `publisher.name`. */
        fun parse(raw: String): ExtensionId =
            requireNotNull(parseOrNull(raw)) { "Not an extension id (publisher.name): '$raw'" }
    }
}

/**
 * An installed version's directory name: SemVer 2.0 without build metadata
 * (sdk-reference Package layout).
 *
 * The grammar starts with a digit and allows only `[0-9A-Za-z.-]`, so it can
 * never be `.`, `..` or contain a separator, and it never collides with the
 * `current` link or the installer's dot-prefixed temp directories that share
 * the extension's directory.
 */
@JvmInline
value class ExtensionVersion private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private const val NUMBER = "(0|[1-9]\\d*)"
        private const val PRERELEASE_ID = "(0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
        private val PATTERN =
            Regex("^$NUMBER\\.$NUMBER\\.$NUMBER(-$PRERELEASE_ID(\\.$PRERELEASE_ID)*)?$")

        fun parseOrNull(raw: String): ExtensionVersion? =
            if (PATTERN.matches(raw)) ExtensionVersion(raw) else null

        /** @throws IllegalArgumentException if [raw] is not SemVer without build metadata. */
        fun parse(raw: String): ExtensionVersion =
            requireNotNull(parseOrNull(raw)) { "Not a SemVer version without build metadata: '$raw'" }
    }
}
