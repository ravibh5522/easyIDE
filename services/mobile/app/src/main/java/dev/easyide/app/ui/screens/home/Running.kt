package dev.easyide.app.ui.screens.home

import dev.easyide.app.ui.screens.workspace.lsp.ServerStatusKind
import dev.easyide.lsp.manager.ServerStatus
import dev.easyide.lsp.session.ServerKey
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.ProjectRecord
import dev.easyide.sandbox.model.SandboxEnvironment

/** Which thing a Running row stands for; it is also what Stop and Attach act on. */
sealed interface RunningId {
    /** A workspace kept alive by the session registry: its shells, buffers and servers. */
    data class Session(val projectId: String) : RunningId
    data class Server(val key: ServerKey) : RunningId
    data class Install(val environmentId: String) : RunningId
}

/** Rows are grouped in this order: what the user started, then what the app started for them. */
enum class RunningKind { SESSION, SERVER, INSTALL }

enum class RunningPhase { RUNNING, STARTING, FAILED }

/**
 * One row of Home's Running section. Names and subjects are data (a project, an environment,
 * a server id); the wording around them lives in string resources.
 *
 * @property environment where a session's shells run; null when the environment record is gone.
 * @property rssKb resident size of a language server's process tree, when it has been sampled.
 * @property projectId the project this row belongs to, which is what "attach" opens.
 */
data class RunningItem(
    val id: RunningId,
    val kind: RunningKind,
    val name: String,
    val subject: String,
    val phase: RunningPhase,
    val environment: String? = null,
    val rssKb: Long? = null,
    val projectId: String? = null,
)

/**
 * Builds the Running section from what the app already tracks, and nothing else: there is no
 * per-terminal or agent source yet, so a live workspace is one row for all of its shells.
 * An empty result hides the section.
 */
object RunningAssembly {
    private const val SESSION_NAME = "sh"
    private const val INSTALL_NAME = "install"

    fun assemble(
        liveProjectIds: Set<String>,
        servers: Collection<ServerStatus>,
        projects: List<ProjectRecord>,
        environments: List<SandboxEnvironment>,
    ): List<RunningItem> {
        val projectById = projects.associateBy { it.id }
        val envLabel = environments.associate { it.id to it.label }

        val sessions = liveProjectIds.mapNotNull(projectById::get)
            .sortedBy { it.name.lowercase() }
            .map { p ->
                RunningItem(
                    id = RunningId.Session(p.id),
                    kind = RunningKind.SESSION,
                    name = SESSION_NAME,
                    subject = p.name,
                    phase = RunningPhase.RUNNING,
                    environment = envLabel[p.environmentId],
                    projectId = p.id,
                )
            }

        val serverRows = servers.mapNotNull { status ->
            val phase = phaseOf(ServerStatusKind.of(status)) ?: return@mapNotNull null
            val project = projectById[status.key.projectId] ?: return@mapNotNull null
            RunningItem(
                id = RunningId.Server(status.key),
                kind = RunningKind.SERVER,
                name = status.key.serverId.substringAfterLast('/'),
                subject = project.name,
                phase = phase,
                rssKb = status.rssKb,
                projectId = project.id,
            )
        }.sortedWith(compareBy({ it.subject.lowercase() }, { it.name }))

        val installs = environments.filter { it.state == EnvironmentState.PROVISIONING }
            .sortedBy { it.label.lowercase() }
            .map { env ->
                RunningItem(
                    id = RunningId.Install(env.id),
                    kind = RunningKind.INSTALL,
                    name = INSTALL_NAME,
                    subject = env.label,
                    phase = RunningPhase.STARTING,
                )
            }

        return sessions + serverRows + installs
    }

    /** Servers that are idle, stopped, disabled or not installed are not "running": they stay out of the list. */
    fun phaseOf(kind: ServerStatusKind): RunningPhase? = when (kind) {
        ServerStatusKind.READY -> RunningPhase.RUNNING
        ServerStatusKind.STARTING -> RunningPhase.STARTING
        ServerStatusKind.CRASHED, ServerStatusKind.OVER_BUDGET -> RunningPhase.FAILED
        else -> null
    }
}
