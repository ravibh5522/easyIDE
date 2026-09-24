package dev.easyide.app.data.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/** Who is writing: the user (UI, JSON editor) or an extension through `setConfig` / `config.set`. */
sealed interface SettingsWriter {
    data object User : SettingsWriter
    /** [mayWriteForeign] = holds `ui.settings`; protected keys stay unwritable either way (M-11). */
    data class Extension(val id: String, val mayWriteForeign: Boolean) : SettingsWriter
}

/** The writable layer an edit goes to. */
data class ConfigTarget(val layer: LayerId, val envId: String? = null, val projectId: String? = null) {
    companion object {
        val USER = ConfigTarget(LayerId.USER)
    }
}

enum class WriteRefusal { NOT_WRITABLE_LAYER, PROTECTED_KEY, FOREIGN_KEY, SCOPE, NOT_LANGUAGE_OVERRIDABLE, INVALID_VALUE, BAD_KEY }

class SettingsRefusedException(val refusal: WriteRefusal, val key: String) : Exception("$refusal: $key")

/**
 * Settings resolution and writes over every layer (LLD sec 3-4). Each layer is
 * a flow; [snapshot] combines the ones relevant to a query into one immutable
 * [SettingsSnapshot] per change - a batch edit is one DataStore/file write and
 * so one emission. Project exec-bearing values are dropped here unless the
 * project is trusted and safe mode is off, so no consumer can forget the gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStore(
    private val user: LayerSource,
    private val registry: SettingsRegistry,
    private val environment: (envId: String) -> LayerSource,
    private val project: (projectId: String) -> LayerSource,
    private val trust: ProjectTrust,
    private val safeMode: Flow<SafeModeReason?>,
    log: InvalidValueSink,
) {
    private val logged = ConcurrentHashMap.newKeySet<Triple<LayerId, String, Int>>()

    /** "Logged once per (layer, key, value hash)" (LLD 3.2). */
    private val sink = InvalidValueSink { layer, key, value ->
        if (logged.add(Triple(layer, key, value.hashCode()))) log.invalid(layer, key, value)
    }

    val schema: Flow<SchemaState> get() = registry.state

    /** The global (no environment, no project) snapshot. */
    val snapshot: Flow<SettingsSnapshot> get() = snapshot(SettingsQuery.GLOBAL)

    fun snapshot(q: SettingsQuery): Flow<SettingsSnapshot> {
        val env: Flow<Layer?> = q.envId?.let { id ->
            environment(id).let { src -> src.doc.map { Layer(LayerId.ENVIRONMENT, it, src.source) } }
        } ?: flowOf(null)
        val proj: Flow<Layer?> = q.projectId?.let(::gatedProject) ?: flowOf(null)
        return combine(user.doc, registry.state, env, proj) { u, schema, e, p ->
            val layers = buildList {
                addAll(schema.extensionDefaults)
                add(Layer(LayerId.USER, u, user.source))
                e?.let(::add)
                p?.let(::add)
            }
            SettingsSnapshot(schema, layers, sink)
        }.flowOn(Dispatchers.Default)
    }

    fun <T> observe(setting: Setting<T>, q: SettingsQuery = SettingsQuery.GLOBAL, languageId: String? = null): Flow<T> =
        snapshot(q).map { it.get(setting, languageId) }.distinctUntilChanged()

    /** The global value of [setting], for callers that predate queries. */
    fun <T> get(setting: Setting<T>): Flow<T> = observe(setting)

    /**
     * The consent question for [projectId]'s exec-bearing values, or null when
     * the project layer has none. The LSP client and the workspace show the
     * sheet while [TrustRequest.state] is PENDING.
     */
    fun trustRequest(projectId: String): Flow<TrustRequest?> =
        combine(project(projectId).doc, registry.state) { doc, schema -> ExecBearing.extract(doc, schema) }
            .distinctUntilChanged()
            .flatMapLatest { extracted ->
                val fp = ExecBearing.fingerprint(extracted) ?: return@flatMapLatest flowOf(null)
                trust.state(projectId, fp).map { TrustRequest(projectId, fp, ExecBearing.items(extracted), it) }
            }

    fun sourceFor(target: ConfigTarget): LayerSource? = when (target.layer) {
        LayerId.USER -> user
        LayerId.ENVIRONMENT -> target.envId?.let(environment)
        LayerId.PROJECT -> target.projectId?.let(project)
        LayerId.BUILT_IN, LayerId.EXTENSION -> null
    }

    /** Validated write; refusals come back as [SettingsRefusedException] with nothing written. */
    suspend fun write(target: ConfigTarget, edits: List<SettingEdit>, writer: SettingsWriter = SettingsWriter.User): Result<Unit> {
        val source = sourceFor(target) ?: return Result.failure(SettingsRefusedException(WriteRefusal.NOT_WRITABLE_LAYER, target.layer.name))
        val schema = registry.state.value
        edits.firstNotNullOfOrNull { e -> refusal(e, target.layer, writer, schema)?.let { SettingsRefusedException(it, e.key) } }
            ?.let { return Result.failure(it) }
        return source.write(edits)
    }

    suspend fun <T> set(
        setting: Setting<T>, value: T, target: ConfigTarget = ConfigTarget.USER, language: String? = null,
    ): Result<Unit> = write(target, listOf(SettingEdit(setting.key, language, setting.encode(value))))

    /** Removes [key] from one layer, so the next lower layer decides again. */
    suspend fun reset(key: String, target: ConfigTarget = ConfigTarget.USER, language: String? = null): Result<Unit> =
        write(target, listOf(SettingEdit(key, language, null)))

    suspend fun reset(setting: Setting<*>, target: ConfigTarget = ConfigTarget.USER, language: String? = null): Result<Unit> =
        reset(setting.key, target, language)

    /**
     * Removes every value from one layer, `[lang]` blocks included. App-level keys
     * (active profile, safe mode) stay: they are device state, not preferences,
     * and each has its own control.
     */
    suspend fun resetAll(target: ConfigTarget): Result<Unit> {
        val source = sourceFor(target) ?: return Result.failure(SettingsRefusedException(WriteRefusal.NOT_WRITABLE_LAYER, target.layer.name))
        val doc = source.doc.first()
        val edits = doc.plain.keys.filterNot(SettingsPolicy::isAppLevel).map { SettingEdit(it, null, null) } +
            doc.lang.flatMap { (l, block) -> block.keys.map { SettingEdit(it, l, null) } }
        return if (edits.isEmpty()) Result.success(Unit) else source.write(edits)
    }

    private fun gatedProject(projectId: String): Flow<Layer> {
        val src = project(projectId)
        return combine(src.doc, registry.state, safeMode) { doc, schema, safe -> Triple(doc, schema, safe) }
            .flatMapLatest { (doc, schema, safe) ->
                val extracted = ExecBearing.extract(doc, schema)
                val fp = ExecBearing.fingerprint(extracted)
                    ?: return@flatMapLatest flowOf(Layer(LayerId.PROJECT, doc, src.source))
                val stripped = Layer(LayerId.PROJECT, ExecBearing.strip(doc, schema), src.source)
                if (safe != null) return@flatMapLatest flowOf(stripped)
                trust.state(projectId, fp).map { st ->
                    if (st.allowsExec) Layer(LayerId.PROJECT, doc, src.source) else stripped
                }
            }
    }

    private fun refusal(e: SettingEdit, layer: LayerId, writer: SettingsWriter, schema: SchemaState): WriteRefusal? {
        if (e.key.isBlank() || LayerDoc.isLanguageKey(e.key)) return WriteRefusal.BAD_KEY
        if (layer !in WRITABLE) return WriteRefusal.NOT_WRITABLE_LAYER
        val setting = schema.byKey[e.key]
        if (writer is SettingsWriter.Extension) {
            if (SettingsPolicy.isExtensionUnwritable(e.key)) return WriteRefusal.PROTECTED_KEY
            val own = (setting as? Setting.Contributed)?.owner == writer.id
            if (!own && !writer.mayWriteForeign) return WriteRefusal.FOREIGN_KEY
        }
        if (!SettingsPolicy.layerMayHold(layer, e.key)) return WriteRefusal.PROTECTED_KEY
        if (setting == null) return null
        if (!setting.scope.allows(layer)) return WriteRefusal.SCOPE
        if (e.language != null && !setting.scope.languageOverridable) return WriteRefusal.NOT_LANGUAGE_OVERRIDABLE
        val value: JsonElement = e.value ?: return null
        return if (setting.isValid(value)) null else WriteRefusal.INVALID_VALUE
    }

    private companion object {
        val WRITABLE = setOf(LayerId.USER, LayerId.ENVIRONMENT, LayerId.PROJECT)
    }
}
