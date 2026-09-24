package dev.easyide.app.ui.screens.settings

import dev.easyide.app.data.settings.Setting

/**
 * The fragment of `easyide://settings/extensions#<extension id>`: which extension's settings the
 * Extensions page should bring into view.
 */
object SettingsLink {

    /**
     * The key of the row to mark and scroll to for [extensionId]: the first row of that extension's
     * section on the Extensions page, as the page lists it. Null when the extension contributes no
     * settings, in which case the page just opens.
     */
    fun rowFor(extensionId: String?, settings: List<Setting<*>>): String? {
        if (extensionId.isNullOrEmpty()) return null
        val rows = pageSettings(SettingsCategory.EXTENSIONS, settings)
        return SettingsSearch.sections(rows).firstOrNull { it.owner == extensionId }?.rows?.firstOrNull()?.key
    }
}
