package dev.easyide.app.ui.screens.workspace.files

import androidx.compose.runtime.compositionLocalOf

/**
 * The `.gitignore` rules the workspace has read, provided by the workspace screen so the
 * explorer can filter with them without every pane in between carrying the parameter.
 */
val LocalIgnoreIndex = compositionLocalOf { IgnoreIndex.EMPTY }
