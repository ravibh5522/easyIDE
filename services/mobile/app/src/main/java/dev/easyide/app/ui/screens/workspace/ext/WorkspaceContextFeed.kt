package dev.easyide.app.ui.screens.workspace.ext

import android.os.Build
import dev.easyide.app.ui.screens.workspace.EditorSelections
import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.app.ui.screens.workspace.lsp.LspLanguageFacts
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter
import dev.easyide.extensions.ExtensionPolicy
import dev.easyide.extensions.ExtensionsRuntime
import dev.easyide.extensions.action.GuestPaths
import dev.easyide.extensions.action.WorkspaceState
import dev.easyide.extensions.manifest.ActivationEvent
import dev.easyide.extensions.whenclause.ContextKey
import dev.easyide.extensions.whenclause.ContextKeys
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.LinuxEnvironment
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.SandboxBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Screen-level facts only composition knows (focus, stages, input, window). */
data class UiContext(
    val terminalFocus: Boolean,
    val editorFocus: Boolean,
    val hardwareKeyboard: Boolean,
    val inputMode: String,
    val windowSizeClass: String,
    val stagesVisible: Map<String, Boolean>,
)

/**
 * Feeds the sdk-reference context keys from workspace state into the runtime's
 * [dev.easyide.extensions.whenclause.ContextKeyService] (extension-runtime.md sec 6.3
 * providers): editor/resource keys from the active tab and caret, env keys from the
 * environment record and rootfs, git keys from the panel state, and the UI keys the
 * screen reports through [setUi]. Also emits the activation events that come from the
 * workspace: `onLanguage` on the first tab of a language, `workspaceContains` once per
 * open. The `lsp*` keys follow [lspFacts], the project's language servers. Unknown facts
 * are left undefined (falsy), never guessed.
 */
class WorkspaceContextFeed(
    private val environmentId: String,
    private val projectRoot: File,
    private val state: StateFlow<WorkspaceUiState>,
    private val git: StateFlow<GitPanelState>,
    private val selections: EditorSelections,
    private val runtime: ExtensionsRuntime,
    private val environmentManager: EnvironmentManager,
    private val linuxEnvironment: LinuxEnvironment,
    private val lspFacts: Flow<Map<String, LspLanguageFacts>>,
    private val scope: CoroutineScope,
) {
    private val languages = ConcurrentHashMap<String, String>()
    private val seenLanguages = ConcurrentHashMap.newKeySet<String>()
    private val jobs = ArrayList<Job>()
    private val scannedGlobs = HashSet<String>()
    @Volatile private var ownedKeys: Set<String> = emptySet()
    @Volatile private var ui: UiContext? = null

    /** The environment's display name for `${envName}`; null until its record is read. */
    @Volatile var environmentName: String? = null
        private set

    /** Language id of an open tab, as last computed (null before the first pass or when unknown). */
    fun languageOf(relativePath: String): String? = languages[relativePath]

    fun start() {
        jobs += scope.launch(Dispatchers.Default) {
            combine(state, git, selections.changes, environmentManager.environments) { s, g, _, envs -> Triple(s, g, envs) }
                .conflate()
                .collect { (s, g, envs) -> publish(s, g, envs.find { it.id == environmentId }) }
        }
        jobs += scope.launch(Dispatchers.Default) {
            // A language whose servers all went away must not keep `lspReady:<lang>` alive.
            var published = emptySet<String>()
            lspFacts.collect { facts ->
                val keys = LspContextKeys.of(facts)
                apply((published - keys.keys).associateWith { null } + keys)
                published = keys.keys
            }
        }
        jobs += scope.launch(Dispatchers.IO) {
            // Globs come from the enabled set, which may still be loading when the workspace opens.
            runtime.extensions.enabled.collect {
                val fresh = runtime.workspaceGlobs() - scannedGlobs
                if (fresh.isNotEmpty()) { scannedGlobs += fresh; scanWorkspace(fresh) }
            }
        }
    }

    fun setUi(next: UiContext) {
        if (ui == next) return
        ui = next
        val keys = HashMap<String, JsonElement?>()
        put(keys, ContextKeys.terminalFocus, next.terminalFocus)
        put(keys, ContextKeys.editorFocus, next.editorFocus)
        put(keys, ContextKeys.editorTextFocus, next.editorFocus)
        put(keys, ContextKeys.hardwareKeyboard, next.hardwareKeyboard)
        put(keys, ContextKeys.inputMode, next.inputMode)
        put(keys, ContextKeys.windowSizeClass, next.windowSizeClass)
        next.stagesVisible.forEach { (stage, visible) -> put(keys, ContextKeys.stageVisible(stage), visible) }
        apply(keys)
    }

    /** Removes every key this workspace set: a closed workspace must not keep `editorLangId` alive. */
    fun clear() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        runtime.contextKeys.setAll(ownedKeys.associateWith { null })
        ownedKeys = emptySet()
        ui = null
    }

    private var distro: String? = null
    private var distroRead = false

    private fun publish(s: WorkspaceUiState, g: GitPanelState, env: dev.easyide.sandbox.model.SandboxEnvironment?) {
        val keys = HashMap<String, JsonElement?>()
        val tab = s.activeTab
        if (tab != null) {
            val firstLine = tab.content.substringBefore('\n').take(ExtensionPolicy.FIRST_LINE_MAX_CHARS)
            val lang = TextMateHighlighter.languageIdFor(tab.name, firstLine)
            if (lang != null) languages[tab.relativePath] = lang else languages.remove(tab.relativePath)
            if (lang != null && seenLanguages.add(lang)) runtime.emit(ActivationEvent.OnLanguage(lang))
            val path = WorkspaceState.GUEST_WORKSPACE + "/" + tab.relativePath
            val sel = selections[tab.relativePath]
            put(keys, ContextKeys.editorLangId, lang)
            put(keys, ContextKeys.resourceLangId, lang)
            put(keys, ContextKeys.resourcePath, path)
            put(keys, ContextKeys.resourceFilename, tab.name)
            put(keys, ContextKeys.resourceDirname, GuestPaths.dirname(path))
            put(keys, ContextKeys.resourceExtname, GuestPaths.extname(path))
            put(keys, ContextKeys.resourceIsFolder, false)
            put(keys, ContextKeys.editorReadonly, !tab.editable)
            put(keys, ContextKeys.activeEditorIsDirty, tab.isDirty)
            put(keys, ContextKeys.editorHasSelection, !sel.collapsed)
            put(keys, ContextKeys.editorHasMultipleSelections, false)
        } else {
            listOf(
                ContextKeys.editorLangId, ContextKeys.resourceLangId, ContextKeys.resourcePath, ContextKeys.resourceFilename,
                ContextKeys.resourceDirname, ContextKeys.resourceExtname, ContextKeys.resourceIsFolder, ContextKeys.editorReadonly,
                ContextKeys.activeEditorIsDirty, ContextKeys.editorHasSelection, ContextKeys.editorHasMultipleSelections,
            ).forEach { keys[it.name] = null }
        }
        put(keys, ContextKeys.terminalProcessRunning, s.activeTerminal?.session?.isRunning == true)
        put(keys, ContextKeys.envId, environmentId)
        put(keys, ContextKeys.envState, envState(s, env))
        put(keys, ContextKeys.envBackend, env?.backend?.let { if (it == SandboxBackend.CHROOT) BACKEND_CHROOT else BACKEND_PROOT })
        put(keys, ContextKeys.envArch, arch())
        if (s.linuxReady && !distroRead) { distroRead = true; distro = readDistro() }
        put(keys, ContextKeys.envDistro, distro)
        environmentName = env?.label
        put(keys, ContextKeys.gitRepo, g.isRepository)
        put(keys, ContextKeys.gitBranch, g.status?.branch?.takeIf { it.isNotEmpty() })
        put(keys, ContextKeys.gitDirty, g.status?.let { !it.isClean })
        apply(keys)
    }

    private fun envState(s: WorkspaceUiState, env: dev.easyide.sandbox.model.SandboxEnvironment?): String = when {
        s.linuxReady -> ContextKeys.ENV_READY
        s.isInstalling || env?.state == EnvironmentState.PROVISIONING -> STATE_PROVISIONING
        env?.state == EnvironmentState.FAILED -> STATE_FAILED
        else -> STATE_STOPPED
    }

    /** `ID=` of the rootfs's os-release: the distro the guest actually runs. */
    private fun readDistro(): String? = try {
        File(linuxEnvironment.rootfsFor(environmentId), OS_RELEASE).takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith(OS_RELEASE_ID) }?.removePrefix(OS_RELEASE_ID)?.trim('"')
    } catch (e: IOException) {
        null
    }

    private fun arch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64"
        "x86_64" -> "x86_64"
        "armeabi-v7a" -> "arm"
        "x86" -> "x86"
        else -> null
    }

    /**
     * `workspaceContains:<glob>` (sdk-reference activation events): one walk of the project
     * for the union of enabled packs' globs, skipping `.git`, bounded by
     * [ExtensionPolicy.WORKSPACE_SCAN_MAX_FILES]. Globs are VS Code style relative to the
     * root; `**` also matches zero directories.
     */
    private fun scanWorkspace(globs: Set<String>) {
        val root = projectRoot
        val fs = FileSystems.getDefault()
        val matchers = globs.associateWith { g -> listOf(g, g.removePrefix(DOUBLE_STAR_SLASH)).distinct().map { fs.getPathMatcher("glob:$it") } }
        val pending = globs.toMutableSet()
        var seen = 0
        root.walkTopDown().onEnter { it.name != GIT_DIR }.forEach { file ->
            if (pending.isEmpty() || seen >= ExtensionPolicy.WORKSPACE_SCAN_MAX_FILES) return
            if (!file.isFile) return@forEach
            seen++
            val rel: Path = root.toPath().relativize(file.toPath())
            val hits = pending.filter { g -> matchers.getValue(g).any { it.matches(rel) } }
            hits.forEach { g -> pending -= g; runtime.emit(ActivationEvent.WorkspaceContains(g)) }
        }
    }

    private fun <T> put(keys: MutableMap<String, JsonElement?>, key: ContextKey<T>, value: T?) {
        keys[key.name] = value?.let(key::encode)
    }

    private fun apply(keys: Map<String, JsonElement?>) {
        ownedKeys = ownedKeys + keys.keys
        runtime.contextKeys.setAll(keys)
    }

    private companion object {
        const val STATE_PROVISIONING = "provisioning"
        const val STATE_FAILED = "failed"
        const val STATE_STOPPED = "stopped"
        const val BACKEND_PROOT = "proot"
        const val BACKEND_CHROOT = "chroot"
        const val OS_RELEASE = "etc/os-release"
        const val OS_RELEASE_ID = "ID="
        const val GIT_DIR = ".git"
        const val DOUBLE_STAR_SLASH = "**/"
    }
}

/**
 * The `lsp*` context keys of sdk-reference for [LspLanguageFacts]. Only offered features are
 * set: an absent `lspSupports:` key is false, and the feed removes keys that disappear.
 */
internal object LspContextKeys {
    fun of(facts: Map<String, LspLanguageFacts>): Map<String, JsonElement> = buildMap {
        for ((language, f) in facts) {
            ContextKeys.lspReady(language).let { put(it.name, it.encode(f.ready)) }
            ContextKeys.lspState(language).let { put(it.name, it.encode(f.state.wire)) }
            for (feature in f.features) ContextKeys.lspSupports(language, feature.id).let { put(it.name, it.encode(true)) }
        }
    }
}
