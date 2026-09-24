package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.sandbox.git.GitFailureKind
import dev.easyide.sandbox.git.GitNetworkOp
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Fetch, pull and push through the guest's git, plus remote management and
 * auto-fetch. Everything here goes through a [GitNetworkRunner], so the
 * controller never learns how a process is spawned or how a token travels.
 *
 * At most one operation runs at a time: a second tap while one is in flight is
 * ignored rather than queued, because two concurrent git processes on one
 * repository contend for its lock files.
 */
class GitRemoteController(
    private val ctx: GitContext,
    private val network: GitNetworkRunner,
    private val settings: GitSettingsPort,
    private val tokens: GitTokenSink,
    private val foreground: StateFlow<Boolean>,
    private val refresh: () -> Unit,
) {
    private var job: Job? = null
    private var lastOp: GitNetworkOp? = null

    init {
        ctx.scope.launch { autoFetch() }
    }

    fun fetch() {
        val state = ctx.state.value
        GitRemotePlanner.fetchOp(state.status, state.remotes)?.let(::start)
    }

    fun pull() {
        ctx.scope.launch {
            val values = settings.current()
            start(GitNetworkOp.Pull(values.pullStrategy, values.identity))
        }
    }

    /** Pushes, or publishes when the branch has no upstream yet. */
    fun push() {
        val state = ctx.state.value
        val status = state.status ?: return
        GitRemotePlanner.pushOp(status, state.remotes)?.let(::start)
    }

    /** Asks first: a forced push can delete other people's commits from the remote. */
    fun requestForcePush() {
        val status = ctx.state.value.status ?: return
        if (GitRemotePlanner.pushOp(status, ctx.state.value.remotes) == null) return
        ctx.state.update { it.copy(confirm = GitConfirm.ForcePush(status.upstream ?: status.branch)) }
    }

    fun forcePush() {
        val state = ctx.state.value
        val status = state.status ?: return
        GitRemotePlanner.pushOp(status, state.remotes, forceWithLease = true)?.let(::start)
    }

    /** Kills the running git process; the output gathered so far stays on screen. */
    fun cancel() {
        job?.cancel()
    }

    fun dismissOperation() {
        if (job?.isActive == true) return
        ctx.state.update { it.copy(operation = null) }
    }

    /**
     * Stores a token the panel just collected and re-runs what failed for lack
     * of one. Returns false when the values cannot be stored.
     */
    suspend fun saveToken(host: String, username: String, token: String): Boolean {
        if (!tokens.save(host, username, token)) return false
        lastOp?.let(::start)
        return true
    }

    fun addRemote(name: String, url: String) {
        ctx.scope.launch {
            when (val result = ctx.service.addRemote(ctx.root, name.trim(), url.trim())) {
                is GitResult.Success -> ctx.state.update { it.copy(remotes = result.value, error = null) }
                is GitResult.Failure -> ctx.state.update { it.copy(error = result.message) }
                GitResult.NotARepository -> Unit
            }
            refresh()
        }
    }

    fun requestRemoveRemote(name: String) = ctx.state.update { it.copy(confirm = GitConfirm.RemoveRemote(name)) }

    /** Runs the remote-management half of an accepted confirmation; true if it was one of ours. */
    fun confirmed(confirm: GitConfirm): Boolean {
        when (confirm) {
            is GitConfirm.RemoveRemote -> removeRemote(confirm.name)
            is GitConfirm.ForcePush -> forcePush()
            else -> return false
        }
        return true
    }

    private fun removeRemote(name: String) {
        ctx.scope.launch {
            when (val result = ctx.service.removeRemote(ctx.root, name)) {
                is GitResult.Success -> ctx.state.update { it.copy(remotes = result.value, error = null) }
                is GitResult.Failure -> ctx.state.update { it.copy(error = result.message) }
                GitResult.NotARepository -> Unit
            }
            refresh()
        }
    }

    private fun start(op: GitNetworkOp) {
        if (job?.isActive == true) return
        lastOp = op
        val kind = GitRemotePlanner.kindOf(op)
        val state = ctx.state.value
        val url = GitRemotePlanner.urlFor(state.status, state.remotes)
        ctx.state.update { it.copy(operation = GitOperation(kind), error = null) }
        job = ctx.scope.launch { run(op, kind, url) }
    }

    private suspend fun run(op: GitNetworkOp, kind: GitOperationKind, url: String?) {
        val result = try {
            network.run(op, ctx.root, url) { line -> append(line) }
        } catch (cancelled: CancellationException) {
            ctx.state.update { s -> s.copy(operation = s.operation?.copy(status = OperationStatus.CANCELLED)) }
            refresh()
            throw cancelled
        }
        when (result) {
            is GitResult.Success -> ctx.state.update { s ->
                s.copy(operation = s.operation?.copy(status = OperationStatus.SUCCEEDED))
            }
            is GitResult.Failure -> ctx.state.update { s ->
                s.copy(
                    operation = GitOperation(
                        kind = kind,
                        status = OperationStatus.FAILED,
                        output = s.operation?.output.orEmpty().ifEmpty { result.message.lines() },
                        failure = result.kind,
                        authHost = if (result.kind == GitFailureKind.AUTH) url?.let(GitUrl::host) else null,
                    ),
                )
            }
            GitResult.NotARepository -> ctx.state.update { it.copy(operation = null) }
        }
        // Fetch/pull/push all move refs (and pull moves the tree): re-read.
        refresh()
    }

    private fun append(line: String) {
        ctx.state.update { s ->
            val op = s.operation ?: return@update s
            s.copy(operation = op.copy(output = (op.output + line).takeLast(MAX_OPERATION_LINES)))
        }
    }

    /**
     * While enabled and the app is in the foreground, fetches now and then every
     * [GitDefaults.AUTO_FETCH_INTERVAL_MS]. Failures are dropped: nobody asked
     * for this fetch, so a flaky network must not put an error in the panel.
     * Skipped while a user-started operation runs.
     */
    private suspend fun autoFetch() {
        active().collectLatest { on ->
            while (on) {
                if (job?.isActive != true) silentFetch()
                delay(GitDefaults.AUTO_FETCH_INTERVAL_MS)
            }
        }
    }

    private fun active(): Flow<Boolean> =
        combine(settings.values.map { it.autoFetch }, foreground) { enabled, fg -> enabled && fg }.distinctUntilChanged()

    private suspend fun silentFetch() {
        val state = ctx.state.value
        val op = GitRemotePlanner.fetchOp(state.status, state.remotes) ?: return
        val url = GitRemotePlanner.urlFor(state.status, state.remotes)
        if (network.run(op, ctx.root, url) {} is GitResult.Success) refresh()
    }
}

/** One home for the values that tune git behaviour, so they are not scattered as literals. */
object GitDefaults {
    /** Between background fetches while auto-fetch is on. Five minutes: fresh enough for ahead/behind, cheap on battery. */
    const val AUTO_FETCH_INTERVAL_MS = 5 * 60 * 1000L

    /** Quiet period after the last keystroke before the commit-message draft is written. */
    const val DRAFT_SAVE_DELAY_MS = 400L
}
