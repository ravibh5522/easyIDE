package dev.easyide.app.extensions.registry

import dev.easyide.app.extensions.install.ExtensionState
import dev.easyide.app.extensions.install.InstallEntry
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.SemVer
import dev.easyide.extensions.manifest.Source
import dev.easyide.extensions.registry.Catalog
import dev.easyide.extensions.registry.IndexEntry
import dev.easyide.extensions.registry.Revocations

/** Pure revocation and update rules (registry-and-install.md sec 6, 10). */
object RegistryRules {

    /**
     * Why [id] [version] (signed by [signedBy] from [registryId], both null for a local
     * install) is revoked by [revocations] of [registryId]: a revoked version range applies
     * whatever the source, a revoked key only to what that registry's key signed.
     */
    fun reason(revocations: Revocations, registryId: String, id: String, version: String, origin: Pair<String, String>?): String? {
        val eid = ExtensionId.parse(id) ?: return null
        val v = SemVer.parse(version) ?: return null
        val signedBy = origin?.takeIf { it.first == registryId }?.second
        return revocations.reason(eid, v, signedBy)
    }

    /** `{"id@version": reason}` for the current versions of [installs] that [revocations] of [registryId] revokes. */
    fun revokedInstalls(installs: List<InstallEntry>, registryId: String, revocations: Revocations): Map<String, String> =
        installs.filter { it.source != Source.BUILT_IN }.mapNotNull { e ->
            reason(revocations, registryId, e.id, e.version, e.origin?.let { it.registryId to it.signedBy })
                ?.let { ExtensionState.revokedKey(e.id, e.version) to it }
        }.toMap()

    /**
     * Whether [id] [version] must never become current (install, cache, rollback): recorded
     * as revoked at a refresh, or revoked by any verified index given its recorded signer.
     */
    fun isRevoked(state: ExtensionState, revocations: Map<String, Revocations>, id: String, version: String): Boolean {
        if (state.revocationReason(id, version) != null) return true
        val origins = state.installs.filter { it.id == id }.flatMap { e ->
            listOfNotNull(e.origin?.takeIf { e.version == version }, e.previous?.takeIf { it.version == version }?.origin)
        }.map { it.registryId to it.signedBy }
        return revocations.any { (registryId, rev) ->
            (origins.ifEmpty { listOf(null) }).any { o -> reason(rev, registryId, id, version, o) != null }
        }
    }

    /**
     * Update available (sec 10): for each registry install, the catalog's newest compatible,
     * unrevoked version when it is newer than the installed one. Notify only; never installs.
     */
    fun updates(installed: List<Pair<String, String>>, catalog: Catalog): Map<String, IndexEntry> {
        val byId = catalog.items.associateBy { it.id.value }
        return installed.mapNotNull { (id, version) ->
            val latest = byId[id]?.latestCompatible ?: return@mapNotNull null
            val have = SemVer.parse(version) ?: return@mapNotNull null
            if (latest.version > have) id to latest else null
        }.toMap()
    }
}
