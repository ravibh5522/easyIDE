package dev.tabcode.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.tabcode.app.R
import dev.tabcode.app.ui.theme.editorColors

/**
 * Terminal panel: tabs across the top, one scrollback below, and an accessory
 * key row pinned to the bottom.
 *
 * The prompt is the last row of the scrollback rather than a separate input
 * box, so typing happens where the output is - the way a real terminal reads.
 * Tapping anywhere in the scrollback focuses the prompt.
 */
@Composable
fun TerminalPane(
    sessions: List<TerminalSession>,
    activeSessionId: String?,
    linuxReady: Boolean,
    isInstalling: Boolean,
    onInputChanged: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onKey: (TerminalKey) -> Unit,
    onCancelCommand: () -> Unit,
    onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onInstallLinux: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = editorColors
    val session = sessions.find { it.id == activeSessionId } ?: sessions.firstOrNull()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Follow output, and keep the prompt visible while typing.
    LaunchedEffect(session?.id, session?.lines?.size) {
        val count = session?.lines?.size ?: 0
        if (count > 0) listState.animateScrollToItem(count)
    }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        TerminalTabBar(
            sessions = sessions,
            activeSessionId = session?.id,
            linuxReady = linuxReady,
            isInstalling = isInstalling,
            onSelectSession = onSelectSession,
            onCloseSession = onCloseSession,
            onNewSession = onNewSession,
            onInstallLinux = onInstallLinux,
        )

        if (session == null) return@Column

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(
                    // No ripple: this is a big invisible tap target for focus,
                    // not a button.
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { focusRequester.requestFocus() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            itemsIndexed(session.lines) { _, line ->
                Text(
                    text = line.text,
                    style = codeTextStyle().copy(
                        color = if (line.isCommand) colors.terminalPrompt else colors.terminalText,
                    ),
                )
            }

            item {
                PromptRow(
                    input = session.input,
                    isRunning = session.isRunning,
                    focusRequester = focusRequester,
                    onInputChanged = onInputChanged,
                    onSubmit = onSubmit,
                    onCancelCommand = onCancelCommand,
                )
            }
        }

        TerminalKeyRow(onKey = onKey)
    }
}

/**
 * The prompt stays editable while a command runs: Enter then writes to the
 * process's stdin, which is how an interactive program gets answered. The
 * spinner and Stop are what mark the difference.
 */
@Composable
private fun PromptRow(
    input: TextFieldValue,
    isRunning: Boolean,
    focusRequester: FocusRequester,
    onInputChanged: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onCancelCommand: () -> Unit,
) {
    val colors = editorColors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = PROMPT, style = codeTextStyle().copy(color = colors.terminalPrompt))

        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(PROGRESS_DP.dp),
                strokeWidth = PROGRESS_STROKE_DP.dp,
                color = colors.terminalPrompt,
            )
        }

        BasicTextField(
            value = input,
            onValueChange = onInputChanged,
            singleLine = true,
            textStyle = codeTextStyle().copy(color = colors.terminalText),
            cursorBrush = SolidColor(colors.terminalText),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
        )

        if (isRunning) {
            TextButton(
                onClick = onCancelCommand,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.terminal_stop),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun TerminalTabBar(
    sessions: List<TerminalSession>,
    activeSessionId: String?,
    linuxReady: Boolean,
    isInstalling: Boolean,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onInstallLinux: () -> Unit,
) {
    val colors = editorColors

    Row(
        modifier = Modifier.fillMaxWidth().background(colors.panel),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tabs scroll; the install action stays pinned. weight() cannot live
        // inside the scrolling row - its width constraint is unbounded there.
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        sessions.forEach { session ->
            val active = session.id == activeSessionId
            Row(
                modifier = Modifier
                    .background(if (active) colors.tabActive else colors.panel)
                    .clickable { onSelectSession(session.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) colors.plainText else colors.gutterText,
                )
                if (sessions.size > 1) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close ${session.title}",
                        tint = colors.gutterText,
                        modifier = Modifier
                            .size(TAB_ICON_DP.dp)
                            .clickable { onCloseSession(session.id) },
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = stringResource(R.string.terminal_new),
            tint = colors.gutterText,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .size(TAB_ICON_DP.dp)
                .clickable(onClick = onNewSession),
        )
        }

        if (!linuxReady) {
            TextButton(
                onClick = onInstallLinux,
                enabled = !isInstalling,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text = if (isInstalling) {
                        stringResource(R.string.terminal_installing)
                    } else {
                        stringResource(R.string.terminal_install_linux)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private const val PROMPT = "$"
private const val PROGRESS_DP = 14
private const val PROGRESS_STROKE_DP = 2
private const val TAB_ICON_DP = 14
