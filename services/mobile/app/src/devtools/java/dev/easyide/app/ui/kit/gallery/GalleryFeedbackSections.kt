package dev.easyide.app.ui.kit.gallery

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.Tone

private val DETERMINATE = listOf(0f, 0.35f, 1f)

/** Banners in every tone (plain, with an action, dismissible, long), empty states, and both progress forms. */
@Composable
fun FeedbackSection() {
    KitSection(stringResource(R.string.gallery_banners_title)) {
        for ((tone, label) in TONE_LABELS) {
            Sample(stringResource(label)) { KitBanner(stringResource(R.string.gallery_banner_text), tone = tone) }
        }
        Sample(stringResource(R.string.gallery_banner_action)) {
            KitBanner(stringResource(R.string.gallery_banner_text), tone = Tone.Warning, action = KitAction(stringResource(R.string.gallery_action_retry)) {}, onDismiss = {})
        }
        Sample(stringResource(R.string.gallery_long_text)) {
            KitBanner(stringResource(R.string.gallery_long_body), tone = Tone.Danger, action = KitAction(stringResource(R.string.gallery_action_retry)) {}, onDismiss = {})
        }
    }
    KitSection(stringResource(R.string.gallery_empty_title), description = stringResource(R.string.gallery_empty_note)) {
        Sample(stringResource(R.string.gallery_empty_with_action)) {
            KitEmptyState(EmptyArt.Prompt, stringResource(R.string.gallery_empty_message), action = KitAction(stringResource(R.string.gallery_empty_action)) {})
        }
        Sample(stringResource(R.string.gallery_empty_plain)) { KitEmptyState(EmptyArt.Search, stringResource(R.string.gallery_empty_message)) }
        Sample(stringResource(R.string.gallery_empty_offline)) { KitEmptyState(EmptyArt.Offline, stringResource(R.string.gallery_long_body)) }
    }
    KitSection(stringResource(R.string.gallery_progress_title)) {
        for (fraction in DETERMINATE) {
            Sample(stringResource(R.string.gallery_progress_fraction, (fraction * PERCENT).toInt())) { KitProgress(fraction) }
        }
        for ((tone, label) in TONE_LABELS) {
            Sample(stringResource(label)) { KitProgress(TONE_FRACTION, tone = tone) }
        }
        Sample(stringResource(R.string.gallery_progress_indeterminate)) { KitProgress(null) }
    }
}

private const val TONE_FRACTION = 0.6f
