package dev.easyide.app.data.settings

import androidx.annotation.StringRes
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.easyide.app.R

/**
 * Which settings layers may hold a value for a key (docs/extension-sdk/sdk-reference.md
 * "Settings keys"): G user global only; E + environment; P every layer but `[lang]`;
 * L every layer, also inside `[lang]` blocks. Only the user layer exists today.
 */
enum class SettingScope { G, E, P, L }

/** Settings screen grouping, in display order. */
enum class SettingCategory(@StringRes val title: Int) {
    APPEARANCE(R.string.settings_theme_section),
    EDITOR(R.string.settings_category_editor),
    TERMINAL(R.string.settings_category_terminal),
}

/**
 * One declared setting. [decode] is the single validation point: it turns
 * whatever a layer stored into a [T], or null when the value is absent, of the
 * wrong type, or out of range - which [resolve] then skips.
 *
 * [storeKey] is the DataStore key. It equals [key] except for settings that
 * predate the schema, which keep their old key so no stored value is lost.
 */
sealed class Setting<T>(
    val key: String,
    val category: SettingCategory,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val default: T,
    val scope: SettingScope,
    val storeKey: String,
) {
    abstract val prefKey: Preferences.Key<*>

    abstract fun decode(raw: Any?): T?

    /** A value of the runtime type [prefKey] holds. */
    abstract fun encode(value: T): Any

    /**
     * [layers] are raw values from the highest layer to the lowest; the first
     * valid one wins, so an invalid value falls through to the next layer and
     * finally to [default]. Environment and project layers go in front of the
     * user value when they exist.
     */
    fun resolve(layers: List<Any?>): T = layers.firstNotNullOfOrNull(::decode) ?: default

    class Bool(
        key: String, category: SettingCategory, @StringRes title: Int, @StringRes description: Int,
        default: Boolean, scope: SettingScope, storeKey: String = key,
    ) : Setting<Boolean>(key, category, title, description, default, scope, storeKey) {
        override val prefKey = booleanPreferencesKey(storeKey)
        override fun decode(raw: Any?): Boolean? = raw as? Boolean
        override fun encode(value: Boolean): Any = value
    }

    class IntRange(
        key: String, category: SettingCategory, @StringRes title: Int, @StringRes description: Int,
        default: Int, scope: SettingScope, val min: Int, val max: Int, val step: Int = 1,
        storeKey: String = key,
    ) : Setting<Int>(key, category, title, description, default, scope, storeKey) {
        override val prefKey = intPreferencesKey(storeKey)
        override fun decode(raw: Any?): Int? = (raw as? Int)?.takeIf { it in min..max }
        override fun encode(value: Int): Any = value
    }

    /** Stored as the constant's name, so reordering [values] never remaps a saved choice. */
    class Enum<E : kotlin.Enum<E>>(
        key: String, category: SettingCategory, @StringRes title: Int, @StringRes description: Int,
        default: E, scope: SettingScope, val values: List<E>, val label: (E) -> Int,
        storeKey: String = key,
    ) : Setting<E>(key, category, title, description, default, scope, storeKey) {
        override val prefKey = stringPreferencesKey(storeKey)
        override fun decode(raw: Any?): E? = values.find { it.name == raw }
        override fun encode(value: E): Any = value.name
    }

    class Str(
        key: String, category: SettingCategory, @StringRes title: Int, @StringRes description: Int,
        default: String, scope: SettingScope, val pattern: Regex? = null, storeKey: String = key,
    ) : Setting<String>(key, category, title, description, default, scope, storeKey) {
        override val prefKey = stringPreferencesKey(storeKey)
        override fun decode(raw: Any?): String? =
            (raw as? String)?.takeIf { pattern == null || pattern.matches(it) }
        override fun encode(value: String): Any = value
    }

    /**
     * Preferences has no ordered list type (string sets lose order), so the
     * list is one newline-joined string; entries therefore cannot hold '\n'.
     */
    class StrList(
        key: String, category: SettingCategory, @StringRes title: Int, @StringRes description: Int,
        default: List<String>, scope: SettingScope, storeKey: String = key,
    ) : Setting<List<String>>(key, category, title, description, default, scope, storeKey) {
        override val prefKey = stringPreferencesKey(storeKey)
        override fun decode(raw: Any?): List<String>? = (raw as? String)?.let { s ->
            if (s.isEmpty()) emptyList() else s.split(SEPARATOR)
        }
        override fun encode(value: List<String>): Any = value.joinToString(SEPARATOR)

        private companion object {
            const val SEPARATOR = "\n"
        }
    }
}
