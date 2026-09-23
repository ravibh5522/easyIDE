package dev.easyide.extensions.host

import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.settings.ExtensionSettings
import dev.easyide.extensions.settings.ExtensionSettings.bool
import dev.easyide.extensions.settings.SettingsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SafeModeReason { SETTING, LAUNCHER_SHORTCUT, AUTO_CRASH }

/**
 * Whether extensions are off for this session (customization.md sec 11). Entry points: the
 * persisted `extensions.safeMode` setting, the launcher "Start in safe mode" shortcut and the
 * crash-journal verdict; the last two are session-only. Safe mode never rewrites stored
 * enablement, so leaving it restores exactly the previous state.
 *
 * Session changes apply synchronously (the verdict must hold before anything registers);
 * setting changes arrive through [SettingsPort.version].
 */
class SafeModeState(private val settings: SettingsPort, launcherShortcut: Boolean, scope: CoroutineScope) {
    @Volatile private var session: SafeModeReason? = if (launcherShortcut) SafeModeReason.LAUNCHER_SHORTCUT else null
    private val state = MutableStateFlow(current())
    private val suspectsState = MutableStateFlow<List<ExtensionId>>(emptyList())

    val active: StateFlow<SafeModeReason?> = state.asStateFlow()

    /** The crash-journal suspects when safe mode was entered automatically (banner text). */
    val suspects: StateFlow<List<ExtensionId>> = suspectsState.asStateFlow()

    val isActive: Boolean get() = state.value != null

    init {
        scope.launch { settings.version.collect { refresh() } }
    }

    /** Session-only automatic entry after repeated start-up crashes. */
    fun enterAuto(suspects: List<ExtensionId>) {
        suspectsState.value = suspects
        if (session == null) session = SafeModeReason.AUTO_CRASH
        refresh()
    }

    /** "Exit safe mode" for session reasons; the app clears the setting when that was the reason. */
    fun exitSession() {
        session = null
        suspectsState.value = emptyList()
        refresh()
    }

    private fun refresh() { state.value = current() }

    private fun current(): SafeModeReason? = if (settings.bool(ExtensionSettings.SAFE_MODE)) SafeModeReason.SETTING else session
}
