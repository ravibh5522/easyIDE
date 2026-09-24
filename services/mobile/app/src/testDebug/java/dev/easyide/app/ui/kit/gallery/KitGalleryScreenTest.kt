package dev.easyide.app.ui.kit.gallery

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import dev.easyide.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The gallery renders every section and its controls really re-theme the page (debug and canary code, so debug tests only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class KitGalleryScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun str(@StringRes id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private val sectionHeaders = listOf(
        R.string.gallery_controls_title, R.string.gallery_rows_title, R.string.gallery_groups_title, R.string.gallery_banners_title,
        R.string.gallery_buttons_title, R.string.gallery_tags_title, R.string.gallery_fields_title, R.string.gallery_toggles_title,
        R.string.gallery_tabs_title, R.string.gallery_overlays_title, R.string.gallery_motifs_title,
    )

    private fun scrollTo(index: Int) = compose.onNodeWithTag("kit:gallery-list").performScrollToIndex(index)

    @Test fun `every section composes when scrolled to`() {
        compose.setContent { KitGalleryScreen(onBack = {}) }
        sectionHeaders.forEachIndexed { index, header ->
            scrollTo(index)
            compose.onNodeWithText(str(header).uppercase()).assertExists()
        }
    }

    @Test fun `density control changes the height of a row`() {
        compose.setContent { KitGalleryScreen(onBack = {}) }
        // The first row of a group sits one hairline above the clip, so its bounds read 1dp short; take the fifth.
        val row = str(R.string.gallery_row_selected)
        compose.onNodeWithText(str(R.string.gallery_density_spacious)).performClick()
        scrollTo(1)
        assertEquals(56.dp, compose.onNodeWithText(row).getBoundsInRoot().height)
        scrollTo(0)
        compose.onNodeWithText(str(R.string.gallery_density_compact)).performClick()
        scrollTo(1)
        // Compact asks for 40dp; the touch floor lifts it to 44.
        assertEquals(44.dp, compose.onNodeWithText(row).getBoundsInRoot().height)
    }

    @Test fun `font scale control grows text`() {
        compose.setContent { KitGalleryScreen(onBack = {}) }
        val title = str(R.string.gallery_font_scale)
        val before = compose.onNodeWithText(title).getBoundsInRoot().height
        compose.onNodeWithText(str(R.string.gallery_font_scale_value, 2f)).performClick()
        assertTrue(compose.onNodeWithText(title).getBoundsInRoot().height > before)
    }
}
