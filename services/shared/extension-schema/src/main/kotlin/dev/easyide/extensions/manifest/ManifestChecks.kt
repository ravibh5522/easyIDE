package dev.easyide.extensions.manifest

import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.InputSpec
import dev.easyide.extensions.action.VariableRef
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.capability.CapabilityRules
import dev.easyide.extensions.capability.CapabilitySet
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.contrib.NavigationTarget
import dev.easyide.extensions.view.ActionTarget
import dev.easyide.extensions.view.ViewDocument
import dev.easyide.extensions.view.ViewTemplate
import dev.easyide.extensions.json.JsonPointer

/**
 * Pipeline phases 7, 9 and 10 (extension-runtime.md sec 2.1): cross-references, the
 * capability audit and the install-scope rule. Pure functions over decoded data.
 */
internal object ManifestChecks {

    /**
     * Commands referenced from menus, keybindings, status items, key rows, executeCommand and
     * inputs must resolve. An id in the extension's own namespace that it does not declare is
     * an error; any other unknown id warns (it may be a built-in of a newer app).
     */
    fun crossReferences(
        ctx: DecodeContext, c: Contributions, actions: Map<String, Action>, inputs: Map<String, InputSpec>, builtIns: Set<String>,
    ) {
        val declared = c.commands.mapTo(HashSet()) { it.command }
        fun check(command: String, pointer: String, file: String = MANIFEST_FILE) {
            if (command in declared || command in builtIns) return
            if (ctx.ownsId(command)) {
                ctx.error(DiagnosticCode.COMMAND_UNRESOLVED, pointer, "command '$command' is not declared in contributes.commands", file)
            } else {
                ctx.warn(DiagnosticCode.COMMAND_UNKNOWN, pointer, "command '$command' is not declared here or known as a built-in", file)
            }
        }
        c.menus.forEach { m ->
            val p = JsonPointer.child("/contributes/menus", m.menuId)
            check(m.command, p)
            m.alt?.let { check(it, p) }
        }
        c.keybindings.forEach { check(it.command, "/contributes/keybindings") }
        c.statusBarItems.forEach { s -> s.command?.let { check(it, "/easyide/statusBarItems") } }
        c.keyRows.forEach { row ->
            row.keys.forEach { k ->
                listOfNotNull(k.action, k.longPress).filterIsInstance<KeyAction.Command>().forEach { check(it.id, "/easyide/keyRows") }
            }
        }
        actions.forEach { (id, a) -> executeTargets(a).forEach { check(it, JsonPointer.child("/easyide/actions", id)) } }
        c.navigation.forEach { n -> (n.target as? NavigationTarget.Command)?.let { check(it.id, "/easyide/navigation") } }
        c.documents.forEach { d -> d.state?.let { check(it.command, "/easyide/documents") } }
        viewDocuments(c).forEach { doc ->
            doc.root.walk().filter { it.action != null }.forEach { node ->
                when (val t = node.action!!.target) {
                    is ActionTarget.Command -> check(t.id, node.pointer, doc.file)
                    is ActionTarget.Inline -> executeTargets(t.action).forEach { check(it, node.pointer, doc.file) }
                    else -> Unit
                }
            }
        }
        inputs.values.filterIsInstance<InputSpec.Command>().forEach { check(it.command, "/easyide/inputs") }
        for (id in actions.keys) {
            if (id !in declared) {
                ctx.error(DiagnosticCode.ACTION_NOT_COMMAND, JsonPointer.child("/easyide/actions", id), "action '$id' is not a declared command")
            }
        }
        actions.forEach { (id, a) ->
            ActionDecoder.templates(a).flatMap { it.variables }.filterIsInstance<VariableRef.Command>().forEach {
                check(it.id, JsonPointer.child("/easyide/actions", id))
            }
        }
    }

    /** Every parsed view file of the pack, each once (two views may share a file). */
    fun viewDocuments(c: Contributions): List<ViewDocument> =
        (c.views.mapNotNull { it.schema } + c.documents.map { it.body }).distinctBy { it.file }

    /**
     * References among the UI points, which only resolve once all of them are decoded: a navigation target names one of
     * the pack's containers, a badge one of its views, an opener one of its document types, and every `open` in a view
     * a declared document type.
     */
    fun uiReferences(ctx: DecodeContext, c: Contributions) {
        val containers = c.viewContainers.mapTo(HashSet()) { it.id }
        val views = c.views.mapTo(HashSet()) { it.id }
        val navIds = c.navigation.mapTo(HashSet()) { it.id }
        val types = c.documents.mapTo(HashSet()) { it.type }
        c.navigation.forEachIndexed { i, n ->
            val p = "/easyide/navigation/$i"
            (n.target as? NavigationTarget.Container)?.takeIf { it.id !in containers }?.let {
                ctx.error(DiagnosticCode.UI_REF, JsonPointer.child(p, "target"), "container '${it.id}' is not declared in contributes.viewsContainers")
            }
            n.badge?.takeIf { it.view !in views }?.let {
                ctx.error(DiagnosticCode.UI_REF, JsonPointer.child(p, "badge"), "badge view '${it.view}' is not declared in contributes.views")
            }
        }
        c.viewBadges.forEachIndexed { i, b ->
            val p = "/easyide/viewBadge/$i"
            if (b.nav !in navIds) ctx.error(DiagnosticCode.UI_REF, p, "viewBadge names navigation item '${b.nav}', which is not declared")
            if (b.binding.view !in views) ctx.error(DiagnosticCode.UI_REF, p, "viewBadge view '${b.binding.view}' is not declared in contributes.views")
        }
        c.documentOpeners.forEachIndexed { i, o ->
            if (o.type !in types) ctx.error(DiagnosticCode.UI_REF, "/easyide/documentOpeners/$i", "opener type '${o.type}' is not declared in easyide.documents")
        }
        val prefix = "ext://${ctx.extensionId.value}/"
        viewDocuments(c).forEach { doc ->
            doc.root.walk().forEach { node ->
                val open = node.action?.target as? ActionTarget.Open ?: return@forEach
                val literal = (open.uri.parts.firstOrNull() as? ViewTemplate.Part.Literal)?.text.orEmpty().removePrefix(prefix)
                val name = literal.substringBefore('/')
                if (literal.contains('/') && "${ctx.extensionId.value}/$name" !in types) {
                    ctx.error(DiagnosticCode.UI_REF, node.pointer, "'open' names document type '${ctx.extensionId.value}/$name', which is not declared in easyide.documents", doc.file)
                }
            }
        }
    }

    /**
     * `${input:x}` must name an `easyide.inputs` entry or a prompt step's `id` in the same
     * action, and `${result:x}` a step's `as`; otherwise the step could only ever fail.
     */
    fun variableReferences(ctx: DecodeContext, actions: Map<String, Action>, inputs: Map<String, InputSpec>) {
        for ((id, action) in actions) {
            val steps = flatten(action)
            val prompts = steps.mapNotNullTo(HashSet()) { s ->
                when (s) { is Action.ShowQuickPick -> s.id; is Action.ShowInputBox -> s.id; else -> null }
            }
            val bound = steps.mapNotNullTo(HashSet()) { it.bindAs }
            val pointer = JsonPointer.child("/easyide/actions", id)
            for (ref in ActionDecoder.templates(action).flatMap { it.variables }) {
                when (ref) {
                    is VariableRef.Input -> if (ref.id !in inputs && ref.id !in prompts) {
                        ctx.error(DiagnosticCode.TEMPLATE, pointer, "\${input:${ref.id}} has no matching input or prompt step")
                    }
                    is VariableRef.Result -> if (ref.name !in bound) {
                        ctx.error(DiagnosticCode.TEMPLATE, pointer, "\${result:${ref.name}} is not bound by any step's \"as\"")
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun flatten(a: Action): List<Action> = listOf(a) + when (a) {
        is Action.Sequence -> a.steps.flatMap(::flatten)
        is Action.ShowMessage -> a.actions.mapNotNull { it.action }.flatMap(::flatten)
        else -> emptyList()
    }

    private fun executeTargets(a: Action): List<String> = when (a) {
        is Action.ExecuteCommand -> listOf(a.command)
        is Action.Sequence -> a.steps.flatMap(::executeTargets)
        is Action.ShowMessage -> a.actions.mapNotNull { it.action }.flatMap(::executeTargets)
        else -> emptyList()
    }

    /** Phase 9: parse `easyide.capabilities` and require it to cover every static need. */
    fun capabilities(ctx: DecodeContext, raw: List<String>, c: Contributions, actions: Map<String, Action>, hasWasm: Boolean): CapabilitySet {
        val parsed = raw.mapIndexedNotNull { i, id ->
            Capability.parse(id) ?: run {
                ctx.error(DiagnosticCode.CAP_SYNTAX, JsonPointer.index("/easyide/capabilities", i), "unknown capability '$id'")
                null
            }
        }
        val declared = CapabilitySet(parsed.toSet())
        val used = HashSet<Capability>()
        for ((id, action) in actions) {
            val need = CapabilityRules.required(action)
            used += need
            declared.missing(need).forEach {
                ctx.error(DiagnosticCode.CAP_UNDECLARED, JsonPointer.child("/easyide/actions", id), "needs capability '${it.id}', which is not declared")
            }
        }
        val pointNeeds = CapabilityRules.required(c)
        used += pointNeeds
        declared.missing(pointNeeds).forEach {
            ctx.error(DiagnosticCode.CAP_UNDECLARED, "/easyide", "contributions need capability '${it.id}', which is not declared")
        }
        if (!hasWasm) {
            val usedSet = CapabilitySet(used)
            declared.items.filter { it in CapabilityRules.STATICALLY_AUDITED && !usedSet.satisfies(it) }.forEach {
                ctx.warn(DiagnosticCode.CAP_UNUSED, "/easyide/capabilities", "capability '${it.id}' is declared but never used")
            }
        }
        return declared
    }

    /**
     * Phase 10: anything that touches an environment forces `environment` scope; a manifest
     * may state `global` only when nothing does.
     */
    fun scope(ctx: DecodeContext, declared: InstallScope?, c: Contributions, actions: Map<String, Action>, caps: CapabilitySet): InstallScope {
        val needsEnv = c.sandbox != null || c.languageServers.isNotEmpty() ||
            caps.items.any { it == Capability.SandboxExec || it == Capability.SandboxInstall } ||
            actions.values.any(::touchesEnvironment) || c.viewData.any { touchesEnvironment(it.from) } ||
            viewDocuments(c).any { d -> d.actions.any { (it.target as? ActionTarget.Inline)?.let { t -> touchesEnvironment(t.action) } == true } }
        val computed = if (needsEnv) InstallScope.ENVIRONMENT else InstallScope.GLOBAL
        if (declared == InstallScope.GLOBAL && computed == InstallScope.ENVIRONMENT) {
            ctx.error(DiagnosticCode.SCOPE, "/easyide/scope", "declared 'global' but uses the environment (sandbox, language servers or process actions)")
        }
        return declared ?: computed
    }

    private fun touchesEnvironment(a: Action): Boolean = when (a) {
        is Action.RunInTerminal, is Action.RunTask, is Action.SandboxExec -> true
        is Action.Sequence -> a.steps.any(::touchesEnvironment)
        is Action.ShowMessage -> a.actions.any { it.action?.let(::touchesEnvironment) == true }
        else -> false
    }
}
