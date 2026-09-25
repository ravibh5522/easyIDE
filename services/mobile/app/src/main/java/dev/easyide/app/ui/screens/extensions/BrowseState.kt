package dev.easyide.app.ui.screens.extensions

import dev.easyide.app.extensions.registry.RegistryError
import dev.easyide.app.extensions.registry.RegistryRecords
import dev.easyide.app.extensions.registry.RegistryRules
import dev.easyide.app.extensions.registry.RegistryView
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.manifest.Source
import dev.easyide.extensions.registry.CatalogFilter
import dev.easyide.extensions.registry.IndexEntry
import java.time.Instant

/** One configured registry as the Browse tab heads it: index age, staleness, last error. */
data class RegistryLine(val id: String, val ageMinutes: Long?, val stale: Boolean, val error: RegistryError?, val refreshing: Boolean)

/** One search result: the newest compatible, unrevoked entry (null when none is) and its install state. */
data class BrowseItem(
    val id: String,
    val displayName: String,
    val description: String?,
    val registryId: String?,
    val entry: IndexEntry?,
    val versions: List<String>,
    val installedVersion: String?,
    /** [entry] is newer than [installedVersion] (registry installs only): notify, never install. */
    val updateAvailable: Boolean,
    /** keyId pinned for this publisher in [registryId], if any. */
    val pinnedKey: String?,
)

/** What a Browse row offers: the install button's label and whether it can be pressed at all. */
enum class BrowseAction { Install, Update, Installed, Unavailable }

/** No compatible signed entry or no registry to fetch it from means nothing can be installed. */
internal fun browseAction(entryVersion: String?, registryId: String?, installedVersion: String?): BrowseAction = when {
    entryVersion == null || registryId == null -> BrowseAction.Unavailable
    installedVersion == entryVersion -> BrowseAction.Installed
    installedVersion != null -> BrowseAction.Update
    else -> BrowseAction.Install
}

fun BrowseItem.action(): BrowseAction = browseAction(entry?.version?.toString(), registryId, installedVersion)

data class BrowseUiState(
    val configured: Boolean = false,
    val problems: List<String> = emptyList(),
    val registries: List<RegistryLine> = emptyList(),
    val items: List<BrowseItem> = emptyList(),
    val query: String = "",
    /** id -> newer version, for registry installs (badges on installed rows and the tab). */
    val updates: Map<String, String> = emptyMap(),
    /** "id@version" -> why that installed version is revoked. */
    val revoked: Map<String, String> = emptyMap(),
)

/** Pure mapping from the registry view to the Browse tab (JVM-testable). */
object BrowseState {
    fun build(view: RegistryView, records: RegistryRecords, query: String, installed: List<InstalledPackage>, now: Instant): BrowseUiState {
        val installedVersions = installed.filter { it.source != Source.BUILT_IN }
            .associate { (it.directory.parentFile?.name.orEmpty()) to it.directory.name }
        val registryInstalls = installed.filter { it.source == Source.REGISTRY }
            .map { it.directory.parentFile?.name.orEmpty() to it.directory.name }
        val updates = RegistryRules.updates(registryInstalls, view.catalog)
        val items = view.catalog.search(query, CatalogFilter(compatibleOnly = false)).map { item ->
            val e = item.latestCompatible
            val registryId = e?.let(view::sourceOf) ?: view.sources.entries.firstOrNull { it.key.first == item.id }?.value
            BrowseItem(
                id = item.id.value,
                displayName = e?.displayName ?: item.id.value,
                description = e?.description,
                registryId = registryId,
                entry = e,
                versions = item.versions.map { it.toString() },
                installedVersion = installedVersions[item.id.value],
                updateAvailable = item.id.value in updates,
                pinnedKey = registryId?.let { records.pins[it]?.get(item.id.publisher) },
            )
        }
        return BrowseUiState(
            configured = view.configured,
            problems = view.configProblems,
            registries = view.statuses.map { s ->
                RegistryLine(s.config.id, s.age(now)?.toMinutes(), s.stale(now), s.error, s.refreshing)
            },
            items = items,
            query = query,
            updates = updates.mapValues { it.value.version.toString() },
            revoked = records.revoked,
        )
    }
}
