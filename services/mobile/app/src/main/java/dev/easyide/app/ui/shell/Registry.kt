package dev.easyide.app.ui.shell

/** Who contributed a registry entry. Core entries always win a collision and outrank extension ones. */
sealed interface Origin {
    data object Core : Origin
    data class Extension(val id: String) : Origin
}

enum class RejectReason {
    /** An id already taken: core beats an extension, and the first extension to register keeps it. */
    DUPLICATE_ID,

    /** An extension id must be `<extension id>.<name>` (or `<extension id>/<name>` for document types). */
    NOT_NAMESPACED,

    /** An extension may only claim `ext://<its own id>/...` URIs. */
    FOREIGN_SCHEME,

    /** Over the per-extension cap. */
    TOO_MANY,

    TITLE_TOO_LONG,

    /** The `builtin` opener priority is reserved for core. */
    RESERVED_PRIORITY,
}

data class Rejection(val id: String, val reason: RejectReason)

/**
 * The outcome of a registration: the (possibly unchanged) registry plus what was refused, so the
 * host can log a line per rejection instead of the engine failing.
 */
data class Registered<T>(val registry: T, val rejections: List<Rejection> = emptyList())

/** Which scope an item or container belongs to (`app`, `workspace` or `both` in the manifest). */
enum class ScopeFilter {
    APP, WORKSPACE, BOTH;

    fun includes(scope: ShellScope): Boolean = this == BOTH || (this == APP) == (scope == ShellScope.APP)
}

enum class ShellScope { APP, WORKSPACE }

internal val Origin.extensionId: String? get() = (this as? Origin.Extension)?.id

/** Core ids are free-form; an extension's must be `<its id>.<name>` so ids never collide across packs. */
internal fun Origin.owns(id: String, separator: Char = '.'): Boolean {
    val ext = extensionId ?: return true
    return id.length > ext.length + 1 && id.startsWith(ext) && id[ext.length] == separator
}
