package dev.easyide.extwasm.host

import dev.easyide.extwasm.WasmPolicy
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Capability ids of sdk-reference "Capabilities" that L2 enforces. */
object Cap {
    const val SANDBOX_EXEC = "sandbox.exec"
    const val FS_READ = "fs.project(read)"
    const val FS_WRITE = "fs.project(write)"
    const val FS_OUTSIDE = "fs.outsideProject"
    const val LSP_REQUEST = "lsp.request"
    const val CLIPBOARD = "clipboard"
    const val UI_STAGE = "ui.stage"
    const val UI_SETTINGS = "ui.settings"
    const val SECRETS_READ = "secrets.read"
    const val NETWORK_PREFIX = "network("
}

/**
 * The granted set of one extension: declared (`easyide.capabilities`) AND approved (the
 * approval record), as computed by `:extensions` at activation. Immutable; a change of
 * approvals takes effect at the next activation.
 *
 * `fs.project(write)` implies read, matching the prompt text "Can read and change files".
 */
class CapabilitySet(granted: Collection<String>) {
    /** Canonical ids, network hosts folded into one `network(...)` entry for the guest. */
    val ids: List<String>
    val network: HostMatcher

    private val plain: Set<String>

    init {
        val hosts = ArrayList<String>()
        val rest = LinkedHashSet<String>()
        for (raw in granted) {
            val id = raw.trim()
            if (id.startsWith(Cap.NETWORK_PREFIX) && id.endsWith(")")) {
                id.substring(Cap.NETWORK_PREFIX.length, id.length - 1).split(',')
                    .map { it.trim().lowercase() }.filterTo(hosts) { it.isNotEmpty() }
            } else if (id.isNotEmpty()) {
                rest += id
            }
        }
        network = HostMatcher(hosts)
        plain = rest
        ids = rest.toList() + if (hosts.isEmpty()) emptyList() else listOf("network(${hosts.distinct().joinToString(",")})")
    }

    fun has(id: String): Boolean = id in plain || (id == Cap.FS_READ && Cap.FS_WRITE in plain)

    companion object {
        val NONE = CapabilitySet(emptyList())
    }
}

/**
 * Declared network hosts: `example.com` matches exactly, `*.example.com` matches any
 * subdomain (not the apex). Case-insensitive; no ports, no IP literals special-cased.
 */
class HostMatcher(patterns: Collection<String>) {
    private val exact = patterns.filterNot { it.startsWith("*.") }.toSet()
    private val suffixes = patterns.filter { it.startsWith("*.") }.map { it.substring(1) }

    val isEmpty: Boolean get() = exact.isEmpty() && suffixes.isEmpty()

    fun matches(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h in exact || suffixes.any { h.endsWith(it) && h.length > it.length }
    }
}

/** Guest path rules for `fs.*` and `editor.*` (threat-model B2 "T", ST-20). */
object GuestPaths {
    /**
     * Lexically normalises an absolute guest path (`.`/`..`/duplicate slashes resolved, `..`
     * at the root stays at the root). Symlink resolution is the FilePort's job, on the host
     * side of the path mapping. Null when the path is not absolute or contains NUL.
     */
    fun normalize(path: String): String? {
        if (!path.startsWith("/") || '\u0000' in path) return null
        val parts = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
        }
        return parts.joinToString("/", prefix = "/")
    }

    fun inProject(normalized: String): Boolean =
        normalized == WasmPolicy.PROJECT_ROOT || normalized.startsWith(WasmPolicy.PROJECT_ROOT + "/")

    /** @return null if allowed, else the missing capability. */
    fun missingFor(caps: CapabilitySet, normalized: String, write: Boolean): String? {
        val need = if (write) Cap.FS_WRITE else Cap.FS_READ
        return when {
            !caps.has(need) -> need
            !inProject(normalized) && !caps.has(Cap.FS_OUTSIDE) -> Cap.FS_OUTSIDE
            else -> null
        }
    }
}

/**
 * Address rules a [NetPort] must apply to every resolved address it connects to, including
 * after each redirect: `net.fetch` never reaches loopback, link-local, private, CGNAT,
 * multicast or unspecified addresses (sdk-reference net row). Kept here so the rule has one
 * implementation whichever networking stack the port uses.
 */
object NetGuard {
    private const val CGNAT_FIRST_OCTET = 100
    private const val CGNAT_SECOND_MASK = 0xC0
    private const val CGNAT_SECOND_BITS = 0x40
    private const val ULA_MASK = 0xFE
    private const val ULA_BITS = 0xFC
    private const val BYTE = 0xFF

    fun isPublic(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        val b = address.address
        return when (address) {
            is Inet4Address -> !(b[0].toInt() and BYTE == CGNAT_FIRST_OCTET &&
                b[1].toInt() and CGNAT_SECOND_MASK == CGNAT_SECOND_BITS)
            is Inet6Address -> b[0].toInt() and ULA_MASK != ULA_BITS
            else -> false
        }
    }
}
