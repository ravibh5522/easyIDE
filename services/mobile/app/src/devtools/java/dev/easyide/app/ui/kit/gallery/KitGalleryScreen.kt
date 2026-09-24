package dev.easyide.app.ui.kit.gallery

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitScaffold
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.theme.EasyIdeTheme
import kotlinx.coroutines.launch

/**
 * Every kit primitive in every state on one page, re-themed live by the controls at the top.
 * The page builds its own [GalleryConfig] and passes it to `EasyIdeTheme`, the path the app takes
 * for the real settings, so what it shows is what those settings would produce. Nothing is saved.
 */
@Composable
fun KitGalleryScreen(onBack: () -> Unit) {
    var config by remember { mutableStateOf(GalleryConfig()) }
    val base = LocalDensity.current
    // The font size is the system's, so it sits above the theme, which multiplies its own scale onto it.
    val density = remember(base, config.fontScale) { Density(base.density, config.fontScale) }
    CompositionLocalProvider(LocalDensity provides density) {
        EasyIdeTheme(themeMode = config.mode, appearance = config.appearance()) {
            GalleryPage(config, { config = it }, onBack)
        }
    }
}

@Composable
private fun GalleryPage(config: GalleryConfig, onConfig: (GalleryConfig) -> Unit, onBack: () -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    KitScaffold(
        title = stringResource(R.string.gallery_title),
        onBack = onBack,
        actions = {
            KitIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.gallery_to_controls), { scope.launch { list.animateScrollToItem(0) } })
        },
        banner = { KitBanner(stringResource(R.string.gallery_banner)) },
    ) { insets ->
        LazyColumn(Modifier.kitTag("gallery-list"), list, contentPadding = PaddingValues(bottom = insets.calculateBottomPadding() + Kit.space.xxl)) {
            item(key = "controls") { GalleryControls(config, onConfig) }
            item(key = "rows") { RowsSection() }
            item(key = "groups") { GroupsSection() }
            item(key = "feedback") { FeedbackSection() }
            item(key = "buttons") { ButtonsSection() }
            item(key = "tags") { TagsSection() }
            item(key = "fields") { FieldsSection() }
            item(key = "toggles") { TogglesSection() }
            item(key = "navigation") { NavigationSection() }
            item(key = "overlays") { OverlaysSection() }
            item(key = "motifs") { MotifsSection() }
            item(key = "icons") { IconsSection() }
        }
    }
}
