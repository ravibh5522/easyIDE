package dev.easyide.app.ui.devtools

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.gallery.KitGalleryScreen

/** Routes of the developer tools; they are compiled into debug and canary only. */
object DevRoutes {
    const val KIT_GALLERY = "devtools/kit_gallery"
}

fun NavGraphBuilder.devToolsRoutes(nav: NavController) {
    composable(DevRoutes.KIT_GALLERY) { KitGalleryScreen(onBack = { nav.popBackStack() }) }
}

/** The Diagnostics entry that opens the gallery. */
fun LazyListScope.devToolsEntries(nav: NavController) {
    item(key = "devtools") {
        KitSection(stringResource(R.string.devtools_section), Modifier.widthIn(max = Kit.contentMax)) {
            KitRow(
                title = stringResource(R.string.devtools_kit_gallery),
                subtitle = stringResource(R.string.devtools_kit_gallery_hint),
                onClick = { nav.navigate(DevRoutes.KIT_GALLERY) },
                id = "open-kit-gallery",
            )
        }
    }
}
