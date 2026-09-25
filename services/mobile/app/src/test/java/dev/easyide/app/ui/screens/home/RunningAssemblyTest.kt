package dev.easyide.app.ui.screens.home

import dev.easyide.lsp.manager.ServerStatus
import dev.easyide.lsp.session.FailReason
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.session.SessionState
import dev.easyide.lsp.session.StopReason
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.ProjectRecord
import dev.easyide.sandbox.model.SandboxBackend
import dev.easyide.sandbox.model.SandboxEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningAssemblyTest {

    private val projects = listOf(
        ProjectRecord("p1", "my-app", "e1", 0, 0),
        ProjectRecord("p2", "Api", "e1", 0, 0),
    )

    private fun env(id: String, label: String, state: EnvironmentState = EnvironmentState.READY) =
        SandboxEnvironment(id, label, SandboxBackend.PROOT, state, 0, 0)

    private val environments = listOf(env("e1", "ubuntu"))

    private fun server(project: String, id: String, state: SessionState, rss: Long? = null) =
        ServerStatus(ServerKey("e1", project, id), setOf("python"), state, pausedForMemory = false, rssKb = rss)

    private fun assemble(
        live: Set<String> = emptySet(),
        servers: List<ServerStatus> = emptyList(),
        envs: List<SandboxEnvironment> = environments,
    ) = RunningAssembly.assemble(live, servers, projects, envs)

    @Test fun `nothing running gives no rows so the section hides`() {
        assertTrue(assemble().isEmpty())
    }

    @Test fun `a live workspace is one shell row naming its project and environment`() {
        val row = assemble(live = setOf("p1")).single()
        assertEquals(RunningId.Session("p1"), row.id)
        assertEquals(RunningKind.SESSION, row.kind)
        assertEquals("my-app", row.subject)
        assertEquals("ubuntu", row.environment)
        assertEquals("p1", row.projectId)
    }

    @Test fun `a session of a deleted project is dropped`() {
        assertTrue(assemble(live = setOf("gone")).isEmpty())
    }

    @Test fun `only ready, starting and failed servers are running`() {
        val rows = assemble(
            servers = listOf(
                server("p1", "ext.py/pyright", SessionState.Running, rss = 217_088),
                server("p1", "ext.kt/kls", SessionState.Starting),
                server("p1", "ext.go/gopls", SessionState.Failed(FailReason.CrashLoop, emptyList())),
                server("p1", "ext.rs/ra", SessionState.Stopped(StopReason.USER)),
                server("p1", "ext.c/clangd", SessionState.NotInstalled),
                server("p1", "ext.ts/tsserver", SessionState.Idle(0)),
            ),
        )
        val byName = rows.associateBy { it.name }
        assertEquals(setOf("pyright", "kls", "gopls", "tsserver"), byName.keys)
        assertEquals(RunningPhase.RUNNING, byName.getValue("pyright").phase)
        assertEquals(217_088L, byName.getValue("pyright").rssKb)
        assertEquals(RunningPhase.STARTING, byName.getValue("kls").phase)
        assertEquals(RunningPhase.FAILED, byName.getValue("gopls").phase)
    }

    @Test fun `a server shows its short name and the project it serves`() {
        val row = assemble(servers = listOf(server("p2", "easyide.python/pyright", SessionState.Running))).single()
        assertEquals("pyright", row.name)
        assertEquals("Api", row.subject)
        assertEquals("p2", row.projectId)
    }

    @Test fun `a server of an unknown project is dropped`() {
        assertTrue(assemble(servers = listOf(server("gone", "s", SessionState.Running))).isEmpty())
    }

    @Test fun `only a provisioning environment is an install`() {
        val rows = assemble(
            envs = listOf(env("e1", "ubuntu"), env("e2", "debian", EnvironmentState.PROVISIONING), env("e3", "alpine", EnvironmentState.FAILED)),
        )
        val install = rows.single()
        assertEquals(RunningId.Install("e2"), install.id)
        assertEquals("debian", install.subject)
        assertEquals(null, install.projectId)
    }

    @Test fun `rows are grouped shells, servers, installs and ordered by name within`() {
        val rows = assemble(
            live = setOf("p1", "p2"),
            servers = listOf(
                server("p1", "b/zeta", SessionState.Running),
                server("p1", "a/alpha", SessionState.Running),
                server("p2", "a/alpha", SessionState.Running),
            ),
            envs = listOf(env("e1", "ubuntu", EnvironmentState.PROVISIONING)),
        )
        assertEquals(
            listOf(
                RunningKind.SESSION to "Api", RunningKind.SESSION to "my-app",
                RunningKind.SERVER to "Api", RunningKind.SERVER to "my-app", RunningKind.SERVER to "my-app",
                RunningKind.INSTALL to "ubuntu",
            ),
            rows.map { it.kind to it.subject },
        )
        assertEquals(listOf("alpha", "alpha", "zeta"), rows.filter { it.kind == RunningKind.SERVER }.map { it.name })
    }
}
