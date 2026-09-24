package dev.easyide.extensions.whenclause

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A typed context key: providers in `:app` set values through these so a key can never
 * receive the wrong JSON type. Names and types are the sdk-reference "When-clause context"
 * table.
 */
sealed class ContextKey<T>(val name: String) {
    abstract fun encode(value: T): JsonElement

    class Bool(name: String) : ContextKey<Boolean>(name) {
        override fun encode(value: Boolean): JsonElement = JsonPrimitive(value)
    }

    /** [values], when non-empty, is the documented closed set (e.g. `inputMode`). */
    class Str(name: String, val values: Set<String> = emptySet()) : ContextKey<String>(name) {
        override fun encode(value: String): JsonElement {
            require(values.isEmpty() || value in values) { "$name does not take '$value'" }
            return JsonPrimitive(value)
        }
    }

    class StrList(name: String) : ContextKey<List<String>>(name) {
        override fun encode(value: List<String>): JsonElement = JsonArray(value.map(::JsonPrimitive))
    }
}

/** Every sdk-reference context key; parameterised keys are functions. */
object ContextKeys {
    val editorLangId = ContextKey.Str("editorLangId")
    val resourceLangId = ContextKey.Str("resourceLangId")
    val resourceExtname = ContextKey.Str("resourceExtname")
    val resourceFilename = ContextKey.Str("resourceFilename")
    val resourceDirname = ContextKey.Str("resourceDirname")
    val resourcePath = ContextKey.Str("resourcePath")
    val resourceIsFolder = ContextKey.Bool("resourceIsFolder")
    val editorFocus = ContextKey.Bool("editorFocus")
    val editorTextFocus = ContextKey.Bool("editorTextFocus")
    val editorReadonly = ContextKey.Bool("editorReadonly")
    val activeEditorIsDirty = ContextKey.Bool("activeEditorIsDirty")
    val editorHasSelection = ContextKey.Bool("editorHasSelection")
    val editorHasMultipleSelections = ContextKey.Bool("editorHasMultipleSelections")
    val inSnippetMode = ContextKey.Bool("inSnippetMode")
    val suggestWidgetVisible = ContextKey.Bool("suggestWidgetVisible")
    val hoverVisible = ContextKey.Bool("hoverVisible")
    val peekVisible = ContextKey.Bool("peekVisible")
    val terminalFocus = ContextKey.Bool("terminalFocus")
    val terminalProcessRunning = ContextKey.Bool("terminalProcessRunning")
    val explorerFocus = ContextKey.Bool("explorerFocus")
    val scmFocus = ContextKey.Bool("scmFocus")
    val focusedView = ContextKey.Str("focusedView")
    val focusedStage = ContextKey.Str("focusedStage")
    val hardwareKeyboard = ContextKey.Bool("hardwareKeyboard")
    val inputMode = ContextKey.Str("inputMode", setOf("touch", "stylus", "mouse", "keyboard"))
    val windowSizeClass = ContextKey.Str("windowSizeClass", setOf("compact", "medium", "expanded"))
    val devicePosture = ContextKey.Str("devicePosture", setOf("flat", "book", "tabletop"))
    val envId = ContextKey.Str("envId")
    val envState = ContextKey.Str("envState", setOf(ENV_READY, "provisioning", "failed", "stopped"))
    val envBackend = ContextKey.Str("envBackend", setOf("proot", "chroot"))
    val envDistro = ContextKey.Str("envDistro")
    val envArch = ContextKey.Str("envArch")
    val gitRepo = ContextKey.Bool("gitRepo")
    val gitBranch = ContextKey.Str("gitBranch")
    val gitDirty = ContextKey.Bool("gitDirty")
    val isOffline = ContextKey.Bool("isOffline")
    val isSafeMode = ContextKey.Bool("isSafeMode")

    fun stageVisible(stageId: String) = ContextKey.Bool("$STAGE_VISIBLE$stageId")
    fun envHasCommand(command: String) = ContextKey.Bool("$ENV_HAS_COMMAND$command")
    fun lspReady(language: String) = ContextKey.Bool("$LSP_READY$language")
    fun lspState(language: String) = ContextKey.Str("$LSP_STATE$language", setOf("off", "starting", "ready", "crashed", "overBudget"))
    fun lspSupports(language: String, feature: String) = ContextKey.Bool("$LSP_SUPPORTS$language:$feature")
    fun extensionEnabled(id: String) = ContextKey.Bool("$EXTENSION_ENABLED$id")

    const val ENV_READY = "ready"
    const val CONFIG_PREFIX = "config."
    private const val STAGE_VISIBLE = "stageVisible:"
    private const val ENV_HAS_COMMAND = "envHasCommand:"
    private const val LSP_READY = "lspReady:"
    private const val LSP_STATE = "lspState:"
    private const val LSP_SUPPORTS = "lspSupports:"
    private const val EXTENSION_ENABLED = "extensionEnabled:"

    private val NAMES: Set<String> = listOf(
        editorLangId, resourceLangId, resourceExtname, resourceFilename, resourceDirname, resourcePath, resourceIsFolder,
        editorFocus, editorTextFocus, editorReadonly, activeEditorIsDirty, editorHasSelection, editorHasMultipleSelections,
        inSnippetMode, suggestWidgetVisible, hoverVisible, peekVisible, terminalFocus, terminalProcessRunning,
        explorerFocus, scmFocus, focusedView, focusedStage, hardwareKeyboard, inputMode, windowSizeClass, devicePosture,
        envId, envState, envBackend, envDistro, envArch, gitRepo, gitBranch, gitDirty, isOffline, isSafeMode,
    ).mapTo(HashSet()) { it.name }

    private val PREFIXES = listOf(CONFIG_PREFIX, STAGE_VISIBLE, ENV_HAS_COMMAND, LSP_READY, LSP_STATE, LSP_SUPPORTS, EXTENSION_ENABLED)

    /** Whether validate knows [name]; unknown keys are legal (undefined) but warned about. */
    fun isKnown(name: String): Boolean = name in NAMES || PREFIXES.any { name.startsWith(it) && name.length > it.length }
}
