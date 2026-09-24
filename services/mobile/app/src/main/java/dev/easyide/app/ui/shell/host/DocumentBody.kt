package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.shell.DocumentRegistry
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.theme.EasyIdeFonts

/**
 * The body of one document: its type's renderer, or the placeholder when the type is unavailable or
 * has no renderer. Keyed by the document so a renderer's remembered state never leaks between two.
 */
@Composable
fun DocumentBody(
    uri: DocumentUri,
    documents: DocumentRegistry,
    renderers: DocumentRendererRegistry,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    key(uri.key) {
        Box(modifier.fillMaxSize()) {
            when (val choice = renderers.choose(documents.resolve(uri))) {
                is DocumentChoice.Render -> choice.renderer.Render(uri, Modifier.fillMaxSize())
                DocumentChoice.Unavailable -> UnavailableDocument(uri, onClose)
            }
        }
    }
}

/** What a tab of a missing type shows: what it was (the URI) and a way to let go of it (shell-model.md section 5). */
@Composable
private fun UnavailableDocument(uri: DocumentUri, onClose: () -> Unit) {
    Column(Modifier.padding(Kit.space.l)) {
        KitEmptyState(
            art = EmptyArt.Offline,
            message = stringResource(R.string.shell_document_unavailable),
            action = KitAction(stringResource(R.string.shell_document_unavailable_close), onClose),
        )
        BasicText(uri.toString(), Modifier.padding(horizontal = Kit.space.l), style = Kit.type.bodySmall.copy(color = Kit.colors.textMuted, fontFamily = EasyIdeFonts.mono))
    }
}
