package dev.easyide.app.ui.shell

import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement
import dev.easyide.app.ui.screens.workspace.layout.StageRule
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A restored workspace: its state, plus the opaque per-document state blobs the document types wrote. */
data class WorkspaceRestore(val state: ScopeState, val docStates: Map<DocumentUri, String>)

/**
 * The persisted form of the shell (shell-model.md section 12), versioned so a later app can change
 * it. App scope keeps the last destination and each destination's selection; a workspace keeps, per
 * arrangement, its layout, plus the stage with every tab. Restore is tolerant by construction:
 * unknown or newer content is dropped, malformed URIs are skipped, a document whose type is missing
 * stays as a tab (it resolves to the "unavailable" placeholder until the type returns), and the
 * result is fitted to the window it is restored into. A file that cannot be read yields null and the
 * caller starts from the default.
 */
object ShellSnapshot {
    const val VERSION = 1

    private const val APP = "app"
    private const val WORKSPACE = "workspace"

    fun encodeApp(app: ScopeState, arrangement: PaneArrangement): String = buildJsonObject {
        put("v", VERSION)
        put("scope", APP)
        put("nav", app.nav)
        put("selection", buildJsonObject { app.selection.toSortedMap().forEach { (nav, uri) -> put(nav, uri.toString()) } })
        put("layouts", SnapshotJson.layouts(arrangement, app))
    }.toString()

    fun decodeApp(json: String, arrangement: PaneArrangement): ScopeState? {
        val root = read(json, APP) ?: return null
        val layouts = SnapshotJson.layoutsFrom(root["layouts"])
        val nav = SnapshotJson.text(root["nav"]) ?: CoreShell.HOME
        val selection = (root["selection"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
            SnapshotJson.text(v)?.let(DocumentUri::parse)?.let { k to it }
        }.toMap()
        val base = ScopeState.app()
        val current = layouts[arrangement] ?: base.layout
        val shown = selection[nav]?.let { EditorStage(listOf(EditorGroup().open(it, preview = true))) } ?: EditorStage()
        return base.copy(nav = nav, layout = current.constrain(rule(arrangement)), saved = layouts - arrangement, stage = shown, selection = selection)
    }

    fun encodeWorkspace(
        workspace: ScopeState,
        arrangement: PaneArrangement,
        typeOf: (DocumentUri) -> DocumentType,
        docStates: Map<DocumentUri, String> = emptyMap(),
    ): String {
        val stage = workspace.stage
        val saved = stage.documents.map { it.key }.filter { typeOf(it).restorable }.toSet()
        return buildJsonObject {
            put("v", VERSION)
            put("scope", WORKSPACE)
            put("nav", workspace.nav)
            put("layouts", SnapshotJson.layouts(arrangement, workspace))
            put("stage", SnapshotJson.stage(stage) { it.key in saved })
            put("docState", buildJsonObject {
                docStates.filterKeys { it in saved }.toSortedMap(compareBy<DocumentUri> { it.toString() }).forEach { (uri, blob) -> put(uri.toString(), blob) }
            })
        }.toString()
    }

    fun decodeWorkspace(json: String, arrangement: PaneArrangement): WorkspaceRestore? {
        val root = read(json, WORKSPACE) ?: return null
        val layouts = SnapshotJson.layoutsFrom(root["layouts"])
        val base = ScopeState.workspace(arrangement)
        val current = layouts[arrangement] ?: base.layout
        val stage = SnapshotJson.stageFrom(root["stage"]).fit(ShellLimits.groupCapacity(arrangement))
        val open = stage.documents.map { it.key }.toSet()
        val states = (root["docState"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
            val uri = DocumentUri.parse(k)?.takeIf { it in open }
            val blob = SnapshotJson.text(v)
            if (uri != null && blob != null) uri to blob else null
        }.toMap()
        val scope = base.copy(
            nav = SnapshotJson.text(root["nav"]) ?: base.nav,
            layout = current.constrain(rule(arrangement)),
            saved = layouts - arrangement,
            stage = stage,
        )
        return WorkspaceRestore(scope, states)
    }

    private fun rule(arrangement: PaneArrangement) = StageRule.of(arrangement)

    /** The root object when [json] parses, is of [scope], and is a version this build understands. */
    private fun read(json: String, scope: String): JsonObject? {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val version = (root["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
        if (version !in 1..VERSION || SnapshotJson.text(root["scope"]) != scope) return null
        return root
    }
}
