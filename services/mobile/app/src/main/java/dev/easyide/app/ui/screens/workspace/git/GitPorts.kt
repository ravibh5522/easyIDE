package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.app.data.settings.ConfigTarget
import dev.easyide.app.data.settings.GitSettingsSchema
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SettingEdit
import dev.easyide.app.data.settings.SettingsQuery
import dev.easyide.app.data.settings.SettingsStore
import dev.easyide.sandbox.git.GitCredentials
import dev.easyide.sandbox.git.GitIdentity
import dev.easyide.sandbox.git.GitNetworkOp
import dev.easyide.sandbox.git.GitRemote
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.PullStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The seams between the git controllers and the rest of the app. Each is one
 * narrow question the controllers ask, so they can be driven in JVM tests with
 * a fake and a real repository instead of a sandbox and a settings database.
 */

/** Runs one guest-git network operation, streaming its output; see [GitRemote.execute]. */
fun interface GitNetworkRunner {
    suspend fun run(op: GitNetworkOp, projectDir: File, url: String?, onOutput: (String) -> Unit): GitResult<String>
}

/** Where a token entered from the panel is saved. Tokens are write-only from here. */
fun interface GitTokenSink {
    suspend fun save(host: String, username: String, token: String): Boolean
}

/** The resolved `git.*` values for one workspace (project layer over user layer). */
data class GitSettingsValues(
    val userName: String,
    val userEmail: String,
    val pullStrategy: PullStrategy,
    val autoFetch: Boolean,
) {
    /** Null until both name and a plausible email are set. */
    val identity: GitIdentity? get() = GitIdentity.of(userName, userEmail)
}

interface GitSettingsPort {
    val values: Flow<GitSettingsValues>

    /** Saves both halves in one write, to this project only or to the user's global settings. */
    suspend fun saveIdentity(identity: GitIdentity, projectOnly: Boolean): Boolean
}

suspend fun GitSettingsPort.current(): GitSettingsValues = values.first()

class StoreGitSettings(
    private val store: SettingsStore,
    private val environmentId: String,
    private val projectId: String,
) : GitSettingsPort {

    override val values: Flow<GitSettingsValues> =
        store.snapshot(SettingsQuery(envId = environmentId, projectId = projectId)).map { s ->
            GitSettingsValues(
                userName = s[GitSettingsSchema.userName],
                userEmail = s[GitSettingsSchema.userEmail],
                pullStrategy = s[GitSettingsSchema.pullStrategy],
                autoFetch = s[GitSettingsSchema.autoFetch],
            )
        }.distinctUntilChanged()

    override suspend fun saveIdentity(identity: GitIdentity, projectOnly: Boolean): Boolean {
        val target = if (projectOnly) {
            ConfigTarget(LayerId.PROJECT, envId = environmentId, projectId = projectId)
        } else {
            ConfigTarget.USER
        }
        val edits = listOf(
            SettingEdit(GitSettingsSchema.userName.key, null, GitSettingsSchema.userName.encode(identity.name)),
            SettingEdit(GitSettingsSchema.userEmail.key, null, GitSettingsSchema.userEmail.encode(identity.email)),
        )
        return store.write(target, edits).isSuccess
    }
}

fun GitRemote.asRunner(environmentId: String) = GitNetworkRunner { op, dir, url, out ->
    execute(op, dir, environmentId, url, out)
}

/** Keystore and file I/O, hence the dispatcher. */
fun GitCredentials.asSink() = GitTokenSink { host, username, token ->
    withContext(Dispatchers.IO) { store(host, token, username) }
}
