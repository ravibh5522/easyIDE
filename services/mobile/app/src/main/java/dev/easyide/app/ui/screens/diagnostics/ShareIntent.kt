package dev.easyide.app.ui.screens.diagnostics

import android.app.Activity
import android.content.Context
import android.content.Intent
import dev.easyide.app.R

private const val TEXT_MIME = "text/plain"

/**
 * The system share sheet for [text] (a report from `DiagnosticsSharing.text`). Shared by the
 * Diagnostics screen and the crash-recovery dialog's caller. A plain-text send: nothing is
 * uploaded by the app, the user picks where it goes.
 */
fun shareChooser(context: Context, text: String): Intent {
    val send = Intent(Intent.ACTION_SEND)
        .setType(TEXT_MIME)
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diag_share_subject))
        .putExtra(Intent.EXTRA_TEXT, text)
    val chooser = Intent.createChooser(send, context.getString(R.string.diag_share_chooser))
    // Started from a non-Activity context (a service, the application) it needs its own task.
    return if (context is Activity) chooser else chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
