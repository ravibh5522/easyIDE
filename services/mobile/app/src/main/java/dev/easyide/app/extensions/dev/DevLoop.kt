package dev.easyide.app.extensions.dev

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.easyide.app.data.settings.AuthoringSettingsSchema
import dev.easyide.app.data.settings.SettingsStore
import dev.easyide.app.extensions.ExtensionLogRing
import dev.easyide.app.extensions.install.DiskExtensionInventory
import dev.easyide.app.extensions.install.LocalInstaller
import dev.easyide.extensions.settings.RuntimeScope
import dev.easyide.sandbox.files.ProjectFileWatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app end of `easyide-ext dev` (lld/cli.md sec 5.7), wired once by `ExtensionsContainer`:
 * the [DevInstaller], the adb inbox (`<external files>/dev-inbox`, fed by [DevReloadReceiver])
 * and, while `extensions.developerMode` is on and a project is open, a watcher on that
 * project's `.easyide/dev/` for `dev --local` requests.
 */
class DevLoop(
    private val context: Context,
    installer: LocalInstaller,
    inventory: DiskExtensionInventory,
    private val settingsStore: SettingsStore,
    log: ExtensionLogRing,
    private val projectRoot: (projectId: String) -> File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val main = Handler(Looper.getMainLooper())
    private val runtimeScope = MutableStateFlow(RuntimeScope.NONE)
    private val started = CompletableDeferred<Unit>()

    /** The open workspace (environment, project), or [RuntimeScope.NONE]. */
    val workspace: StateFlow<RuntimeScope> = runtimeScope.asStateFlow()

    val installer = DevInstaller(
        installer, { inventory.installed.value },
        developerMode = { settingsStore.snapshot.first()[AuthoringSettingsSchema.developerMode] },
        log = log::append,
        notify = { text -> main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() } },
        io = io,
    )

    /** Main thread only (ProjectFileWatcher is not thread-safe). */
    private var watcher: ProjectFileWatcher? = null
    private var watchedRoot: File? = null

    /** Call once staging has been cleared at startup, so no request stages into a dir being wiped. */
    fun start() {
        if (!started.complete(Unit)) return
        scope.launch {
            val on = settingsStore.snapshot.map { it[AuthoringSettingsSchema.developerMode] }.distinctUntilChanged()
            combine(on, runtimeScope) { enabled, s -> enabled to s }.distinctUntilChanged().collect { (enabled, s) ->
                installer.currentEnv = s.envId
                // adb creates the directory too, but then it may belong to the shell user.
                if (enabled) withContext(io) { inboxDir()?.mkdirs() }
                val root = s.projectId?.takeIf { enabled }?.let(projectRoot)
                withContext(Dispatchers.Main) { watch(root) }
                root?.let { scan(it) }
            }
        }
    }

    /** The workspace switched project or environment (called with `ExtensionsRuntime.setRuntimeScope`). */
    fun setScope(scope: RuntimeScope) { runtimeScope.value = scope }

    /** From [DevReloadReceiver]; [done] finishes the broadcast. */
    fun onReload(id: String?, done: () -> Unit) {
        scope.launch {
            try {
                started.await()
                installer.fromInbox(withContext(io) { inboxDir() }, id)
            } finally {
                done()
            }
        }
    }

    private fun inboxDir(): File? = context.getExternalFilesDir(null)?.let { File(it, DevReload.INBOX_DIR) }

    private fun watch(root: File?) {
        if (root == watchedRoot) return
        watcher?.stop()
        watcher = null
        watchedRoot = root
        if (root == null) return
        // The root and `.easyide/` are watched too, so the request dir is picked up once created.
        val w = ProjectFileWatcher(onChanged = {
            if (watchedRoot == root) {
                watcher?.watch(root, targets(root))
                scope.launch { scan(root) }
            }
        })
        watcher = w
        w.watch(root, targets(root))
    }

    private fun targets(root: File): Set<File> = setOf(File(root, ".easyide"), File(root, DevReload.REQUEST_DIR))

    private suspend fun scan(root: File) {
        val requests = withContext(io) { DevReload.requestFiles(File(root, DevReload.REQUEST_DIR)) }
        requests.forEach { installer.fromRequest(it, root) }
    }
}
