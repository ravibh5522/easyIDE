package dev.easyide.app.ui.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.ui.theme.Spacing

/**
 * Install Linux outside first-run: what Home's prompt opens for a user who
 * skipped the environment step. The same content as onboarding, in a screen with
 * its own Back; leaving while installing cancels it (the download resumes later).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallLinuxScreen(
    setup: EnvironmentSetupViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by setup.state.collectAsStateWithLifecycle()
    val leave = {
        setup.cancel()
        onBack()
    }
    BackHandler(onBack = leave)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                EnvironmentSetupContent(
                    state = state,
                    onImageSelected = setup::onImageSelected,
                    onInstall = setup::install,
                    onCancel = setup::cancel,
                    onChooseAnother = setup::backToChoosing,
                )
                if (state.stage is SetupStage.Ready) {
                    Button(onClick = onBack) { Text(stringResource(R.string.setup_done)) }
                }
            }
        }
    }
}

private val CONTENT_MAX_WIDTH = 520.dp
