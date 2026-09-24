package dev.easyide.extensions.view

import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.OpenGroup
import dev.easyide.extensions.contrib.CommandIcon
import dev.easyide.extensions.contrib.PackageFile
import dev.easyide.extensions.whenclause.WhenExpr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A validated prop value; the kind is the catalog's, so a renderer never checks types. */
sealed interface PropValue {
    data class Text(val template: ViewTemplate) : PropValue
    data class Path(val path: String) : PropValue
    data class Flag(val value: Boolean) : PropValue
    data class Num(val value: Double) : PropValue
    data class Choice(val value: String) : PropValue
    data class IconRef(val icon: CommandIcon) : PropValue
    data class Condition(val expr: WhenExpr) : PropValue
    data class Paths(val paths: List<String>) : PropValue
    data class Options(val options: List<Option>) : PropValue
    data class Columns(val columns: List<Column>) : PropValue
    data class Pattern(val pattern: String) : PropValue
    data class Image(val file: PackageFile) : PropValue
}

data class Option(val label: ViewTemplate, val value: JsonElement)

data class Column(val title: ViewTemplate, val field: String, val format: ViewFormat?, val mono: Boolean, val weight: Double)

/** A confirm sheet the shell shows before the action runs; [destructive] paints its button as danger. */
data class Confirm(val title: ViewTemplate, val body: ViewTemplate?, val destructive: Boolean)

enum class EffectOp(val wire: String) { SET("set"), APPEND("append"), CLEAR("clear") }

/** A local edit of the view's data: `before` the action runs (an optimistic chat message, a cleared draft) or `after` it ended, whatever its outcome. */
data class Effect(val op: EffectOp, val path: String, val value: ViewValue?)

enum class IntoMode(val wire: String) { SET("set"), APPEND("append") }

enum class ResultParse(val wire: String) { JSON("json"), TEXT("text"), LINES("lines") }

/** Where an action's result is written back in the view's data (`as`), and how stdout is read. */
data class Into(val path: String, val mode: IntoMode, val parse: ResultParse)

/** What a component's event does: run a command, run an inline action, open one of the pack's documents, or only edit local data. */
sealed interface ActionTarget {
    /** A command id of the pack (or a built-in), run through the action engine. */
    data class Command(val id: String) : ActionTarget

    /** An action of the existing vocabulary written in place; it reads its [ViewAction.args] as `${arg:name}`. */
    data class Inline(val action: Action) : ActionTarget

    /** `open`: a `ext://<own id>/...` document URI (a view template, so a row can name its item). */
    data class Open(val uri: ViewTemplate, val group: OpenGroup) : ActionTarget

    /** Only the [ViewAction.before] effects: clear a draft, flip a local flag. */
    data object Local : ActionTarget
}

/**
 * What a component does when the user acts on it. [args] are resolved against the component's
 * scope when it fires and reach the command as its `args` (`${arg:name}` in the action language).
 */
data class ViewAction(
    val target: ActionTarget,
    val args: ViewValue?,
    val confirm: Confirm?,
    val into: Into?,
    val before: List<Effect>,
    val after: List<Effect> = emptyList(),
)

/**
 * One component of a view tree. [children] fill the container types, [item] is the row template
 * of `list` and `tree`, [empty] what a `list` or `table` shows without rows. [pointer] names the
 * node in its file so a render-time problem can be reported where the author can find it.
 */
data class ViewNode(
    val type: ViewType,
    val id: String?,
    val condition: WhenExpr?,
    val props: Map<String, PropValue>,
    val children: List<ViewNode> = emptyList(),
    val item: ViewNode? = null,
    val empty: ViewNode? = null,
    val action: ViewAction? = null,
    val pointer: String = "",
) {
    fun text(name: String): ViewTemplate? = (props[name] as? PropValue.Text)?.template
    fun path(name: String): String? = (props[name] as? PropValue.Path)?.path
    fun flag(name: String): Boolean? = (props[name] as? PropValue.Flag)?.value
    fun num(name: String): Double? = (props[name] as? PropValue.Num)?.value
    fun choice(name: String): String? = (props[name] as? PropValue.Choice)?.value
    fun icon(name: String): CommandIcon? = (props[name] as? PropValue.IconRef)?.icon
    fun condition(name: String): WhenExpr? = (props[name] as? PropValue.Condition)?.expr
    fun paths(name: String): List<String> = (props[name] as? PropValue.Paths)?.paths.orEmpty()
    fun options(name: String): List<Option> = (props[name] as? PropValue.Options)?.options.orEmpty()
    fun columns(name: String): List<Column> = (props[name] as? PropValue.Columns)?.columns.orEmpty()

    /** This node and everything under it. */
    fun walk(): Sequence<ViewNode> = sequence {
        yield(this@ViewNode)
        children.forEach { yieldAll(it.walk()) }
        item?.let { yieldAll(it.walk()) }
        empty?.let { yieldAll(it.walk()) }
    }
}

/**
 * A parsed `viewSchema: 1` file. [state] is the view's initial data; [nodes] and [depth] are what
 * the static limits measured; [file] is the package path, for messages.
 */
data class ViewDocument(val file: String, val state: JsonObject, val root: ViewNode, val nodes: Int, val depth: Int) {
    val actions: List<ViewAction> get() = root.walk().mapNotNull { it.action }.toList()
}
