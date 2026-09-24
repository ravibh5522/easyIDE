package dev.easyide.app.ui.screens.workspace.lsp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.easyide.app.ui.theme.EditorColors
import dev.easyide.lsp.protocol.CompletionItemKind
import dev.easyide.lsp.protocol.DiagnosticSeverity
import dev.easyide.lsp.protocol.SymbolKind

/** The one table of icons the LSP UI draws: item and symbol kinds, severities. */
internal object LspIcons {

    fun completion(kind: CompletionItemKind?): ImageVector = when (kind) {
        CompletionItemKind.METHOD, CompletionItemKind.FUNCTION, CompletionItemKind.CONSTRUCTOR -> Icons.Filled.Functions
        CompletionItemKind.FIELD, CompletionItemKind.VARIABLE, CompletionItemKind.PROPERTY -> Icons.Filled.DataObject
        CompletionItemKind.CLASS, CompletionItemKind.INTERFACE, CompletionItemKind.STRUCT -> Icons.Filled.Category
        CompletionItemKind.MODULE -> Icons.Filled.ViewModule
        CompletionItemKind.UNIT, CompletionItemKind.VALUE, CompletionItemKind.ENUM,
        CompletionItemKind.ENUM_MEMBER, CompletionItemKind.CONSTANT -> Icons.Filled.Numbers
        CompletionItemKind.KEYWORD -> Icons.Filled.Key
        CompletionItemKind.SNIPPET -> Icons.AutoMirrored.Filled.ShortText
        CompletionItemKind.COLOR -> Icons.Filled.Palette
        CompletionItemKind.FILE -> Icons.Filled.Description
        CompletionItemKind.FOLDER -> Icons.Filled.Folder
        CompletionItemKind.REFERENCE -> Icons.Filled.Link
        CompletionItemKind.EVENT -> Icons.Filled.Bolt
        CompletionItemKind.OPERATOR -> Icons.Filled.Calculate
        CompletionItemKind.TYPE_PARAMETER -> Icons.Filled.Code
        CompletionItemKind.TEXT, null -> Icons.Filled.TextFields
    }

    fun symbol(kind: SymbolKind): ImageVector = when (kind) {
        SymbolKind.FUNCTION, SymbolKind.METHOD, SymbolKind.CONSTRUCTOR, SymbolKind.OPERATOR -> Icons.Filled.Functions
        SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.STRUCT, SymbolKind.OBJECT -> Icons.Filled.Category
        SymbolKind.MODULE, SymbolKind.NAMESPACE, SymbolKind.PACKAGE -> Icons.Filled.ViewModule
        SymbolKind.FILE -> Icons.Filled.Description
        SymbolKind.ENUM, SymbolKind.ENUM_MEMBER, SymbolKind.CONSTANT, SymbolKind.NUMBER, SymbolKind.BOOLEAN -> Icons.Filled.Numbers
        SymbolKind.EVENT -> Icons.Filled.Bolt
        SymbolKind.KEY -> Icons.Filled.Key
        SymbolKind.STRING -> Icons.Filled.TextFields
        else -> Icons.Filled.DataObject
    }

    fun severity(s: DiagnosticSeverity): ImageVector = when (s) {
        DiagnosticSeverity.ERROR -> Icons.Filled.Error
        DiagnosticSeverity.WARNING -> Icons.Filled.Warning
        DiagnosticSeverity.INFORMATION, DiagnosticSeverity.HINT -> Icons.Filled.Info
    }

    fun severityTint(s: DiagnosticSeverity, colors: EditorColors): Color = when (s) {
        DiagnosticSeverity.ERROR -> colors.decorations.diagnosticError
        DiagnosticSeverity.WARNING -> colors.decorations.diagnosticWarning
        DiagnosticSeverity.INFORMATION -> colors.decorations.diagnosticInformation
        DiagnosticSeverity.HINT -> colors.decorations.diagnosticHint
    }

    val quickFix: ImageVector get() = Icons.Filled.Lightbulb

    /** The same glyph as the gutter's code lens marker. */
    val codeLens: ImageVector get() = Icons.Filled.MoreHoriz
}
