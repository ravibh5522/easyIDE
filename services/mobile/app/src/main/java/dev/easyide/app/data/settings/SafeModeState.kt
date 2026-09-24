package dev.easyide.app.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Why safe mode is on (LLD sec 11). Only [SETTING] survives a restart. */
enum class SafeModeReason { SETTING, LAUNCHER_SHORTCUT, AUTO_CRASH }

/**
 * Safe mode: every non-built-in extension off and project exec-bearing values
 * ignored, while all validated data (settings, keybindings) still applies.
 * Built from the default user store directly - not from resolved settings -
 * because resolution itself depends on it (project exec values), and
 * `extensions.safeMode` is app-level, so it only ever lives there.
 */
class SafeModeState(private val defaultUser: DataStoreUserLayer) {

    private val session = MutableStateFlow<SafeModeReason?>(null)

    private val persisted: Flow<Boolean> = defaultUser.doc
        .map { doc -> doc.plain[SettingsSchema.safeMode.key]?.let(SettingsSchema.safeMode::decode) ?: false }
        .distinctUntilChanged()

    /** Null when off. A session reason wins so the banner explains the launch the user just made. */
    val active: Flow<SafeModeReason?> = combine(persisted, session) { p, s -> s ?: if (p) SafeModeReason.SETTING else null }
        .distinctUntilChanged()

    /** Launcher shortcut or crash-journal verdict: this process only, stored enablement untouched. */
    fun enterForSession(reason: SafeModeReason) {
        require(reason != SafeModeReason.SETTING) { "the setting reason is persisted through extensions.safeMode" }
        session.value = reason
    }

    /**
     * Clears the session reason and, only if it is set, the persisted setting.
     * The caller recreates the activity so everything starts without safe mode.
     */
    suspend fun exit(): Result<Unit> {
        session.value = null
        if (!persisted.first()) return Result.success(Unit)
        return defaultUser.write(listOf(SettingEdit(SettingsSchema.safeMode.key, null, null)))
    }
}
