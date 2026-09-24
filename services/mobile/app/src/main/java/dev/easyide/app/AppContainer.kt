package dev.easyide.app

import android.content.Context
import dev.easyide.sandbox.git.GitCredentials
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
import dev.easyide.app.data.settings.SettingsDirWatcher
import dev.easyide.app.data.settings.SettingsRegistry
import dev.easyide.app.data.settings.SettingsSchema
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

    private val paths = SandboxPaths(appContext.filesDir)

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

    init {
        // For the app's lifetime: a pack's servers come and go with its enablement through
        // the provider's flow, so the registration itself is never closed.
        lsp.servers.register(extensions.languageServers)
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
        KeymapResolver.resolve(Keymap.DEFAULT + layer, KeybindingsFile.parse(text), CommandIds.ALL + commands.map { it.value.command })
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
        const val USER_DIR = "user"
        const val PROFILES_DIR = "profiles"
        const val KEYBINDINGS_FILE = "keybindings.json"
        const val ENVIRONMENT_SETTINGS = "easyide/settings.json"
    }
}
