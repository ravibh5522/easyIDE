package dev.easyide.ext.cli

/**
 * Hand-rolled argument parsing (cli.md sec 2: few commands, few flags, no CLI library).
 * `--flag value`, `--flag=value` and boolean `--flag`; anything else is positional. Unknown
 * flags are a usage error so a typo never silently changes behaviour.
 */
class Args private constructor(val positional: List<String>, private val values: Map<String, String>, private val switches: Set<String>) {

    fun value(name: String): String? = values[name]

    fun flag(name: String): Boolean = name in switches

    fun positional(index: Int): String? = positional.getOrNull(index)

    companion object {
        /** Flags every command accepts. */
        val GLOBAL_SWITCHES = setOf("json", "help")

        fun parse(raw: List<String>, valueFlags: Set<String>, switchFlags: Set<String>, maxPositional: Int): Args {
            val positional = ArrayList<String>()
            val values = HashMap<String, String>()
            val switches = HashSet<String>()
            var i = 0
            while (i < raw.size) {
                val a = raw[i++]
                if (a == "--") { positional += raw.subList(i, raw.size); break }
                if (!a.startsWith("--") || a.length == 2) { positional += a; continue }
                val eq = a.indexOf('=')
                val name = if (eq > 0) a.substring(2, eq) else a.substring(2)
                when (name) {
                    in valueFlags -> values[name] = if (eq > 0) a.substring(eq + 1) else raw.getOrNull(i++) ?: usage("--$name needs a value")
                    in switchFlags, in GLOBAL_SWITCHES -> {
                        if (eq > 0) usage("--$name takes no value")
                        switches += name
                    }
                    else -> usage("unknown flag --$name")
                }
            }
            if (positional.size > maxPositional) usage("unexpected argument '${positional[maxPositional]}'")
            return Args(positional, values, switches)
        }
    }
}
