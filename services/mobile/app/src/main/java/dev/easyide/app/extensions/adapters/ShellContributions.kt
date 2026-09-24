package dev.easyide.app.extensions.adapters

import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement
import dev.easyide.app.ui.shell.ContainerSpec
import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.shell.LayoutPreset
import dev.easyide.app.ui.shell.NavBadge
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.Opener
import dev.easyide.app.ui.shell.OpenerPriority
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ScopeFilter
import dev.easyide.app.ui.shell.SplitAxis
import dev.easyide.app.ui.shell.UriPattern
import dev.easyide.app.ui.shell.ext.ExtBadge
import dev.easyide.app.ui.shell.ext.ExtContainer
import dev.easyide.app.ui.shell.ext.ExtDataSource
import dev.easyide.app.ui.shell.ext.ExtDocument
import dev.easyide.app.ui.shell.ext.ExtOpener
import dev.easyide.app.ui.shell.ext.ExtPreset
import dev.easyide.app.ui.shell.ext.ExtShell
import dev.easyide.app.ui.shell.ext.ExtView
import dev.easyide.app.ui.shell.nav.NavContribution
import dev.easyide.extensions.contrib.CommandIcon
import dev.easyide.extensions.contrib.ContainerPlacement
import dev.easyide.extensions.contrib.ContributionSnapshot
import dev.easyide.extensions.contrib.NavigationContribution
import dev.easyide.extensions.contrib.NavigationTarget
import dev.easyide.extensions.contrib.OpenerPriorityName
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.SizeClassName
import dev.easyide.extensions.contrib.UiScope
import dev.easyide.extensions.contrib.ViewContainerContribution
import dev.easyide.extensions.contrib.ViewContainerLocation
import dev.easyide.extensions.view.ViewLimits
import dev.easyide.extensions.whenclause.WhenParser

/**
 * The extension registry's shell points, translated into what the shell engine takes (extension-ui.md section 8
 * item 4): containers, navigation items, document types, openers, presets, the views of each container and the
 * data sources that fill them. Pure: the snapshot already reflects enablement, safe mode, environment scope and the
 * `ui.contribute` grant; [off] is the user's per-pack switch (`shell.extensions.contribute`) for UI contributions.
 *
 * Ids are made `<extension id>.<name>` here, which the engine requires. A bare `activitybar` container (the shape
 * older packs use) becomes a sidebar container plus a navigation item, and a view without a schema shows the pack's
 * `viewData` as a plain list.
 */
object ShellContributions {

    fun of(snapshot: ContributionSnapshot, off: Set<String> = emptySet()): ExtShell {
        fun <T> Iterable<Owned<T>>.on() = filter { (it.owner as? Owner.Ext)?.id?.value.let { id -> id != null && id !in off } }
        val containers = snapshot.viewContainers.on().map { o -> ExtContainer(ext(o), container(o)) }
        val containerIds = containers.mapTo(HashSet()) { it.spec.id }
        val explicit = snapshot.navigation.on().map { o -> NavContribution(ext(o), navItem(o, snapshot)) }
        val implicit = snapshot.viewContainers.on().filter { it.value.location == ViewContainerLocation.ACTIVITY_BAR }
            .filter { o -> explicit.none { (it.item.target as? NavTarget.Container)?.id == shellId(ext(o), o.value.id) } }
            .mapIndexed { i, o -> NavContribution(ext(o), legacyNav(o, i)) }
        val data = snapshot.viewData.on()
        return ExtShell(
            navigation = explicit + implicit,
            containers = containers,
            documents = snapshot.documents.on().map { o -> document(o) },
            openers = snapshot.documentOpeners.on().map { o ->
                ExtOpener(ext(o), Opener(o.value.glob, o.value.type, if (o.value.priority == OpenerPriorityName.DEFAULT) OpenerPriority.DEFAULT else OpenerPriority.OPTION))
            },
            presets = snapshot.layoutPresets.on().map { o -> ExtPreset(ext(o), preset(o, snapshot)) },
            views = snapshot.views.on().mapNotNull { o -> view(o, containerIds, data.any { it.value.viewId == o.value.id }) }.groupBy({ it.first }, { it.second }),
            badges = snapshot.navigation.on().mapNotNull { o -> badgeOf(o, snapshot)?.let { ExtBadge(o.value.id, it.view, it.path, it.kind) } },
            dataSources = data.map { o -> ExtDataSource(ext(o), o.value.viewId, o.value.kind, o.value.from, o.value.intervalSec) },
        )
    }

    private fun ext(o: Owned<*>): String = (o.owner as Owner.Ext).id.value

    /** `<extension id>.<name>`: an id that already is, stays; a VS Code style id (`docker.main`) is prefixed. */
    fun shellId(extensionId: String, id: String): String = if (id.startsWith("$extensionId.")) id else "$extensionId.$id"

    fun icon(icon: CommandIcon): IconRef = when (icon) {
        is CommandIcon.Token -> IconRef(icon.name)
        is CommandIcon.Svg -> IconRef(EXT_ICON_PREFIX + icon.file.hostPath)
    }

    private fun scope(s: UiScope) = when (s) {
        UiScope.APP -> ScopeFilter.APP
        UiScope.WORKSPACE -> ScopeFilter.WORKSPACE
        UiScope.BOTH -> ScopeFilter.BOTH
    }

    private fun placement(p: ContainerPlacement) = when (p) {
        ContainerPlacement.SIDEBAR -> Placement.SIDEBAR
        ContainerPlacement.SECONDARY_SIDEBAR -> Placement.SECONDARY_SIDEBAR
        ContainerPlacement.PANEL -> Placement.PANEL
    }

    private fun container(o: Owned<ViewContainerContribution>): ContainerSpec {
        val c = o.value
        val allowed = c.locations.map(::placement).toSet().ifEmpty { Placement.entries.toSet() }
        return ContainerSpec(shellId(ext(o), c.id), c.title, icon(c.icon), placement(c.location.placement), scope(c.scope), allowed)
    }

    private fun navItem(o: Owned<NavigationContribution>, snapshot: ContributionSnapshot): NavItem {
        val n = o.value
        val target = when (val t = n.target) {
            is NavigationTarget.Container -> NavTarget.Container(shellId(ext(o), t.id))
            is NavigationTarget.Command -> NavTarget.Command(t.id)
        }
        val badge = badgeOf(o, snapshot)
        return NavItem(n.id, n.title, icon(n.icon), target, n.order, scope(n.scope), n.`when`?.let(WhenParser::normalize), badge?.let { NavBadge(it.view, it.path) })
    }

    /** The item's own `badge`, else the pack's `viewBadge` entry naming it. */
    private fun badgeOf(o: Owned<NavigationContribution>, snapshot: ContributionSnapshot) =
        o.value.badge ?: snapshot.viewBadges.firstOrNull { it.value.nav == o.value.id && it.owner == o.owner }?.value?.binding

    /** A bare `activitybar` container's own navigation item: its title and icon, after the pack's explicit items. */
    private fun legacyNav(o: Owned<ViewContainerContribution>, index: Int): NavItem {
        val c = o.value
        val id = shellId(ext(o), c.id)
        return NavItem("$id.nav", c.title.take(ViewLimits.NAV_TITLE_MAX), icon(c.icon), NavTarget.Container(id), LEGACY_NAV_ORDER + index, scope(c.scope))
    }

    private fun document(o: Owned<dev.easyide.extensions.contrib.DocumentContribution>): ExtDocument {
        val d = o.value
        val type = DocumentType(
            id = d.type,
            pattern = requireNotNull(UriPattern.forExtensionType(d.type)) { "a validated document type is <extension id>/<name>" },
            title = { it.segments.lastOrNull() ?: d.type },
            icon = { icon(d.icon) },
            multiple = d.multiple,
            supportsSplit = d.supportsSplit,
        )
        return ExtDocument(ext(o), type, d.body, d.title, d.state)
    }

    private fun preset(o: Owned<dev.easyide.extensions.contrib.LayoutPresetContribution>, snapshot: ContributionSnapshot): LayoutPreset {
        val p = o.value
        val own = snapshot.viewContainers.filter { it.owner == o.owner }.associate { it.value.id to shellId(ext(o), it.value.id) }
        return LayoutPreset(
            id = p.id,
            title = p.title,
            containers = p.containers.mapKeys { placement(it.key) }.mapValues { (_, id) -> own[id] ?: id },
            open = p.panels.filterValues { it }.keys.mapTo(HashSet(), ::placement),
            groups = p.stage?.groups ?: 1,
            axis = if (p.stage?.axis == "column") SplitAxis.COLUMN else SplitAxis.ROW,
            arrangements = p.sizeClasses.flatMapTo(HashSet()) { arrangements(it) },
        )
    }

    private fun arrangements(s: SizeClassName): Set<PaneArrangement> = when (s) {
        SizeClassName.COMPACT -> setOf(PaneArrangement.SINGLE_PANE)
        SizeClassName.MEDIUM -> setOf(PaneArrangement.ONE_SIDE)
        SizeClassName.EXPANDED -> setOf(PaneArrangement.FULL)
    }

    /** A view under one of the pack's own containers; a view placed in a core container has nowhere to draw and is left out. */
    private fun view(o: Owned<dev.easyide.extensions.contrib.ViewContribution>, containers: Set<String>, hasData: Boolean): Pair<String, ExtView>? {
        val v = o.value
        val containerId = shellId(ext(o), v.containerId)
        if (containerId !in containers) return null
        val body = v.schema ?: LegacyView.document(v.id, hasData)
        return containerId to ExtView(ext(o), v.id, v.name, body, v.`when`, legacy = v.schema == null)
    }

    /** Marks an icon reference that names a file in a pack instead of a glyph of the icon set; the rest is the host path. */
    const val EXT_ICON_PREFIX = "ext:"

    /** Nav items synthesized from bare activity bar containers sort after the pack's declared ones. */
    private const val LEGACY_NAV_ORDER = 900
}
