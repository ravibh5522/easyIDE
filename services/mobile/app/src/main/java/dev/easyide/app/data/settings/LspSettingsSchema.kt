package dev.easyide.app.data.settings

import dev.easyide.app.R
import dev.easyide.lsp.TraceLevel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** `editor.acceptSuggestionOnEnter`: `smart` accepts only when that changes more than the typed word. */
enum class AcceptOnEnter { ON, OFF, SMART }

/** `editor.inlayHints.enabled`; `onUnlessPressed` hides hints while Ctrl+Alt is held. */
enum class InlayHintsMode { ON, OFF, ON_UNLESS_PRESSED }

/** `editor.diagnostics.minSeverity`, most severe first (the protocol's order). */
enum class MinSeverity { ERROR, WARNING, INFORMATION, HINT }

/**
 * The language-server settings of sdk-reference "Settings keys" that the LSP integration
 * reads: the global `lsp.*` limits, `lsp.enabled` / `lsp.servers`, and the editor keys of
 * lsp-features.md sec 7 that have a presenter today. Defaults and ranges are the schema's;
 * nothing else in the app states them.
 */
object LspSettingsSchema {

    val enabled = Setting.Bool(
        "lsp.enabled", SettingCategory.LANGUAGE_SERVERS, R.string.setting_lsp_enabled_title,
        R.string.setting_lsp_enabled_desc, default = true, scope = SettingScope.L,
    )

    /** Keyed by server key; each value an object of `easyide.languageServers` fields plus `enabled`. */
    val servers = Setting.Json(
        "lsp.servers", SettingCategory.LANGUAGE_SERVERS, R.string.setting_lsp_servers_title,
        R.string.setting_lsp_servers_desc, default = JsonObject(emptyMap()), scope = SettingScope.P,
        accepts = { it is JsonObject && it.values.all { v -> v is JsonObject } },
        // Per server, fields merge across layers: a project can override one field of a user entry.
        // Its exec-bearing fields (command, env, initializationOptions) are trust-gated per
        // field by ProjectTrust, so the setting itself is not marked exec-bearing.
        merge = Merge.OBJECT_2,
    )

    val trace = Setting.Enum(
        "lsp.trace", SettingCategory.LANGUAGE_SERVERS, R.string.setting_lsp_trace_title,
        R.string.setting_lsp_trace_desc, default = TraceLevel.OFF, scope = SettingScope.G,
        values = TraceLevel.entries, label = ::traceLabel,
    )

    val globalMemoryBudgetMb = Setting.IntRange(
        "lsp.globalMemoryBudgetMb", SettingCategory.LANGUAGE_SERVERS, R.string.setting_lsp_global_budget_title,
        R.string.setting_lsp_global_budget_desc, default = 1200, scope = SettingScope.G, min = 200, max = 8000, step = 100,
    )

    val defaultMemoryBudgetMb = Setting.IntRange(
        "lsp.defaultMemoryBudgetMb", SettingCategory.LANGUAGE_SERVERS, R.string.setting_lsp_default_budget_title,
        R.string.setting_lsp_default_budget_desc, default = 400, scope = SettingScope.G, min = 50, max = 4000, step = 50,
    )

    val maxServers = Setting.IntRange(
        "lsp.maxServers", SettingCategory.LANGUAGE_SERVERS, R.string.setting_lsp_max_servers_title,
        R.string.setting_lsp_max_servers_desc, default = 3, scope = SettingScope.G, min = 1, max = 12,
    )

    val idleShutdownSec = Setting.IntRange(
        "lsp.idleShutdownSec", SettingCategory.LANGUAGE_SERVERS, R.string.setting_lsp_idle_shutdown_title,
        R.string.setting_lsp_idle_shutdown_desc, default = 600, scope = SettingScope.G, min = 30, max = 7200, step = 30,
    )

    val startupTimeoutSec = Setting.IntRange(
        "lsp.startupTimeoutSec", SettingCategory.LANGUAGE_SERVERS, R.string.setting_lsp_startup_timeout_title,
        R.string.setting_lsp_startup_timeout_desc, default = 30, scope = SettingScope.G, min = 5, max = 300, step = 5,
    )

    val restartMaxRetries = Setting.IntRange(
        "lsp.restart.maxRetries", SettingCategory.LANGUAGE_SERVERS, R.string.setting_lsp_max_retries_title,
        R.string.setting_lsp_max_retries_desc, default = 3, scope = SettingScope.G, min = 0, max = 10,
    )

    val restartBackoffMs = Setting.IntRange(
        "lsp.restart.backoffMs", SettingCategory.LANGUAGE_SERVERS, R.string.setting_lsp_backoff_title,
        R.string.setting_lsp_backoff_desc, default = 2000, scope = SettingScope.G, min = 250, max = 60_000, step = 250,
    )

    val didChangeDebounceMs = Setting.IntRange(
        "lsp.didChangeDebounceMs", SettingCategory.LANGUAGE_SERVERS, R.string.setting_lsp_change_debounce_title,
        R.string.setting_lsp_change_debounce_desc, default = 150, scope = SettingScope.G, min = 0, max = 2000, step = 10,
    )

    val requestTimeoutMs = Setting.IntRange(
        "lsp.requestTimeoutMs", SettingCategory.LANGUAGE_SERVERS, R.string.setting_lsp_request_timeout_title,
        R.string.setting_lsp_request_timeout_desc, default = 5000, scope = SettingScope.G, min = 500, max = 60_000, step = 500,
    )

    /** `true`, `false`, or `{other, comments, strings}` booleans. */
    val quickSuggestions = Setting.Json(
        "editor.quickSuggestions", SettingCategory.EDITOR, R.string.setting_quick_suggestions_title,
        R.string.setting_quick_suggestions_desc,
        default = JsonObject(mapOf("other" to JsonPrimitive(true), "comments" to JsonPrimitive(false), "strings" to JsonPrimitive(false))),
        scope = SettingScope.L, accepts = ::isQuickSuggestions,
    )

    val quickSuggestionsDelay = Setting.IntRange(
        "editor.quickSuggestionsDelay", SettingCategory.EDITOR, R.string.setting_quick_suggestions_delay_title,
        R.string.setting_quick_suggestions_delay_desc, default = 10, scope = SettingScope.L, min = 0, max = 2000, step = 10,
    )

    val suggestOnTriggerCharacters = Setting.Bool(
        "editor.suggestOnTriggerCharacters", SettingCategory.EDITOR, R.string.setting_suggest_trigger_title,
        R.string.setting_suggest_trigger_desc, default = true, scope = SettingScope.L,
    )

    val acceptSuggestionOnEnter = Setting.Enum(
        "editor.acceptSuggestionOnEnter", SettingCategory.EDITOR, R.string.setting_accept_on_enter_title,
        R.string.setting_accept_on_enter_desc, default = AcceptOnEnter.ON, scope = SettingScope.L,
        values = AcceptOnEnter.entries, label = ::acceptLabel,
    )

    val parameterHints = Setting.Bool(
        "editor.parameterHints.enabled", SettingCategory.EDITOR, R.string.setting_parameter_hints_title,
        R.string.setting_parameter_hints_desc, default = true, scope = SettingScope.L,
    )

    val hoverEnabled = Setting.Bool(
        "editor.hover.enabled", SettingCategory.EDITOR, R.string.setting_hover_enabled_title,
        R.string.setting_hover_enabled_desc, default = true, scope = SettingScope.L,
    )

    val hoverDelay = Setting.IntRange(
        "editor.hover.delay", SettingCategory.EDITOR, R.string.setting_hover_delay_title,
        R.string.setting_hover_delay_desc, default = 500, scope = SettingScope.L, min = 0, max = 5000, step = 50,
    )

    val inlayHints = Setting.Enum(
        "editor.inlayHints.enabled", SettingCategory.EDITOR, R.string.setting_inlay_hints_title,
        R.string.setting_inlay_hints_desc, default = InlayHintsMode.ON, scope = SettingScope.L,
        values = InlayHintsMode.entries, label = ::inlayLabel,
    )

    val formatOnSave = Setting.Bool(
        "editor.formatOnSave", SettingCategory.EDITOR, R.string.setting_format_on_save_title,
        R.string.setting_format_on_save_desc, default = false, scope = SettingScope.L,
    )

    val formatOnType = Setting.Bool(
        "editor.formatOnType", SettingCategory.EDITOR, R.string.setting_format_on_type_title,
        R.string.setting_format_on_type_desc, default = false, scope = SettingScope.L,
    )

    /** A server key `<extId>/<id>` or a user `lsp.servers` key; empty means none. */
    val defaultFormatter = Setting.Str(
        "editor.defaultFormatter", SettingCategory.EDITOR, R.string.setting_default_formatter_title,
        R.string.setting_default_formatter_desc, default = "", scope = SettingScope.L,
    )

    /** `{kind: bool}`, e.g. `{"source.organizeImports": true}`. */
    val codeActionsOnSave = Setting.Json(
        "editor.codeActionsOnSave", SettingCategory.EDITOR, R.string.setting_code_actions_on_save_title,
        R.string.setting_code_actions_on_save_desc, default = JsonObject(emptyMap()), scope = SettingScope.L,
        accepts = { it is JsonObject && it.values.all(::isBoolean) },
    )

    val lightbulb = Setting.Bool(
        "editor.lightbulb.enabled", SettingCategory.EDITOR, R.string.setting_lightbulb_title,
        R.string.setting_lightbulb_desc, default = true, scope = SettingScope.L,
    )

    val occurrencesHighlight = Setting.Bool(
        "editor.occurrencesHighlight", SettingCategory.EDITOR, R.string.setting_occurrences_title,
        R.string.setting_occurrences_desc, default = true, scope = SettingScope.L,
    )

    val diagnosticsMinSeverity = Setting.Enum(
        "editor.diagnostics.minSeverity", SettingCategory.EDITOR, R.string.setting_min_severity_title,
        R.string.setting_min_severity_desc, default = MinSeverity.HINT, scope = SettingScope.L,
        values = MinSeverity.entries, label = ::severityLabel,
    )

    val diagnosticsShowInGutter = Setting.Bool(
        "editor.diagnostics.showInGutter", SettingCategory.EDITOR, R.string.setting_diagnostics_gutter_title,
        R.string.setting_diagnostics_gutter_desc, default = true, scope = SettingScope.L,
    )

    val diagnosticsShowSquiggles = Setting.Bool(
        "editor.diagnostics.showSquiggles", SettingCategory.EDITOR, R.string.setting_diagnostics_squiggles_title,
        R.string.setting_diagnostics_squiggles_desc, default = true, scope = SettingScope.L,
    )

    val diagnosticsIgnoreSources = Setting.StrList(
        "editor.diagnostics.ignoreSources", SettingCategory.EDITOR, R.string.setting_diagnostics_ignore_title,
        R.string.setting_diagnostics_ignore_desc, default = emptyList(), scope = SettingScope.L,
    )

    val all: List<Setting<*>> = listOf(
        enabled, servers, trace, globalMemoryBudgetMb, defaultMemoryBudgetMb, maxServers, idleShutdownSec,
        startupTimeoutSec, restartMaxRetries, restartBackoffMs, didChangeDebounceMs, requestTimeoutMs,
        quickSuggestions, quickSuggestionsDelay, suggestOnTriggerCharacters, acceptSuggestionOnEnter,
        parameterHints, hoverEnabled, hoverDelay, inlayHints, formatOnSave, formatOnType, defaultFormatter,
        codeActionsOnSave, lightbulb, occurrencesHighlight, diagnosticsMinSeverity, diagnosticsShowInGutter,
        diagnosticsShowSquiggles, diagnosticsIgnoreSources,
    )

    /** The `{other, comments, strings}` keys `editor.quickSuggestions` accepts. */
    val QUICK_SUGGESTION_KEYS = setOf("other", "comments", "strings")

    private fun isBoolean(e: JsonElement): Boolean = e is JsonPrimitive && !e.isString && e.booleanOrNull != null

    private fun isQuickSuggestions(e: JsonElement): Boolean =
        isBoolean(e) || (e is JsonObject && e.keys.all { it in QUICK_SUGGESTION_KEYS } && e.values.all(::isBoolean))

    private fun traceLabel(level: TraceLevel): Int = when (level) {
        TraceLevel.OFF -> R.string.setting_value_off
        TraceLevel.MESSAGES -> R.string.setting_value_trace_messages
        TraceLevel.VERBOSE -> R.string.setting_value_trace_verbose
    }

    private fun acceptLabel(value: AcceptOnEnter): Int = when (value) {
        AcceptOnEnter.ON -> R.string.setting_value_on
        AcceptOnEnter.OFF -> R.string.setting_value_off
        AcceptOnEnter.SMART -> R.string.setting_value_smart
    }

    private fun inlayLabel(value: InlayHintsMode): Int = when (value) {
        InlayHintsMode.ON -> R.string.setting_value_on
        InlayHintsMode.OFF -> R.string.setting_value_off
        InlayHintsMode.ON_UNLESS_PRESSED -> R.string.setting_value_on_unless_pressed
    }

    private fun severityLabel(value: MinSeverity): Int = when (value) {
        MinSeverity.ERROR -> R.string.severity_error
        MinSeverity.WARNING -> R.string.severity_warning
        MinSeverity.INFORMATION -> R.string.severity_information
        MinSeverity.HINT -> R.string.severity_hint
    }
}
