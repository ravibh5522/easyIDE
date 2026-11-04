package dev.easyide.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.components.ProseText
import dev.easyide.app.ui.kit.Kit

/**
 * The thin strip under the app while Linux installs in the background after onboarding: the same bar
 * and phase as the rail, so the user can keep working and still sees how far it is. Nothing is drawn
 * once the install is not running; a failure is reported by Home's install prompt and the Install
 * Linux screen, which also hold Cancel and Retry.
 */
@Composable
fun InstallStatusBar(stage: SetupStage, modifier: Modifier = Modifier) {
    val installing = stage as? SetupStage.Installing ?: return
    val phase = installPhaseText(installing.event)
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Kit.control.hPad, vertical = Kit.space.s)) {
        ProseText(stringResource(R.string.setup_installing_title, installing.imageLabel), muted = true)
        InstallSummaryBlock(InstallSummary(installing.fraction, phase), Modifier)
    }
}
