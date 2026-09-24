package dev.easyide.extensions.contrib

import dev.easyide.extensions.manifest.ExtensionId

/** Who contributed something. Targets in `:app` carry only this tag (extension-runtime.md sec 7.2). */
sealed interface Owner {
    data object BuiltIn : Owner { override fun toString() = "builtin" }
    data class Ext(val id: ExtensionId) : Owner { override fun toString() = id.value }
}

/**
 * Stable identity of one contribution, used by `workbench.contributions.hidden/order` and
 * the contribution inspector. Text form `<kind>:<location>:<id>` or `<kind>:<id>`, e.g.
 * `menu:editor/title:python.runFile`, `view:python.venvs`.
 */
data class ContributionRef(val kind: Kind, val location: String?, val id: String) {

    enum class Kind(val tag: String) {
        COMMAND("command"), MENU("menu"), KEYBINDING("keybinding"), CONFIGURATION("configuration"),
        LANGUAGE("language"), GRAMMAR("grammar"), LANGUAGE_CONFIGURATION("languageConfiguration"),
        SNIPPET("snippet"), THEME("theme"), ICON_THEME("iconTheme"), VIEW_CONTAINER("viewContainer"),
        VIEW("view"), VIEW_WELCOME("viewWelcome"), TASK_DEFINITION("taskDefinition"),
        PROBLEM_MATCHER("problemMatcher"), WALKTHROUGH("walkthrough"), STAGE("stage"),
        STATUS_BAR("statusBar"), KEY_ROW("keyRow"), SERVER("server"), SANDBOX("sandbox"), VIEW_DATA("viewData"),
        NAVIGATION("navigation"), VIEW_BADGE("viewBadge"), DOCUMENT("document"), DOCUMENT_OPENER("documentOpener"),
        LAYOUT_PRESET("layoutPreset");

        companion object {
            fun parse(tag: String): Kind? = entries.firstOrNull { it.tag == tag }
        }
    }

    override fun toString(): String = if (location == null) "${kind.tag}:$id" else "${kind.tag}:$location:$id"

    companion object {
        /** Kinds whose refs carry a location (the menu id); `menu:<menuId>:<command>`. */
        private val LOCATED = setOf(Kind.MENU)

        fun parse(text: String): ContributionRef? {
            val kind = Kind.parse(text.substringBefore(':')) ?: return null
            val rest = text.substringAfter(':', "")
            if (rest.isEmpty()) return null
            if (kind !in LOCATED) return ContributionRef(kind, null, rest)
            // Menu ids contain '/', command ids may contain ':'; the menu id never does.
            val colon = rest.indexOf(':')
            if (colon <= 0 || colon == rest.length - 1) return null
            return ContributionRef(kind, rest.substring(0, colon), rest.substring(colon + 1))
        }
    }
}
