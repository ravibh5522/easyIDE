package dev.easyide.app.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.easyide.app.R
import dev.easyide.app.ui.foundation.currentWindowSize
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitScaffold
import dev.easyide.app.ui.kit.Tone
import dev.easyide.sandbox.external.ExternalFolderSync

/**
 * Settings as its own screen, for as long as navigation still opens it that way: the panel and the
 * page of the shell's model side by side on a wide window, the list and then the pushed page on a
 * phone. The shell hosts [SettingsPanel] and [SettingsPage] directly and this adapter goes away with
 * the last route that uses it.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    externalFolderSync: ExternalFolderSync,
    onOpenExtensions: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wide = currentWindowSize().width.atLeastMedium
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val host = remember(externalFolderSync, onOpenExtensions, onOpenDiagnostics) {
        SettingsHost(externalFolderSync, onOpenExtensions, onOpenDiagnostics, onNotify = { notice = it })
    }
    val shown = page ?: SettingsCategory.APPEARANCE.id.takeIf { wide }
    val pushed = !wide && page != null
    BackHandler(enabled = pushed) { page = null }

    KitScaffold(
        title = if (pushed) stringResource(SettingsCategory.ofId(page)?.title ?: R.string.nav_settings) else stringResource(R.string.nav_settings),
        onBack = if (pushed) ({ page = null }) else onBack,
        banner = notice?.let { text ->
            { KitBanner(text, Modifier.padding(Kit.space.s), Tone.Info, onDismiss = { notice = null }) }
        },
        maxContentWidth = if (wide) Dp.Infinity else Kit.contentMax,
        modifier = modifier,
    ) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                SettingsPanel(
                    viewModel, shown, { page = it },
                    Modifier.width(Kit.control.panelWidth).fillMaxHeight().border(Kit.hairline, Kit.colors.panelBorder),
                )
                SettingsPage(viewModel, shown.orEmpty(), host, Modifier.weight(1f).fillMaxHeight())
            }
        } else if (page == null) {
            SettingsPanel(viewModel, null, { page = it }, Modifier.fillMaxSize())
        } else {
            SettingsPage(viewModel, page.orEmpty(), host, Modifier.fillMaxSize())
        }
    }
}
