package dev.easyide.app.extensions.wasm

import dev.easyide.extensions.ExtensionPolicy
import dev.easyide.extensions.ExtensionsRuntime
import dev.easyide.extensions.action.ActionError
import dev.easyide.extensions.action.ActionOutcome
import dev.easyide.extensions.capability.CapabilityRules
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.settings.ConfigTarget
import dev.easyide.extensions.settings.SettingsPort
import dev.easyide.extensions.settings.SettingsQuery
import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostCallException
import dev.easyide.extwasm.host.CommandPort
import dev.easyide.extwasm.host.ConfigPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * `commands.*` over the runtime's command path: `execute` runs any command the way the
 * palette does ([ExtensionsRuntime.run]: built-ins through the workspace, extension commands
 * with their owner's grants, WASM ones through the host again) under the runner's
 * single-flight guard; a cycle back into a busy WASM instance is refused by the host
 * (E_UNAVAILABLE). The host router asks [requiredCapability]
 * first: the caller must hold what the target's action needs, so a WASM extension cannot
 * borrow another extension's grants.
 *
 * `register` records which declared commands a guest bound (the router has checked they are
 * in `contributes.commands`); dispatch does not depend on it.
 */
class WasmCommandPort(private val runtime: () -> ExtensionsRuntime) : CommandPort {

    private val registered = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Commands each extension registered in its current instance. */
    val registrations: StateFlow<Map<String, Set<String>>> = registered.asStateFlow()

    override suspend fun execute(extensionId: String, command: String, args: JsonArray): JsonElement? {
        val single = when (args.size) {
            0 -> null
            1 -> args.single()
            else -> args
        }
        return when (val r = runtime().run(command, single)) {
            is ActionOutcome.Done -> r.value
            ActionOutcome.Cancelled -> throw HostCallException(ErrorCode.E_CANCELLED, "$command was cancelled")
            is ActionOutcome.Failed -> throw HostCallException(codeOf(r.error), "$command: ${r.message}")
        }
    }

    /**
     * The first (by id) capability the target's declarative action needs; null for built-ins,
     * WASM commands (their owner acts under its own grants) and unknown commands. The port
     * contract carries one id, so an action needing several is gated on the first only.
     */
    override fun requiredCapability(command: String): String? {
        val rt = runtime()
        val owner = rt.contributions.commandOwner(command) as? Owner.Ext ?: return null
        val action = rt.extensions.enabled.value.byId(owner.id)?.descriptor?.actions?.get(command) ?: return null
        return CapabilityRules.required(action).map { it.id }.sorted().firstOrNull()
    }

    override fun register(extensionId: String, command: String) =
        registered.update { it + (extensionId to (it[extensionId].orEmpty() + command)) }

    override fun unregisterAll(extensionId: String) = registered.update { it - extensionId }

    private fun codeOf(e: ActionError): ErrorCode = ErrorCode.entries.firstOrNull { it.name == e.code } ?: ErrorCode.E_INTERNAL
}

/**
 * `config.get/set` over the layered settings store, with the L1 `setConfig` rules: the
 * host router has decided ownership (own `contributes.configuration` keys, others need
 * `ui.settings`) and refused its protected keys; this port also refuses every key the L1
 * runner refuses ([ExtensionPolicy.isUnwritable]), so both layers write the same set.
 * `target` is user (default), language, environment or project, resolved for the open
 * workspace.
 */
class WasmConfigPort(
    private val settings: SettingsPort,
    private val query: () -> SettingsQuery,
) : ConfigPort {

    override fun get(extensionId: String, key: String): JsonElement? = settings.value(key, query())

    override suspend fun set(extensionId: String, key: String, value: JsonElement, target: String?) {
        if (ExtensionPolicy.isUnwritable(key)) throw HostCallException(ErrorCode.E_CAPABILITY, "'$key' cannot be changed by extensions")
        val t = target?.let { ConfigTarget.parse(it) ?: throw HostCallException(ErrorCode.E_ARGS, "\"target\" must be user, language, environment or project") }
            ?: ConfigTarget.USER
        val q = query()
        val missing = when (t) {
            ConfigTarget.LANGUAGE -> q.languageId == null
            ConfigTarget.ENVIRONMENT -> q.envId == null
            ConfigTarget.PROJECT -> q.projectId == null
            ConfigTarget.USER -> false
        }
        if (missing) throw HostCallException(ErrorCode.E_UNAVAILABLE, "no ${t.wire} is active for a ${t.wire} setting")
        settings.write(key, value.takeIf { it != JsonNull }, t, q)
    }
}
