package dev.easyide.extensions.capability

/**
 * One sdk-reference capability id. Declared in `easyide.capabilities`; an extension's
 * **granted** set is declared AND approved (arch.md sec 9: never silently granted).
 *
 * Which of these are enforced and which are disclosure only is a property of the layer,
 * not of the type: see the sdk-reference Capabilities table. Nothing here restricts what a
 * process started in the environment can do.
 */
sealed interface Capability {
    /** The canonical id as written in a manifest, e.g. `network(pypi.org)`. */
    val id: String

    data object SandboxExec : Capability { override val id = "sandbox.exec" }
    data object SandboxInstall : Capability { override val id = "sandbox.install" }
    data object FsOutsideProject : Capability { override val id = "fs.outsideProject" }
    data object LspSpawn : Capability { override val id = "lsp.spawn" }
    data object LspRequest : Capability { override val id = "lsp.request" }
    data object Clipboard : Capability { override val id = "clipboard" }
    data object UiStage : Capability { override val id = "ui.stage" }
    data object UiSettings : Capability { override val id = "ui.settings" }
    data object SecretsRead : Capability { override val id = "secrets.read" }

    /** `fs.project(read)` or `fs.project(write)`; write implies read. */
    data class FsProject(val write: Boolean) : Capability {
        override val id: String get() = if (write) "fs.project(write)" else "fs.project(read)"
    }

    /** `network(h1,h2)`; hosts lower-cased, `*.x` allowed (suffix match, enforced for `net.fetch`). */
    data class Network(val hosts: Set<String>) : Capability {
        override val id: String get() = "network(" + hosts.sorted().joinToString(",") + ")"

        fun allows(host: String): Boolean {
            val h = host.lowercase()
            return hosts.any { p -> if (p.startsWith("*.")) h.endsWith(p.substring(1)) else h == p }
        }
    }

    companion object {
        private val SIMPLE: Map<String, Capability> = listOf(
            SandboxExec, SandboxInstall, FsOutsideProject, LspSpawn, LspRequest, Clipboard, UiStage, UiSettings, SecretsRead,
        ).associateBy { it.id }
        private val HOST = Regex("""(\*\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*""")

        /** Null for an unknown id or malformed arguments. */
        fun parse(raw: String): Capability? {
            val text = raw.trim()
            SIMPLE[text]?.let { return it }
            return when {
                text == "fs.project(read)" -> FsProject(write = false)
                text == "fs.project(write)" -> FsProject(write = true)
                text.startsWith("network(") && text.endsWith(")") -> {
                    val hosts = text.removePrefix("network(").removeSuffix(")").split(',').map { it.trim().lowercase() }
                    if (hosts.isEmpty() || hosts.any { !HOST.matches(it) }) null else Network(hosts.toSet())
                }
                else -> null
            }
        }
    }
}

/**
 * A set of capabilities with the implication rules applied: `fs.project(write)` satisfies a
 * read requirement, and several `network(...)` entries act as one host list.
 */
class CapabilitySet(val items: Set<Capability>) {

    fun satisfies(required: Capability): Boolean = when (required) {
        is Capability.FsProject -> items.any { it is Capability.FsProject && (it.write || !required.write) }
        is Capability.Network -> required.hosts.all { h -> networkHosts.contains(h) }
        else -> required in items
    }

    /** Required capabilities this set does not satisfy, in a stable order for messages. */
    fun missing(required: Collection<Capability>): List<Capability> = required.filterNot(::satisfies).distinct().sortedBy { it.id }

    private val networkHosts: Set<String> by lazy { items.filterIsInstance<Capability.Network>().flatMap { it.hosts }.toSet() }

    /** Declared AND approved: what an extension may actually use. */
    fun intersect(approved: CapabilitySet): CapabilitySet = CapabilitySet(items.filter { approved.satisfies(it) }.toSet())

    override fun equals(other: Any?): Boolean = other is CapabilitySet && other.items == items
    override fun hashCode(): Int = items.hashCode()
    override fun toString(): String = items.map { it.id }.sorted().joinToString(", ", "[", "]")

    companion object {
        val EMPTY = CapabilitySet(emptySet())
        fun of(vararg c: Capability) = CapabilitySet(c.toSet())
    }
}
