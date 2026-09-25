package dev.easyide.app.ui.shell

/**
 * The document types and openers of the shell, filled by core at startup and by extensions when a
 * pack activates (shell-model.md section 5). Immutable: every change returns a new registry, so the
 * host can publish it through a StateFlow and tests can compare results.
 */
class DocumentRegistry private constructor(
    private val types: List<Entry<DocumentType>>,
    private val openers: List<Entry<Opener>>,
) {
    private class Entry<T>(val value: T, val origin: Origin)

    /**
     * Adds [type]. An extension may only register `ext://<its id>/<type>` documents, under an id of
     * the form `<extension id>/<type>`, at most [ShellLimits.DOCUMENT_TYPES_PER_EXTENSION] of them;
     * a duplicate id is refused (the earlier registration stays).
     */
    fun register(type: DocumentType, origin: Origin): Registered<DocumentRegistry> {
        val refusal = refusal(type, origin)
        if (refusal != null) return Registered(this, listOf(Rejection(type.id, refusal)))
        return Registered(DocumentRegistry(types + Entry(type, origin), openers))
    }

    private fun refusal(type: DocumentType, origin: Origin): RejectReason? {
        if (types.any { it.value.id == type.id }) return RejectReason.DUPLICATE_ID
        val ext = origin.extensionId ?: return null
        return when {
            !origin.owns(type.id, '/') -> RejectReason.NOT_NAMESPACED
            type.pattern.scheme != "ext" || type.pattern.authority != ext -> RejectReason.FOREIGN_SCHEME
            types.count { it.origin == origin } >= ShellLimits.DOCUMENT_TYPES_PER_EXTENSION -> RejectReason.TOO_MANY
            else -> null
        }
    }

    /** Adds an opener. `builtin` is reserved for core; an opener naming an unregistered type still registers (the pack may load later). */
    fun registerOpener(opener: Opener, origin: Origin): Registered<DocumentRegistry> {
        if (origin is Origin.Extension && opener.priority == OpenerPriority.BUILTIN) {
            return Registered(this, listOf(Rejection(opener.typeId, RejectReason.RESERVED_PRIORITY)))
        }
        return Registered(DocumentRegistry(types, openers + Entry(opener, origin)))
    }

    /** Removes everything an extension contributed (pack disabled or uninstalled). */
    fun unregister(extensionId: String): DocumentRegistry {
        val gone = Origin.Extension(extensionId)
        return DocumentRegistry(types.filter { it.origin != gone }, openers.filter { it.origin != gone })
    }

    fun typeById(id: String): DocumentType? = types.firstOrNull { it.value.id == id }?.value

    /** The type that opens [uri]; never null: unknown URIs resolve to [DocumentType.UNAVAILABLE]. */
    fun resolve(uri: DocumentUri, associations: List<Association> = emptyList()): DocumentType =
        associated(uri, associations) ?: opener(uri, OpenerPriority.DEFAULT) ?: opener(uri, OpenerPriority.BUILTIN)
            ?: byScheme(uri) ?: DocumentType.UNAVAILABLE

    /**
     * Every type that can open [uri], the default first, for an "Open with" list. Contains
     * [DocumentType.UNAVAILABLE] only when nothing else can.
     */
    fun alternatives(uri: DocumentUri, associations: List<Association> = emptyList()): List<DocumentType> {
        val offered = openers.filter { it.value.matches(uri) }.mapNotNull { typeById(it.value.typeId) }
        val all = (listOf(resolve(uri, associations)) + offered + listOfNotNull(byScheme(uri))).distinctBy { it.id }
        return all.filter { it.id != DocumentType.UNAVAILABLE_ID }.ifEmpty { listOf(DocumentType.UNAVAILABLE) }
    }

    /** A user association beats every opener; one naming a type that is not registered is skipped. */
    private fun associated(uri: DocumentUri, associations: List<Association>): DocumentType? =
        associations.firstNotNullOfOrNull { a -> if (a.matches(uri)) typeById(a.typeId) else null }

    /** Earliest registration wins among openers of one priority, so resolution never depends on hash order. */
    private fun opener(uri: DocumentUri, priority: OpenerPriority): DocumentType? =
        openers.firstNotNullOfOrNull { e ->
            if (e.value.priority == priority && e.value.matches(uri)) typeById(e.value.typeId) else null
        }

    private fun byScheme(uri: DocumentUri): DocumentType? =
        types.map { it.value }.filter { it.pattern.matches(uri) }.maxByOrNull { it.pattern.specificity }

    companion object {
        val EMPTY = DocumentRegistry(emptyList(), emptyList())
    }
}
