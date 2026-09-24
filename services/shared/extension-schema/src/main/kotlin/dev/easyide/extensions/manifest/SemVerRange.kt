package dev.easyide.extensions.manifest

/**
 * An npm-style version range (`^0.3.0`, `~1.2`, `>=1.0.0 <2.0.0`, `1.x`, `1.0.0 - 2.0.0`,
 * `a || b`): the syntax VS Code's `engines.vscode` uses, so authors already know it.
 *
 * Prerelease rule (as npm): a prerelease version matches only when some comparator in the
 * matching set names a prerelease of the same major.minor.patch. So `^0.3.0` does not
 * silently accept `0.4.0-beta`.
 */
class SemVerRange private constructor(val raw: String, private val sets: List<List<Comparator>>) {

    fun contains(v: SemVer): Boolean = sets.any { set ->
        set.all { it.matches(v) } &&
            (!v.isPrerelease || set.any { it.version.isPrerelease && it.version.sameCore(v) })
    }

    override fun equals(other: Any?): Boolean = other is SemVerRange && other.sets == sets
    override fun hashCode(): Int = sets.hashCode()
    override fun toString(): String = raw

    internal enum class Op { LT, LE, GT, GE, EQ }

    internal data class Comparator(val op: Op, val version: SemVer) {
        fun matches(v: SemVer): Boolean {
            val c = v.compareTo(version)
            return when (op) {
                Op.LT -> c < 0
                Op.LE -> c <= 0
                Op.GT -> c > 0
                Op.GE -> c >= 0
                Op.EQ -> c == 0
            }
        }
    }

    /** A possibly partial version: null components are wildcards (`1.x`, `1.2`, `*`). */
    private data class Partial(val major: Int?, val minor: Int?, val patch: Int?, val pre: List<String>) {
        val full: Boolean get() = patch != null
        fun floor() = SemVer(major ?: 0, minor ?: 0, patch ?: 0, pre)
    }

    companion object {
        private val XR = Regex("x|X|\\*|0|[1-9][0-9]*")
        private val PRE_ID = Regex("[0-9A-Za-z-]+")
        private val OPERATOR_SPACE = Regex("(<=|>=|<|>|=|~|\\^)\\s+")
        private val ANY = listOf(Comparator(Op.GE, SemVer(0, 0, 0)))

        /** Null when [raw] is not a valid range. */
        fun parse(raw: String): SemVerRange? {
            if (raw.isBlank()) return null
            val sets = raw.split("||").map { part -> parseSet(part.trim()) ?: return null }
            return SemVerRange(raw, sets)
        }

        private fun parseSet(text: String): List<Comparator>? {
            if (text.isEmpty()) return ANY
            val hyphen = Regex("^(\\S+)\\s+-\\s+(\\S+)$").find(text)
            if (hyphen != null) {
                val lo = parsePartial(hyphen.groupValues[1]) ?: return null
                val hi = parsePartial(hyphen.groupValues[2]) ?: return null
                return listOf(Comparator(Op.GE, lo.floor())) + upperInclusive(hi)
            }
            val tokens = text.replace(OPERATOR_SPACE, "$1").split(Regex("\\s+"))
            return tokens.flatMap { desugar(it) ?: return null }
        }

        private fun desugar(token: String): List<Comparator>? = when {
            token.startsWith("^") -> parsePartial(token.substring(1))?.let(::caret)
            token.startsWith("~") -> parsePartial(token.removePrefix("~>").removePrefix("~"))?.let(::tilde)
            token.startsWith(">=") -> parsePartial(token.substring(2))?.let { listOf(Comparator(Op.GE, it.floor())) }
            token.startsWith("<=") -> parsePartial(token.substring(2))?.let(::upperInclusive)
            token.startsWith(">") -> parsePartial(token.substring(1))?.let(::greater)
            token.startsWith("<") -> parsePartial(token.substring(1))?.let { listOf(Comparator(Op.LT, it.floor())) }
            token.startsWith("=") -> parsePartial(token.substring(1))?.let(::exact)
            else -> parsePartial(token)?.let(::exact)
        }

        private fun exact(p: Partial): List<Comparator> =
            if (p.full) listOf(Comparator(Op.EQ, p.floor())) else xRange(p)

        private fun xRange(p: Partial): List<Comparator> = when {
            p.major == null -> ANY
            p.minor == null -> listOf(Comparator(Op.GE, p.floor()), Comparator(Op.LT, SemVer(p.major + 1, 0, 0)))
            else -> listOf(Comparator(Op.GE, p.floor()), Comparator(Op.LT, SemVer(p.major, p.minor + 1, 0)))
        }

        private fun caret(p: Partial): List<Comparator> {
            val lo = Comparator(Op.GE, p.floor())
            val major = p.major ?: return ANY
            val hi = when {
                major > 0 || p.minor == null -> SemVer(major + 1, 0, 0)
                p.minor > 0 || p.patch == null -> SemVer(0, p.minor + 1, 0)
                else -> SemVer(0, 0, p.patch + 1)
            }
            return listOf(lo, Comparator(Op.LT, hi))
        }

        private fun tilde(p: Partial): List<Comparator> {
            val major = p.major ?: return ANY
            val hi = if (p.minor == null) SemVer(major + 1, 0, 0) else SemVer(major, p.minor + 1, 0)
            return listOf(Comparator(Op.GE, p.floor()), Comparator(Op.LT, hi))
        }

        /** `<=1.2` means "any 1.2.x", so a partial upper bound is exclusive of the next step. */
        private fun upperInclusive(p: Partial): List<Comparator> = when {
            p.full -> listOf(Comparator(Op.LE, p.floor()))
            p.major == null -> ANY
            p.minor == null -> listOf(Comparator(Op.LT, SemVer(p.major + 1, 0, 0)))
            else -> listOf(Comparator(Op.LT, SemVer(p.major, p.minor + 1, 0)))
        }

        private fun greater(p: Partial): List<Comparator> = when {
            p.full -> listOf(Comparator(Op.GT, p.floor()))
            p.major == null -> listOf(Comparator(Op.LT, SemVer(0, 0, 0)))   // `>*` matches nothing
            p.minor == null -> listOf(Comparator(Op.GE, SemVer(p.major + 1, 0, 0)))
            else -> listOf(Comparator(Op.GE, SemVer(p.major, p.minor + 1, 0)))
        }

        private fun parsePartial(text: String): Partial? {
            val t = text.removePrefix("v")
            val dash = t.indexOf('-')
            val core = if (dash < 0) t else t.substring(0, dash)
            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 3 || parts.any { !XR.matches(it) }) return null
            val nums = parts.map { it.toIntOrNull() }
            // A wildcard may not be followed by a number (`1.x.3`).
            if (nums.indices.any { i -> nums[i] == null && nums.drop(i).any { it != null } }) return null
            val pre = if (dash < 0) emptyList() else t.substring(dash + 1).split('.')
            if (pre.any { !PRE_ID.matches(it) }) return null
            val p = Partial(nums.getOrNull(0), nums.getOrNull(1), nums.getOrNull(2), pre)
            if (pre.isNotEmpty() && !p.full) return null
            return p
        }
    }
}
