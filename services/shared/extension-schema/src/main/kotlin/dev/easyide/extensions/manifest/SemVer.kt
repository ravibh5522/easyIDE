package dev.easyide.extensions.manifest

/**
 * SemVer 2.0 version without build metadata (sdk-reference: `version` forbids `+build`,
 * so two packages can never differ only in metadata that precedence ignores).
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<SemVer> {

    val isPrerelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A release outranks any of its prereleases (SemVer 2.0 rule 11.3).
        if (pre.isEmpty() && other.pre.isEmpty()) return 0
        if (pre.isEmpty()) return 1
        if (other.pre.isEmpty()) return -1
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val c = comparePreId(pre[i], other.pre[i])
            if (c != 0) return c
        }
        return compareValues(pre.size, other.pre.size)
    }

    /** Same major.minor.patch, ignoring prerelease (npm's prerelease-range rule needs it). */
    fun sameCore(other: SemVer): Boolean = major == other.major && minor == other.minor && patch == other.patch

    override fun toString(): String = "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private val NUMERIC = Regex("0|[1-9][0-9]*")
        private val PRE_ID = Regex("[0-9A-Za-z-]+")

        /** Strict SemVer 2.0 core with optional prerelease; null on anything else (incl. `+build`). */
        fun parse(text: String): SemVer? {
            val dash = text.indexOf('-')
            val core = if (dash < 0) text else text.substring(0, dash)
            val parts = core.split('.')
            if (parts.size != 3 || parts.any { !NUMERIC.matches(it) }) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            val pre = if (dash < 0) emptyList() else {
                val ids = text.substring(dash + 1).split('.')
                if (ids.any { !PRE_ID.matches(it) || (it.all(Char::isDigit) && !NUMERIC.matches(it)) }) return null
                ids
            }
            return SemVer(nums[0], nums[1], nums[2], pre)
        }

        private fun comparePreId(a: String, b: String): Int {
            val an = a.toLongOrNull()?.takeIf { a.all(Char::isDigit) }
            val bn = b.toLongOrNull()?.takeIf { b.all(Char::isDigit) }
            return when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1          // numeric identifiers sort before alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
