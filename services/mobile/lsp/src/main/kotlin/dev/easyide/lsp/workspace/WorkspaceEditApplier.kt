package dev.easyide.lsp.workspace

import dev.easyide.lsp.docs.DocumentStore
import dev.easyide.lsp.protocol.EditOperation
import dev.easyide.lsp.protocol.TextEdit
import dev.easyide.lsp.protocol.WorkspaceEdit

/** Outcome of one primitive edit in the app. */
sealed interface EditResult {
    data object Ok : EditResult
    data class Failed(val reason: String) : EditResult
}

/**
 * The editor/file-system primitives an edit needs, implemented by the app (`LspWorkspaceBridge`):
 * open tabs are edited in the buffer as one undo unit per file, closed project files through
 * `ProjectFiles`. Targets are already mapped and checked writable.
 */
interface EditPort {
    suspend fun applyTextEdits(target: HostLocation, edits: List<TextEdit>, label: String): EditResult
    suspend fun createFile(target: HostLocation, overwrite: Boolean, ignoreIfExists: Boolean): EditResult
    suspend fun renameFile(from: HostLocation, to: HostLocation, overwrite: Boolean, ignoreIfExists: Boolean): EditResult
    suspend fun deleteFile(target: HostLocation, recursive: Boolean, ignoreIfNotExists: Boolean): EditResult
}

/** `ApplyWorkspaceEditResult`; [failedChange] is the index of the operation that failed. */
data class ApplyResult(val applied: Boolean, val failureReason: String? = null, val failedChange: Int? = null)

/**
 * Applies a [WorkspaceEdit] (rename, code action, formatting, `workspace/applyEdit`) with the
 * advertised `failureHandling: "abort"`: all targets are mapped and checked before anything
 * changes, then operations run in order and stop at the first failure (earlier ones stay).
 *
 * A text edit carrying a version is applied only if the document is still at that version in
 * [store]: edits computed for version N corrupt text at N+1 (lsp-features.md 3.2).
 */
class WorkspaceEditApplier(
    private val mapper: PathMapper,
    private val store: DocumentStore,
    private val port: EditPort,
) {
    suspend fun apply(edit: WorkspaceEdit, label: String): ApplyResult {
        val planned = edit.operations.mapIndexed { i, op ->
            plan(op) ?: return ApplyResult(false, "${targetUri(op)} is outside the project and cannot be edited", i)
        }
        planned.forEachIndexed { i, step ->
            val result = run(step, label)
            if (result is EditResult.Failed) return ApplyResult(false, result.reason, i)
        }
        return ApplyResult(true)
    }

    private sealed interface Step {
        data class Text(val uri: String, val version: Int?, val target: HostLocation, val edits: List<TextEdit>) : Step
        data class Create(val target: HostLocation, val op: EditOperation.Create) : Step
        data class Rename(val from: HostLocation, val to: HostLocation, val op: EditOperation.Rename) : Step
        data class Delete(val target: HostLocation, val op: EditOperation.Delete) : Step
    }

    private fun plan(op: EditOperation): Step? = when (op) {
        is EditOperation.Text -> writable(op.uri)?.let { Step.Text(op.uri, op.version, it, op.edits) }
        is EditOperation.Create -> writable(op.uri)?.let { Step.Create(it, op) }
        is EditOperation.Rename -> {
            val from = writable(op.oldUri)
            val to = writable(op.newUri)
            if (from != null && to != null) Step.Rename(from, to, op) else null
        }
        is EditOperation.Delete -> writable(op.uri)?.let { Step.Delete(it, op) }
    }

    private suspend fun run(step: Step, label: String): EditResult = when (step) {
        is Step.Text -> {
            val current = store.snapshot(step.uri)?.version
            if (step.version != null && current != null && current != step.version) {
                EditResult.Failed("Document changed, try again")
            } else {
                port.applyTextEdits(step.target, step.edits, label)
            }
        }
        is Step.Create -> port.createFile(step.target, step.op.overwrite, step.op.ignoreIfExists)
        is Step.Rename -> port.renameFile(step.from, step.to, step.op.overwrite, step.op.ignoreIfExists)
        is Step.Delete -> port.deleteFile(step.target, step.op.recursive, step.op.ignoreIfNotExists)
    }

    private fun writable(uri: String): HostLocation? = mapper.toHost(uri)?.takeIf { !it.readOnly }

    private fun targetUri(op: EditOperation): String = when (op) {
        is EditOperation.Text -> op.uri
        is EditOperation.Create -> op.uri
        is EditOperation.Rename -> op.newUri
        is EditOperation.Delete -> op.uri
    }
}
