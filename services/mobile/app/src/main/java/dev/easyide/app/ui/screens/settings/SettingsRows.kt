package dev.easyide.app.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.ThemeSettingsSchema
import dev.easyide.app.ui.kit.KitSection

/** What a page needs to draw its rows, the special pickers included. */
internal class PageEnv(
    val ctx: SettingsContext,
    val settings: List<Setting<*>>,
    val themeCards: List<ContributedThemeCard>,
    val themeActions: ThemeActions,
    val keyRows: KeyRowPickerState,
)

/**
 * The row for [setting]: a picker where the value is chosen by looking or from a dynamic list (theme,
 * icon theme, key row), the generic schema row otherwise. Theme and icon theme are user-layer only,
 * so other layers fall back to the generic read-only row.
 */
@Composable
internal fun SettingRowFor(setting: Setting<*>, env: PageEnv, contextLabel: String? = null, inlineChoice: Boolean = false) {
    val ctx = env.ctx
    when {
        setting === SettingsSchema.themeMode && ctx.layer == LayerId.USER -> ThemePickerRow(ctx.snapshot, env.themeCards, env.themeActions)
        setting === SettingsSchema.keyRowsActive -> KeyRowPickerRow(env.keyRows, ctx)
        setting === ThemeSettingsSchema.iconTheme && ctx.layer == LayerId.USER -> IconThemePickerRow(ctx.snapshot, ctx.actions)
        else -> SettingRow(setting, ctx, contextLabel = contextLabel, inlineChoice = inlineChoice)
    }
}

/** The schema settings that belong on [category]'s page, in declared order, without keys another control edits. */
internal fun pageSettings(category: SettingsCategory, settings: List<Setting<*>>): List<Setting<*>> =
    settings.filter { SettingsCategory.of(it) == category && it.key !in SettingsSchema.managedElsewhere }

/**
 * The page's schema rows as sections: built-in rows first, under [title] (the page's own name), then
 * one section per contributing extension. Each header carries its row count and collapses.
 */
@Composable
internal fun CategoryRows(rows: List<Setting<*>>, env: PageEnv, title: String? = null, inlineChoice: Boolean = false) {
    SettingsSearch.sections(rows).forEach { section ->
        val name = section.title?.let { stringResource(R.string.settings_contributed_section, it) } ?: title
        KitSection(name, count = section.rows.size, collapsible = name != null) {
            section.rows.forEach { SettingRowFor(it, env, inlineChoice = inlineChoice) }
        }
    }
}

/** Title and description for every row, resolved once per composition so search does not re-read resources per keystroke. */
@Composable
internal fun rememberSettingText(): (Setting<*>) -> List<String> {
    val resources = LocalContext.current.resources
    return remember(resources) { { s -> listOf(s.title.resolve(resources), s.description.resolve(resources)) } }
}
