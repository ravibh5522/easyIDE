package dev.easyide.app.ui.commands

import dev.easyide.app.data.settings.DiagnosticCode
import dev.easyide.app.data.settings.Jsonc
import dev.easyide.app.data.settings.JsoncResult
import dev.easyide.app.data.settings.SchemaValidator
import dev.easyide.app.data.settings.SettingsDiagnostic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One keybindings.json entry as written; [offset] locates it for diagnostics. */
data class KeybindingEntry(val key: String?, val command: String, val whenText: String?, val offset: Int)

/** keybindings.json: a VS Code-shaped array of `{key, command, when?, args?}`; `"-cmd"` removes. */
object KeybindingsFile {

    data class Parsed(val entries: List<KeybindingEntry>, val diagnostics: List<SettingsDiagnostic>, val array: JsonArray)

    fun parse(text: String): Parsed {
        if (text.isBlank()) return Parsed(emptyList(), emptyList(), JsonArray(emptyList()))
        val root = when (val r = Jsonc.parse(text)) {
            is JsoncResult.Failure -> return Parsed(
                emptyList(), listOf(SettingsDiagnostic(DiagnosticCode.PARSE_ERROR, offset = r.offset, parseError = r.error)), JsonArray(emptyList()),
            )
            is JsoncResult.Ok -> r.root
        }
        val array = root.value as? JsonArray
            ?: return Parsed(emptyList(), listOf(SettingsDiagnostic(DiagnosticCode.NOT_AN_ARRAY, offset = root.start)), JsonArray(emptyList()))
        val entries = ArrayList<KeybindingEntry>()
        val diagnostics = ArrayList<SettingsDiagnostic>()
        for (item in root.items) {
            val obj = item.value as? JsonObject
            val command = obj?.get("command")?.let(SchemaValidator::stringOrNull)?.takeIf { it.isNotBlank() }
            val key = obj?.get("key")?.let(SchemaValidator::stringOrNull)
            // A removal may omit the key (remove every chord of the command); an addition may not.
            if (obj == null || command == null || (key == null && !command.startsWith(REMOVE))) {
                diagnostics += SettingsDiagnostic(DiagnosticCode.BAD_ENTRY, offset = item.start)
                continue
            }
            entries += KeybindingEntry(key, command, obj["when"]?.let(SchemaValidator::stringOrNull), item.start)
        }
        return Parsed(entries, diagnostics, array)
    }

    const val REMOVE = "-"
}

/** Two bindings on one chord whose focus conditions overlap: [winner] fires, [shadowed] never does. */
data class KeyConflict(val chord: KeyChord, val winner: KeyBinding, val shadowed: KeyBinding)

/**
 * The keymap after user overrides (LLD 7.2), with diagnostics for the JSON editor.
 * Entries apply in file order after the built-in table: a removal deletes
 * matching earlier bindings (by command, plus chord and `when` when given);
 * anything else is appended, so it wins dispatch through [Keymap]'s last-wins scan.
 */
object KeymapResolver {

    data class Result(val keymap: Keymap, val diagnostics: List<SettingsDiagnostic>, val conflicts: List<KeyConflict>)

    fun resolve(defaults: Keymap, user: KeybindingsFile.Parsed, knownCommands: Set<String>): Result {
        val effective = defaults.bindings.toMutableList()
        val diagnostics = user.diagnostics.toMutableList()
        val offsets = HashMap<KeyBinding, Int>()
        for (e in user.entries) {
            val keys = e.key?.let { k -> KeyNames.parseSequence(k) ?: run { diagnostics += diag(DiagnosticCode.BAD_CHORD, e, k); null } }
            if (e.key != null && keys == null) continue
            val focus = if (e.whenText == null) null else KeyFocus.parse(e.whenText)
            if (e.whenText != null && focus == null) { diagnostics += diag(DiagnosticCode.UNSUPPORTED_WHEN, e, e.whenText); continue }
            val removal = e.command.startsWith(KeybindingsFile.REMOVE)
            val command = if (removal) e.command.drop(KeybindingsFile.REMOVE.length) else e.command
            if (command !in knownCommands) diagnostics += diag(DiagnosticCode.UNKNOWN_COMMAND, e, command)
            if (removal) {
                effective.removeAll { b ->
                    b.command == command && (keys == null || (b.chord == keys.chord && b.prefix == keys.prefix)) && (focus == null || b.focus == focus)
                }
            } else {
                val seq = requireNotNull(keys)
                val binding = KeyBinding(seq.chord, command, focus ?: KeyFocus.ANYWHERE, prefix = seq.prefix)
                effective += binding
                offsets[binding] = e.offset
            }
        }
        val conflicts = conflictsOf(effective)
        conflicts.forEach { c ->
            diagnostics += SettingsDiagnostic(
                DiagnosticCode.CHORD_CONFLICT, key = KeyNames.format(KeySequence(c.winner.prefix, c.chord)), detail = c.shadowed.command,
                offset = offsets[c.winner] ?: offsets[c.shadowed],
            )
        }
        return Result(Keymap(effective), diagnostics, conflicts)
    }

    /**
     * Same chord (both steps), different commands, focus conditions that can hold at once
     * (LLD 7.3 "same chord"). A later binding with a `when` expression shadows nothing for
     * certain - packs sharing a key under disjoint `when`s is normal - so it is not reported.
     */
    fun conflictsOf(bindings: List<KeyBinding>): List<KeyConflict> = buildList {
        bindings.forEachIndexed { i, earlier ->
            for (j in i + 1 until bindings.size) {
                val later = bindings[j]
                if (later.chord == earlier.chord && later.prefix == earlier.prefix && later.command != earlier.command &&
                    later.whenExpr == null && later.focus.overlaps(earlier.focus)
                ) {
                    add(KeyConflict(later.chord, later, earlier))
                }
            }
        }
    }

    private fun diag(code: DiagnosticCode, e: KeybindingEntry, detail: String) =
        SettingsDiagnostic(code, key = e.command, detail = detail, offset = e.offset)
}
