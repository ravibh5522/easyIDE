package dev.easyide.app.data.settings

import dev.easyide.app.ui.commands.KeybindingsFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Named sets of user settings + keybindings (+ extension allowlist and key rows
 * carried for later features), LLD sec 14. `default` is the DataStore user
 * layer plus `<files>/user/keybindings.json`; others are `profiles/<name>.json`.
 * App-level keys always stay in the default store, whichever profile is active.
 *
 * Switching swaps the USER layer and the keymap's user layer through
 * [flatMapLatest], so dependents see one new snapshot, and nothing restarts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileManager(
    private val defaultUser: DataStoreUserLayer,
    private val defaultKeybindings: FileKeybindings,
    private val profilesDir: File,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
) {
    private val named = HashMap<String, NamedProfile>()
    private val _profiles = MutableStateFlow<List<String>>(emptyList())

    /** Named profiles on disk, sorted; `default` is implicit. */
    val profiles: StateFlow<List<String>> = _profiles.asStateFlow()

    /** The stored choice; a profile whose file cannot be loaded falls back to default in [userLayer]. */
    val active: Flow<String> = defaultUser.doc
        .map { SettingsSchema.activeProfile.decode(it.plain[SettingsSchema.activeProfile.key] ?: NULL) ?: SettingsPolicy.DEFAULT_PROFILE }
        .distinctUntilChanged()

    /** Provenance label of the profile currently feeding [userLayer]. */
    @Volatile
    private var activeSourceLabel: String = defaultUser.source

    /**
     * Cold on purpose: a shared, replaying flow could hand a write issued right
     * after a switch the previous profile from its cache. Loading is a cached
     * handle plus one small file read.
     */
    private val activeProfile: Flow<NamedProfile?> = active
        .mapLatest { name ->
            val p = if (name == SettingsPolicy.DEFAULT_PROFILE) null else profile(name).takeIf { it.load().isSuccess }
            activeSourceLabel = p?.settings?.source ?: defaultUser.source
            p
        }

    /** The active profile's USER layer, app-level keys merged in from the default store. */
    val userLayer: LayerSource = object : LayerSource {
        override val source: String get() = activeSourceLabel
        override val doc: Flow<LayerDoc> = activeProfile.flatMapLatest { p ->
            if (p == null) defaultUser.doc else combine(defaultUser.doc, p.settings.doc, ::overlay)
        }

        override suspend fun write(edits: List<SettingEdit>): Result<Unit> {
            val p = activeProfile.first() ?: return defaultUser.write(edits)
            val (app, rest) = edits.partition { SettingsPolicy.isAppLevel(it.key) }
            if (app.isNotEmpty()) defaultUser.write(app).onFailure { return Result.failure(it) }
            return if (rest.isEmpty()) Result.success(Unit) else p.settings.write(rest)
        }

        override suspend fun readText(): String = JsoncEditor.render(doc.first().toJson())

        override suspend fun writeText(text: String): Result<Unit> {
            val p = activeProfile.first() ?: return defaultUser.writeText(text)
            val parsed = when (val r = LayerDoc.parse(text)) {
                is ParsedLayer.Bad -> return Result.failure(SettingsWriteException(r.error, "settings JSON does not parse"))
                is ParsedLayer.Ok -> r.doc
            }
            defaultUser.replace(parsed, SettingsPolicy::isAppLevel).onFailure { return Result.failure(it) }
            return p.settings.writeText(JsoncEditor.render(parsed.withoutAppLevel().toJson()))
        }
    }

    /** The active profile's keybindings.json. */
    val keybindings: KeybindingsSource = object : KeybindingsSource {
        override val text: Flow<String> = activeProfile.flatMapLatest { it?.keybindings?.text ?: defaultKeybindings.text }
        override suspend fun readText(): String = (activeProfile.first()?.keybindings ?: defaultKeybindings).readText()
        override suspend fun writeText(text: String): Result<Unit> =
            (activeProfile.first()?.keybindings ?: defaultKeybindings).writeText(text)
    }

    init {
        scope.launch {
            defaultKeybindings.load()
            refresh()
        }
    }

    /** Validates the target before switching: a profile that does not parse is refused, not half-applied. */
    suspend fun switchTo(name: String): Result<Unit> {
        if (name != SettingsPolicy.DEFAULT_PROFILE) {
            if (name !in profiles.value) return Result.failure(IllegalArgumentException("no profile $name"))
            profile(name).load().onFailure { return Result.failure(it) }
        }
        return defaultUser.write(listOf(SettingEdit(SettingsSchema.activeProfile.key, null, SettingsSchema.activeProfile.encode(name))))
    }

    /** New profile, empty or copied from [copyFrom] (a profile name, `default` included). */
    suspend fun create(name: String, copyFrom: String?): Result<Unit> {
        validateNewName(name)?.let { return Result.failure(it) }
        if (copyFrom != null && copyFrom != SettingsPolicy.DEFAULT_PROFILE && copyFrom !in profiles.value) {
            return Result.failure(IllegalArgumentException("no profile $copyFrom"))
        }
        val content = when (copyFrom) {
            null -> ProfileContent.EMPTY
            SettingsPolicy.DEFAULT_PROFILE -> ProfileContent.EMPTY.copy(
                settings = defaultUser.doc.first().withoutAppLevel().toJson(),
                keybindings = KeybindingsFile.parse(defaultKeybindings.readText()).array,
            )
            else -> profile(copyFrom).load().getOrElse { return Result.failure(it) }
        }
        return profile(name).save(content).onSuccess { refresh() }
    }

    suspend fun rename(from: String, to: String): Result<Unit> {
        guardMutable(from)?.let { return Result.failure(it) }
        validateNewName(to)?.let { return Result.failure(it) }
        val content = profile(from).load().getOrElse { return Result.failure(it) }
        profile(to).save(content).onFailure { return Result.failure(it) }
        return delete(from)
    }

    suspend fun delete(name: String): Result<Unit> {
        guardMutable(name)?.let { return Result.failure(it) }
        return withContext(io) {
            runCatching {
                val file = fileOf(name)
                if (file.exists() && !file.delete()) throw java.io.IOException("cannot delete ${file.path}")
            }
        }.onSuccess { synchronized(named) { named.remove(name) }; refresh() }
    }

    /** Named profile handle, for export/import. */
    fun profile(name: String): NamedProfile = synchronized(named) {
        named.getOrPut(name) { NamedProfile(name, PlainFileIo(fileOf(name), io)) }
    }

    suspend fun refresh() {
        _profiles.value = withContext(io) {
            profilesDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(EXT) }
                .map { it.name.removeSuffix(EXT) }
                .filter { SettingsPolicy.PROFILE_NAME.matches(it) && it != SettingsPolicy.DEFAULT_PROFILE }
                .sorted()
        }
    }

    private suspend fun guardMutable(name: String): Exception? = when {
        name == SettingsPolicy.DEFAULT_PROFILE -> IllegalArgumentException("the default profile cannot be changed this way")
        name == active.first() -> IllegalStateException("switch away from $name first")
        name !in profiles.value -> IllegalArgumentException("no profile $name")
        else -> null
    }

    private fun validateNewName(name: String): Exception? = when {
        !SettingsPolicy.PROFILE_NAME.matches(name) -> IllegalArgumentException("invalid profile name")
        name == SettingsPolicy.DEFAULT_PROFILE || name in profiles.value -> IllegalArgumentException("profile $name exists")
        else -> null
    }

    private fun fileOf(name: String) = File(profilesDir, name + EXT)

    private companion object {
        const val EXT = ".json"
        val NULL = kotlinx.serialization.json.JsonNull
    }
}

/** A named profile's layer with the device's app-level keys taken from the default store. */
private fun overlay(defaults: LayerDoc, profile: LayerDoc): LayerDoc = LayerDoc(
    version = LayerDoc.nextVersion(),
    plain = profile.plain.filterKeys { !SettingsPolicy.isAppLevel(it) } + defaults.plain.filterKeys(SettingsPolicy::isAppLevel),
    lang = profile.lang,
    errors = profile.errors + defaults.errors,
)

internal fun LayerDoc.withoutAppLevel(): LayerDoc = LayerDoc(
    version = LayerDoc.nextVersion(),
    plain = plain.filterKeys { !SettingsPolicy.isAppLevel(it) },
    lang = lang.mapValues { (_, b) -> b.filterKeys { !SettingsPolicy.isAppLevel(it) } }.filterValues { it.isNotEmpty() },
    errors = errors,
)
