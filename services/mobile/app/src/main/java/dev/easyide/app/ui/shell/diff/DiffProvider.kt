package dev.easyide.app.ui.shell.diff

import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.sandbox.git.DiffHunk
import dev.easyide.sandbox.git.FileDiff
import kotlinx.coroutines.flow.Flow

/** What a diff side is called in a title or header; the UI maps the three fixed ones to its strings. */
sealed interface SideLabel {
    data class Named(val text: String) : SideLabel
    data object Index : SideLabel
    data object Worktree : SideLabel
    data object Nothing : SideLabel
}

/** [path] names the file (its extension picks the syntax colours); the labels say what each side is. */
class DiffSubject(val path: String, val left: SideLabel, val right: SideLabel)

/** What loading a comparison came to. [Gone] is a source that no longer exists (the project stopped being a repository). */
sealed interface DiffOutcome {
    data class Ready(val diff: FileDiff) : DiffOutcome
    data class Failed(val message: String) : DiffOutcome
    data object Gone : DiffOutcome
}

enum class HunkAction { STAGE, UNSTAGE, DISCARD }

/** What can be done to a single hunk of one comparison. [busy] is true while an earlier action is still being applied. */
interface HunkActions {
    val available: List<HunkAction>
    val busy: Flow<Boolean>
    fun perform(action: HunkAction, hunk: DiffHunk)
}

/**
 * Where one URI scheme's comparisons come from (extension-ui.md section 7): the built-in `git-diff`
 * one reads the repository; a pack's would read whatever it compares. The document draws whatever
 * a provider returns, so every provider gets the same side-by-side view, word-level highlighting
 * and hunk navigation.
 */
interface DiffProvider {
    val scheme: String

    /** Null when [uri] is not a comparison this provider can describe. */
    fun subject(uri: DocumentUri): DiffSubject?

    suspend fun load(uri: DocumentUri): DiffOutcome

    /** Null makes the document read-only. */
    fun actions(uri: DocumentUri): HunkActions?
}

/**
 * The providers of the shell, by scheme. Immutable like the other registries: a change returns a new
 * value. A second provider for a scheme is refused and the first stays, so a pack cannot take over
 * `git-diff`. Extension providers are not fed in yet: the extension runtime has no `compare` contribution
 * to register from, and `register` is where it lands.
 */
class DiffProviders private constructor(private val providers: List<DiffProvider>) {

    fun register(provider: DiffProvider): DiffProviders =
        if (providers.any { it.scheme == provider.scheme }) this else DiffProviders(providers + provider)

    fun unregister(scheme: String): DiffProviders = DiffProviders(providers.filter { it.scheme != scheme })

    fun forUri(uri: DocumentUri): DiffProvider? = providers.firstOrNull { it.scheme == uri.scheme }

    companion object {
        val EMPTY = DiffProviders(emptyList())
    }
}
