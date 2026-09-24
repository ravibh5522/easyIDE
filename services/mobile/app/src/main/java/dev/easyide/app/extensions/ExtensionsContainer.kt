package dev.easyide.app.extensions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.easyide.app.R
import dev.easyide.app.data.settings.ProfileManager
import dev.easyide.app.data.settings.SettingsRegistry
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsStore
import dev.easyide.app.data.settings.SafeModeReason as AppSafeModeReason
import dev.easyide.app.data.settings.SafeModeState as AppSafeModeState
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.settings.RuntimeScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.easyide.app.extensions.adapters.ContributedKeybindings
import dev.easyide.app.extensions.adapters.ContributedServers
import dev.easyide.app.extensions.adapters.ExtensionLanguages
import dev.easyide.app.extensions.adapters.SnippetCatalog
import dev.easyide.app.extensions.host.AppHostPort
import dev.easyide.app.extensions.host.ExtensionUiHost
import dev.easyide.app.extensions.host.UrlOpener
import dev.easyide.app.extensions.install.AssetTree
import dev.easyide.app.extensions.install.BuiltInExtensions
import dev.easyide.app.extensions.install.DiskExtensionInventory
import dev.easyide.app.extensions.install.ExtensionStateStore
import dev.easyide.app.extensions.install.LocalInstaller
import dev.easyide.app.extensions.registry.RegistryConfigs
import dev.easyide.app.extensions.registry.RegistryService
import dev.easyide.app.extensions.registry.TinkEd25519
import dev.easyide.app.extensions.registry.UrlConnectionFetcher
import dev.easyide.app.data.settings.RegistrySettingsSchema
import dev.easyide.app.extensions.wasm.AndroidClipboard
import dev.easyide.app.extensions.wasm.WasmDeps
import dev.easyide.app.extensions.wasm.WasmRuntime
import dev.easyide.extensions.AppApi
import dev.easyide.extwasm.host.HostInfo
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.KeyBinding
import dev.easyide.app.ui.screens.workspace.TerminalKeyboard
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter
import dev.easyide.app.ui.theme.ThemeTokens
import dev.easyide.extensions.ExtensionPolicy
import dev.easyide.extensions.ExtensionsRuntime
import dev.easyide.extensions.RuntimePorts
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.contrib.CommandContribution
import dev.easyide.extensions.contrib.CommandIcon
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.schema.ManifestSchema
import dev.easyide.sandbox.SandboxPaths
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything the extension platform needs in the app, wired once (lld/extension-runtime.md
 * "Ports into the app"): the [ExtensionsRuntime] over real ports, the inventory over
 * [SandboxPaths] plus the APK's built-in packs, the local installer, and the adapters
 * that are not tied to one screen: grammars/languages into the highlighter, snippets,
 * keybindings (into the keymap's extension layer), `configuration` into the
 * [SettingsRegistry], and contributed colour themes ([colorTheme]). Screen-bound adapters
 * (menus, palette, status bar, key rows) read [runtime]'s registry directly.
 */
class ExtensionsContainer(
    private val context: Context,
    private val paths: SandboxPaths,
    private val settingsStore: SettingsStore,
    profiles: ProfileManager,
    private val settingsRegistry: SettingsRegistry,
    private val appSafeMode: AppSafeModeState,
    shellEnvironment: () -> Map<String, String>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    val log = ExtensionLogRing()
    val ui = ExtensionUiHost()
    val themes = ContributedThemeCatalog(log, io)
    val settings = AppSettingsPort(settingsStore, profiles, log, scope)
    val host = AppHostPort(ui, UrlOpener(::openExternal), shellEnvironment, log, io)

    private val state = ExtensionStateStore(paths.extensionStateFile)
    private val builtIns = BuiltInExtensions(AssetTree.of(context.assets), paths.builtInExtensionsDir, apkStamp())
    val inventory = DiskExtensionInventory(paths, builtIns::directories, state, io)

    private val builtInContributions = Contributions(
        commands = builtInCommands(),
        keyRows = listOf(TerminalKeyboard.row(context.getString(R.string.key_row_terminal_title))),
    )

    /** L2: the WASM host with its ports bridged to the app (lld/wasm-host.md sec 12, 14). */
    val wasm: WasmRuntime = WasmRuntime(
        WasmDeps(
            extensionsDir = paths.extensionsDir,
            settings = settings,
            host = host,
            workspace = { host.workspaceBridge },
            rootfs = paths::rootfsDir,
            clipboard = AndroidClipboard(context),
            log = log,
            inventory = inventory,
            info = HostInfo(appVersionName(), AppApi.VERSION.toString(), Locale.getDefault().toLanguageTag()),
            scope = scope,
            io = io,
        ),
    ) { runtime }

    val runtime: ExtensionsRuntime = ExtensionsRuntime(
        ports = RuntimePorts(settings, inventory, host, log, paths.extensionJournalFile, activators = listOf(wasm.activator), logic = wasm),
        scope = scope,
        builtIn = builtInContributions,
        builtInCommands = CommandIds.ALL,
        io = io,
    )

    val installer = LocalInstaller(
        paths, state, inventory,
        ManifestParser(ManifestSchema.validator, ParseOptions(locale = Locale.getDefault(), builtInCommands = CommandIds.ALL)),
        { PackageLimits.from(settings) }, io,
        // Declared below; only called once installs happen, after construction.
        isRevoked = { id, version -> registry.isRevoked(id, version) },
    )

    /** Signed registries: browse, install, update check, revocation (registry-and-install.md). */
    val registry: RegistryService = RegistryService(
        paths, state, UrlConnectionFetcher(), TinkEd25519, installer, inventory,
        { PackageLimits.from(settings).packageBytes }, io,
        notify = { id, message -> log.append(LogEntry(id, LogLevel.WARN, message)) },
    )

    /** Contributed `languageServers` for the LSP registry (registered once by the composition root). */
    val languageServers = ContributedServers.Provider(runtime, settings, log)

    private val snippetState = MutableStateFlow(SnippetCatalog.EMPTY)
    val snippets: StateFlow<SnippetCatalog> = snippetState.asStateFlow()

    private val keybindingState = MutableStateFlow<List<KeyBinding>>(emptyList())

    /** The keymap's extension layer, rebuilt when contributed keybindings change. */
    val keybindings: StateFlow<List<KeyBinding>> = keybindingState.asStateFlow()

    private val colorThemeState = MutableStateFlow<ThemeTokens?>(null)

    /** The selected extension colour theme's tokens; null means the built-in palette (sec 8.1). */
    val colorTheme: StateFlow<ThemeTokens?> = colorThemeState.asStateFlow()

    private val started = AtomicBoolean(false)
    private val startupFinished = AtomicBoolean(false)

    /**
     * Starts the runtime and the global adapters. Call once, at process start, before any
     * screen observes contributions (the crash-journal verdict must hold first).
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        runtime.start()
        wasm.start()
        TextMateHighlighter.onSlowGrammar = { scopeName ->
            log.append(LogEntry(null, LogLevel.WARN, "grammar $scopeName exceeded ${ExtensionPolicy.GRAMMAR_LINE_TIME_LIMIT_MS} ms on a line; the rest of that file stays plain"))
        }
        val c = runtime.contributions
        scope.launch {
            combine(c.languages.entries, c.grammars.entries, c.languageConfigurations.entries) { l, g, lc -> ExtensionLanguages(l, g, lc) }
                .collect { withContext(io) { TextMateHighlighter.setExtensionLanguages(it) } }
        }
        scope.launch {
            c.snippets.entries.collect { entries ->
                snippetState.value = withContext(io) {
                    SnippetCatalog.load(entries, ::readSnippetFile) { owned, message -> log.append(LogEntry(ownerId(owned.owner), LogLevel.WARN, message)) }
                }
            }
        }
        scope.launch {
            c.keybindings.entries.collect { entries ->
                keybindingState.value = ContributedKeybindings.bindings(entries) { owned, message ->
                    log.append(LogEntry(ownerId(owned.owner), LogLevel.WARN, message))
                }
            }
        }
        // configuration + configurationDefaults -> the settings schema and its extension layer.
        scope.launch {
            runtime.extensions.enabled.collect { set ->
                settingsRegistry.setContributions(set.extensions.map { ConfigurationContributions.of(it.descriptor) })
            }
        }
        scope.launch {
            settingsRegistry.state.map { it.diagnostics }.distinctUntilChanged().collect { diagnostics ->
                diagnostics.forEach { d ->
                    log.append(LogEntry(ExtensionId.parse(d.owner), LogLevel.WARN, "configuration: ${d.diagnostic.code} ${d.diagnostic.key.orEmpty()}"))
                }
            }
        }
        bridgeSafeMode()
        scope.launch { combine(c.themes.entries, c.iconThemes.entries) { t, i -> t to i }.collect { (t, i) -> themes.update(t, i) } }
        scope.launch {
            val selection = settingsStore.snapshot.map { it[SettingsSchema.colorTheme] }.distinctUntilChanged()
            themes.active(selection).collect { colorThemeState.value = it }
        }
        scope.launch {
            installer.clearStaging()
            inventory.rescan()
        }
        scope.launch {
            var first = true
            settingsStore.snapshot.map { it[RegistrySettingsSchema.registries] }.distinctUntilChanged().collect { value ->
                registry.setConfigs(RegistryConfigs.parse(value))
                // App start counts as a check point for extensions.autoCheckUpdates (sec 3.3).
                if (first) registry.refreshIfDue(settingsStore.snapshot.first()[RegistrySettingsSchema.autoCheckUpdates])
                first = false
            }
        }
    }

    /**
     * First frame plus [ExtensionPolicy.STARTUP_IDLE_DELAY_MS]: `onStartupFinished`
     * activations, and a clean run resets the crash journal. Once per process.
     */
    fun onFirstFrame() {
        if (!startupFinished.compareAndSet(false, true)) return
        scope.launch {
            delay(ExtensionPolicy.STARTUP_IDLE_DELAY_MS)
            runtime.onStartupFinished()
        }
    }

    /**
     * The app's safe mode (setting, launcher shortcut) and the runtime's (setting, crash
     * verdict) are one state to the user: a session reason on either side enters the
     * other, and [exitSafeMode] leaves both.
     */
    private fun bridgeSafeMode() {
        if (runtime.startupVerdict.enterSafeMode) appSafeMode.enterForSession(AppSafeModeReason.AUTO_CRASH)
        scope.launch {
            appSafeMode.active.collect { reason ->
                if (reason == AppSafeModeReason.LAUNCHER_SHORTCUT && !runtime.safeMode.isActive) runtime.safeMode.enterAuto(emptyList())
            }
        }
    }

    /** "Exit safe mode": clears the session reasons and, when it is set, the persisted setting. */
    suspend fun exitSafeMode(): Result<Unit> {
        runtime.safeMode.exitSession()
        return appSafeMode.exit()
    }

    /** What the settings export lists as installed (`{id, version, source, scope}`). */
    fun installedSummary(): List<JsonObject> = runtime.extensions.loaded.value.map { l ->
        JsonObject(mapOf(
            "id" to JsonPrimitive(l.descriptor.id.value),
            "version" to JsonPrimitive(l.descriptor.version.toString()),
            "source" to JsonPrimitive(l.pkg.source.name.lowercase()),
            "scope" to JsonPrimitive(l.pkg.scope.wire),
        ))
    }

    /** Environment or project switched: the runtime and the settings port follow together. */
    fun setRuntimeScope(scope: RuntimeScope) {
        settings.setScope(scope)
        runtime.setRuntimeScope(scope)
    }

    /** For the environment binds: only enabled environment packs appear in the guest. */
    fun isEnabledIn(envId: String, id: String): Boolean =
        runtime.extensions.enabled.value.extensions.any { it.id.value == id && it.pkg.envId == envId }

    private fun readSnippetFile(hostPath: String): String? {
        val file = File(hostPath)
        return try {
            if (file.length() > ExtensionUiPolicy.SNIPPET_FILE_MAX_BYTES) null else file.readText()
        } catch (e: IOException) {
            null
        }
    }

    private fun ownerId(owner: dev.easyide.extensions.contrib.Owner) = (owner as? dev.easyide.extensions.contrib.Owner.Ext)?.id

    /** The app's own commands as built-in contributions: menus and keybindings of packs can then name them. */
    private fun builtInCommands(): List<CommandContribution> = BuiltInCommandTable.ALL.map { c ->
        CommandContribution(c.id, context.getString(c.title), null, c.shortTitle?.let(context::getString), c.icon?.let(CommandIcon::Token), null)
    }

    private fun openExternal(url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    @Suppress("DEPRECATION") // the flags overload is API 33+; minSdk is 26
    private fun appVersionName(): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** Changes with every install or update of the APK, so built-ins are re-unpacked exactly then. */
    private fun apkStamp(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
}
