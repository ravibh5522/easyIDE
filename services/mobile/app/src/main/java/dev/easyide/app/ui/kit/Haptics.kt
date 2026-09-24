package dev.easyide.app.ui.kit

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import dev.easyide.app.ui.props.HapticsLevel

/**
 * Haptic events (identity.md 8). Each maps to one platform constant and to the lowest
 * `appearance.haptics` level at which it plays: [Full] events play only at "full", [Subtle]
 * events at "subtle" and "full". No UI sounds.
 */
enum class HapticEvent(val minimum: HapticsLevel, private val since: Int, private val constant: Int, private val fallback: Int) {
    KeyTap(HapticsLevel.FULL, Build.VERSION_CODES.BASE, HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.KEYBOARD_TAP),
    ModifierLatch(HapticsLevel.FULL, Build.VERSION_CODES.R, HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.KEYBOARD_TAP),
    Snap(HapticsLevel.FULL, Build.VERSION_CODES.BASE, HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.KEYBOARD_TAP),
    PickUp(HapticsLevel.FULL, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, HapticFeedbackConstants.LONG_PRESS),
    Toggle(HapticsLevel.SUBTLE, Build.VERSION_CODES.M, HapticFeedbackConstants.CONTEXT_CLICK, HapticFeedbackConstants.KEYBOARD_TAP),
    LongPress(HapticsLevel.SUBTLE, Build.VERSION_CODES.BASE, HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.LONG_PRESS),
    Success(HapticsLevel.SUBTLE, Build.VERSION_CODES.R, HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.KEYBOARD_TAP),
    Reject(HapticsLevel.SUBTLE, Build.VERSION_CODES.R, HapticFeedbackConstants.REJECT, HapticFeedbackConstants.LONG_PRESS);

    /** True when the user's [level] lets this event play. */
    fun allowedAt(level: HapticsLevel): Boolean = level != HapticsLevel.OFF && (minimum == HapticsLevel.SUBTLE || level == HapticsLevel.FULL)

    internal fun constantFor(sdk: Int): Int = if (sdk >= since) constant else fallback
}

/**
 * The one-shot event a message of this tone plays as it appears: a success confirms, a danger
 * (a rejected or blocked action) rejects, everything else is silent.
 */
fun Tone.hapticEvent(): HapticEvent? = when (this) {
    Tone.Success -> HapticEvent.Success
    Tone.Danger -> HapticEvent.Reject
    else -> null
}

/** Plays [HapticEvent]s through the host view, honouring `appearance.haptics`. */
class Haptics internal constructor(private val view: View, private val level: HapticsLevel) {
    fun play(event: HapticEvent) {
        if (event.allowedAt(level)) view.performHapticFeedback(event.constantFor(Build.VERSION.SDK_INT))
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val level = Kit.feel.haptics
    return remember(view, level) { Haptics(view, level) }
}
