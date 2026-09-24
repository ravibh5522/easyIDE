package dev.easyide.app.data.settings

import androidx.annotation.StringRes
import dev.easyide.app.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Which settings layers may hold a value for a key (docs/extension-sdk/sdk-reference.md
 * "Settings keys"): G user global only; E + environment; P every layer but `[lang]`;
 * L every layer, also inside `[lang]` blocks.
 */
enum class SettingScope {
    G, E, P, L;

    fun allows(layer: LayerId): Boolean = when (layer) {
        LayerId.BUILT_IN, LayerId.EXTENSION, LayerId.USER -> true
        LayerId.ENVIRONMENT -> this != G
        LayerId.PROJECT -> this == P || this == L
    }

    val languageOverridable: Boolean get() = this == L
}

/**
 * How values from several layers combine (LLD sec 2): objects may merge key-wise
 * one or two levels deep; arrays and scalars always replace (VS Code's rule).
 */
enum class Merge(val depth: Int) { REPLACE(0), OBJECT(1), OBJECT_2(2) }

/** Display text that is either a string resource (built-ins) or manifest text (contributions). */
sealed interface Text {
    data class Res(@StringRes val id: Int) : Text
    data class Literal(val text: String) : Text
}

/** Built-in Settings screen grouping, in display order. */
enum class SettingCategory(@StringRes val title: Int) {
    APPEARANCE(R.string.settings_theme_section),
    EDITOR(R.string.settings_category_editor),
    TERMINAL(R.string.settings_category_terminal),
    LANGUAGE_SERVERS(R.string.settings_category_language_servers),
    EXTENSIONS(R.string.settings_category_extensions),
}

/** Where a setting is listed: a built-in category, or one contributed `configuration` section. */
sealed interface SettingGroup {
    data class BuiltIn(val category: SettingCategory) : SettingGroup
    data class Contributed(val owner: String, val title: String, val order: Int?) : SettingGroup
}

/**
 * One declared setting. [decode] is the single validation point: it turns
 * whatever a layer stored into a [T], or null when the value is of the wrong
 * type or out of range - which resolution then skips, falling through to the
 * next lower layer.
 */
sealed class Setting<T>(
    val key: String,
    val group: SettingGroup,
    val title: Text,
    val description: Text,
    val default: T,
    val scope: SettingScope,
    val merge: Merge = Merge.REPLACE,
    /** The value can make the app spawn a process; project values need project trust (LLD sec 12). */
    val execBearing: Boolean = false,
    val keywords: List<String> = emptyList(),
    val deprecation: String? = null,
) {
    /** Device-level: lives in the default profile store and is never exported (LLD sec 14). */
    val appLevel: Boolean get() = SettingsPolicy.isAppLevel(key)

    abstract fun decode(value: JsonElement): T?

    abstract fun encode(value: T): JsonElement

    fun isValid(value: JsonElement): Boolean = decode(value) != null

    class Bool(
        key: String, category: SettingCategory, @StringRes title: Int, @StringRes description: Int,
        default: Boolean, scope: SettingScope,
    ) : Setting<Boolean>(key, SettingGroup.BuiltIn(category), Text.Res(title), Text.Res(description), default, scope) {
        override fun decode(value: JsonElement): Boolean? =
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        override fun encode(value: Boolean): JsonElement = JsonPrimitive(value)
    }

    class IntRange(
        key: String, category: SettingCategory, @StringRes title: Int, @StringRes description: Int,
        default: Int, scope: SettingScope, val min: Int, val max: Int, val step: Int = 1,
    ) : Setting<Int>(key, SettingGroup.BuiltIn(category), Text.Res(title), Text.Res(description), default, scope) {
        override fun decode(value: JsonElement): Int? =
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in min..max }
        override fun encode(value: Int): JsonElement = JsonPrimitive(value)
    }

    /**
     * Stored as the constant's name, so reordering [values] never remaps a saved choice.
     * [aliases] also accepts other spellings of a value, such as VS Code's `true` /
     * `"configuredByTheme"`, so a settings.json copied from VS Code keeps working.
     */
    class Enum<E : kotlin.Enum<E>>(
        key: String, category: SettingCategory, @StringRes title: Int, @StringRes description: Int,
        default: E, scope: SettingScope, val values: List<E>, val label: (E) -> Int,
        private val aliases: (JsonElement) -> E? = { null },
    ) : Setting<E>(key, SettingGroup.BuiltIn(category), Text.Res(title), Text.Res(description), default, scope) {
        override fun decode(value: JsonElement): E? {
            val name = SchemaValidator.stringOrNull(value) ?: return aliases(value)
            return values.find { it.name == name } ?: aliases(value)
        }
        override fun encode(value: E): JsonElement = JsonPrimitive(value.name)
    }

    class Str(
        key: String, category: SettingCategory, @StringRes title: Int, @StringRes description: Int,
        default: String, scope: SettingScope, val pattern: Regex? = null,
    ) : Setting<String>(key, SettingGroup.BuiltIn(category), Text.Res(title), Text.Res(description), default, scope) {
        override fun decode(value: JsonElement): String? =
            SchemaValidator.stringOrNull(value)?.takeIf { pattern == null || pattern.matches(it) }
        override fun encode(value: String): JsonElement = JsonPrimitive(value)
    }

    class StrList(
        key: String, category: SettingCategory, @StringRes title: Int, @StringRes description: Int,
        default: List<String>, scope: SettingScope,
    ) : Setting<List<String>>(key, SettingGroup.BuiltIn(category), Text.Res(title), Text.Res(description), default, scope) {
        override fun decode(value: JsonElement): List<String>? {
            val array = value as? JsonArray ?: return null
            val strings = array.mapNotNull(SchemaValidator::stringOrNull)
            return strings.takeIf { it.size == array.size }
        }
        override fun encode(value: List<String>): JsonElement = JsonArray(value.map(::JsonPrimitive))
    }

    /**
     * A setting from an extension's `configuration` contribution, validated by
     * its own JSON schema. [control] is how the Settings screen edits it; the
     * value type stays JSON because the schema, not Kotlin, defines it.
     */
    class Contributed(
        key: String,
        val owner: String,
        group: SettingGroup.Contributed,
        title: String,
        description: String,
        default: JsonElement,
        scope: SettingScope,
        val schema: JsonObject,
        val control: ContributedControl,
        /** Position within its section (`order`); unordered entries sort after, by key. */
        val order: Int?,
        deprecation: String?,
    ) : Setting<JsonElement>(
        key = key, group = group, title = Text.Literal(title), description = Text.Literal(description),
        default = default, scope = scope,
        merge = if (schema["type"] == JsonPrimitive("object")) Merge.OBJECT else Merge.REPLACE,
        keywords = listOf(owner), deprecation = deprecation,
    ) {
        override fun decode(value: JsonElement): JsonElement? = value.takeIf { SchemaValidator.isValid(schema, it) }
        override fun encode(value: JsonElement): JsonElement = value
    }

    /**
     * A structured built-in value (`lsp.servers`, `editor.codeActionsOnSave`, the object form
     * of `editor.quickSuggestions`). [accepts] is the shape check, so a value of the wrong
     * shape falls through to the next layer like any invalid value. Edited in settings.json.
     */
    class Json(
        key: String, category: SettingCategory, @StringRes title: Int, @StringRes description: Int,
        default: JsonElement, scope: SettingScope, val accepts: (JsonElement) -> Boolean,
        merge: Merge = Merge.REPLACE, execBearing: Boolean = false,
    ) : Setting<JsonElement>(
        key, SettingGroup.BuiltIn(category), Text.Res(title), Text.Res(description), default, scope,
        merge = merge, execBearing = execBearing,
    ) {
        override fun decode(value: JsonElement): JsonElement? = value.takeIf(accepts)
        override fun encode(value: JsonElement): JsonElement = value
    }
}

/** The Settings-screen control for a contributed setting, derived from its schema. */
sealed interface ContributedControl {
    data object Switch : ContributedControl
    data class Choice(val values: List<String>, val descriptions: List<String>) : ContributedControl
    data class Stepper(val min: Int, val max: Int) : ContributedControl
    data object NumberField : ContributedControl
    data class TextField(val pattern: String?) : ContributedControl
    data object StringList : ContributedControl
    /** Objects, arrays of objects, unions: edited in settings.json. */
    data object JsonOnly : ContributedControl
}
