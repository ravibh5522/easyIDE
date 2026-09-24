package dev.easyide.lsp.testing

import dev.easyide.lsp.LspSettings
import dev.easyide.lsp.TraceLevel
import dev.easyide.lsp.manager.EditPortFactory
import dev.easyide.lsp.manager.LanguageServerManager
import dev.easyide.lsp.manager.ManagerDeps
import dev.easyide.lsp.protocol.ClientUi
import dev.easyide.lsp.protocol.Milestone
import dev.easyide.lsp.protocol.TextEdit
import dev.easyide.lsp.protocol.UiLayer
import dev.easyide.lsp.session.ClientInfo
import dev.easyide.lsp.session.Clock
import dev.easyide.lsp.session.ConfigurationItem
import dev.easyide.lsp.session.ConfigurationProvider
import dev.easyide.lsp.session.EnvironmentNotReadyException
import dev.easyide.lsp.session.FeatureFilter
import dev.easyide.lsp.session.LspUi
import dev.easyide.lsp.session.MessageType
import dev.easyide.lsp.session.ProjectLocator
import dev.easyide.lsp.session.ServerConfig
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.session.ServerLauncher
import dev.easyide.lsp.session.ServerProcessHandle
import dev.easyide.lsp.session.SessionState
import dev.easyide.lsp.workspace.EditPort
import dev.easyide.lsp.workspace.EditResult
import dev.easyide.lsp.workspace.HostLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Settings with test-sized timings; real time is used, so everything here is short. */
fun testSettings(
    requestTimeoutMs: Long = 2000,
    debounceMs: Long = 20,
    maxRetries: Int = 2,
    backoffMs: Long = 50,
    globalBudgetMb: Int = 1200,
    maxServers: Int = 3,
) = LspSettings(requestTimeoutMs, debounceMs, maxRetries, backoffMs, globalBudgetMb, maxServers, TraceLevel.MESSAGES)

fun serverConfig(
    id: String,
    languages: Set<String> = setOf("python"),
    command: List<String> = listOf(id, "--stdio"),
    memoryBudgetMb: Int = 400,
    idleShutdownSec: Int = 600,
    startupTimeoutSec: Int = 5,
    features: FeatureFilter = FeatureFilter.ALL,
    priority: Int = 0,
    settingsSection: String? = null,
    rootMarkers: List<String> = listOf(".git"),
    enabled: Boolean = true,
) = ServerConfig(
    serverId = id,
    languages = languages,
    command = command,
    env = emptyMap(),
    initializationOptions = null,
    settingsSection = settingsSection,
    rootMarkers = rootMarkers,
    memoryBudgetMb = memoryBudgetMb,
    idleShutdownSec = idleShutdownSec,
    startupTimeoutSec = startupTimeoutSec,
    features = features,
    priority = priority,
    enabled = enabled,
)

class FakeServerLauncher(private val scope: CoroutineScope) : ServerLauncher {
    data class Launch(val key: ServerKey, val argv: List<String>, val server: FakeLanguageServer)

    val launches = CopyOnWriteArrayList<Launch>()
    @Volatile var installed: (ServerKey) -> Boolean = { true }
    @Volatile var launchFailure: Exception? = null

    /** Scripts each new server before it starts; the Int is how many launches that key had before. */
    @Volatile var configure: FakeLanguageServer.(ServerKey, Int) -> Unit = { _, _ -> }

    override suspend fun isInstalled(key: ServerKey, command: String): Boolean = installed(key)

    override suspend fun launch(key: ServerKey, argv: List<String>, env: Map<String, String>): ServerProcessHandle {
        launchFailure?.let { throw it }
        val process = FakeProcess()
        val server = FakeLanguageServer(process, scope)
        server.configure(key, launches.count { it.key == key })
        launches += Launch(key, argv, server)
        server.start()
        return process
    }

    fun servers(key: ServerKey): List<FakeLanguageServer> = launches.filter { it.key == key }.map { it.server }

    fun latest(key: ServerKey): FakeLanguageServer = servers(key).last()

    companion object {
        val NOT_READY = EnvironmentNotReadyException("environment not provisioned")
    }
}

/** Records every edit primitive; text edits succeed unless [failing] names the file. */
class RecordingEditPort : EditPort {
    val textEdits = CopyOnWriteArrayList<Pair<HostLocation, List<TextEdit>>>()
    val operations = CopyOnWriteArrayList<String>()
    @Volatile var failing: String? = null

    override suspend fun applyTextEdits(target: HostLocation, edits: List<TextEdit>, label: String): EditResult {
        if (target.file.name == failing) return EditResult.Failed("cannot write ${target.file.name}")
        textEdits += target to edits
        return EditResult.Ok
    }

    override suspend fun createFile(target: HostLocation, overwrite: Boolean, ignoreIfExists: Boolean): EditResult {
        operations += "create ${target.projectRelative}"
        return EditResult.Ok
    }

    override suspend fun renameFile(from: HostLocation, to: HostLocation, overwrite: Boolean, ignoreIfExists: Boolean): EditResult {
        operations += "rename ${from.projectRelative} ${to.projectRelative}"
        return EditResult.Ok
    }

    override suspend fun deleteFile(target: HostLocation, recursive: Boolean, ignoreIfNotExists: Boolean): EditResult {
        operations += "delete ${target.projectRelative}"
        return EditResult.Ok
    }
}

class FakeConfiguration : ConfigurationProvider {
    val values = ConcurrentHashMap<String, JsonElement>()
    val sections = MutableStateFlow<Map<String, JsonElement>>(emptyMap())

    override suspend fun configuration(key: ServerKey, items: List<ConfigurationItem>): List<JsonElement> =
        items.map { values[it.section.orEmpty()] ?: JsonNull }

    override fun section(key: ServerKey, section: String): Flow<JsonElement> = sections.map { it[section] ?: JsonNull }
}

class RecordingUi : LspUi {
    val shown = CopyOnWriteArrayList<String>()
    @Volatile var answer: String? = null

    override fun showMessage(key: ServerKey, type: MessageType, message: String) {
        shown += "$type $message"
    }

    override suspend fun ask(key: ServerKey, type: MessageType, message: String, actions: List<String>): String? = answer
}

/**
 * A manager wired to fakes: temp project and rootfs dirs, scripted servers, in-memory
 * settings and configs. Real dispatchers and real time.
 */
class LspTestHarness(settings: LspSettings = testSettings(), val layers: Set<UiLayer> = UiLayer.entries.toSet()) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val root: File = Files.createTempDirectory("lsp-test").toFile()
    val launcher = FakeServerLauncher(scope)
    val edits = RecordingEditPort()
    val configuration = FakeConfiguration()
    val ui = RecordingUi()
    val clock = Clock.SYSTEM
    val settings = MutableStateFlow(settings)
    val rss = ConcurrentHashMap<ServerKey, Long>()
    val log = CopyOnWriteArrayList<String>()
    private val configs = ConcurrentHashMap<Pair<String, String>, MutableStateFlow<List<ServerConfig>>>()

    val locator = object : ProjectLocator {
        override fun projectName(environmentId: String, projectId: String) = projectId
        override fun projectDir(environmentId: String, projectId: String) = File(root, "projects/$projectId").apply { mkdirs() }
        override fun rootfsDir(environmentId: String) = File(root, "environments/$environmentId/rootfs").apply { mkdirs() }
        override val guestWorkspace = "/workspace"
        override val passthroughMounts = listOf("/dev", "/proc", "/sys")
    }

    val manager = LanguageServerManager(
        configs = { env, project -> configsFor(env, project) },
        deps = ManagerDeps(
            launcher = launcher,
            locator = locator,
            configuration = configuration,
            ui = ui,
            edits = EditPortFactory { _, _ -> edits },
            logSink = { key, line -> log += "$key $line" },
            memoryProbe = { key, _ -> rss[key] },
            clock = clock,
            settings = this.settings,
            client = ClientInfo("easyIDE", "test", "en", Milestone.M4, ClientUi(layers, listOf("class", "function", "variable"), listOf("declaration", "readonly"))),
        ),
        parent = scope,
    )

    fun configsFor(env: String, project: String): MutableStateFlow<List<ServerConfig>> =
        configs.getOrPut(env to project) { MutableStateFlow(emptyList()) }

    fun setServers(env: String, project: String, vararg servers: ServerConfig) {
        configsFor(env, project).value = servers.toList()
    }

    fun key(env: String, project: String, server: String) = ServerKey(env, project, server)

    suspend fun awaitState(key: ServerKey, predicate: (SessionState) -> Boolean): SessionState = withTimeout(WAIT_MS) {
        manager.statuses.map { it[key]?.state }.first { it != null && predicate(it) }!!
    }

    override fun close() {
        runBlocking { manager.close() }
        scope.cancel()
        root.deleteRecursively()
    }

    companion object {
        const val WAIT_MS = 8000L
    }
}
