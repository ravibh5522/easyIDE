package dev.easyide.app.ui.screens.workspace.ext

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import dev.easyide.app.extensions.ExtensionsContainer
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.foundation.WindowSize
import dev.easyide.extensions.contrib.StagePlacement

/** The last input kind (`inputMode` context key), updated from pointer and key events. */
class InputModeState {
    var mode by mutableStateOf(TOUCH)

    fun onKey() { mode = KEYBOARD }

    companion object {
        const val TOUCH = "touch"
        const val STYLUS = "stylus"
        const val MOUSE = "mouse"
        const val KEYBOARD = "keyboard"
    }
}

/** Observes pointer types without consuming anything, so every child still gets its events. */
fun Modifier.trackInputMode(state: InputModeState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val type = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()?.type ?: continue
            state.mode = when (type) {
                PointerType.Touch -> InputModeState.TOUCH
                PointerType.Stylus, PointerType.Eraser -> InputModeState.STYLUS
                PointerType.Mouse -> InputModeState.MOUSE
                else -> state.mode
            }
        }
    }
}

/** The stages as the extension host sees them: whether each shows, and a request to show one. The shell implements it. */
interface StageAccess {
    val leftVisible: Boolean
    val rightVisible: Boolean
    val bottomVisible: Boolean
    fun show(placement: StagePlacement)
}

/**
 * The workspace screen's side of the extension host: reports UI context keys, applies
 * stage requests from actions (`revealStage`, a focused `runInTerminal`) and shows
 * extension notices (failed commands, plain messages) in the workspace snackbar.
 */
@Composable
fun ExtensionEffects(
    host: WorkspaceExtensionHost,
    extensions: ExtensionsContainer,
    stages: StageAccess,
    hardwareKeyboard: Boolean,
    windowSize: WindowSize,
    inputMode: InputModeState,
    terminalFocus: Boolean,
    editorFocus: Boolean,
    snackbar: SnackbarHostState,
) {
    SideEffect {
        host.context.setUi(
            UiContext(
                terminalFocus = terminalFocus,
                editorFocus = editorFocus,
                hardwareKeyboard = hardwareKeyboard,
                inputMode = inputMode.mode,
                windowSizeClass = when (windowSize.width) {
                    WidthClass.COMPACT -> "compact"
                    WidthClass.MEDIUM -> "medium"
                    WidthClass.EXPANDED -> "expanded"
                },
                stagesVisible = mapOf(
                    StagePlacement.LEFT.wire to stages.leftVisible,
                    StagePlacement.RIGHT.wire to stages.rightVisible,
                    StagePlacement.BOTTOM.wire to stages.bottomVisible,
                    StagePlacement.MAIN.wire to true,
                ),
            ),
        )
    }
    LaunchedEffect(host) { host.stageRequests.collect { stages.show(it.stage) } }
    LaunchedEffect(extensions) {
        extensions.ui.notices.collect { snackbar.showSnackbar(it) }
    }
    ExtensionPromptHost(extensions.ui)
}

/** Remembered [InputModeState] for a screen. */
@Composable
fun rememberInputMode(): InputModeState = remember { InputModeState() }

/** Whether a hardware keyboard is attached and in use: the on-screen key rows and the input dock stand down for it. */
@Composable
fun rememberHardwareKeyboard(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
        configuration.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
}
