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
import dev.easyide.app.ui.shell.NavRegistry
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.OpenOptions
import dev.easyide.app.ui.shell.Origin
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ScopeState
import dev.easyide.app.ui.shell.ShellAction
import dev.easyide.app.ui.shell.ShellEnv
import dev.easyide.app.ui.shell.ShellReducer
import dev.easyide.app.ui.shell.ShellScope
import dev.easyide.app.ui.shell.ShellSnapshot
import dev.easyide.app.ui.shell.ShellState
import dev.easyide.app.ui.shell.nav.NavItemSource
import dev.easyide.app.ui.shell.nav.NavSettings
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
) : ViewModel(scope) {

    private val mutable = MutableStateFlow<ShellState?>(null)
    val state: StateFlow<ShellState?> = mutable.asStateFlow()

    val navSettings: StateFlow<NavSettings> = settings.stateIn(scope, SharingStarted.Eagerly, NavSettings())

    val navBadges = source.badges

    val navItems: StateFlow<List<NavItem>> =
        combine(source.contributions, navSettings) { contributions, nav ->
            val registry = contributions.fold(registries.navigation) { r, c -> r.register(c.item, Origin.Extension(c.extensionId)).registry }
            visible(registry, nav)
        }.stateIn(scope, SharingStarted.Eagerly, visible(registries.navigation, NavSettings()))

    private val env = ShellEnv(::typeOf)
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

    private fun visible(registry: NavRegistry, nav: NavSettings): List<NavItem> =
        registry.visible(ShellScope.APP, nav.prefs, NavEnv(source::holds) { it is NavTarget.Container && registries.containers.byId(it.id) != null })

    fun typeOf(uri: DocumentUri): DocumentType = registries.documents.resolve(uri)

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
        mutable.update { s -> s?.let { ShellReducer.reduce(it, action, env) } }
    }

    fun selectNav(item: NavItem) {
        val target = item.target as? NavTarget.Container ?: return
        val spec = registries.containers.byId(target.id) ?: return
        dispatch(ShellAction.SelectNav(item.id, ContainerRef(registries.containers.placementOf(spec), spec.id)))
    }

    /** Selects a core destination by id (a Settings link inside Home, the workspace's "open settings"). */
    fun goTo(navId: String) {
        registries.navigation.byId(navId)?.let(::selectNav)
    }

    fun open(uri: DocumentUri, options: OpenOptions = OpenOptions(preview = true)) = dispatch(ShellAction.Open(uri, options))

    fun activate(uri: DocumentUri) = dispatch(ShellAction.Activate(0, uri))

    fun keep(uri: DocumentUri) = dispatch(ShellAction.Keep(0, uri))

    fun close(uri: DocumentUri) = dispatch(ShellAction.Close(0, uri))

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
