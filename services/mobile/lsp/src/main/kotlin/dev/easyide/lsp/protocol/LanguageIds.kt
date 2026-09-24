package dev.easyide.lsp.protocol

/**
 * The editor names a language after its grammar (`tsx`, `jsx`); servers expect the LSP /
 * VS Code language identifiers (`typescriptreact`, `javascriptreact`). Only the wire form
 * in `textDocument/didOpen` changes: server matching, settings and `editorLangId` keep
 * the editor's id. Ids not listed already match (python, go, cpp, shellscript, ...).
 */
object LanguageIds {
    private val WIRE = mapOf(
        "tsx" to "typescriptreact",
        "jsx" to "javascriptreact",
    )

    fun wire(editorId: String): String = WIRE[editorId] ?: editorId
}
