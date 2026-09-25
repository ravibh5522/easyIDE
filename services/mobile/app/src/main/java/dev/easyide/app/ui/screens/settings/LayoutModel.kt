package dev.easyide.app.ui.screens.settings

import dev.easyide.app.ui.shell.ContainerRegistry
import dev.easyide.app.ui.shell.ContainerSpec
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.NavEnv
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.NavPrefs
import dev.easyide.app.ui.shell.NavRegistry
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ShellScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A navigation item as the Layout page lists it; [pack] names the extension that added it, null for built-ins. */
data class LayoutNavEntry(val id: String, val title: String, val scope: ShellScope, val pack: String?)

/** A container that can be moved between panels, with the placements it allows. */
data class LayoutContainerEntry(val id: String, val title: String, val placement: Placement, val allowed: List<Placement>, val pack: String?)

/**
 * What the shell offers for rearranging: the navigation items and containers it currently knows,
 * built-in and contributed. The shell owns the registries, so it hands them over; [core] is the
 * built-in set for a host that has no extensions.
 */
data class LayoutCatalog(val navigation: List<LayoutNavEntry>, val containers: List<LayoutContainerEntry>) {
    /** The extensions that added anything, by id: the packs whose screens the user can switch off as a whole. */
    val packs: List<String> get() = (navigation.mapNotNull { it.pack } + containers.mapNotNull { it.pack }).distinct().sorted()

    companion object {
        private val EVERYTHING = NavEnv(holds = { true }, resolves = { true })

        fun core(): LayoutCatalog = of(CoreShell.navigation(), CoreShell.containers())

        /** Everything [navigation] and [containers] hold, per scope, with the extension that added each. */
        fun of(navigation: NavRegistry, containers: ContainerRegistry): LayoutCatalog = LayoutCatalog(
            navigation = ShellScope.entries.flatMap { scope ->
                navigation.visible(scope, NavPrefs(), EVERYTHING).map { entry(it, scope, navigation.packOf(it.id)) }
            },
            containers = ShellScope.entries.flatMap { scope -> Placement.entries.flatMap { containers.inPlacement(it, scope) } }
                .distinctBy { it.id }.map { entry(it, containers.packOf(it.id)) },
        )

        fun entry(item: NavItem, scope: ShellScope, pack: String?) = LayoutNavEntry(item.id, item.title, scope, pack)

        fun entry(spec: ContainerSpec, pack: String?) = LayoutContainerEntry(spec.id, spec.title, spec.placement, spec.allowed.toList(), pack)
    }
}

/** Order, hide and pin edits on the id lists behind `shell.navigation.*`. */
object NavOrdering {

    /** The order the shell shows: the user's ids first (those that still exist), the rest in default order. */
    fun effective(defaultIds: List<String>, order: List<String>): List<String> =
        order.filter { it in defaultIds }.distinct().let { first -> first + defaultIds.filter { it !in first } }

    /** [effective] with [id] moved by [delta] places, held at the ends. */
    fun moved(effective: List<String>, id: String, delta: Int): List<String> {
        val from = effective.indexOf(id)
        if (from < 0) return effective
        val to = (from + delta).coerceIn(0, effective.lastIndex)
        return effective.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun toggled(ids: List<String>, id: String): List<String> = if (id in ids) ids - id else ids + id
}

/** Edits to the `shell.containers.placement` object. */
object PlacementEdit {

    /** [json] with [id] placed in [placement]; choosing the container's own placement removes the override. */
    fun with(json: JsonObject, id: String, placement: Placement, own: Placement): JsonObject {
        val rest = json.filterKeys { it != id }
        return JsonObject(if (placement == own) rest else rest + (id to JsonPrimitive(placement.wire)))
    }
}
