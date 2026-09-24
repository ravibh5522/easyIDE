package dev.easyide.extensions.manifest

import dev.easyide.extensions.contrib.InstallStep
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.contrib.KeyRowContribution
import dev.easyide.extensions.contrib.LanguageServerContribution
import dev.easyide.extensions.contrib.RowKey
import dev.easyide.extensions.contrib.SandboxContribution
import dev.easyide.extensions.contrib.StageContribution
import dev.easyide.extensions.contrib.StagePlacement
import dev.easyide.extensions.contrib.StatusBarAlignment
import dev.easyide.extensions.contrib.StatusBarItemContribution
import dev.easyide.extensions.contrib.ViewDataContribution
import dev.easyide.extensions.contrib.ViewDataKind
import dev.easyide.extensions.json.JsonPointer
import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonObject

/** Decodes the easyIDE-only points under `easyide` (stages, key rows, servers, sandbox, ...). */
internal class EasyideDecoder(private val ctx: DecodeContext, private val actions: ActionDecoder) {
    private val base = "/easyide"

    fun stages(e: JsonObject): List<StageContribution> = e.objs("stages").mapIndexed { i, o ->
        val p = JsonPointer.index("$base/stages", i)
        StageContribution(
            o.reqStr("id"), o.reqStr("title"), o.str("icon")?.let { ctx.icon(it, JsonPointer.child(p, "icon")) },
            StagePlacement.parse(o.reqStr("defaultStage"))!!, o.strs("views"), ctx.whenAt(o, "when", p),
        )
    }.also { list -> ctx.unique(list.mapIndexed { i, s -> JsonPointer.index("$base/stages", i) to s.id }, "stage") }

    fun statusBarItems(e: JsonObject): List<StatusBarItemContribution> = e.objs("statusBarItems").mapIndexed { i, o ->
        val p = JsonPointer.index("$base/statusBarItems", i)
        StatusBarItemContribution(
            o.reqStr("id"), ctx.template(o.reqStr("text"), JsonPointer.child(p, "text")), ctx.templateAt(o, "tooltip", p),
            o.str("command"), if (o.str("alignment") == StatusBarAlignment.RIGHT.wire) StatusBarAlignment.RIGHT else StatusBarAlignment.LEFT,
            o.int("priority") ?: 0, ctx.whenAt(o, "when", p),
        )
    }.also { list -> ctx.unique(list.mapIndexed { i, s -> JsonPointer.index("$base/statusBarItems", i) to s.id }, "status bar item") }

    fun keyRows(e: JsonObject): List<KeyRowContribution> = e.objs("keyRows").mapIndexed { i, o ->
        val p = JsonPointer.index("$base/keyRows", i)
        ctx.checkPrefix(o.reqStr("id"), JsonPointer.child(p, "id"), "key row")
        val keys = o.objs("keys").mapIndexedNotNull { j, k -> rowKey(k, JsonPointer.index(JsonPointer.child(p, "keys"), j)) }
        KeyRowContribution(o.reqStr("id"), o.reqStr("title"), ctx.whenAt(o, "when", p), keys)
    }.also { list -> ctx.unique(list.mapIndexed { i, r -> JsonPointer.index("$base/keyRows", i) to r.id }, "key row") }

    /**
     * At most one of insert/snippet/key/command. A key with none inserts its label: the
     * sdk-reference and author-guide examples use `{ "label": ":" }` that way.
     */
    private fun rowKey(o: JsonObject, p: String): RowKey? {
        val label = o.reqStr("label")
        val action = if (KEY_FIELDS.none { it in o }) KeyAction.Insert(label) else keyAction(o, p) ?: return null
        val longPress = o.obj("longPress")?.let { keyAction(it, JsonPointer.child(p, "longPress")) ?: return null }
        return RowKey(label, action, longPress)
    }

    private fun keyAction(o: JsonObject, p: String): KeyAction? {
        val present = KEY_FIELDS.filter { it in o }
        if (present.size != 1) {
            ctx.error(DiagnosticCode.KEY_ROW_KEY, p, "a key needs exactly one of insert, snippet, key or command (found ${present.size})")
            return null
        }
        return when (present.single()) {
            "insert" -> KeyAction.Insert(o.reqStr("insert"))
            "snippet" -> KeyAction.Snippet(o.reqStr("snippet"))
            "key" -> KeyAction.Key(o.reqStr("key"))
            else -> KeyAction.Command(o.reqStr("command"))
        }
    }

    fun languageServers(e: JsonObject): List<LanguageServerContribution> = e.objs("languageServers").mapIndexed { i, o ->
        val p = JsonPointer.index("$base/languageServers", i)
        val id = o.reqStr("id")
        val features = o.obj("features")
        LanguageServerContribution(
            key = "${ctx.extensionId.value}/$id", id = id, languages = o.strs("languages"),
            command = o.arr("command")!!.mapIndexed { j, c -> ctx.template(c.stringOrNull.orEmpty(), JsonPointer.index(JsonPointer.child(p, "command"), j)) },
            env = ctx.envMap(o.obj("env"), JsonPointer.child(p, "env")),
            initializationOptions = o.obj("initializationOptions") ?: JsonObject(emptyMap()),
            settingsSection = o.str("settingsSection"), rootMarkers = o.arr("rootMarkers")?.let { o.strs("rootMarkers") } ?: DEFAULT_ROOT_MARKERS,
            memoryBudgetMb = o.int("memoryBudgetMb"), idleShutdownSec = o.int("idleShutdownSec"), startupTimeoutSec = o.int("startupTimeoutSec"),
            featuresOnly = features?.arr("only")?.let { features.strs("only") }, featuresExclude = features?.strs("exclude") ?: emptyList(),
            priority = o.int("priority") ?: 0,
        )
    }.also { list -> ctx.unique(list.mapIndexed { i, s -> JsonPointer.index("$base/languageServers", i) to s.id }, "language server") }

    fun sandbox(e: JsonObject): SandboxContribution? {
        val s = e.obj("sandbox") ?: return null
        val p = "$base/sandbox"
        val steps = s.objs("install").mapIndexed { i, o ->
            val sp = JsonPointer.index(JsonPointer.child(p, "install"), i)
            InstallStep(o.reqStr("id"), o.reqStr("title"), ctx.template(o.reqStr("run"), JsonPointer.child(sp, "run")), ctx.whenAt(o, "when", sp))
        }
        ctx.unique(steps.mapIndexed { i, st -> JsonPointer.index(JsonPointer.child(p, "install"), i) to st.id }, "install step")
        return SandboxContribution(
            s.strs("requires"), steps, ctx.templateAt(s, "verify", p),
            s.strs("uninstall").mapIndexed { i, u -> ctx.template(u, JsonPointer.index(JsonPointer.child(p, "uninstall"), i)) },
        )
    }

    fun viewData(e: JsonObject): List<ViewDataContribution> = (e.obj("viewData") ?: return emptyList()).mapNotNull { (viewId, v) ->
        val o = v as JsonObject
        val p = JsonPointer.child("$base/viewData", viewId)
        val from = actions.action(o.obj("from")!!, JsonPointer.child(p, "from")) ?: return@mapNotNull null
        ViewDataContribution(viewId, if (o.str("kind") == ViewDataKind.TREE.wire) ViewDataKind.TREE else ViewDataKind.LIST, from, o.strs("refreshOn"))
    }

    private companion object {
        val KEY_FIELDS = listOf("insert", "snippet", "key", "command")
        val DEFAULT_ROOT_MARKERS = listOf(".git")
    }
}
