package dev.easyide.extensions.contrib

import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.settings.ExtensionSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One contribution point's entries, built-ins first then `EnabledSet` order. A store emits
 * only when its own list changed, so enabling a theme pack does not wake the keymap.
 */
class ContributionStore<T> internal constructor(val kind: ContributionRef.Kind) {
    private val state = MutableStateFlow<List<Owned<T>>>(emptyList())
    val entries: StateFlow<List<Owned<T>>> = state.asStateFlow()

    internal fun publish(list: List<Owned<T>>) { state.value = list }
}

/** What the contribution inspector shows for one ref (ECO-32). */
data class InspectorEntry(
    val ref: ContributionRef,
    val owner: Owner,
    val pointer: String,
    /** The settings key hiding it, when `workbench.contributions.hidden` lists it. */
    val hiddenBy: String?,
    /** Collisions this ref took part in, as winner or loser. */
    val conflicts: List<ContributionConflict>,
)

/** Which owners a registry update added, removed or left alone (by id and version). */
data class RegistryDiff(val added: List<ExtensionId>, val removed: List<ExtensionId>, val unchanged: List<ExtensionId>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
}

/**
 * Holds every contribution of the built-ins and the enabled extensions in typed stores
 * (extension-runtime.md sec 7). It renders and runs nothing: adapters in `:app` project the
 * stores into the command registry, keymap, settings schema, theme catalog and so on.
 *
 * [snapshot] is the atomic view (every store at one version); the per-point stores are
 * for observers that care about one point. Updates are serialised; each store emits at
 * most once per update.
 */
class ContributionRegistry(private val builtIn: Contributions = Contributions.EMPTY) {
    private val lock = Any()
    private var registered: List<RegisteredExtension> = emptyList()
    private val state = MutableStateFlow(ContributionResolver.resolve(0, builtIn, emptyList()))
    val snapshot: StateFlow<ContributionSnapshot> = state.asStateFlow()

    val commands = ContributionStore<CommandContribution>(ContributionRef.Kind.COMMAND)
    val menus = ContributionStore<MenuItemContribution>(ContributionRef.Kind.MENU)
    val keybindings = ContributionStore<KeybindingContribution>(ContributionRef.Kind.KEYBINDING)
    val configuration = ContributionStore<ConfigurationProperty>(ContributionRef.Kind.CONFIGURATION)
    val configurationDefaults = ContributionStore<ConfigurationDefault>(ContributionRef.Kind.CONFIGURATION)
    val languages = ContributionStore<LanguageContribution>(ContributionRef.Kind.LANGUAGE)
    val grammars = ContributionStore<GrammarContribution>(ContributionRef.Kind.GRAMMAR)
    val languageConfigurations = ContributionStore<LanguageConfigurationContribution>(ContributionRef.Kind.LANGUAGE_CONFIGURATION)
    val snippets = ContributionStore<SnippetContribution>(ContributionRef.Kind.SNIPPET)
    val themes = ContributionStore<ThemeContribution>(ContributionRef.Kind.THEME)
    val iconThemes = ContributionStore<IconThemeContribution>(ContributionRef.Kind.ICON_THEME)
    val viewContainers = ContributionStore<ViewContainerContribution>(ContributionRef.Kind.VIEW_CONTAINER)
    val views = ContributionStore<ViewContribution>(ContributionRef.Kind.VIEW)
    val viewsWelcome = ContributionStore<ViewWelcomeContribution>(ContributionRef.Kind.VIEW_WELCOME)
    val taskDefinitions = ContributionStore<TaskDefinitionContribution>(ContributionRef.Kind.TASK_DEFINITION)
    val problemMatchers = ContributionStore<ProblemMatcherContribution>(ContributionRef.Kind.PROBLEM_MATCHER)
    val walkthroughs = ContributionStore<WalkthroughContribution>(ContributionRef.Kind.WALKTHROUGH)
    val stages = ContributionStore<StageContribution>(ContributionRef.Kind.STAGE)
    val statusBarItems = ContributionStore<StatusBarItemContribution>(ContributionRef.Kind.STATUS_BAR)
    val keyRows = ContributionStore<KeyRowContribution>(ContributionRef.Kind.KEY_ROW)
    val languageServers = ContributionStore<LanguageServerContribution>(ContributionRef.Kind.SERVER)
    val sandbox = ContributionStore<SandboxContribution>(ContributionRef.Kind.SANDBOX)
    val viewData = ContributionStore<ViewDataContribution>(ContributionRef.Kind.VIEW_DATA)

    init { publish(state.value) }

    /**
     * Replaces the enabled set ([enabled] in `EnabledSet` order). Owners whose `(id, version)`
     * is unchanged keep their entries; removed owners leave every store; added owners enter
     * atomically with the rest of the update. A call that changes nothing emits nothing.
     */
    fun update(enabled: List<RegisteredExtension>): RegistryDiff = synchronized(lock) {
        val before = registered.associateBy { it.id }
        val after = enabled.associateBy { it.id }
        val diff = RegistryDiff(
            added = enabled.filter { before[it.id]?.version != it.version }.map { it.id },
            removed = registered.filter { after[it.id]?.version != it.version }.map { it.id },
            unchanged = enabled.filter { before[it.id]?.version == it.version }.map { it.id },
        )
        val orderChanged = registered.map { it.id } != enabled.map { it.id }
        if (diff.isEmpty && !orderChanged) return diff
        registered = enabled.toList()
        val next = ContributionResolver.resolve(state.value.version + 1, builtIn, registered)
        publish(next)
        state.value = next
        diff
    }

    fun add(ext: RegisteredExtension): RegistryDiff = synchronized(lock) { update(registered.filter { it.id != ext.id } + ext) }

    fun remove(id: ExtensionId): RegistryDiff = synchronized(lock) { update(registered.filter { it.id != id }) }

    /** The enabled extensions as last registered, in `EnabledSet` order. */
    fun registered(): List<RegisteredExtension> = synchronized(lock) { registered }

    /** Owner of the winning command [commandId], or null when no one contributes it. */
    fun commandOwner(commandId: String): Owner? = state.value.commands.firstOrNull { it.value.command == commandId }?.owner

    /** Setting keys [owner] owns after conflict resolution (`setConfig` "own keys"). */
    fun ownedSettings(owner: Owner): Set<String> = state.value.configuration.filter { it.owner == owner }.mapTo(HashSet()) { it.value.key }

    fun inspect(ref: ContributionRef, hidden: Collection<String> = emptyList()): InspectorEntry? {
        val snap = state.value
        val entry = storeEntries(snap, ref.kind).firstOrNull { it.ref == ref } ?: return null
        return InspectorEntry(
            ref, entry.owner, entry.pointer,
            hiddenBy = if (ref.toString() in hidden) ExtensionSettings.WORKBENCH_HIDDEN else null,
            conflicts = snap.conflicts.filter { it.ref.kind == ref.kind && it.ref.id == ref.id },
        )
    }

    private fun storeEntries(s: ContributionSnapshot, kind: ContributionRef.Kind): List<Owned<*>> = when (kind) {
        ContributionRef.Kind.COMMAND -> s.commands
        ContributionRef.Kind.MENU -> s.menus
        ContributionRef.Kind.KEYBINDING -> s.keybindings
        ContributionRef.Kind.CONFIGURATION -> s.configuration + s.configurationDefaults
        ContributionRef.Kind.LANGUAGE -> s.languages
        ContributionRef.Kind.GRAMMAR -> s.grammars
        ContributionRef.Kind.LANGUAGE_CONFIGURATION -> s.languageConfigurations
        ContributionRef.Kind.SNIPPET -> s.snippets
        ContributionRef.Kind.THEME -> s.themes
        ContributionRef.Kind.ICON_THEME -> s.iconThemes
        ContributionRef.Kind.VIEW_CONTAINER -> s.viewContainers
        ContributionRef.Kind.VIEW -> s.views
        ContributionRef.Kind.VIEW_WELCOME -> s.viewsWelcome
        ContributionRef.Kind.TASK_DEFINITION -> s.taskDefinitions
        ContributionRef.Kind.PROBLEM_MATCHER -> s.problemMatchers
        ContributionRef.Kind.WALKTHROUGH -> s.walkthroughs
        ContributionRef.Kind.STAGE -> s.stages
        ContributionRef.Kind.STATUS_BAR -> s.statusBarItems
        ContributionRef.Kind.KEY_ROW -> s.keyRows
        ContributionRef.Kind.SERVER -> s.languageServers
        ContributionRef.Kind.SANDBOX -> s.sandbox
        ContributionRef.Kind.VIEW_DATA -> s.viewData
    }

    private fun publish(s: ContributionSnapshot) {
        commands.publish(s.commands); menus.publish(s.menus); keybindings.publish(s.keybindings)
        configuration.publish(s.configuration); configurationDefaults.publish(s.configurationDefaults)
        languages.publish(s.languages); grammars.publish(s.grammars); languageConfigurations.publish(s.languageConfigurations)
        snippets.publish(s.snippets); themes.publish(s.themes); iconThemes.publish(s.iconThemes)
        viewContainers.publish(s.viewContainers); views.publish(s.views); viewsWelcome.publish(s.viewsWelcome)
        taskDefinitions.publish(s.taskDefinitions); problemMatchers.publish(s.problemMatchers); walkthroughs.publish(s.walkthroughs)
        stages.publish(s.stages); statusBarItems.publish(s.statusBarItems); keyRows.publish(s.keyRows)
        languageServers.publish(s.languageServers); sandbox.publish(s.sandbox); viewData.publish(s.viewData)
    }
}
