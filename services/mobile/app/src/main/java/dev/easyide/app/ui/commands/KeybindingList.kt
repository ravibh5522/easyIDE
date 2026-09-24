package dev.easyide.app.ui.commands

import dev.easyide.app.data.settings.Jsonc
import dev.easyide.app.data.settings.JsoncEditor
import dev.easyide.app.data.settings.JsoncResult
import dev.easyide.app.data.settings.SettingsDiagnostic
import dev.easyide.extensions.whenclause.CompareOp
import dev.easyide.extensions.whenclause.WhenExpr
import dev.easyide.extensions.whenclause.WhenParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.IdentityHashMap

/** Which keymap layer a binding comes from (customization.md sec 7.1). */
enum class BindingSource { BUILT_IN, EXTENSION, USER }

/**
 * One binding of the effective keymap as the Keyboard Shortcuts screen lists it: [owner] is
 * the contributing extension id, [entry] the keybindings.json entry that added it.
 */
data class EffectiveBinding(val binding: KeyBinding, val source: BindingSource, val owner: String?, val entry: KeybindingEntry?) {
    val keyText: String get() = KeyNames.format(KeySequence(binding.prefix, binding.chord))
    /** The focus condition keybindings.json can express (what a removal entry must repeat). */
    val whenText: String? get() = binding.focus.whenText

    /** The full condition for display: a contributed `when`, else the focus condition. */
    val conditionText: String? get() = binding.whenExpr?.let(WhenParser::normalize) ?: whenText
}

/** Two bindings that can fire for the same key press: [winner] (the later one) runs, [shadowed] never does. */
data class BindingConflict(val winner: EffectiveBinding, val shadowed: EffectiveBinding)

/**
 * The effective keymap with provenance and conflicts, for the Keyboard Shortcuts screen
 * (customization.md sec 7.3), plus the keybindings.json edits the screen makes (sec 7.3
 * "UI edits write the user file"): add = a new entry; remove a user entry = delete it;
 * remove a built-in or extension binding = a `-command` entry for that chord.
 */
object KeybindingList {

    data class Result(val bindings: List<EffectiveBinding>, val conflicts: List<BindingConflict>, val diagnostics: List<SettingsDiagnostic>)

    fun build(
        defaults: List<KeyBinding>,
        extension: List<Pair<KeyBinding, String>>,
        user: KeybindingsFile.Parsed,
        knownCommands: Set<String>,
    ): Result {
        val origin = IdentityHashMap<KeyBinding, EffectiveBinding>()
        defaults.forEach { origin[it] = EffectiveBinding(it, BindingSource.BUILT_IN, null, null) }
        extension.forEach { (b, owner) -> origin[b] = EffectiveBinding(b, BindingSource.EXTENSION, owner, null) }
        val resolved = KeymapResolver.resolve(Keymap(defaults + extension.map { it.first }), user, knownCommands)
        val userEntries = IdentityHashMap<KeyBinding, KeybindingEntry>().apply { resolved.userEntries.forEach { (b, e) -> put(b, e) } }
        val list = resolved.keymap.bindings.map { b ->
            origin[b] ?: EffectiveBinding(b, BindingSource.USER, null, userEntries[b])
        }
        return Result(list, conflictsOf(list), resolved.diagnostics)
    }

    /**
     * Same chord (both presses), different commands, and focus plus `when` that may hold at
     * once ([mayOverlap]). Unlike [KeymapResolver.conflictsOf], contributed bindings with a
     * `when` are included, since the screen shows the pairs rather than warning about them.
     */
    fun conflictsOf(bindings: List<EffectiveBinding>): List<BindingConflict> = buildList {
        bindings.forEachIndexed { i, earlier ->
            for (j in i + 1 until bindings.size) {
                val later = bindings[j]
                val a = earlier.binding
                val b = later.binding
                if (a.chord == b.chord && a.prefix == b.prefix && a.command != b.command &&
                    a.focus.overlaps(b.focus) && mayOverlap(a.whenExpr, b.whenExpr)
                ) {
                    add(BindingConflict(later, earlier))
                }
            }
        }
    }

    /**
     * False only when both clauses are conjunctions of atoms with a contradiction between
     * them (`k == x` vs `k == y`, `k` vs `!k`, `k == x` vs `k != x`); anything else may overlap.
     */
    fun mayOverlap(a: WhenExpr?, b: WhenExpr?): Boolean {
        val atoms = (conjunction(a) ?: return true) + (conjunction(b) ?: return true)
        return atoms.indices.none { i -> (i + 1 until atoms.size).any { j -> contradicts(atoms[i], atoms[j]) } }
    }

    private fun conjunction(e: WhenExpr?): List<WhenExpr>? = when (e) {
        null -> emptyList()
        is WhenExpr.And -> e.parts.flatMap { conjunction(it) ?: return null }
        is WhenExpr.Key, is WhenExpr.Compare -> listOf(e)
        is WhenExpr.Not -> if (e.expr is WhenExpr.Key) listOf(e) else null
        is WhenExpr.Const -> if (e.value) emptyList() else null
        else -> null
    }

    private fun contradicts(x: WhenExpr, y: WhenExpr): Boolean = when {
        x is WhenExpr.Key && y is WhenExpr.Not -> y.expr == x
        x is WhenExpr.Not && y is WhenExpr.Key -> x.expr == y
        x is WhenExpr.Compare && y is WhenExpr.Compare && x.key == y.key -> when {
            x.op == CompareOp.EQ && y.op == CompareOp.EQ -> x.literal != y.literal
            x.op == CompareOp.EQ && y.op == CompareOp.NE -> x.literal == y.literal
            x.op == CompareOp.NE && y.op == CompareOp.EQ -> x.literal == y.literal
            else -> false
        }
        else -> false
    }

    /** keybindings.json after adding `{key, command, when?}`; null when [text] is not a JSONC array. */
    fun add(text: String, key: String, command: String, whenText: String?): String? =
        append(text, entry(key, command, whenText))

    /**
     * keybindings.json after removing [b]: its own entry for a user binding, otherwise a
     * `-command` removal for its chord and focus. Null when the file cannot be edited.
     */
    fun remove(text: String, b: EffectiveBinding): String? {
        val own = b.entry
        if (b.source == BindingSource.USER && own != null) return deleteItem(text, own.offset)
        return append(text, entry(b.keyText, KeybindingsFile.REMOVE + b.binding.command, b.whenText))
    }

    private fun entry(key: String, command: String, whenText: String?) = JsonObject(buildMap {
        put("key", JsonPrimitive(key))
        put("command", JsonPrimitive(command))
        whenText?.takeIf { it.isNotBlank() }?.let { put("when", JsonPrimitive(it)) }
    })

    private fun append(text: String, item: JsonObject): String? {
        if (text.isBlank()) return JsoncEditor.render(JsonArray(listOf(item))) + "\n"
        val root = (Jsonc.parse(text) as? JsoncResult.Ok)?.root?.takeIf { it.value is JsonArray } ?: return null
        val rendered = JsoncEditor.render(item).replace("\n", "\n$INDENT")
        val last = root.items.lastOrNull()
            ?: return text.replaceRange(root.start + 1, root.end - 1, "\n$INDENT$rendered\n")
        val after = nextSignificant(text, last.end)
        return if (after < text.length && text[after] == ',') {
            text.replaceRange(after + 1, after + 1, "\n$INDENT$rendered,")
        } else {
            text.replaceRange(last.end, last.end, ",\n$INDENT$rendered")
        }
    }

    private fun deleteItem(text: String, offset: Int): String? {
        val root = (Jsonc.parse(text) as? JsoncResult.Ok)?.root?.takeIf { it.value is JsonArray } ?: return null
        val index = root.items.indexOfFirst { it.start == offset }.takeIf { it >= 0 } ?: return null
        val item = root.items[index]
        val after = nextSignificant(text, item.end)
        val (start, end) = when {
            after < text.length && text[after] == ',' -> item.start to after + 1
            index > 0 -> previousComma(text, item.start) to item.end
            else -> item.start to item.end
        }
        // Drop the line too when the item was alone on it.
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        return if (text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank()) {
            text.removeRange(lineStart, (lineEnd + 1).coerceAtMost(text.length))
        } else {
            text.removeRange(start, end)
        }
    }

    private fun nextSignificant(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun previousComma(text: String, before: Int): Int {
        var i = before - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        return if (i >= 0 && text[i] == ',') i else before
    }

    private const val INDENT = "  "
}
