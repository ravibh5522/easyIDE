package dev.easyide.app.ui.shell.host

import androidx.lifecycle.ViewModel
import dev.easyide.app.data.ShellStorage
import dev.easyide.app.ui.foundation.WindowSize
import dev.easyide.app.ui.screens.workspace.layout.Pane
import dev.easyide.app.ui.shell.BackContext
import dev.easyide.app.ui.shell.BackNavigation
import dev.easyide.app.ui.shell.BackStep
import dev.easyide.app.ui.shell.ContainerRef
import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.NavEnv
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.LayoutPreset
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.OpenOptions
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ScopeState
import dev.easyide.app.ui.shell.ShellAction
import dev.easyide.app.ui.shell.ShellEnv
import dev.easyide.app.ui.shell.ShellReducer
import dev.easyide.app.ui.shell.ShellScope
import dev.easyide.app.ui.shell.ShellSnapshot
import dev.easyide.app.ui.shell.ShellState
import dev.easyide.app.ui.shell.ext.ExtRegistries
import dev.easyide.app.ui.shell.ext.ExtShell
import dev.easyide.app.ui.shell.nav.NavItemSource
import dev.easyide.app.ui.shell.nav.NavSettings
import dev.easyide.app.ui.shell.workspace.WorkspaceNav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The app-scope shell: holds the [ShellState], applies [ShellAction]s through the reducer, restores
 * the last snapshot and saves changes (debounced), and derives the navigation items.
 *
 * [state] is null until the snapshot is read: what to restore depends on the window's arrangement,
 * which only the composition knows, so the first [onWindow] starts the restore and the host draws
 * nothing before it lands (one frame of background instead of a flash of the wrong layout).
 * [scope] is the view-model scope; tests pass a test scope.
 */
@OptIn(FlowPreview::class)
class ShellViewModel(
    private val storage: ShellStorage,
    val registries: AppRegistries,
    private val source: NavItemSource,
    settings: Flow<NavSettings>,
    private val scope: CoroutineScope,
    debounceMs: Long = ShellTokens.SAVE_DEBOUNCE_MS,
    extensions: StateFlow<ExtShell> = MutableStateFlow(ExtShell.EMPTY),
    private val runCommand: suspend (String) -> Unit = {},
) : ViewModel(scope) {

    private val mutable = MutableStateFlow<ShellState?>(null)
    val state: StateFlow<ShellState?> = mutable.asStateFlow()

    val navSettings: StateFlow<NavSettings> = settings.stateIn(scope, SharingStarted.Eagerly, NavSettings())

    val navBadges = source.badges

    private val toasts = MutableStateFlow(ToastQueue())

    /** The toast on screen, or null. */
    val toast: StateFlow<String?> = toasts.map { it.current }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * [registries] with what the enabled extensions contribute folded in: their containers, document types, openers and
     * navigation items. The engine's own rules keep core first, so an extension never displaces or removes a core item.
     */
    val effective: StateFlow<AppRegistries> = combine(extensions, source.contributions) { ext, nav ->
        val folded = ExtRegistries.fold(registries, ext).registries
        AppRegistries(folded.documents, folded.containers, ExtRegistries.navigation(registries.navigation, nav).registry)
    }.stateIn(scope, SharingStarted.Eagerly, registries)

    /** The presets extensions offer, beside the built-in ones. */
    val extensionPresets: StateFlow<List<LayoutPreset>> = extensions.map { it.presetList }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val navItems: StateFlow<List<NavItem>> = itemsIn(ShellScope.APP)

    /** The items of an open workspace's navigation surface: the same registry and settings, workspace scope. */
    val workspaceNavItems: StateFlow<List<NavItem>> = itemsIn(ShellScope.WORKSPACE)

    private fun itemsIn(shellScope: ShellScope): StateFlow<List<NavItem>> =
        combine(effective, navSettings) { reg, nav -> visible(reg, nav, shellScope) }
            .stateIn(scope, SharingStarted.Eagerly, visible(registries, NavSettings(), shellScope))

    private var restoring = false

    init {
        scope.launch {
            mutable.filterNotNull()
                .map { ShellSnapshot.encodeApp(it.app, it.arrangement) }
                .distinctUntilChanged()
                .drop(1) // the first value is what was just restored (or the default): nothing to write
                .debounce(debounceMs)
                .collect { storage.write(it) }
        }
    }

    private fun visible(reg: AppRegistries, nav: NavSettings, shellScope: ShellScope): List<NavItem> =
        if (shellScope == ShellScope.WORKSPACE) {
            WorkspaceNav.items(reg.navigation, reg.containers, nav.prefs, source.commands, source::holds)
        } else {
            reg.navigation.visible(shellScope, nav.prefs, NavEnv(source::holds) { it.resolvesIn(reg) })
        }

    /** A container target needs the container; a command target needs a command an extension declared. */
    private fun NavTarget.resolvesIn(reg: AppRegistries): Boolean = when (this) {
        is NavTarget.Container -> reg.containers.byId(id) != null
        is NavTarget.Command -> id in source.commands
    }

    fun typeOf(uri: DocumentUri): DocumentType = effective.value.documents.resolve(uri)

    /** Called with the window on every change; the first call restores, later ones re-fit the layout. */
    fun onWindow(window: WindowSize) {
        val current = mutable.value
        when {
            current == null -> restore(window)
            current.window != window -> dispatch(ShellAction.Resize(window))
        }
    }

    private fun restore(window: WindowSize) {
        if (restoring) return
        restoring = true
        scope.launch {
            val base = ShellState(window = window)
            val app = storage.read()?.let { ShellSnapshot.decodeApp(it, base.arrangement) } ?: ScopeState.app()
            mutable.value = base.copy(app = app)
        }
    }

    private fun dispatch(action: ShellAction) {
        mutable.update { s -> s?.let { ShellReducer.reduce(it, action, ShellEnv(::typeOf, extensionPresets.value)) } }
    }

    fun selectNav(item: NavItem) {
        val containers = effective.value.containers
        when (val target = item.target) {
            is NavTarget.Command -> scope.launch { runCommand(target.id) }
            is NavTarget.Container -> containers.byId(target.id)?.let { spec ->
                dispatch(ShellAction.SelectNav(item.id, ContainerRef(containers.placementOf(spec), spec.id)))
            }
        }
    }

    /** Selects a core destination by id (a Settings link inside Home, the workspace's "open settings"). */
    fun goTo(navId: String) {
        effective.value.navigation.byId(navId)?.let(::selectNav)
    }

    fun open(uri: DocumentUri, options: OpenOptions = OpenOptions(preview = true)) = dispatch(ShellAction.Open(uri, options))

    fun activate(uri: DocumentUri) = dispatch(ShellAction.Activate(0, uri))

    fun keep(uri: DocumentUri) = dispatch(ShellAction.Keep(0, uri))

    fun close(uri: DocumentUri) = dispatch(ShellAction.Close(0, uri))

    fun notify(text: String) = toasts.update { it.show(text) }

    fun toastDismissed() = toasts.update { it.dismissed() }

    fun togglePanel(placement: Placement) = dispatch(ShellAction.TogglePanel(placement))

    fun resizePane(pane: Pane, size: Float?) = dispatch(ShellAction.ResizePane(pane, size))

    /** One Back press through the rules of shell-model.md section 11; what it did tells the host whether to keep handling Back. */
    fun back(): BackStep {
        val current = mutable.value ?: return BackStep.SYSTEM
        val result = BackNavigation.back(current, BackContext())
        mutable.value = result.state
        return result.step
    }
}
