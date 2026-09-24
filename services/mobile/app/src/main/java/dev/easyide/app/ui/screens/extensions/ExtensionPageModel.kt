package dev.easyide.app.ui.screens.extensions

import androidx.annotation.StringRes
import dev.easyide.app.R
import dev.easyide.extensions.contrib.ContributionRef.Kind

/** One declared capability and whether the user's approval currently covers it. */
data class CapabilityLine(val id: String, val granted: Boolean, val revocable: Boolean)

/**
 * Declared versus granted (an extension is granted what it declares AND the user approved).
 * Built-ins are trusted with what they declare, so every line is granted and none can be
 * revoked; approvals for something no longer declared are not shown, they grant nothing.
 */
internal fun capabilityLines(declared: Set<String>, approved: Set<String>, builtIn: Boolean): List<CapabilityLine> =
    declared.sorted().map { id ->
        val granted = builtIn || id in approved
        CapabilityLine(id, granted, revocable = granted && !builtIn)
    }

/** The kinds of contribution a person recognises, in the order the Contributions tab lists them. */
enum class ContributionGroup(@StringRes val title: Int, val kinds: Set<Kind>) {
    Languages(R.string.extui_group_languages, setOf(Kind.LANGUAGE, Kind.GRAMMAR, Kind.LANGUAGE_CONFIGURATION, Kind.SNIPPET)),
    Servers(R.string.extui_group_servers, setOf(Kind.SERVER)),
    Commands(R.string.extui_group_commands, setOf(Kind.COMMAND, Kind.MENU, Kind.KEYBINDING, Kind.TASK_DEFINITION, Kind.PROBLEM_MATCHER)),
    Views(R.string.extui_group_views, setOf(Kind.VIEW_CONTAINER, Kind.VIEW, Kind.VIEW_WELCOME, Kind.VIEW_DATA, Kind.STAGE, Kind.STATUS_BAR, Kind.KEY_ROW, Kind.WALKTHROUGH)),
    Screens(R.string.extui_group_screens, setOf(Kind.NAVIGATION, Kind.VIEW_BADGE, Kind.DOCUMENT, Kind.DOCUMENT_OPENER, Kind.LAYOUT_PRESET)),
    Appearance(R.string.extui_group_appearance, setOf(Kind.THEME, Kind.ICON_THEME)),
    Other(R.string.extui_group_other, setOf(Kind.CONFIGURATION, Kind.SANDBOX)),
}

/** The group of a contribution ref such as `menu:editor/title:python.run`; null for a tag this app does not know. */
internal fun groupOf(ref: String): ContributionGroup? {
    val kind = Kind.parse(ref.substringBefore(':')) ?: return null
    return ContributionGroup.entries.firstOrNull { kind in it.kinds }
}

/** Lines per group, non-empty groups only, in group order; a ref of an unknown kind is listed under Other. */
internal fun groupContributions(lines: List<InspectorLine>): List<Pair<ContributionGroup, List<InspectorLine>>> {
    val byGroup = lines.groupBy { groupOf(it.ref) ?: ContributionGroup.Other }
    return ContributionGroup.entries.mapNotNull { g -> byGroup[g]?.let { g to it } }
}

/** Whether the extension adds settings, which is what the page's Settings link is for. */
internal fun contributesSettings(lines: List<InspectorLine>): Boolean =
    lines.any { it.ref.substringBefore(':') == Kind.CONFIGURATION.tag }

/** Where the extension's settings live; the shell resolves it like any `easyide://` document. */
internal fun settingsTarget(id: String): String = "easyide://settings/extensions#$id"
