package dev.easyide.app.ui.screens.workspace.zoom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.ui.foundation.LocalSettings
import dev.easyide.app.ui.foundation.LocalSettingsEditor
import kotlinx.coroutines.delay

/**
 * A font-size setting that a pinch gesture drives.
 *
 * While the fingers move, the size lives in composition state ([size] follows it at once, so
 * the text scales under the fingers); a write to the settings store happens only once the
 * size has been still for [COMMIT_IDLE_MS], not once per step - each write rewrites the
 * settings file. The bounds are the schema's own, so a pinch can never store a value the
 * schema would reject.
 */
@Stable
class FontZoom internal constructor(
    /** The size to render at: the pinch's live value while zooming, else the stored setting. */
    val size: Int,
    val min: Int,
    val max: Int,
    private val setLive: (Int) -> Unit,
) {
    /** Zooms to [start] scaled by [scale]; true when that changed the size. */
    fun zoomTo(start: Int, scale: Float): Boolean {
        val next = PinchZoom.fontSize(start, scale, min, max)
        if (next == size) return false
        setLive(next)
        return true
    }

    companion object {
        /** Quiet time after the last pinch step before the size is stored. */
        const val COMMIT_IDLE_MS = 400L
    }
}

/**
 * [setting]'s size for a document in [languageId], pinch-adjustable.
 *
 * The value is written where it currently comes from: inside the user's `[lang]` block when
 * that is what decides the size for this language, otherwise as the global user value.
 * (A project or environment value still outranks the user layer, by design: project settings win.)
 */
@Composable
fun rememberFontZoom(setting: Setting.IntRange, languageId: String? = null): FontZoom {
    val settings = LocalSettings.current
    val editor = LocalSettingsEditor.current
    val stored = settings.get(setting, languageId)
    val winner = settings.inspect(setting, languageId).winner
    val language = winner.language.takeIf { winner.layer == LayerId.USER }
    var live by remember { mutableStateOf<Int?>(null) }

    // The store caught up with (or was never moved from) the live value: the setting rules again.
    LaunchedEffect(stored) { if (live == stored) live = null }
    LaunchedEffect(live) {
        val pending = live ?: return@LaunchedEffect
        delay(FontZoom.COMMIT_IDLE_MS)
        if (pending == stored) live = null else editor?.set(setting, pending, language)
    }
    val size = live ?: stored
    return remember(size, setting.min, setting.max) { FontZoom(size, setting.min, setting.max) { live = it } }
}
