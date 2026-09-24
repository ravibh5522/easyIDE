package dev.easyide.app.ui.screens.onboarding

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.components.ProseText
import dev.easyide.app.ui.kit.CursorBlock
import dev.easyide.app.ui.kit.CursorStyle
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.PromptGlyph
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.kitMono
import kotlinx.coroutines.delay

/** The step title types itself once (identity.md 2.3), 60ms per character; reduced motion shows it whole. */
private const val TYPE_STEP_MS = 60L

/**
 * A step's title. The full text is always laid out, with the characters not yet typed drawn
 * transparent, so the block does not grow as it types and a screen reader reads the whole title.
 * [onTyped] is called once the title is complete, so coming back to a step does not type it again.
 */
@Composable
internal fun StepTitle(@StringRes title: Int, alreadyTyped: Boolean, onTyped: () -> Unit, modifier: Modifier = Modifier) {
    val text = stringResource(title)
    val reduce = Kit.motion.reduce
    var shown by remember(text) { mutableIntStateOf(if (alreadyTyped || reduce) text.length else 0) }
    LaunchedEffect(text, reduce) {
        while (shown < text.length) {
            delay(TYPE_STEP_MS)
            shown++
        }
        onTyped()
    }
    val colors = Kit.colors
    val styled: AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(color = colors.plainText)) { append(text.take(shown)) }
        withStyle(SpanStyle(color = Color.Transparent)) { append(text.drop(shown)) }
    }
    BasicText(styled, modifier.semantics { heading(); contentDescription = text }, style = Kit.type.headlineMedium)
}

/** The mark: the prompt glyph, the name and a block cursor that blinks only where the flow is waiting for a first tap. */
@Composable
internal fun Mark(blinking: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Kit.space.s),
    ) {
        PromptGlyph(color = Kit.colors.plainText, height = Kit.space.xl)
        BasicText(
            stringResource(R.string.app_name),
            style = Kit.type.headlineMedium.kitMono().copy(color = Kit.colors.plainText),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        CursorBlock(style = if (blinking) CursorStyle.Blinking else CursorStyle.Solid, height = Kit.space.xl)
    }
}

@Composable
internal fun WelcomeBody(showMark: Boolean, alreadyTyped: Boolean, onTyped: () -> Unit) {
    Column(Modifier.padding(Kit.space.l), verticalArrangement = Arrangement.spacedBy(Kit.space.m)) {
        if (showMark) Mark(blinking = true)
        StepTitle(R.string.onboarding_title, alreadyTyped, onTyped)
        ProseText(stringResource(R.string.onboarding_body))
        ProseText(stringResource(R.string.onboarding_footnote), muted = true)
    }
}

/** A permission step: what it is for and whether it is in place now (a word and a tag, never colour alone). */
@Composable
internal fun PermissionBody(
    @StringRes title: Int,
    @StringRes body: Int,
    granted: Boolean,
    @StringRes grantedText: Int,
    @StringRes pendingText: Int,
    alreadyTyped: Boolean,
    onTyped: () -> Unit,
) {
    Column(Modifier.padding(start = Kit.space.l, end = Kit.space.l, top = Kit.space.l), verticalArrangement = Arrangement.spacedBy(Kit.space.m)) {
        StepTitle(title, alreadyTyped, onTyped)
        ProseText(stringResource(body))
    }
    KitSection(null) { StatusRow(granted, stringResource(if (granted) grantedText else pendingText)) }
}

@Composable
internal fun DoneBody(status: DeviceStatus, environmentReady: Boolean, alreadyTyped: Boolean, onTyped: () -> Unit) {
    Column(Modifier.padding(start = Kit.space.l, end = Kit.space.l, top = Kit.space.l)) {
        StepTitle(R.string.onboarding_done_title, alreadyTyped, onTyped)
    }
    KitSection(null) {
        StatusRow(status.batteryUnrestricted, stringResource(if (status.batteryUnrestricted) R.string.onboarding_done_battery_on else R.string.onboarding_done_battery_off))
        if (Build.VERSION.SDK_INT >= OnboardingSteps.NOTIFICATION_PERMISSION_MIN_SDK) {
            StatusRow(status.notificationsAllowed, stringResource(if (status.notificationsAllowed) R.string.onboarding_done_notifications_on else R.string.onboarding_done_notifications_off))
        }
        StatusRow(environmentReady, stringResource(if (environmentReady) R.string.onboarding_done_linux_on else R.string.onboarding_done_linux_off))
    }
    ProseText(stringResource(R.string.onboarding_done_settings_note), Modifier.padding(start = Kit.space.l, end = Kit.space.l, top = Kit.space.m), muted = true)
}

@Composable
private fun StatusRow(on: Boolean, text: String) {
    KitRow(
        title = text,
        trailing = {
            KitTag(stringResource(if (on) R.string.flow_tag_on else R.string.flow_tag_off), tone = if (on) Tone.Success else Tone.Warning)
        },
    )
}
