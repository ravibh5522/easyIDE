package dev.easyide.app.lsp

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import dev.easyide.app.R
import dev.easyide.app.data.settings.SafeModeReason
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsQuery
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.SettingsStore
import dev.easyide.app.lsp.servers.ServerRegistry
import dev.easyide.app.ui.screens.workspace.syntax.SemanticRules
import dev.easyide.lsp.client.LspClient
import dev.easyide.lsp.manager.LanguageServerManager
import dev.easyide.lsp.manager.ManagerDeps
import dev.easyide.lsp.manager.MemoryPressure
import dev.easyide.lsp.protocol.ClientUi
import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.protocol.Milestone
import dev.easyide.lsp.protocol.UiLayer
import dev.easyide.lsp.session.ClientInfo
import dev.easyide.lsp.session.Clock
import dev.easyide.lsp.session.ProjectLocator
import dev.easyide.sandbox.ProjectManager
import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.shell.ServerProcessFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The language-server half of the composition root: the one [LanguageServerManager] and
 * [LspClient] of the process, with every `:lsp` port implemented over the sandbox, settings
 * and the workspace (hld.md sec 4). Application-scoped: servers outlive screens and are
 * shared by every workspace of the same project.
 */
class LspRuntime(
    context: Context,
    private val paths: SandboxPaths,
    processFactory: ServerProcessFactory,
    projectManager: ProjectManager,
    private val settingsStore: SettingsStore,
    safeMode: Flow<SafeModeReason?>,
    private val scope: CoroutineScope,
) {
    /** The global (user-layer) settings: the `lsp.*` limits are G scope. */
    val settings: StateFlow<SettingsSnapshot> = settingsStore.snapshot.stateIn(scope, SharingStarted.Eagerly, SettingsSnapshot.DEFAULTS)

    private val projectSettings = ConcurrentHashMap<Pair<String, String>, StateFlow<SettingsSnapshot>>()

    /**
     * Settings of one (environment, project): user, environment and project layers, the
     * project's exec-bearing values (`lsp.servers.*.command` ...) trust-gated by the store.
     */
    fun projectSettings(environmentId: String, projectId: String): StateFlow<SettingsSnapshot> =
        projectSettings.getOrPut(environmentId to projectId) {
            settingsStore.snapshot(SettingsQuery(envId = environmentId, projectId = projectId))
                .stateIn(scope, SharingStarted.Eagerly, settings.value)
        }

    /** Register the extension runtime's contributed servers here ([ServerRegistry.register]). */
    val servers = ServerRegistry(::projectSettings, scope, safeMode.map { it != null }) { keys -> Log.w(TAG, "lsp.servers entries ignored (no languages/command): $keys") }

    val messages = LspMessageBus()

    /** WASM completion/hover providers, merged after the servers' answers (registered by AppContainer). */
    val extensionProviders = ExtensionProviderSlot()

    /** Workspaces register their buffer editor here; closed projects are edited on disk. */
    val editPorts = EditPortRegistry(DiskEditPort(Dispatchers.IO))

    private val projectNames: StateFlow<Map<String, String>> = projectManager.projects
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val locator = object : ProjectLocator {
        override fun projectName(environmentId: String, projectId: String) = projectNames.value[projectId] ?: projectId
        // Canonical: the app's files dir is reachable as /data/data and /data/user/0, and the
        // mapper compares paths lexically, so every root it sees must be spelled one way.
        override fun projectDir(environmentId: String, projectId: String): File = paths.projectDir(projectId).canonicalFile
        override fun rootfsDir(environmentId: String): File = paths.rootfsDir(environmentId).canonicalFile
        override val guestWorkspace: String get() = paths.guestProjectPath()
        override val passthroughMounts: List<String> get() = paths.guestPassthroughMounts
    }

    val manager = LanguageServerManager(
        configs = servers,
        deps = ManagerDeps(
            launcher = SandboxServerLauncher(processFactory, paths, android.os.Process.myPid()),
            locator = locator,
            configuration = SettingsConfigurationProvider({ key -> projectSettings(key.environmentId, key.projectId) }) { SettingsSchema.all },
            ui = messages,
            edits = editPorts,
            logSink = LogcatLspSink,
            memoryProbe = ProcMemoryProbe(),
            clock = Clock.SYSTEM,
            settings = settings.map { it.lspSettings() }.stateIn(scope, SharingStarted.Eagerly, SettingsSnapshot.DEFAULTS.lspSettings()),
            client = ClientInfo(
                name = context.getString(R.string.app_name),
                version = appVersion(context),
                locale = Locale.getDefault().toLanguageTag(),
                milestone = Milestone.M4,
                ui = CLIENT_UI,
            ),
        ),
        parent = scope,
    )

    val client = LspClient(manager)

    /** Host directory of an environment's rootfs, where env files a server points at live. */
    fun rootfsDir(environmentId: String): File = paths.rootfsDir(environmentId).canonicalFile

    /** `ComponentCallbacks2.onTrimMemory`, forwarded by the application. */
    fun onTrimMemory(level: Int) {
        memoryPressureFor(level)?.let(manager::onMemoryPressure)
    }

    companion object {
        private const val TAG = "EasyIdeLsp"

        /**
         * What the editor renders today (decision 0018): underline, background ranges, gutter
         * icons (also code lens, listed on a gutter tap), caret popups, end-of-line inlay text
         * and the semantic token overlay over TextMate colouring, with the token types
         * [SemanticRules] can colour. No between-line blocks. Folding, selection ranges and
         * document links have no presenter, so they are withheld rather than advertised and
         * discarded.
         */
        val CLIENT_UI = ClientUi(
            layers = setOf(
                UiLayer.UNDERLINE, UiLayer.BACKGROUND_RANGE, UiLayer.GUTTER_ICON, UiLayer.CARET_POPUP, UiLayer.INLINE_TEXT,
                UiLayer.TOKEN_OVERLAY,
            ),
            semanticTokenTypes = SemanticRules.TOKEN_TYPES,
            semanticTokenModifiers = SemanticRules.TOKEN_MODIFIERS,
            withheld = setOf(LspFeature.FOLDING_RANGE, LspFeature.SELECTION_RANGE, LspFeature.DOCUMENT_LINK),
        )

        /**
         * `ComponentCallbacks2` levels to the kill-order steps of lsp-lifecycle.md 3.2. Newer
         * Android versions deliver only UI_HIDDEN and BACKGROUND; the RSS sampler, not these
         * callbacks, is the primary budget mechanism, so fewer signals only mean less eager
         * shedding.
         */
        @Suppress("DEPRECATION") // The deprecated levels still arrive on the API levels we support.
        fun memoryPressureFor(level: Int): MemoryPressure? = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryPressure.COMPLETE
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> MemoryPressure.BACKGROUND
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryPressure.UI_HIDDEN
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressure.RUNNING_LOW
            else -> null
        }

        private fun appVersion(context: Context): String =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
}
