package dev.easyide.app.ui.screens.extensions

import dev.easyide.app.extensions.TimedLogEntry
import dev.easyide.app.ui.kit.Tone
import dev.easyide.extensions.host.ActivationState
import dev.easyide.extensions.host.DisabledReason
import dev.easyide.extensions.manifest.Source

/**
 * The state tags a list row can carry, most urgent first. The wording is a string resource
 * (identity.md 5: caps are for status tags only), the tone is what tells the states apart.
 */
enum class RowTag(val tone: Tone) {
    Invalid(Tone.Danger),
    Revoked(Tone.Danger),
    Crashed(Tone.Danger),
    Failed(Tone.Danger),
    NeedsApproval(Tone.Warning),
    Disabled(Tone.Neutral),
    Update(Tone.Accent),
}

/** Enough state tags to explain a row without turning it into a status bar. */
private const val MAX_STATE_TAGS = 2

/** One row of the Installed tab, reduced to what the row shows. */
data class ExtensionListItem(
    val key: String,
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val source: Source,
    /** A pack that failed validation has no toggle: there is nothing to enable. */
    val toggleable: Boolean,
    val enabled: Boolean,
    val tags: List<RowTag>,
)

/** Installed rows grouped for the list: what the user manages first, then what ships with the app. */
data class ExtensionGroups(val installed: List<ExtensionListItem>, val builtIn: List<ExtensionListItem>) {
    val isEmpty: Boolean get() = installed.isEmpty() && builtIn.isEmpty()
}

/** The tags of one row, urgent first and capped; [updateAvailable] is a registry install with a newer version. */
internal fun rowTags(
    invalid: Boolean,
    reason: DisabledReason?,
    activation: ActivationState?,
    revokedByRegistry: Boolean,
    updateAvailable: Boolean,
): List<RowTag> {
    val tags = LinkedHashSet<RowTag>()
    if (invalid) tags += RowTag.Invalid
    if (revokedByRegistry || reason == DisabledReason.REVOKED) tags += RowTag.Revoked
    if (reason == DisabledReason.CRASH_DISABLED || activation == ActivationState.CRASHED || activation == ActivationState.CRASH_DISABLED) tags += RowTag.Crashed
    if (activation == ActivationState.FAILED) tags += RowTag.Failed
    if (reason == DisabledReason.NEEDS_APPROVAL) tags += RowTag.NeedsApproval
    if (reason in QUIET_REASONS) tags += RowTag.Disabled
    if (updateAvailable) tags += RowTag.Update
    return tags.take(MAX_STATE_TAGS)
}

/** Reasons that switch a pack off without anything being wrong with it. */
private val QUIET_REASONS = setOf(
    DisabledReason.USER_DISABLED, DisabledReason.EXTENSIONS_OFF, DisabledReason.SAFE_MODE,
    DisabledReason.NOT_IN_PROFILE, DisabledReason.OTHER_ENVIRONMENT,
)

/** [updateTo] is the newer registry version, if any; [revokedReason] why the registry revoked the installed one. */
internal fun ExtensionRow.toItem(updateTo: String?, revokedReason: String?): ExtensionListItem {
    val d = loaded?.descriptor
    return ExtensionListItem(
        key = key,
        id = id,
        name = d?.displayName ?: id,
        version = d?.version?.toString() ?: pkg.directory.name,
        description = d?.description,
        source = pkg.source,
        toggleable = d != null,
        enabled = userEnabled,
        tags = rowTags(problem != null, disabledReason, activation, revokedReason != null, updateTo != null && pkg.source == Source.REGISTRY),
    )
}

/** Every whitespace-separated word of [query] must appear in the id, name or description, ignoring case. */
internal fun filterItems(items: List<ExtensionListItem>, query: String): List<ExtensionListItem> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return items
    return items.filter { item ->
        val haystack = listOfNotNull(item.id, item.name, item.description).joinToString(" ").lowercase()
        words.all { it in haystack }
    }
}

internal fun groupItems(items: List<ExtensionListItem>): ExtensionGroups {
    val (builtIn, installed) = items.partition { it.source == Source.BUILT_IN }
    return ExtensionGroups(installed, builtIn)
}

/** Rows that need the person before they can work: the panel says so in a banner above the list. */
data class Attention(val needsApproval: List<ExtensionListItem>, val revoked: List<ExtensionListItem>)

internal fun attention(items: List<ExtensionListItem>): Attention = Attention(
    needsApproval = items.filter { RowTag.NeedsApproval in it.tags },
    revoked = items.filter { RowTag.Revoked in it.tags },
)

internal fun List<ExtensionRow>.byId(id: String): ExtensionRow? = firstOrNull { it.id == id }

/** The Extension Log lines written by [id], newest first as the ring keeps them. */
internal fun logFor(log: List<TimedLogEntry>, id: String): List<TimedLogEntry> =
    log.filter { it.entry.extensionId?.value == id }

/** The display name of extension [id] (its id while it is not loaded), or null when it is not installed. */
internal fun ExtensionsUiState.extensionName(id: String?): String? =
    rows.byId(id ?: return null)?.let { it.loaded?.descriptor?.displayName ?: it.id }
