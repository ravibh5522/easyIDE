package dev.easyide.app

import android.content.Context
import dev.easyide.app.data.SandboxImages
import dev.easyide.app.data.UiPreferences
import dev.easyide.sandbox.EnvironmentManager
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
import dev.easyide.sandbox.shell.ShellRunner
import dev.easyide.sandbox.store.SandboxStore
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

    // Public and safe to hold anywhere: this is the Application context
    // itself, not an Activity - PtyTerminalTab sessions need it for
    // clipboard access and outlive any single screen.
    val appContext: Context = context.applicationContext

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
