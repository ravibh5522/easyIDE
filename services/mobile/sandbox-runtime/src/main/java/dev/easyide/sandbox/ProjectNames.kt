package dev.easyide.sandbox

/**
 * The rules a project name must satisfy, in one place so [ProjectManager]
 * (which enforces them) and the UI (which shows them while typing) cannot
 * disagree about what is acceptable.
 */
object ProjectNames {

    enum class Problem { BLANK, DUPLICATE }

    /**
     * @param others the names of every *other* project; compared ignoring case,
     *   since two projects that differ only in case are indistinguishable in a list.
     */
    fun problem(name: String, others: Collection<String>): Problem? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> Problem.BLANK
            others.any { it.equals(trimmed, ignoreCase = true) } -> Problem.DUPLICATE
            else -> null
        }
    }

    /**
     * [desired] if free, else "[desired] 2", "[desired] 3", ... - the first
     * that does not collide. Used to propose the name of a duplicate.
     */
    fun unique(desired: String, existing: Collection<String>): String {
        val base = desired.trim()
        if (problem(base, existing) == null) return base
        var n = 2
        while (problem("$base $n", existing) != null) n++
        return "$base $n"
    }
}
