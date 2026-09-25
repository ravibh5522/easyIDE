package dev.easyide.app.ui.shell

/** A glyph name from the icon set, or `ext:./path.svg` for a pack-supplied vector. Resolved by the icon layer. */
@JvmInline
value class IconRef(val name: String)

/**
 * Which URIs a [DocumentType] renders: a scheme, narrowed by an authority (`easyide://settings`)
 * and, for extension documents, the first path segment (`ext://acme.docker/container/...`).
 * The most specific matching pattern wins, so `easyide://settings` beats a bare `easyide` type.
 */
data class UriPattern(val scheme: String, val authority: String? = null, val firstSegment: String? = null) {
    init {
        require(authority != null || firstSegment == null) { "a first segment needs an authority" }
    }

    val specificity: Int get() = 1 + (if (authority != null) 1 else 0) + (if (firstSegment != null) 1 else 0)

    fun matches(uri: DocumentUri): Boolean =
        uri.scheme == scheme &&
            (authority == null || uri.authority == authority) &&
            (firstSegment == null || uri.segments.firstOrNull() == firstSegment)

    companion object {
        /** The pattern of a manifest document type id `<extension id>/<type>`, or null for any other shape. */
        fun forExtensionType(typeId: String): UriPattern? {
            val (ext, type) = typeId.split('/').takeIf { it.size == 2 && it.none(String::isEmpty) } ?: return null
            return UriPattern("ext", ext, type)
        }
    }
}

/**
 * How one kind of document is identified, titled and restored. Rendering is not here: the Compose
 * layer maps [id] to a body, which keeps the engine pure and testable on the JVM.
 *
 * [multiple] mirrors the spec's flag: a document is normally unique on the stage (opening it again
 * focuses it); a `multiple` type may appear once in each group (a terminal shown in two splits).
 * [restorable] false keeps the document out of saved sessions.
 */
class DocumentType(
    val id: String,
    val pattern: UriPattern,
    val title: (DocumentUri) -> String,
    val icon: (DocumentUri) -> IconRef,
    val multiple: Boolean = false,
    val supportsSplit: Boolean = true,
    val restorable: Boolean = true,
) {
    companion object {
        const val UNAVAILABLE_ID = "easyide.unavailable"

        /**
         * What a URI whose type is missing (a disabled extension, a newer app's page) resolves to. It
         * keeps the tab and the URI so the type coming back, or "close all of this type", still works.
         */
        val UNAVAILABLE = DocumentType(
            id = UNAVAILABLE_ID,
            pattern = UriPattern("easyide", "unavailable"),
            title = { it.name },
            icon = { IconRef("warning") },
        )
    }
}

/** How strongly an opener claims a file: `builtin` is core-only, `default` opens it, `option` is offered in "Open with". */
enum class OpenerPriority { BUILTIN, DEFAULT, OPTION }

/** `documentOpeners` entry: files matching [glob] can be opened by the type [typeId]. */
class Opener(val glob: String, val typeId: String, val priority: OpenerPriority) {
    private val matcher = Glob.compile(glob)

    /** Openers only apply to workspace files; other schemes name their own type. */
    fun matches(uri: DocumentUri): Boolean = uri.scheme == "file" && matcher.matches(uri)
}

/** A user `workbench.editorAssociations` entry. */
data class Association(val glob: String, val typeId: String) {
    private val matcher = Glob.compile(glob)
    fun matches(uri: DocumentUri): Boolean = uri.scheme == "file" && matcher.matches(uri)
}
