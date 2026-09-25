package dev.easyide.app.ui.shell.ext

import dev.easyide.app.ui.shell.ContainerRegistry
import dev.easyide.app.ui.shell.ContainerSpec
import dev.easyide.app.ui.shell.DocumentRegistry
import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.LayoutPreset
import dev.easyide.app.ui.shell.NavRegistry
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.Opener
import dev.easyide.app.ui.shell.Origin
import dev.easyide.app.ui.shell.Registered
import dev.easyide.app.ui.shell.Rejection
import dev.easyide.app.ui.shell.host.AppRegistries
import dev.easyide.app.ui.shell.nav.NavContribution
import dev.easyide.extensions.action.Action
import dev.easyide.extensions.contrib.BadgeKind
import dev.easyide.extensions.contrib.DocumentStateProvider
import dev.easyide.extensions.contrib.ViewDataKind
import dev.easyide.extensions.view.ViewDocument
import dev.easyide.extensions.view.ViewTemplate
import dev.easyide.extensions.whenclause.WhenExpr

/** A container an extension contributed, with the pack that owns it. */
data class ExtContainer(val extensionId: String, val spec: ContainerSpec)

/** A document type: [type] is the shell's entry, [body] the view it draws, [title] the tab title template over the document's data. */
data class ExtDocument(
    val extensionId: String, val type: DocumentType, val body: ViewDocument, val title: ViewTemplate, val state: DocumentStateProvider?,
)

data class ExtOpener(val extensionId: String, val opener: Opener)

data class ExtPreset(val extensionId: String, val preset: LayoutPreset)

/** A view of a container. [legacy] marks one a pack declared without a schema, drawn from `viewData` as a plain list. */
data class ExtView(val extensionId: String, val id: String, val name: String, val body: ViewDocument, val condition: WhenExpr?, val legacy: Boolean)

/** What feeds a navigation item's badge: the value at [path] of the data of [view]. */
data class ExtBadge(val navId: String, val view: String, val path: String, val kind: BadgeKind)

/** `easyide.viewData`: how a view's data is fetched while it is on screen. */
data class ExtDataSource(val extensionId: String, val viewId: String, val kind: ViewDataKind, val from: Action, val intervalSec: Int?)

/**
 * Everything extensions contribute to the shell, in the shell's own terms and already filtered by what may show
 * (enabled, safe mode, environment, the `ui.contribute` grant and the user's per-pack switch). Built by
 * `ShellContributions` from the extension registry, folded into the shell registries by [ExtRegistries].
 * [views] is keyed by shell container id, in declaration order.
 */
data class ExtShell(
    val navigation: List<NavContribution> = emptyList(),
    val containers: List<ExtContainer> = emptyList(),
    val documents: List<ExtDocument> = emptyList(),
    val openers: List<ExtOpener> = emptyList(),
    val presets: List<ExtPreset> = emptyList(),
    val views: Map<String, List<ExtView>> = emptyMap(),
    val badges: List<ExtBadge> = emptyList(),
    val dataSources: List<ExtDataSource> = emptyList(),
) {
    val commands: Set<String> get() = navigation.mapNotNullTo(HashSet()) { (it.item.target as? NavTarget.Command)?.id }

    fun packOfContainer(id: String): String? = containers.firstOrNull { it.spec.id == id }?.extensionId

    fun document(typeId: String): ExtDocument? = documents.firstOrNull { it.type.id == typeId }

    fun view(id: String): ExtView? = views.values.flatten().firstOrNull { it.id == id }

    val presetList: List<LayoutPreset> get() = presets.map { it.preset }

    companion object { val EMPTY = ExtShell() }
}

/** The shell registries with extensions folded in, and what the engine refused (logged by the host, never fatal). */
class FoldedRegistries(val registries: AppRegistries, val rejections: List<Rejection>)


/**
 * Registers an [ExtShell]'s containers, document types and openers into the pure engine registries (navigation goes
 * through [navigation], because the shell view model folds the items its [NavItemSource] supplies). The engine applies its own rules (namespacing, caps,
 * core wins, extension nav order raised above the built-ins), so an extension can never displace or remove a core
 * item; this only feeds it and collects the refusals.
 */
object ExtRegistries {

    fun fold(base: AppRegistries, ext: ExtShell): FoldedRegistries {
        val rejections = ArrayList<Rejection>()
        var containers: ContainerRegistry = base.containers
        ext.containers.forEach { c -> containers = containers.register(c.spec, Origin.Extension(c.extensionId)).also { rejections += it.rejections }.registry }
        var documents: DocumentRegistry = base.documents
        ext.documents.forEach { d -> documents = documents.register(d.type, Origin.Extension(d.extensionId)).also { rejections += it.rejections }.registry }
        ext.openers.forEach { o -> documents = documents.registerOpener(o.opener, Origin.Extension(o.extensionId)).also { rejections += it.rejections }.registry }
        return FoldedRegistries(AppRegistries(documents, containers, base.navigation), rejections)
    }

    /** [base] with the extension navigation items registered; the engine drops the ones it refuses and says which. */
    fun navigation(base: NavRegistry, contributions: List<NavContribution>): Registered<NavRegistry> {
        val rejections = ArrayList<Rejection>()
        val registry = contributions.fold(base) { r, c -> r.register(c.item, Origin.Extension(c.extensionId)).also { rejections += it.rejections }.registry }
        return Registered(registry, rejections)
    }
}
