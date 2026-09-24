package dev.easyide.app.extensions.adapters

import dev.easyide.extensions.action.EditorState
import dev.easyide.extensions.action.GuestPaths
import dev.easyide.extensions.action.PredefinedVariable
import dev.easyide.extensions.action.Template
import dev.easyide.extensions.action.VariableRef
import dev.easyide.extensions.action.WorkspaceState
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.manifest.ExtensionDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renders a [Template] with only the variables that resolve synchronously and without
 * side effects: `${config:}` and the editor/workspace variables. For text that is
 * re-rendered often or outside an action (status bar items, `tasks.json` fields);
 * actions use the runtime's full resolver instead.
 *
 * `${command:}`, `${input:}`, `${result:}` and `${env:}` would run commands, prompt or
 * spawn a process per render; they render empty, as does a variable with no value.
 */
object SyncVariables {

    fun render(t: Template, config: (String) -> JsonElement?, editor: EditorState?, workspace: WorkspaceState, owner: Owner): String =
        t.segments.joinToString("") { seg ->
            when (seg) {
                is Template.Segment.Literal -> seg.text
                is Template.Segment.Var -> value(seg.ref, config, editor, workspace, owner).orEmpty()
            }
        }

    /** [text] parsed as a template; text that is not valid template syntax renders as written. */
    fun render(text: String, config: (String) -> JsonElement?, editor: EditorState?, workspace: WorkspaceState, owner: Owner): String =
        when (val p = Template.parse(text)) {
            is Template.Parse.Ok -> render(p.template, config, editor, workspace, owner)
            is Template.Parse.Error -> text
        }

    private fun value(ref: VariableRef, config: (String) -> JsonElement?, editor: EditorState?, ws: WorkspaceState, owner: Owner): String? =
        when (ref) {
            is VariableRef.Config -> config(ref.key)?.let { v -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: v.stringOrNull ?: v.toString() }
            is VariableRef.Predefined -> predefined(ref.variable, editor, ws, owner)
            is VariableRef.Command, is VariableRef.Input, is VariableRef.Result, is VariableRef.Env -> null
        }

    private fun predefined(v: PredefinedVariable, editor: EditorState?, ws: WorkspaceState, owner: Owner): String? {
        val file = editor?.path
        return when (v) {
            PredefinedVariable.WORKSPACE_FOLDER, PredefinedVariable.CWD, PredefinedVariable.FILE_WORKSPACE_FOLDER -> ws.folder
            PredefinedVariable.WORKSPACE_FOLDER_BASENAME -> ws.name
            PredefinedVariable.FILE -> file
            PredefinedVariable.RELATIVE_FILE -> file?.let { GuestPaths.relativeTo(ws.folder, it) }
            PredefinedVariable.FILE_BASENAME -> file?.let(GuestPaths::basename)
            PredefinedVariable.FILE_BASENAME_NO_EXTENSION -> file?.let(GuestPaths::stem)
            PredefinedVariable.FILE_EXTNAME -> file?.let(GuestPaths::extname)
            PredefinedVariable.FILE_DIRNAME -> file?.let(GuestPaths::dirname)
            PredefinedVariable.RELATIVE_FILE_DIRNAME -> file?.let { GuestPaths.relativeTo(ws.folder, GuestPaths.dirname(it)) }
            PredefinedVariable.LINE_NUMBER -> editor?.line?.toString()
            PredefinedVariable.COLUMN -> editor?.column?.toString()
            PredefinedVariable.SELECTED_TEXT -> editor?.selectedText
            PredefinedVariable.CURRENT_WORD -> editor?.currentWord
            PredefinedVariable.LINE_TEXT -> editor?.lineText
            PredefinedVariable.LANGUAGE_ID -> editor?.languageId
            PredefinedVariable.PATH_SEPARATOR -> "/"
            PredefinedVariable.EXTENSION_PATH -> (owner as? Owner.Ext)?.let { ExtensionDescriptor.GUEST_EXTENSIONS_ROOT + it.id.value }
            PredefinedVariable.ENV_ID -> ws.envId
            PredefinedVariable.ENV_NAME -> ws.envName
        }
    }
}
