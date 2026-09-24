package dev.easyide.app.ui.shell

import dev.easyide.app.ui.screens.workspace.layout.Stage

/** Where a container shows. [wire] is the manifest and settings spelling. */
enum class Placement(val wire: String, val stage: Stage) {
    SIDEBAR("sidebar", Stage.LEFT),
    SECONDARY_SIDEBAR("secondarySidebar", Stage.RIGHT),
    PANEL("panel", Stage.BOTTOM);

    companion object {
        fun ofWire(wire: String): Placement? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A switchable collection of views (shell-model.md section 3). [locations] limits where the user or
 * a preset may move it (a wide table can refuse the narrow right panel); the default placement is
 * always allowed.
 */
data class ContainerSpec(
    val id: String,
    val title: String,
    val icon: IconRef,
    val placement: Placement,
    val scope: ScopeFilter = ScopeFilter.WORKSPACE,
    val locations: Set<Placement> = Placement.entries.toSet(),
) {
    val allowed: Set<Placement> get() = locations + placement
}

/** The user's `shell.containers.placement` and `shell.containers.hidden`. */
data class ContainerPrefs(
    val placement: Map<String, Placement> = emptyMap(),
    val hidden: Set<String> = emptySet(),
)

/**
 * Containers contributed by core and extensions. Immutable; a change returns a new registry.
 * An extension id must be `<extension id>.<name>`; on a collision core wins and otherwise the first
 * registration keeps the id. Within a placement core containers come first, then extensions in
 * registration order.
 */
class ContainerRegistry private constructor(private val entries: List<Entry>) {
    private class Entry(val spec: ContainerSpec, val origin: Origin)

    fun register(spec: ContainerSpec, origin: Origin): Registered<ContainerRegistry> {
        val refusal = when {
            entries.any { it.spec.id == spec.id } -> RejectReason.DUPLICATE_ID
            !origin.owns(spec.id) -> RejectReason.NOT_NAMESPACED
            else -> null
        }
        if (refusal != null) return Registered(this, listOf(Rejection(spec.id, refusal)))
        return Registered(ContainerRegistry(entries + Entry(spec, origin)))
    }

    fun unregister(extensionId: String): ContainerRegistry {
        val gone = Origin.Extension(extensionId)
        return ContainerRegistry(entries.filter { it.origin != gone })
    }

    fun byId(id: String): ContainerSpec? = entries.firstOrNull { it.spec.id == id }?.spec

    /** The placement [spec] shows in: the user's override when it is allowed, else its own. */
    fun placementOf(spec: ContainerSpec, prefs: ContainerPrefs = ContainerPrefs()): Placement =
        prefs.placement[spec.id]?.takeIf { it in spec.allowed } ?: spec.placement

    /** Containers available in [placement] for [scope], in display order, without the ones the user hid. */
    fun inPlacement(placement: Placement, scope: ShellScope, prefs: ContainerPrefs = ContainerPrefs()): List<ContainerSpec> =
        entries.sortedBy { it.origin != Origin.Core }
            .map { it.spec }
            .filter { it.scope.includes(scope) && it.id !in prefs.hidden && placementOf(it, prefs) == placement }

    /**
     * What a panel shows: [wanted] when it is still available there, else the first container of that
     * placement (an uninstalled pack's container degrades to the default), else null.
     */
    fun active(placement: Placement, wanted: String?, scope: ShellScope, prefs: ContainerPrefs = ContainerPrefs()): ContainerSpec? {
        val available = inPlacement(placement, scope, prefs)
        return available.firstOrNull { it.id == wanted } ?: available.firstOrNull()
    }

    fun canMove(id: String, to: Placement): Boolean = byId(id)?.let { to in it.allowed } ?: false

    companion object {
        val EMPTY = ContainerRegistry(emptyList())
    }
}
