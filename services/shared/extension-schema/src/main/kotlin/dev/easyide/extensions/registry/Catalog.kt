package dev.easyide.extensions.registry

import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.Layer
import dev.easyide.extensions.manifest.SemVer

/** One extension in browse: its newest installable entry and every version the index lists. */
data class CatalogItem(val id: ExtensionId, val latestCompatible: IndexEntry?, val versions: List<SemVer>, val searchText: String)

data class CatalogFilter(
    val categories: Set<String> = emptySet(),
    val layers: Set<Layer> = emptySet(),
    /** Only extensions needing at most these capabilities (by capability id prefix). */
    val maxCapabilities: Set<String>? = null,
    val compatibleOnly: Boolean = true,
)

/**
 * Search and browse over verified indexes (registry-and-install.md sec 7): token-prefix match,
 * ranked exact id > display-name prefix > other; a linear scan, since an index is small.
 */
class Catalog(indexes: List<VerifiedIndex>, apiVersion: SemVer) {
    val items: List<CatalogItem> = indexes.flatMap { idx -> idx.entries.map { idx to it } }
        .groupBy { it.second.id }
        .map { (id, pairs) ->
            val entries = pairs.sortedByDescending { it.second.version }
            val latest = entries.firstOrNull { (idx, e) ->
                e.engines.contains(apiVersion) && idx.revocations.reason(e.id, e.version, e.signature.keyId) == null &&
                    (idx.minAppVersion == null || idx.minAppVersion <= apiVersion)
            }?.second
            val shown = latest ?: entries.first().second
            CatalogItem(
                id, latest, entries.map { it.second.version }.distinct(),
                listOf(id.value, shown.displayName.orEmpty(), shown.description.orEmpty(), shown.categories.joinToString(" ")).joinToString(" ").lowercase(),
            )
        }
        .sortedBy { it.id.value }

    fun search(query: String, filter: CatalogFilter = CatalogFilter()): List<CatalogItem> {
        // Query and text split the same way, so `acme.zig` matches the words `acme` and `zig`.
        val tokens = words(query.lowercase())
        return items.filter { item -> filter.matches(item) && words(item.searchText).let { w -> tokens.all { t -> w.any { it.startsWith(t) } } } }
            .sortedWith(compareBy({ rank(it, query.lowercase().trim()) }, { it.id.value }))
    }

    private fun words(text: String): List<String> = text.split(WORD_BREAK).filter { it.isNotEmpty() }

    private fun rank(item: CatalogItem, q: String): Int = when {
        q.isEmpty() -> 2
        item.id.value == q -> 0
        item.latestCompatible?.displayName?.lowercase()?.startsWith(q) == true -> 1
        else -> 2
    }

    private fun CatalogFilter.matches(item: CatalogItem): Boolean {
        val e = item.latestCompatible ?: return !compatibleOnly
        if (categories.isNotEmpty() && e.categories.none { it in categories }) return false
        if (layers.isNotEmpty() && e.layers.none { it in layers }) return false
        maxCapabilities?.let { allowed -> if (e.capabilities.any { c -> allowed.none { c == it || c.startsWith("$it(") } }) return false }
        return true
    }

    private companion object {
        val WORD_BREAK = Regex("[^a-z0-9]+")
    }
}
