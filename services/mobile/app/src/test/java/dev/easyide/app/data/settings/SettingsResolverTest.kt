package dev.easyide.app.data.settings

import dev.easyide.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsResolverTest {

    private fun int(key: String, scope: SettingScope) =
        Setting.IntRange(key, SettingCategory.EDITOR, 0, 0, default = 1, scope = scope, min = 0, max = 100)

    private val allLayers = listOf(
        layer(LayerId.EXTENSION, "{\"k\": 2, \"[py]\": {\"k\": 20}}"),
        layer(LayerId.USER, "{\"k\": 3, \"[py]\": {\"k\": 30}}"),
        layer(LayerId.ENVIRONMENT, "{\"k\": 4, \"[py]\": {\"k\": 40}}"),
        layer(LayerId.PROJECT, "{\"k\": 5, \"[py]\": {\"k\": 50}}"),
    )

    private fun resolve(s: Setting<Int>, layers: List<Layer>, lang: String?) =
        SettingsResolver.resolve(s, layers, lang, NO_SINK)

    /** Every scope x every top layer x plain / [lang]: the table of LLD sec 19 "Resolution". */
    @Test
    fun resolutionTable() {
        data class Case(val scope: SettingScope, val top: Int, val lang: String?, val expected: Int)
        val cases = listOf(
            // G: user global only - environment and project are ignored, [lang] too.
            Case(SettingScope.G, 4, null, 3), Case(SettingScope.G, 4, "py", 3), Case(SettingScope.G, 2, null, 3), Case(SettingScope.G, 1, null, 2),
            // E: adds environment.
            Case(SettingScope.E, 4, null, 4), Case(SettingScope.E, 4, "py", 4), Case(SettingScope.E, 3, null, 4),
            // P: project wins, no [lang].
            Case(SettingScope.P, 4, null, 5), Case(SettingScope.P, 4, "py", 5), Case(SettingScope.P, 3, null, 4),
            // L: [lang] beats plain inside each layer, higher layers still win.
            Case(SettingScope.L, 4, null, 5), Case(SettingScope.L, 4, "py", 50), Case(SettingScope.L, 3, "py", 40),
            Case(SettingScope.L, 2, "py", 30), Case(SettingScope.L, 1, "py", 20), Case(SettingScope.L, 0, "py", 1),
            Case(SettingScope.L, 2, "go", 3), Case(SettingScope.L, 1, "go", 2),
        )
        for (c in cases) {
            val layers = allLayers.take(c.top)
            assertEquals(c.toString(), c.expected, resolve(int("k", c.scope), layers, c.lang).value)
        }
    }

    @Test
    fun invalidValuesFallThroughAndAreReportedOnce() {
        val reported = ArrayList<Pair<LayerId, String>>()
        val layers = listOf(layer(LayerId.USER, "{\"k\": 7}"), layer(LayerId.PROJECT, "{\"k\": 999, \"[py]\": {\"k\": \"x\"}}"))
        val r = SettingsResolver.resolve(int("k", SettingScope.L), layers, "py") { l, k, _ -> reported += l to k }
        assertEquals(7, r.value)
        assertEquals(LayerId.USER, r.winner.layer)
        assertEquals(2, reported.size)
    }

    @Test
    fun provenanceAndShadowed() {
        val r = resolve(int("k", SettingScope.L), allLayers, "py")
        assertEquals(Provenance(LayerId.PROJECT, "py", "project"), r.winner)
        assertEquals(7, r.shadowed.size)
        assertEquals(SettingsResolver.DEFAULT_PROVENANCE, resolve(int("none", SettingScope.L), allLayers, null).winner)
    }

    @Test
    fun objectsMergeKeyWiseArraysReplace() {
        val servers = ContributedSettings.parse("ext", obj("{\"properties\": {\"lsp.servers\": {\"type\": \"object\", \"default\": {}}}}")).settings.single()
        assertEquals(Merge.OBJECT, servers.merge)
        val layers = listOf(
            layer(LayerId.USER, "{\"lsp.servers\": {\"a/py\": {\"memoryBudgetMb\": 100}, \"b\": {\"enabled\": true}}}"),
            layer(LayerId.PROJECT, "{\"lsp.servers\": {\"a/py\": {\"enabled\": false}}}"),
        )
        // One level: project's entry replaces the user's entry for the same server, others kept.
        assertEquals(
            obj("{\"a/py\": {\"enabled\": false}, \"b\": {\"enabled\": true}}"),
            SettingsResolver.resolve(servers, layers, null, NO_SINK).value,
        )
        // Two levels (OBJECT_2, lsp.servers): fields merge inside each server entry.
        val low = json("{\"a/py\": {\"memoryBudgetMb\": 100, \"env\": {\"X\": \"1\"}}, \"b\": {\"enabled\": true}}")
        val high = json("{\"a/py\": {\"enabled\": false}, \"b\": {\"enabled\": false}}")
        val merged = SettingsResolver.mergeJson(low, high, Merge.OBJECT_2.depth)
        assertEquals(obj("{\"a/py\": {\"memoryBudgetMb\": 100, \"env\": {\"X\": \"1\"}, \"enabled\": false}, \"b\": {\"enabled\": false}}"), merged)

        val list = Setting.StrList("l", SettingCategory.EDITOR, 0, 0, listOf("d"), SettingScope.P)
        val lists = listOf(layer(LayerId.USER, "{\"l\": [\"a\", \"b\"]}"), layer(LayerId.PROJECT, "{\"l\": [\"c\"]}"))
        assertEquals(listOf("c"), SettingsResolver.resolve(list, lists, null, NO_SINK).value)
    }

    @Test
    fun protectedKeysIgnoredInExtensionAndProjectLayers() {
        val profile = SettingsSchema.activeProfile
        val p = SettingsResolver.resolve(profile, listOf(layer(LayerId.EXTENSION, "{\"profiles.active\": \"evil\"}")), null, NO_SINK)
        assertEquals(SettingsPolicy.DEFAULT_PROFILE, p.value)
        val flag = Setting.Bool("extensions.enabled", SettingCategory.EXTENSIONS, 0, 0, true, SettingScope.P)
        val f = SettingsResolver.resolve(flag, listOf(layer(LayerId.PROJECT, "{\"extensions.enabled\": false}")), null, NO_SINK)
        assertEquals(true, f.value)
        assertNull(SettingsResolver.raw("extensions.wasm.enabled", listOf(layer(LayerId.EXTENSION, "{\"extensions.wasm.enabled\": false}")), null))
        // sdk-reference makes extensions.disabled project-scoped, so a project may hold it.
        val disabled = Setting.StrList("extensions.disabled", SettingCategory.EXTENSIONS, 0, 0, emptyList(), SettingScope.P)
        assertEquals(listOf("x"), SettingsResolver.resolve(disabled, listOf(layer(LayerId.PROJECT, "{\"extensions.disabled\": [\"x\"]}")), null, NO_SINK).value)
    }

    @Test
    fun builtInDefaultsAllDecode() {
        SettingsSchema.all.forEach { s ->
            @Suppress("UNCHECKED_CAST")
            val t = s as Setting<Any?>
            assertEquals(s.key, t.default, t.decode(t.encode(t.default)))
        }
    }

    @Test
    fun builtInTypesDecodeStrictly() {
        assertNull(SettingsSchema.editorFontSize.decode(json("\"12\"")))
        assertNull(SettingsSchema.editorFontSize.decode(json("7")))
        assertNull(SettingsSchema.editorFontSize.decode(json("12.5")))
        assertEquals(12, SettingsSchema.editorFontSize.decode(json("12")))
        assertEquals(ThemeMode.DARK, SettingsSchema.themeMode.decode(json("\"DARK\"")))
        assertNull(SettingsSchema.themeMode.decode(json("\"NEON\"")))
        assertNull(SettingsSchema.safeMode.decode(json("\"true\"")))
        assertNull(SettingsSchema.activeProfile.decode(json("\"../x\"")))
    }

    @Test
    fun snapshotMemoizesAndResolvesRaw() {
        val snapshot = SettingsSnapshot(SchemaState.builtInOnly(SettingsSchema.all), listOf(layer(LayerId.USER, "{\"editor.fontSize\": 20, \"x.y\": [1]}")))
        assertEquals(20, snapshot[SettingsSchema.editorFontSize])
        assertEquals(json("[1]"), snapshot.raw("x.y")?.value)
        assertEquals(json("20"), snapshot.raw("editor.fontSize")?.value)
        assertEquals(true, snapshot.isSetIn(SettingsSchema.editorFontSize, LayerId.USER))
    }
}
