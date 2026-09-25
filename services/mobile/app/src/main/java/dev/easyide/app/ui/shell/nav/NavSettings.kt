package dev.easyide.app.ui.shell.nav

import dev.easyide.app.ui.shell.NavPrefs

/** The `shell.navigation.*` properties as the surface reads them. */
data class NavSettings(
    val prefs: NavPrefs = NavPrefs(),
    val position: NavPosition = NavPosition.AUTO,
    val labels: NavLabels = NavLabels.AUTO,
)
