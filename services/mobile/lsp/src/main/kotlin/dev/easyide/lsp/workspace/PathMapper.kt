package dev.easyide.lsp.workspace

import java.io.File

/** Where a guest URI lives on the host, and whether the editor may write it. */
data class HostLocation(val file: File, val readOnly: Boolean, val projectRelative: String?)

/** Host file <-> guest `file://` URI (lsp-client.md sec 4). */
interface PathMapper {
    /** Guest URI of the project root, advertised as `rootUri` and the one workspace folder. */
    val rootUri: String

    /** Host file -> guest URI, or null when the file is not visible in the guest. */
    fun toGuestUri(host: File): String?

    /** Guest URI -> host location, or null when it has no host counterpart. */
    fun toHost(uri: String): HostLocation?
}

/**
 * The sandbox layout as `:lsp` sees it, without depending on `:sandbox-runtime`: the project
 * directory is bind-mounted at [guestWorkspace], every other guest path lives under
 * [rootfsDir], and [passthroughMounts] are Android's own `/dev`, `/proc`, `/sys` which have
 * no file the editor could open.
 *
 * The app constructs it from `SandboxPaths` (`projectDir`, `rootfsDir`,
 * `guestProjectPath()`), so no guest path is spelled here.
 *
 * Mapping is lexical: `.`/`..` are resolved and anything escaping the project or rootfs maps
 * to null, but symlinks are not chased. That keeps it pure, and matches what the server
 * already did - it resolves links in the guest before it sends a URI. This is path hygiene,
 * not a security boundary: the server can read anything in the guest anyway.
 */
class WorkspacePathMapper(
    private val projectDir: File,
    private val rootfsDir: File,
    private val guestWorkspace: String,
    private val passthroughMounts: List<String>,
) : PathMapper {

    private val projectRoot = FileUri.normalizePath(projectDir.absolutePath)
        ?: throw IllegalArgumentException("project dir must be absolute: $projectDir")
    private val rootfsRoot = FileUri.normalizePath(rootfsDir.absolutePath)
        ?: throw IllegalArgumentException("rootfs dir must be absolute: $rootfsDir")
    private val workspaceRoot = FileUri.normalizePath(guestWorkspace)
        ?: throw IllegalArgumentException("guest workspace must be absolute: $guestWorkspace")

    override val rootUri: String = FileUri.fromPath(workspaceRoot)

    override fun toGuestUri(host: File): String? {
        val path = FileUri.normalizePath(host.absolutePath) ?: return null
        FileUri.relativeTo(path, projectRoot)?.let { rel -> return FileUri.fromPath(join(workspaceRoot, rel)) }
        FileUri.relativeTo(path, rootfsRoot)?.let { rel ->
            val guest = join("/", rel)
            // A rootfs path that the guest actually sees as the workspace or a pass-through
            // mount is shadowed there, so it is not the file the server would mean.
            if (isUnder(guest, workspaceRoot) || passthroughMounts.any { isUnder(guest, it) }) return null
            return FileUri.fromPath(guest)
        }
        return null
    }

    override fun toHost(uri: String): HostLocation? {
        val path = FileUri.toPath(uri) ?: return null
        FileUri.relativeTo(path, workspaceRoot)?.let { rel ->
            return HostLocation(if (rel.isEmpty()) projectDir else File(projectDir, rel), readOnly = false, projectRelative = rel)
        }
        if (passthroughMounts.any { isUnder(path, it) }) return null
        val rel = FileUri.relativeTo(path, "/") ?: return null
        return HostLocation(if (rel.isEmpty()) rootfsDir else File(rootfsDir, rel), readOnly = true, projectRelative = null)
    }

    private fun isUnder(path: String, root: String) = FileUri.relativeTo(path, root) != null

    private fun join(root: String, rel: String) = when {
        rel.isEmpty() -> root
        root.endsWith("/") -> root + rel
        else -> "$root/$rel"
    }
}

/**
 * `file:` URI encoding shared by everything that builds or compares URIs.
 *
 * Output matches vscode-uri (which pyright, tsserver and most Node servers use): each path
 * segment is percent-encoded except RFC 3986 unreserved characters, with upper-case hex over
 * UTF-8 bytes. Servers echo the URIs we send, so producing their canonical form makes the
 * echoed string equal to ours. Input accepts `file:/p`, `file:///p`, `file://localhost/p`,
 * any hex case, `.`/`..` segments and a trailing `/`.
 */
object FileUri {
    private const val SCHEME = "file:"
    private const val LOCALHOST = "localhost"
    private const val HEX = "0123456789ABCDEF"

    fun fromPath(absolutePath: String): String {
        val sb = StringBuilder(SCHEME).append("//")
        for (b in absolutePath.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and BYTE_MASK
            if (c == '/'.code || isUnreserved(c)) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(HEX[c shr NIBBLE]).append(HEX[c and NIBBLE_MASK])
            }
        }
        return sb.toString()
    }

    /** Decoded, normalised absolute path of a `file:` URI, or null for other schemes or bad input. */
    fun toPath(uri: String): String? {
        if (!uri.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) return null
        var rest = uri.substring(SCHEME.length).substringBefore('#').substringBefore('?')
        if (rest.startsWith("//")) {
            val slash = rest.indexOf('/', 2)
            val authority = if (slash < 0) rest.substring(2) else rest.substring(2, slash)
            if (authority.isNotEmpty() && !authority.equals(LOCALHOST, ignoreCase = true)) return null
            rest = if (slash < 0) "/" else rest.substring(slash)
        }
        return normalizePath(percentDecode(rest) ?: return null)
    }

    /** The canonical spelling of a `file:` URI; other schemes are returned unchanged. */
    fun canonical(uri: String): String = toPath(uri)?.let(::fromPath) ?: uri

    /**
     * Resolves `.` and `..` lexically and drops empty segments and a trailing `/`.
     *
     * @return null for a relative path or one whose `..` climbs above `/`.
     */
    fun normalizePath(path: String): String? {
        if (!path.startsWith("/")) return null
        val out = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
                else -> out += seg
            }
        }
        return out.joinToString("/", prefix = "/")
    }

    /** Path of [path] relative to [root] ("" when equal), or null when it is not under it. Both normalised. */
    fun relativeTo(path: String, root: String): String? {
        if (root == "/") return path.removePrefix("/")
        if (path == root) return ""
        return if (path.startsWith("$root/")) path.substring(root.length + 1) else null
    }

    private fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], HEX_RADIX)
                val lo = Character.digit(s[i + 2], HEX_RADIX)
                if (hi < 0 || lo < 0) return null
                bytes.write((hi shl NIBBLE) or lo)
                i += PERCENT_ESCAPE_LENGTH
            } else {
                // Encode the whole literal run at once so a surrogate pair stays together.
                val next = s.indexOf('%', i).let { if (it < 0) s.length else it }
                val encoded = s.substring(i, next).toByteArray(Charsets.UTF_8)
                bytes.write(encoded, 0, encoded.size)
                i = next
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun isUnreserved(c: Int): Boolean =
        c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code

    private const val BYTE_MASK = 0xFF
    private const val NIBBLE = 4
    private const val NIBBLE_MASK = 0x0F
    private const val HEX_RADIX = 16
    private const val PERCENT_ESCAPE_LENGTH = 3
}
