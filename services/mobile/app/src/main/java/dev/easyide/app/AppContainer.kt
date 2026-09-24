package dev.easyide.app

import android.app.Application
import android.content.Context
import dev.easyide.sandbox.git.GitCredentials
import dev.easyide.sandbox.git.GitRemote
import dev.easyide.sandbox.service.SandboxForegroundService
import dev.easyide.app.ui.screens.onboarding.InstallKeepAlive
import dev.easyide.app.ui.screens.workspace.git.AppForeground
import dev.easyide.sandbox.git.GitService
import dev.easyide.app.data.SandboxImages
import dev.easyide.app.data.UiPreferences
import dev.easyide.app.data.preferencesStore
import dev.easyide.app.data.settings.DataStoreUserLayer
import dev.easyide.app.data.settings.FileKeybindings
import dev.easyide.app.data.settings.FileLayerSource
import dev.easyide.app.data.settings.InvalidValueSink
import dev.easyide.app.data.settings.LayerSource
import dev.easyide.app.data.settings.PlainFileIo
import dev.easyide.app.data.settings.ProfileManager
import dev.easyide.app.data.settings.ProjectFileIo
import dev.easyide.app.data.settings.ProjectTrust
import dev.easyide.app.data.settings.SafeModeState
import dev.easyide.app.data.settings.ThemeSettingsSchema
import dev.easyide.app.extensions.ActiveIconTheme
import dev.easyide.app.data.settings.SettingsDirWatcher
import dev.easyide.app.data.settings.SettingsRegistry
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.WorkspaceSettingsSchema
import dev.easyide.app.diagnostics.AppLog
import dev.easyide.app.diagnostics.BuildInfo
import dev.easyide.app.diagnostics.ExtensionLogBridge
import dev.easyide.app.diagnostics.LspStatusBridge
import dev.easyide.app.diagnostics.readBuildInfo
import dev.easyide.app.diagnostics.CrashReports
import dev.easyide.app.session.SessionStore
import dev.easyide.app.session.WorkspaceRegistry
import dev.easyide.app.ui.WorkspaceViewModelFactory
import dev.easyide.app.ui.screens.workspace.session.WorkspaceHandle
import dev.easyide.sandbox.service.SandboxKeepAlive
import dev.easyide.sandbox.service.SessionHost
import dev.easyide.app.data.settings.SettingsStore
import dev.easyide.app.extensions.ExtensionsContainer
import dev.easyide.app.data.settings.SettingsTransfer
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.KeybindingsFile
import dev.easyide.app.ui.commands.Keymap
import dev.easyide.app.ui.commands.KeymapResolver
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.extensions.EnvironmentExtensionBinds
import dev.easyide.sandbox.ProjectManager
import dev.easyide.sandbox.RootDetector
import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.LinuxEnvironment
import dev.easyide.sandbox.bootstrap.ProotInstaller
import dev.easyide.sandbox.bootstrap.RootfsProvisioner
import dev.easyide.sandbox.bootstrap.TarGzExtractor
import dev.easyide.sandbox.external.ExternalFolderSync
import dev.easyide.sandbox.files.ProjectFiles
import dev.easyide.sandbox.model.SandboxImage
import dev.easyide.sandbox.shell.ServerProcessFactory
import dev.easyide.sandbox.shell.ShellRunner
import dev.easyide.app.lsp.LspRuntime
import dev.easyide.sandbox.store.SandboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manual dependency wiring. A DI framework would need annotation processing,
 * which is not worth a codegen dependency for a graph this size - revisit if
 * the graph grows past a handful of objects.
 */
class AppContainer(context: Context) {

    val gitService = GitService(ioDispatcher = Dispatchers.IO)

    // Public and safe to hold anywhere: this is the Application context
    // itself, not an Activity - PtyTerminalTab sessions need it for
    // clipboard access and outlive any single screen.
    val appContext: Context = context.applicationContext

    /** Outlives any screen; sandbox state must survive navigation. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val uiPreferences: UiPreferences = UiPreferences(appContext)

    private val defaultUserLayer = DataStoreUserLayer(appContext.preferencesStore)

    private val userDir = File(appContext.filesDir, USER_DIR)

    private val defaultKeybindings = FileKeybindings(PlainFileIo(File(userDir, KEYBINDINGS_FILE), Dispatchers.IO))

    /** Built-in settings now; the extension runtime adds `configuration` contributions at runtime. */
    val settingsRegistry = SettingsRegistry(SettingsSchema.all)

    val profileManager = ProfileManager(
        defaultUser = defaultUserLayer,
        defaultKeybindings = defaultKeybindings,
        profilesDir = File(userDir, PROFILES_DIR),
        io = Dispatchers.IO,
        scope = applicationScope,
    )

    /** Built before anything that could start extension code (LLD sec 11). */
    val safeMode = SafeModeState(defaultUserLayer)

    val projectTrust = ProjectTrust(appContext.preferencesStore)

    private val environmentLayers = ConcurrentHashMap<String, LayerSource>()
    private val projectLayers = ConcurrentHashMap<String, LayerSource>()

    val settingsStore: SettingsStore = SettingsStore(
        user = profileManager.userLayer,
        registry = settingsRegistry,
        environment = { id ->
            environmentLayers.getOrPut(id) {
                // Beside rootfs/, not inside it: guests never see or write it.
                FileLayerSource(PlainFileIo(File(paths.environmentDir(id), ENVIRONMENT_SETTINGS), Dispatchers.IO), applicationScope)
            }
        },
        project = { id ->
            projectLayers.getOrPut(id) {
                FileLayerSource(ProjectFileIo(projectFiles, id, ProjectFileIo.SETTINGS), applicationScope) { onChange ->
                    SettingsDirWatcher(projectFiles.projectRoot(id), onChange)
                }
            }
        },
        trust = projectTrust,
        safeMode = safeMode.active,
        // Key and layer only: a value may be a path or token the user would not want in logcat.
        log = InvalidValueSink { layer, key, _ -> Log.w(LOG_TAG, "ignoring invalid value for $key in $layer") },
    )

    val settingsTransfer = SettingsTransfer(
        resolver = appContext.contentResolver,
        defaultUser = defaultUserLayer,
        defaultKeybindings = defaultKeybindings,
        profiles = profileManager,
        registry = settingsRegistry,
        appVersion = appVersionName(),
        // Read at export time, after `extensions` below is constructed.
        installedExtensions = { extensions.installedSummary() },
        io = Dispatchers.IO,
    )

    val gitCredentials = GitCredentials(appContext)

    val paths = SandboxPaths(appContext.filesDir)

    /** Visible-or-not, for work (auto-fetch) that must not run in the background. */
    val appForeground = AppForeground(appContext as Application)

    private val store: SandboxStore = SandboxStore.create(appContext.filesDir, applicationScope)

    val projectFiles: ProjectFiles = ProjectFiles(paths, Dispatchers.IO)

    val externalFolderSync: ExternalFolderSync = ExternalFolderSync(appContext, Dispatchers.IO)

    val shellRunner: ShellRunner = ShellRunner(Dispatchers.IO)

    val environmentManager: EnvironmentManager = EnvironmentManager(
        store = store,
        paths = paths,
        rootDetector = RootDetector(),
        extractor = TarGzExtractor(),
        ioDispatcher = Dispatchers.IO,
    )

    val projectManager: ProjectManager = ProjectManager(
        store = store,
        paths = paths,
        projectFiles = projectFiles,
        externalFolderSync = externalFolderSync,
        ioDispatcher = Dispatchers.IO,
    )

    val linuxEnvironment: LinuxEnvironment = LinuxEnvironment(
        paths = paths,
        prootInstaller = ProotInstaller(appContext, Dispatchers.IO),
        provisioner = RootfsProvisioner(Dispatchers.IO),
        fallbackShell = shellRunner,
        ioDispatcher = Dispatchers.IO,
        // Only enabled environment packs appear in the guest; read at each launch, after
        // `extensions` below is constructed.
        guestBinds = EnvironmentExtensionBinds(paths) { envId, id -> extensions.isEnabledIn(envId, id.value) },
    )

    /** Network git (clone, pull, push) through the guest's own `git`; tokens come from [gitCredentials]. */
    val gitRemote = GitRemote(linuxEnvironment, gitCredentials, Dispatchers.IO)

    /** Holds the sandbox foreground service open while an install runs, so backgrounding the app does not kill it. */
    val installKeepAlive: InstallKeepAlive = object : InstallKeepAlive {
        // Starting a foreground service can be refused (background-start limits on Android 12+, a
        // missing permission); the install then simply runs unprotected, which is what it did before.
        override fun start() {
            runCatching { SandboxForegroundService.start(appContext) }
        }

        override fun stop() = SandboxForegroundService.stop(appContext)
    }

    /** The extension platform; started by [EasyIdeApplication] before any screen exists. */
    val extensions: ExtensionsContainer = ExtensionsContainer(
        context = appContext,
        paths = paths,
        settingsStore = settingsStore,
        profiles = profileManager,
        settingsRegistry = settingsRegistry,
        appSafeMode = safeMode,
        shellEnvironment = linuxEnvironment::guestShellEnvironment,
        scope = applicationScope,
        io = Dispatchers.IO,
    )

    /** Language servers: one manager for the process, shared by every workspace. */
    val lsp: LspRuntime = LspRuntime(
        context = appContext,
        paths = paths,
        processFactory = ServerProcessFactory(linuxEnvironment, Dispatchers.IO),
        projectManager = projectManager,
        settingsStore = settingsStore,
        safeMode = safeMode.active,
        scope = applicationScope,
    )

    /** `workbench.iconTheme`: the explorer's icon theme, loaded off the main thread. */
    val iconTheme = ActiveIconTheme(
        iconThemes = extensions.themes.iconThemes,
        selection = settingsStore.observe(ThemeSettingsSchema.iconTheme),
        io = Dispatchers.IO,
        scope = applicationScope,
        warn = { Log.w(LOG_TAG, it) },
    )

    init {
        // For the app's lifetime: a pack's servers come and go with its enablement through
        // the provider's flow, so the registration itself is never closed.
        lsp.servers.register(extensions.languageServers)
        // WASM completion/hover providers join the LSP presenters' merge.
        lsp.extensionProviders.register(extensions.wasm)
    }

    /** The app's own log file and the crash reports written by the uncaught-exception handler. */
    val logDir = File(appContext.filesDir, LOG_DIR)

    val appLog = AppLog(logDir)

    val crashReports = CrashReports(File(appContext.filesDir, CRASH_DIR))

    val buildInfo: BuildInfo = readBuildInfo(appContext)

    /** Hot-exit snapshots and unsaved-buffer backups, one directory per project. */
    val sessionsDir = File(appContext.filesDir, SESSIONS_DIR)

    val sessionStore = SessionStore(sessionsDir)

    init {
        // Subsystems that own a log port are read through their public state, never edited.
        ExtensionLogBridge.start(extensions.log.entries, appLog, applicationScope)
        LspStatusBridge.start(lsp.manager, appLog, applicationScope)
    }

    /** The user layer only (the `workspace.*` settings are global), kept as a value for the registry's synchronous reads. */
    private val globalSettings = settingsStore.snapshot.stateIn(applicationScope, SharingStarted.Eagerly, SettingsSnapshot.DEFAULTS)

    /**
     * The live workspaces of the process, parked or on screen (decision 0023). Main-confined
     * like every UI owner: workspace view models are created and cleared on the main thread.
     */
    val workspaces = WorkspaceRegistry<WorkspaceHandle>(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        clock = System::currentTimeMillis,
        parkLimit = { globalSettings.value[WorkspaceSettingsSchema.maxParkedProjects] },
        open = { projectId, environmentId, settled ->
            WorkspaceHandle.open(WorkspaceViewModelFactory(this, projectId, environmentId, settled))
        },
    )

    init {
        // Sessions keep the app process at foreground priority; the notification's "Stop all" ends them.
        val keepAliveHost = SessionHost { workspaces.endAll() }
        applicationScope.launch(Dispatchers.Main) {
            workspaces.liveIds.collect { SandboxKeepAlive.update(appContext, it.size, keepAliveHost) }
        }
        // A deleted project takes its live session and stored session with it. Compared between
        // emissions, never against an empty list on its own: the store reports an unreadable file as
        // "no projects", and that must not erase every user's saved work.
        applicationScope.launch(Dispatchers.Main) {
            var known: Set<String>? = null
            projectManager.projects.collect { list ->
                val ids = list.mapTo(HashSet()) { it.id }
                val previous = known
                known = ids
                val gone = when {
                    previous != null -> previous - ids
                    ids.isEmpty() -> emptySet()
                    else -> withContext(Dispatchers.IO) { sessionStore.storedIds() } - ids
                }
                gone.forEach { id ->
                    workspaces.close(id)
                    withContext(Dispatchers.IO) { sessionStore.clear(id) }
                }
            }
        }
    }

    /**
     * The keymap: built-ins, then the enabled extensions' keybindings, then the active
     * profile's keybindings.json (whose `-command` entries can remove either), plus its
     * diagnostics. Extension command ids count as known.
     */
    val keymap: Flow<KeymapResolver.Result> = combine(
        profileManager.keybindings.text,
        extensions.keybindings,
        extensions.runtime.contributions.commands.entries,
    ) { text, layer, commands ->
        KeymapResolver.resolve(Keymap.DEFAULT + layer, KeybindingsFile.parse(text), CommandIds.KNOWN + commands.map { it.value.command })
    }

    /**
     * The preset an environment was created from. Resolved here rather than
     * held by the workspace because provisioning is deferred - the choice was
     * persisted at creation time, possibly in an earlier app session.
     */
    suspend fun imageFor(environmentId: String): SandboxImage =
        SandboxImages.byId(
            environmentManager.environments.first().find { it.id == environmentId }?.imageId
        )

    @Suppress("DEPRECATION") // the flags overload is API 33+; minSdk is 26
    private fun appVersionName(): String =
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()

    private companion object {
        const val LOG_TAG = "Settings"
        const val LOG_DIR = "logs"
        const val CRASH_DIR = "crashes"
        const val SESSIONS_DIR = "sessions"
        const val USER_DIR = "user"
        const val PROFILES_DIR = "profiles"
        const val KEYBINDINGS_FILE = "keybindings.json"
        const val ENVIRONMENT_SETTINGS = "easyide/settings.json"
    }
}
