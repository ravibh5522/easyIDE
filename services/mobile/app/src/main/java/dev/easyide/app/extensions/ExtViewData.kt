package dev.easyide.app.extensions

import dev.easyide.app.extensions.host.AppHostPort
import dev.easyide.app.extensions.host.ExtViewCalls
import dev.easyide.app.ui.shell.ext.ExtShell
import dev.easyide.app.ui.shell.ext.ExtViewHost
import dev.easyide.app.ui.shell.ext.ViewDataDriver
import dev.easyide.app.ui.shell.ext.ViewDataHub
import dev.easyide.app.ui.shell.ext.ViewEvents
import dev.easyide.extensions.ExtensionsRuntime
import dev.easyide.extensions.action.ExtensionLog
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.manifest.ExtensionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the extension views draw from and act through, wired once: the [hub] that holds their data, the [driver] that
 * fetches it while a view is on screen, and the [host] the surfaces read (events, context keys, log).
 */
class ExtViewData(
    runtime: ExtensionsRuntime,
    hostPort: AppHostPort,
    shell: StateFlow<ExtShell>,
    private val log: ExtensionLog,
    scope: CoroutineScope,
) {
    val hub = ViewDataHub()
    private val calls = ExtViewCalls(runtime, hostPort, log)

    val driver = ViewDataDriver(shell, hub, calls, ::warn, scope)

    val host = ExtViewHost(hub, ViewEvents(hub, calls), runtime.contextKeys.snapshot, ::warn)

    /** A WASM provider's `ui.setViewData`: merged into the view's data over its `state`, limited in rate and size. */
    fun provide(shell: ExtShell, viewId: String, update: kotlinx.serialization.json.JsonElement) {
        val initial = shell.view(viewId)?.body?.state ?: kotlinx.serialization.json.JsonObject(emptyMap())
        hub.provide(viewId, update, initial)
    }

    private fun warn(owner: String, message: String) = log.append(LogEntry(ExtensionId.parse(owner), LogLevel.WARN, message))
}
