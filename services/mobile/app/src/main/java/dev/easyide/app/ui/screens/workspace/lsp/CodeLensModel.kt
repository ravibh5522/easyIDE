package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.lsp.client.FromServer
import dev.easyide.lsp.protocol.CodeLens
import dev.easyide.lsp.protocol.Command
import dev.easyide.lsp.protocol.Location
import kotlinx.serialization.json.JsonArray

/** One lens of a document: its server's answer plus the presenter's id and anchor line. */
data class LensEntry(val id: String, val lens: FromServer<CodeLens>, val line: Int) {
    val command: Command? get() = lens.value.command

    /** What the gutter popup lists; an unresolved lens shows a placeholder until resolved. */
    val title: String? get() = command?.title?.takeIf { it.isNotBlank() }
}

/** What running a lens's command means for the client (lsp-features.md 4.16). */
sealed interface LensAction {
    /** `editor.action.showReferences` / `editor.action.peekLocations`: the references list. */
    data class ShowLocations(val locations: List<Location>) : LensAction

    /** Any other command: `workspace/executeCommand` on the server that produced the lens. */
    data class Execute(val command: Command) : LensAction

    /** A title-only lens (no command): nothing to run. */
    data object None : LensAction
}

/** Pure code lens rules: line grouping, what to resolve, and client-side command mapping. */
object CodeLensModel {

    /** VS Code client commands servers use in lenses, whose arguments are `[uri, position, locations]`. */
    private val SHOW_LOCATION_COMMANDS = setOf("editor.action.showReferences", "editor.action.peekLocations", "editor.action.goToLocations")

    /** Stable ids (`<version>:<index>`) and anchor lines, in the servers' order. */
    fun entries(lenses: List<FromServer<CodeLens>>, version: Int?): List<LensEntry> =
        lenses.mapIndexed { i, l -> LensEntry("${version ?: 0}:$i", l, l.value.range.start.line) }

    /** Lenses anchored on [line], in the servers' order. */
    fun onLine(entries: List<LensEntry>, line: Int): List<LensEntry> = entries.filter { it.line == line }

    /**
     * Lenses in [lines] that still need `codeLens/resolve`, at most [limit]: resolving is lazy,
     * only for what can be seen, so a file with thousands of lenses costs a screenful.
     */
    fun toResolve(entries: List<LensEntry>, lines: IntRange, alreadyAsked: Set<String>, limit: Int): List<LensEntry> =
        entries.asSequence()
            .filter { it.command == null && it.line in lines && it.id !in alreadyAsked }
            .take(limit)
            .toList()

    fun actionFor(command: Command?): LensAction {
        command ?: return LensAction.None
        if (command.command.isBlank()) return LensAction.None
        if (command.command in SHOW_LOCATION_COMMANDS) {
            val locations = (command.arguments?.getOrNull(2) as? JsonArray)?.mapNotNull(Location::fromJson)
            if (locations != null) return LensAction.ShowLocations(locations)
        }
        return LensAction.Execute(command)
    }
}
