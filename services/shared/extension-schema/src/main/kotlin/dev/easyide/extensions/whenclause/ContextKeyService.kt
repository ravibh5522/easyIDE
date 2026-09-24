package dev.easyide.extensions.whenclause

import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.settings.RuntimeScope
import dev.easyide.extensions.settings.SettingsPort
import dev.easyide.extensions.settings.SettingsQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * One immutable view of the context. `config.<key>` is never stored: it resolves lazily
 * through [SettingsPort] for this snapshot's scope and `editorLangId`, so settings are not
 * mirrored into a second copy that could drift.
 */
class ContextSnapshot internal constructor(
    val version: Long,
    internal val values: Map<String, JsonElement>,
    val runtime: RuntimeScope,
    private val settings: SettingsPort,
) : ContextLookup {

    val languageId: String? get() = values[ContextKeys.editorLangId.name]?.stringOrNull

    override fun get(key: String): JsonElement? {
        values[key]?.let { return it }
        if (!key.startsWith(ContextKeys.CONFIG_PREFIX)) return null
        return settings.value(key.removePrefix(ContextKeys.CONFIG_PREFIX), SettingsQuery.of(runtime, languageId))
    }

    /** The stored value, without `config.*` resolution (change detection). */
    internal fun stored(key: String): JsonElement? = values[key]

    /**
     * A lookup where [overrides] shadow this snapshot (null in the map = undefined): the
     * scoped context for one evaluation, e.g. a tree item's keys under `view/item/context`.
     */
    fun with(overrides: Map<String, JsonElement?>): ContextLookup = ContextLookup { key ->
        if (key in overrides) overrides[key] else this[key]
    }

    fun evaluate(expr: WhenExpr?): Boolean = expr == null || WhenEvaluator.evaluate(expr, this)
}

/**
 * The live context-key table (extension-runtime.md sec 6.3). Providers in `:app` push
 * values; menus, keybindings and key rows read [snapshot] or [observe] a clause.
 *
 * Writes are atomic swaps of an immutable map, so readers on any thread see a consistent
 * snapshot and [setAll] produces exactly one new version.
 */
class ContextKeyService(
    private val settings: SettingsPort,
    runtime: RuntimeScope = RuntimeScope.NONE,
    private val evaluator: (WhenExpr, ContextLookup) -> Boolean = WhenEvaluator::evaluate,
) {
    private val state = MutableStateFlow(ContextSnapshot(0, emptyMap(), runtime, settings))
    val snapshot: StateFlow<ContextSnapshot> = state.asStateFlow()

    fun <T> set(key: ContextKey<T>, value: T?) = setRaw(key.name, value?.let(key::encode))

    /** Untyped write for keys built at runtime; null removes the key. */
    fun setRaw(name: String, value: JsonElement?) = setAll(mapOf(name to value))

    /** Applies all changes as one version bump; a no-op change does not bump. */
    fun setAll(changes: Map<String, JsonElement?>) {
        require(changes.keys.none { it.startsWith(ContextKeys.CONFIG_PREFIX) }) { "config.* keys resolve from settings" }
        state.update { s ->
            if (changes.all { (k, v) -> s.stored(k) == v }) return@update s
            val next = HashMap(s.values)
            for ((k, v) in changes) if (v == null) next.remove(k) else next[k] = v
            ContextSnapshot(s.version + 1, next, s.runtime, settings)
        }
    }

    fun setRuntime(runtime: RuntimeScope) = state.update { s ->
        if (s.runtime == runtime) s else ContextSnapshot(s.version + 1, s.values, runtime, settings)
    }

    /**
     * The value of [expr] over time. It re-evaluates only when a key it references changed
     * (or, for `config.*` keys, when settings, the scope or the editor language changed),
     * and emits only when the result changes.
     */
    fun observe(expr: WhenExpr): Flow<Boolean> {
        val usesConfig = expr.keys.any { it.startsWith(ContextKeys.CONFIG_PREFIX) }
        val watched = if (usesConfig) expr.keys + ContextKeys.editorLangId.name else expr.keys
        val settingsVersion = if (usesConfig) settings.version else flowOf(0L)
        return combine(state, settingsVersion) { s, v -> s to v }
            .distinctUntilChanged { (a, av), (b, bv) ->
                av == bv && (!usesConfig || a.runtime == b.runtime) && watched.all { a.stored(it) == b.stored(it) }
            }
            .map { (s, _) -> evaluator(expr, s) }
            .distinctUntilChanged()
    }
}
