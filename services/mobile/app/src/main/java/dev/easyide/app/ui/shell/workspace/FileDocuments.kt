package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.EditorStage
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.shell.OpenOptions
import dev.easyide.app.ui.shell.Origin
import dev.easyide.app.ui.shell.ShellAction
import dev.easyide.app.ui.shell.UriPattern
import dev.easyide.app.ui.shell.host.AppRegistries

/**
 * Files as documents: `file:///workspace/<project-relative path>`. The workspace view model keeps
 * the buffers (text, dirty state, carets, the session backup), so the stage holds a URI per open
 * file and the view model stays the authority on which files are open.
 */
object FileDocuments {
    const val TYPE_ID = "easyide.file"
    private const val ROOT = "workspace"
    private const val SCHEME = "file"

    fun uriOf(relativePath: String): DocumentUri? = DocumentUri.of(SCHEME, segments = listOf(ROOT) + relativePath.split('/'))

    /** The project-relative path of a file document, or null for any other URI. */
    fun pathOf(uri: DocumentUri?): String? =
        uri?.takeIf { it.scheme == SCHEME && it.segments.size > 1 && it.segments.first() == ROOT }?.segments?.drop(1)?.joinToString("/")

    /**
     * Not restorable through the shell snapshot: the session restores tabs from disk and unsaved
     * backups, and a tab the shell restored on its own could name a file that is gone.
     */
    val type = DocumentType(TYPE_ID, UriPattern(SCHEME), { it.name }, { IconRef("file") }, restorable = false)
}

/**
 * Keeps the stage's file documents in step with the view model's open tabs. The view model
 * decides what is open (a tap in the tree, a jump to a definition, a restored session); the stage
 * mirrors it, so a document closed there through the unsaved-changes guard disappears here too.
 */
object FileTabSync {

    /** The stage actions that make its files equal to [openPaths], with [activePath] focused. */
    fun plan(openPaths: List<String>, activePath: String?, stage: EditorStage): List<ShellAction> {
        val wanted = openPaths.mapNotNull(FileDocuments::uriOf)
        val staged = stage.documents.map { it.key }.filter { FileDocuments.pathOf(it) != null }
        val active = activePath?.let(FileDocuments::uriOf)
        val gone = staged.filter { it !in wanted }.toSet()
        val actions = ArrayList<ShellAction>()
        if (gone.isNotEmpty()) actions += ShellAction.CloseWhere { it.key in gone }
        wanted.filter { it !in staged && it != active }.forEach { actions += ShellAction.Open(it, OpenOptions(focus = false)) }
        if (active != null) actions += activate(active, stage)
        return actions
    }

    private fun activate(active: DocumentUri, stage: EditorStage): ShellAction {
        val group = stage.groups.indexOfFirst { active in it }
        val shown = group == stage.active && stage.groups[group].active == active
        return when {
            group < 0 -> ShellAction.Open(active)
            shown -> ShellAction.FocusGroup(group)
            else -> ShellAction.Activate(group, active)
        }
    }
}

/** The app-scope registries plus the workspace's own document type. */
fun AppRegistries.forWorkspace(): AppRegistries =
    AppRegistries(documents.register(FileDocuments.type, Origin.Core).registry, containers, navigation)
