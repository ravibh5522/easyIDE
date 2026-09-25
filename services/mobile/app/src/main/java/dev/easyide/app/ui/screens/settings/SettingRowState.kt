package dev.easyide.app.ui.screens.settings

import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingsPolicy
import dev.easyide.app.data.settings.SettingsSnapshot

/** Why a row is read-only in the selected layer. */
enum class RowBlock {
    /** The key's scope excludes this layer (a G key in the Project layer). */
    LAYER_SCOPE,

    /** The key decides which code runs, so a project file may not hold it. */
    PROTECTED_IN_LAYER,

    /** The key cannot be set inside a `[lang]` block. */
    NOT_PER_LANGUAGE,
}

/**
 * What a settings row shows about one setting in one layer (and language), worked out without
 * Compose so the rules are testable: whether it can be edited, whether this layer holds a value,
 * which layer wins, and whether the stored value is being ignored.
 */
data class RowState(
    val block: RowBlock?,
    val modifiedHere: Boolean,
    /** The layer whose value is in effect; null while the default applies. */
    val winnerLayer: LayerId?,
    /** Set when a layer above the selected one wins ("Overridden by Project"). */
    val overriddenBy: LayerId?,
    val invalidHere: Boolean,
    /** No extension may write this key; the row says so. */
    val protectedKey: Boolean,
) {
    val editable: Boolean get() = block == null

    companion object {
        fun of(setting: Setting<*>, snapshot: SettingsSnapshot, layer: LayerId, language: String?): RowState {
            val winner = snapshot.inspect(setting, language).winner.layer
            return RowState(
                block = blockOf(setting, layer, language),
                modifiedHere = snapshot.isSetIn(setting, layer, language),
                winnerLayer = winner.takeIf { it != LayerId.BUILT_IN },
                overriddenBy = winner.takeIf { it.ordinal > layer.ordinal },
                invalidHere = snapshot.isInvalidIn(setting, layer, language),
                protectedKey = SettingsPolicy.isExtensionUnwritable(setting.key),
            )
        }

        fun blockOf(setting: Setting<*>, layer: LayerId, language: String?): RowBlock? = when {
            !setting.scope.allows(layer) -> RowBlock.LAYER_SCOPE
            !SettingsPolicy.layerMayHold(layer, setting.key) -> RowBlock.PROTECTED_IN_LAYER
            language != null && !setting.scope.languageOverridable -> RowBlock.NOT_PER_LANGUAGE
            else -> null
        }
    }
}
