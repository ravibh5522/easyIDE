package dev.easyide.app.ui.screens.workspace

import android.content.Context
import com.termux.terminal.TerminalSession
import dev.easyide.extensions.action.ExecOutcome
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.sandbox.LinuxEnvironment
import dev.easyide.sandbox.shell.PtyShellParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The workspace's terminal tabs (extension-runtime.md sec 9 `WorkspaceTerminals`):
 * interactive shells, the named terminals extension actions reuse (`runInTerminal`), and
 * command terminals that run one argv and report its exit (`sandboxExec` with
 * `output: terminal`, tasks).
 *
 * There is no input mediation here: a real TerminalSession/TerminalView owns typing,
 * scrollback and process state (see TerminalPane). This owns the tab list: create on
 * request, retire processes when a tab or the workspace closes, keep titles in step
 * with what the shell reports.
 */
class WorkspaceTerminals(
    private val state: MutableStateFlow<WorkspaceUiState>,
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val linuxEnvironment: LinuxEnvironment,
    private val environmentId: String,
    private val projectRoot: File,
    private val setStatus: (String) -> Unit,
) {
    private val installLog = InstallLogPump { state.value.terminals }

    /** Tabs extension actions opened by name, per owner, so a rerun reuses its tab. */
    private val named = HashMap<Pair<ExtensionId, String>, String>()

    /**
     * A new interactive shell. [initialCommand] is typed into it (an install recipe the user
     * asked to run) and the terminal panel is revealed so they see it run.
     */
    fun newShell(initialCommand: String? = null) {
        scope.launch {
            val tab = openShell(null) ?: return@launch
            if (initialCommand == null) return@launch
            tab.session.write(initialCommand + "\n")
            state.update { it.copy(terminalRevealRequests = it.terminalRevealRequests + 1) }
        }
    }

    /** Shows the terminal panel without starting anything: Home's "Open with terminal". */
    fun reveal() = state.update { it.copy(terminalRevealRequests = it.terminalRevealRequests + 1) }

    /**
     * Resolves proot-or-fallback shell params off the main thread, then constructs the real
     * `TerminalSession` and adds it as a new tab. Suspends because
     * [LinuxEnvironment.interactiveShellParams] may need to install proot on first use.
     */
    suspend fun openShell(title: String?): PtyTerminalTab? {
        // Process-spawn boundary: proot preparation failures surface as a status message.
        val params = try {
            linuxEnvironment.interactiveShellParams(environmentId, projectRoot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus(e.message ?: "Could not start a terminal")
            return null
        }
        return addTab(params, title ?: "sh ${state.value.terminals.size + 1}") { }
    }

    /** The tab [owner] opened as [name] if it is still open, else a new shell titled [name]. */
    suspend fun namedTerminal(owner: ExtensionId, name: String): PtyTerminalTab? {
        val key = owner to name
        named[key]?.let { id -> state.value.terminals.find { it.id == id && it.session.isRunning }?.let { return it } }
        val tab = openShell(name) ?: return null
        named[key] = tab.id
        return tab
    }

    /**
     * Runs [argv] in a new tab titled [title] and waits for it to exit, killing it at
     * [timeoutMs]. The finished tab keeps its scrollback so the output stays readable.
     */
    suspend fun runCommand(title: String, argv: List<String>, cwd: String?, env: Map<String, String>, timeoutMs: Long): ExecOutcome {
        val params = try {
            linuxEnvironment.commandPtyParams(environmentId, projectRoot, argv, cwd, env)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ExecOutcome.Unavailable(e.message ?: "cannot start $title")
        } ?: return ExecOutcome.Unavailable("environment $environmentId has no Linux userland")
        val exit = CompletableDeferred<Int>()
        val tab = addTab(params, title) { session -> exit.complete(session.exitStatus) }
        return try {
            val code = withTimeoutOrNull(timeoutMs) { exit.await() }
            if (code == null) {
                tab.session.finishIfRunning()
                ExecOutcome.TimedOut("")
            } else {
                ExecOutcome.Exited(code, "", "", truncated = false)
            }
        } catch (e: CancellationException) {
            tab.session.finishIfRunning()
            throw e
        }
    }

    fun select(id: String) = state.update { it.copy(activeTerminalId = id) }

    /** User-driven rename, distinct from [retitle] which tracks the shell's own OSC title. */
    fun rename(id: String, title: String) = retitle(id, title)

    /** The last tab is never closed, so the panel always has something to show. */
    fun close(id: String) {
        val current = state.value
        if (current.terminals.size <= 1) return
        current.terminals.find { it.id == id }?.session?.finishIfRunning()
        state.update { s ->
            val remaining = s.terminals.filterNot { it.id == id }
            val active = if (s.activeTerminalId == id) remaining.lastOrNull()?.id else s.activeTerminalId
            s.copy(terminals = remaining, activeTerminalId = active)
        }
    }

    /**
     * A pty subprocess is a real Linux process, not something garbage collection
     * reclaims - it needs an explicit SIGKILL or it keeps running (and holding the pty)
     * after the workspace is gone.
     */
    fun release() {
        state.value.terminals.forEach { it.session.finishIfRunning() }
        installLog.stop()
    }

    private fun addTab(params: PtyShellParams, title: String, onFinished: (TerminalSession) -> Unit): PtyTerminalTab {
        val id = UUID.randomUUID().toString()
        val client = EasyTerminalSessionClient(
            context = appContext,
            onTitleChanged = { changed -> retitle(id, changed.title) },
            // Frozen scrollback with the exit message is the desired end state.
            onSessionFinished = onFinished,
        )
        val session = TerminalSession(
            params.shellPath,
            params.cwd,
            params.args.toTypedArray(),
            params.env.map { (key, value) -> "$key=$value" }.toTypedArray(),
            null,
            client,
        )
        val tab = PtyTerminalTab(id = id, title = title, session = session, client = client)
        state.update { it.copy(terminals = it.terminals + tab, activeTerminalId = tab.id) }
        return tab
    }

    private fun retitle(id: String, title: String?) {
        if (title.isNullOrBlank()) return
        state.update { s -> s.copy(terminals = s.terminals.map { if (it.id == id) it.copy(title = title) else it }) }
    }

    /**
     * Install progress into [tabId]'s screen buffer as process output (see [InstallLogPump]);
     * callable from any thread.
     */
    fun appendInstallLog(tabId: String?, line: String) = installLog.append(tabId, line)
}
