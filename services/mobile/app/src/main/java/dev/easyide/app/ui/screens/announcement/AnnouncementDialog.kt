package dev.easyide.app.ui.screens.announcement

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.telemetry.PushMessage

/** The pushed image beside the body; the notification's large icon at dialog scale. */
private val IMAGE_SIZE = 48.dp

/**
 * The in-app copy of a pushed announcement, so it is seen even when the notification was missed or
 * notifications are off. Any answer, including Back, counts as seen: [onDone] clears it.
 */
@Composable
fun AnnouncementDialog(message: PushMessage, image: ImageBitmap?, onDone: () -> Unit) {
    val uri = LocalUriHandler.current
    val open = message.link?.let { link ->
        KitAction(message.action ?: stringResource(R.string.announcement_open)) {
            // Boundary: no app may handle the link (no browser); the dialog still closes.
            runCatching { uri.openUri(link) }
            onDone()
        }
    }
    KitDialog(
        title = message.title,
        onDismiss = onDone,
        confirm = open,
        dismiss = KitAction(stringResource(if (open == null) R.string.announcement_close else R.string.announcement_later), onDone),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Kit.space.m), verticalAlignment = Alignment.CenterVertically) {
            image?.let { Image(it, contentDescription = null, Modifier.size(IMAGE_SIZE)) }
            BasicText(message.body, Modifier.weight(1f), style = Kit.text.body.copy(color = Kit.colors.plainText))
        }
    }
}
