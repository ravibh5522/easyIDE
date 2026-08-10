package dev.tabcode.app

import android.content.Context
import dev.tabcode.app.data.SandboxImages
import dev.tabcode.app.data.UiPreferences
import dev.tabcode.sandbox.EnvironmentManager
import dev.tabcode.sandbox.ProjectManager
import dev.tabcode.sandbox.RootDetector
import dev.tabcode.sandbox.SandboxPaths
import dev.tabcode.sandbox.LinuxEnvironment
import dev.tabcode.sandbox.bootstrap.ProotInstaller
import dev.tabcode.sandbox.bootstrap.RootfsProvisioner
import dev.tabcode.sandbox.bootstrap.TarGzExtractor
import dev.tabcode.sandbox.external.ExternalFolderSync
import dev.tabcode.sandbox.files.ProjectFiles
import dev.tabcode.sandbox.model.SandboxImage
import dev.tabcode.sandbox.shell.ShellRunner
import dev.tabcode.sandbox.store.SandboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/**
 * Manual dependency wiring. A DI framework would need annotation processing,
 * which is not worth a codegen dependency for a graph this size - revisit if
 * the graph grows past a handful of objects.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Outlives any screen; sandbox state must survive navigation. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val uiPreferences: UiPreferences = UiPreferences(appContext)

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
    )

    /**
     * The preset an environment was created from. Resolved here rather than
     * held by the workspace because provisioning is deferred - the choice was
     * persisted at creation time, possibly in an earlier app session.
     */
    suspend fun imageFor(environmentId: String): SandboxImage =
        SandboxImages.byId(
            environmentManager.environments.first().find { it.id == environmentId }?.imageId
        )
}
