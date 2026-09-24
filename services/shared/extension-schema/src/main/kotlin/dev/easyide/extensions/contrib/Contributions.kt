package dev.easyide.extensions.contrib

import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.Template
import dev.easyide.extensions.whenclause.WhenExpr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A file inside a package: [path] is the normalised package-relative path (what manifests
 * and diagnostics name), [hostPath] the absolute Android path the app reads. Host paths
 * never reach guests.
 */
data class PackageFile(val path: String, val hostPath: String)

/** Every contribution of one extension (or of the built-ins), one list per sdk-reference point. */
data class Contributions(
    val commands: List<CommandContribution> = emptyList(),
    val menus: List<MenuItemContribution> = emptyList(),
    val keybindings: List<KeybindingContribution> = emptyList(),
    val configuration: List<ConfigurationProperty> = emptyList(),
    val configurationDefaults: List<ConfigurationDefault> = emptyList(),
    val languages: List<LanguageContribution> = emptyList(),
    val grammars: List<GrammarContribution> = emptyList(),
    val languageConfigurations: List<LanguageConfigurationContribution> = emptyList(),
    val snippets: List<SnippetContribution> = emptyList(),
    val themes: List<ThemeContribution> = emptyList(),
    val iconThemes: List<IconThemeContribution> = emptyList(),
    val viewContainers: List<ViewContainerContribution> = emptyList(),
    val views: List<ViewContribution> = emptyList(),
    val viewsWelcome: List<ViewWelcomeContribution> = emptyList(),
    val taskDefinitions: List<TaskDefinitionContribution> = emptyList(),
    val problemMatchers: List<ProblemMatcherContribution> = emptyList(),
    val walkthroughs: List<WalkthroughContribution> = emptyList(),
    val stages: List<StageContribution> = emptyList(),
    val statusBarItems: List<StatusBarItemContribution> = emptyList(),
    val keyRows: List<KeyRowContribution> = emptyList(),
    val languageServers: List<LanguageServerContribution> = emptyList(),
    val sandbox: SandboxContribution? = null,
    val viewData: List<ViewDataContribution> = emptyList(),
) {
    companion object { val EMPTY = Contributions() }
}

data class CommandContribution(
    val command: String, val title: String, val category: String?, val shortTitle: String?,
    val icon: CommandIcon?, val enablement: WhenExpr?,
)

sealed interface CommandIcon {
    /** An easyIDE icon token name, resolved by the app's icon table. */
    data class Token(val name: String) : CommandIcon
    /** A monochrome SVG in the package, tinted by the theme. */
    data class Svg(val file: PackageFile) : CommandIcon
}

/** [group] and [order] come from VS Code's `"group": "name@order"`. */
data class MenuItemContribution(
    val menuId: String, val command: String, val alt: String?, val `when`: WhenExpr?, val group: String?, val order: Double?,
)

/** `win` is ignored and `cmd` maps to Meta (sdk-reference); [key] is the author's chord text. */
data class KeybindingContribution(
    val command: String, val key: String, val mac: String?, val linux: String?, val `when`: WhenExpr?, val args: JsonElement?,
)

/** One contributed setting; [schema] is the property object (a JSON Schema plus VS Code UI fields). */
data class ConfigurationProperty(
    val key: String, val section: String?, val sectionOrder: Int?, val schema: JsonObject,
    val default: JsonElement?, val scope: SettingScopeName?, val order: Int?,
)

/** VS Code `scope` values plus easyIDE's `environment` and `project`; mapping in customization.md sec 5.1. */
enum class SettingScopeName(val wire: String) {
    APPLICATION("application"), MACHINE("machine"), MACHINE_OVERRIDABLE("machine-overridable"), WINDOW("window"),
    RESOURCE("resource"), LANGUAGE_OVERRIDABLE("language-overridable"), ENVIRONMENT("environment"), PROJECT("project");

    companion object {
        fun parse(wire: String): SettingScopeName? = entries.firstOrNull { it.wire == wire }
    }
}

/** A `configurationDefaults` entry; [language] set for `"[lang]": {...}` blocks. */
data class ConfigurationDefault(val key: String, val language: String?, val value: JsonElement)

data class LanguageContribution(
    val id: String, val aliases: List<String>, val extensions: List<String>, val filenames: List<String>,
    val filenamePatterns: List<String>, val firstLine: String?, val mimetypes: List<String>, val configuration: PackageFile?,
)

data class GrammarContribution(
    val language: String?, val scopeName: String, val file: PackageFile, val embeddedLanguages: Map<String, String>,
    val injectTo: List<String>, val tokenTypes: Map<String, String>,
)

/** The `languageConfiguration` point: the file a `languages[].configuration` names. */
data class LanguageConfigurationContribution(val language: String, val file: PackageFile)

data class SnippetContribution(val language: String?, val file: PackageFile)

enum class UiTheme(val wire: String) {
    LIGHT("vs"), DARK("vs-dark"), HIGH_CONTRAST_DARK("hc-black"), HIGH_CONTRAST_LIGHT("hc-light");

    companion object {
        fun parse(wire: String): UiTheme? = entries.firstOrNull { it.wire == wire }
    }
}

data class ThemeContribution(val id: String?, val label: String, val uiTheme: UiTheme, val file: PackageFile)

data class IconThemeContribution(val id: String, val label: String, val file: PackageFile)

enum class ViewContainerLocation(val wire: String) { ACTIVITY_BAR("activitybar"), PANEL("panel") }

data class ViewContainerContribution(val location: ViewContainerLocation, val id: String, val title: String, val icon: CommandIcon)

data class ViewContribution(val containerId: String, val id: String, val name: String, val `when`: WhenExpr?)

data class ViewWelcomeContribution(val view: String, val contents: String, val `when`: WhenExpr?)

data class TaskDefinitionContribution(val type: String, val required: List<String>, val properties: JsonObject, val `when`: WhenExpr?)

/** Kept as JSON: the matcher engine (EXT-30) reads VS Code's own shape, including string references. */
data class ProblemMatcherContribution(val name: String, val definition: JsonObject)

data class WalkthroughContribution(
    val id: String, val title: String, val description: String, val `when`: WhenExpr?, val steps: List<WalkthroughStep>,
)

data class WalkthroughStep(
    val id: String, val title: String, val description: String?, val `when`: WhenExpr?,
    val image: PackageFile?, val markdown: PackageFile?, val svg: PackageFile?, val completionEvents: List<String>,
)

enum class StagePlacement(val wire: String) {
    LEFT("left"), MAIN("main"), RIGHT("right"), BOTTOM("bottom");

    companion object {
        fun parse(wire: String): StagePlacement? = entries.firstOrNull { it.wire == wire }
    }
}

data class StageContribution(
    val id: String, val title: String, val icon: CommandIcon?, val defaultStage: StagePlacement, val views: List<String>, val `when`: WhenExpr?,
)

enum class StatusBarAlignment(val wire: String) { LEFT("left"), RIGHT("right") }

data class StatusBarItemContribution(
    val id: String, val text: Template, val tooltip: Template?, val command: String?, val alignment: StatusBarAlignment,
    val priority: Int, val `when`: WhenExpr?,
)

/** What a key-row key does (customization.md sec 10). */
sealed interface KeyAction {
    data class Insert(val text: String) : KeyAction
    data class Snippet(val body: String) : KeyAction
    data class Key(val chord: String) : KeyAction
    data class Command(val id: String) : KeyAction
}

data class RowKey(val label: String, val action: KeyAction, val longPress: KeyAction?)

data class KeyRowContribution(val id: String, val title: String, val `when`: WhenExpr?, val keys: List<RowKey>)

/** A language server definition; the global key is `<extId>/<id>` ([key]), so two packs cannot collide. */
data class LanguageServerContribution(
    val key: String, val id: String, val languages: List<String>, val command: List<Template>, val env: Map<String, Template>,
    val initializationOptions: JsonObject, val settingsSection: String?, val rootMarkers: List<String>,
    val memoryBudgetMb: Int?, val idleShutdownSec: Int?, val startupTimeoutSec: Int?,
    val featuresOnly: List<String>?, val featuresExclude: List<String>, val priority: Int,
)

data class InstallStep(val id: String, val title: String, val run: Template, val `when`: WhenExpr?)

data class SandboxContribution(
    val requires: List<String>, val install: List<InstallStep>, val verify: Template?, val uninstall: List<Template>,
)

enum class ViewDataKind(val wire: String) { LIST("list"), TREE("tree") }

data class ViewDataContribution(val viewId: String, val kind: ViewDataKind, val from: Action, val refreshOn: List<String>)
