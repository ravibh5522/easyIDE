package dev.easyide.extensions.contrib

import dev.easyide.extensions.view.ViewDocument
import dev.easyide.extensions.view.ViewTemplate
import dev.easyide.extensions.whenclause.WhenExpr

/** Which shell scope shows a container or navigation item (`scope` in the manifest). */
enum class UiScope(val wire: String) {
    APP("app"), WORKSPACE("workspace"), BOTH("both");

    companion object {
        fun parse(wire: String): UiScope? = entries.firstOrNull { it.wire == wire }
    }
}

/** The panel a container fills: primary (`sidebar`), secondary, or the bottom `panel` (shell-model.md section 7). */
enum class ContainerPlacement(val wire: String) {
    SIDEBAR("sidebar"), SECONDARY_SIDEBAR("secondarySidebar"), PANEL("panel");

    companion object {
        fun parse(wire: String): ContainerPlacement? = entries.firstOrNull { it.wire == wire }
    }
}

/** What a navigation item does: reveal one of the pack's containers, or run a command. */
sealed interface NavigationTarget {
    data class Container(val id: String) : NavigationTarget
    data class Command(val id: String) : NavigationTarget
}

enum class BadgeKind(val wire: String) {
    COUNT("count"), DOT("dot");

    companion object {
        fun parse(wire: String): BadgeKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** The number or dot on a navigation item: the value at [path] in the data of [view] (`viewBadge`). */
data class BadgeBinding(val view: String, val path: String, val kind: BadgeKind)

/** A `navigation` item (extension-ui.md section 2.1). [order] is the pack's default; the shell raises it above the built-ins. */
data class NavigationContribution(
    val id: String, val title: String, val icon: CommandIcon, val target: NavigationTarget, val scope: UiScope,
    val order: Int, val `when`: WhenExpr?, val badge: BadgeBinding?,
)

/** The badge point spelled apart from its item: `viewBadge` names the navigation item it feeds. */
data class ViewBadgeContribution(val nav: String, val binding: BadgeBinding)

/**
 * Where a document's state comes from: [command] is called with `{uri, key}` and its result (JSON
 * object) becomes the view's data; [intervalSec] refreshes it while the document is visible.
 */
data class DocumentStateProvider(val command: String, val intervalSec: Int?)

/** A `documents` type: `ext://<extension id>/<type name>/<key>` renders [body] with the document's state as data. */
data class DocumentContribution(
    val type: String, val title: ViewTemplate, val icon: CommandIcon, val body: ViewDocument,
    val multiple: Boolean, val supportsSplit: Boolean, val state: DocumentStateProvider?,
)

/** How strongly an opener claims a file; `builtin` exists in the manifest vocabulary only to be refused. */
enum class OpenerPriorityName(val wire: String) {
    DEFAULT("default"), OPTION("option");

    companion object {
        fun parse(wire: String): OpenerPriorityName? = entries.firstOrNull { it.wire == wire }
    }
}

data class DocumentOpenerContribution(val glob: String, val type: String, val priority: OpenerPriorityName)

enum class SizeClassName(val wire: String) {
    COMPACT("compact"), MEDIUM("medium"), EXPANDED("expanded");

    companion object {
        fun parse(wire: String): SizeClassName? = entries.firstOrNull { it.wire == wire }
    }
}

data class StageSplit(val axis: String, val groups: Int)

/** A recommended arrangement, offered in the layout picker and never applied by itself. */
data class LayoutPresetContribution(
    val id: String, val title: String, val sizeClasses: Set<SizeClassName>,
    val containers: Map<ContainerPlacement, String>, val panels: Map<ContainerPlacement, Boolean>, val stage: StageSplit?,
)
