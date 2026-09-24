package dev.easyide.sandbox.extensions

import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.backend.GuestBind
import dev.easyide.sandbox.backend.GuestBindSource

/**
 * Makes each installed ENVIRONMENT extension's active version visible at
 * `/opt/easyide/extensions/<id>` in every process launched into that
 * environment (lld/registry-and-install.md sec 11.2).
 *
 * The `current` link is resolved here, on the host, and the version dir itself
 * is bound: a later flip then affects only processes started after it, which is
 * the documented contract, instead of changing files under a running server.
 *
 * Anything on disk that does not look exactly like an install the store wrote
 * is skipped rather than bound (see [ExtensionInstalls]).
 *
 * @param isEnabled user/system enablement, owned by the extensions layer
 *   (the app passes the runtime's enabled set). The default binds every
 *   installed extension.
 */
class EnvironmentExtensionBinds(
    private val paths: SandboxPaths,
    private val isEnabled: (environmentId: String, id: ExtensionId) -> Boolean = { _, _ -> true },
) : GuestBindSource {

    override fun bindsFor(environmentId: String): List<GuestBind> =
        ExtensionInstalls.activeIn(paths, paths.environmentExtensionsDir(environmentId))
            .filter { isEnabled(environmentId, it.id) }
            .map { GuestBind(it.versionDir, paths.guestExtensionPath(it.id)) }
}
