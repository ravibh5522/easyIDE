package dev.easyide.extensions.whenclause

/**
 * A parsed when-clause (sdk-reference "When-clause context"). Trees are built once at load
 * and shared; [keys] lets observers skip re-evaluation when unrelated keys change.
 */
sealed interface WhenExpr {
    val keys: Set<String>

    /** Bare key: a truthiness test. */
    data class Key(val name: String) : WhenExpr {
        override val keys: Set<String> get() = setOf(name)
    }

    data class Const(val value: Boolean) : WhenExpr {
        override val keys: Set<String> get() = emptySet()
    }

    data class Not(val expr: WhenExpr) : WhenExpr {
        override val keys: Set<String> get() = expr.keys
    }

    data class And(val parts: List<WhenExpr>) : WhenExpr {
        override val keys: Set<String> by lazy { parts.flatMapTo(HashSet()) { it.keys } }
    }

    data class Or(val parts: List<WhenExpr>) : WhenExpr {
        override val keys: Set<String> by lazy { parts.flatMapTo(HashSet()) { it.keys } }
    }

    /** `key op literal`; the right-hand side is always text (unquoted words are strings). */
    data class Compare(val key: String, val op: CompareOp, val literal: String) : WhenExpr {
        override val keys: Set<String> get() = setOf(key)
    }

    /**
     * `key =~ /pattern/flags`. Equality is by source text so trees compare structurally
     * (normalize round trip, conflict detection); [regex] is compiled once.
     */
    class Matches(val key: String, val pattern: String, val flags: String) : WhenExpr {
        override val keys: Set<String> get() = setOf(key)

        val regex: Regex = Regex(pattern, flags.mapNotNullTo(HashSet()) { FLAG_OPTIONS[it] })

        override fun equals(other: Any?): Boolean =
            other is Matches && other.key == key && other.pattern == pattern && other.flags == flags

        override fun hashCode(): Int = (key.hashCode() * 31 + pattern.hashCode()) * 31 + flags.hashCode()
        override fun toString(): String = "Matches($key, /$pattern/$flags)"

        companion object {
            /**
             * JavaScript flags VS Code accepts. `u` needs no option: JVM patterns already
             * match by code point, and Kotlin's IGNORE_CASE is Unicode-aware.
             */
            val FLAG_OPTIONS: Map<Char, RegexOption> = mapOf(
                'i' to RegexOption.IGNORE_CASE,
                'm' to RegexOption.MULTILINE,
                's' to RegexOption.DOT_MATCHES_ALL,
            )
            const val VALID_FLAGS = "imsu"
        }
    }

    /** `key in container` / `key not in container`. */
    data class In(val key: String, val container: Container, val negated: Boolean) : WhenExpr {
        override val keys: Set<String> get() = when (container) {
            is Container.KeyRef -> setOf(key, container.name)
            is Container.Literal -> setOf(key)
        }
    }

    sealed interface Container {
        /** A context key holding an array or object. */
        data class KeyRef(val name: String) : Container

        /** A quoted comma list, `'debian,ubuntu'`, split and trimmed at parse time. */
        data class Literal(val items: List<String>) : Container
    }
}

enum class CompareOp(val text: String) {
    EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=")
}
