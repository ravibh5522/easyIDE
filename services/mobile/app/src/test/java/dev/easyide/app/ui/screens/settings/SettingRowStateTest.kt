package dev.easyide.app.ui.screens.settings

import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingCategory
import dev.easyide.app.data.settings.SettingScope
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.SchemaState
import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.data.settings.layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingRowStateTest {

    private fun int(key: String, scope: SettingScope) =
        Setting.IntRange(key, SettingCategory.EDITOR, 0, 0, default = 1, scope = scope, min = 0, max = 100)

    private fun snapshot(vararg layers: Pair<LayerId, String>): SettingsSnapshot {
        val setting = listOf(int("k", SettingScope.L), int("g", SettingScope.G))
        return SettingsSnapshot(SchemaState.builtInOnly(setting), layers.map { (id, text) -> layer(id, text) })
    }

    @Test fun `a default row is unmodified and shows no layer`() {
        val s = RowState.of(int("k", SettingScope.L), snapshot(), LayerId.USER, null)
        assertTrue(s.editable)
        assertFalse(s.modifiedHere)
        assertNull(s.winnerLayer)
        assertNull(s.overriddenBy)
    }

    @Test fun `a value in the selected layer is modified here and wins`() {
        val s = RowState.of(int("k", SettingScope.L), snapshot(LayerId.USER to """{"k": 5}"""), LayerId.USER, null)
        assertTrue(s.modifiedHere)
        assertEquals(LayerId.USER, s.winnerLayer)
        assertNull(s.overriddenBy)
    }

    @Test fun `a higher layer that wins is named as the override`() {
        val snap = snapshot(LayerId.USER to """{"k": 5}""", LayerId.PROJECT to """{"k": 9}""")
        val s = RowState.of(int("k", SettingScope.L), snap, LayerId.USER, null)
        assertTrue(s.modifiedHere)
        assertEquals(LayerId.PROJECT, s.overriddenBy)
        assertEquals(LayerId.PROJECT, s.winnerLayer)
    }

    @Test fun `a lower layer's value is the winner without being an override`() {
        val s = RowState.of(int("k", SettingScope.L), snapshot(LayerId.USER to """{"k": 5}"""), LayerId.PROJECT, null)
        assertFalse(s.modifiedHere)
        assertEquals(LayerId.USER, s.winnerLayer)
        assertNull(s.overriddenBy)
    }

    @Test fun `a stored value the schema rejects is flagged and the default applies`() {
        val s = RowState.of(int("k", SettingScope.L), snapshot(LayerId.USER to """{"k": 999}"""), LayerId.USER, null)
        assertTrue(s.invalidHere)
        assertNull(s.winnerLayer)
    }

    @Test fun `scope decides which layers can edit`() {
        val g = int("g", SettingScope.G)
        assertNull(RowState.blockOf(g, LayerId.USER, null))
        assertEquals(RowBlock.LAYER_SCOPE, RowState.blockOf(g, LayerId.ENVIRONMENT, null))
        assertEquals(RowBlock.LAYER_SCOPE, RowState.blockOf(g, LayerId.PROJECT, null))
        assertNull(RowState.blockOf(int("p", SettingScope.P), LayerId.PROJECT, null))
        assertEquals(RowBlock.LAYER_SCOPE, RowState.blockOf(int("e", SettingScope.E), LayerId.PROJECT, null))
    }

    @Test fun `per-language editing needs a language-overridable key`() {
        assertNull(RowState.blockOf(int("k", SettingScope.L), LayerId.USER, "python"))
        assertEquals(RowBlock.NOT_PER_LANGUAGE, RowState.blockOf(int("p", SettingScope.P), LayerId.USER, "python"))
    }

    @Test fun `protected keys are marked and a project file may not hold most of them`() {
        val servers = LspSettingsSchema.servers
        assertTrue(RowState.of(servers, snapshot(), LayerId.USER, null).protectedKey)
        assertNull(RowState.blockOf(servers, LayerId.PROJECT, null))
        val profile = SettingsSchema.activeProfile
        assertTrue(RowState.of(profile, snapshot(), LayerId.USER, null).protectedKey)
        assertEquals(RowBlock.LAYER_SCOPE, RowState.blockOf(profile, LayerId.PROJECT, null))
        val enabled = SettingsSchema.extensionsEnabled
        assertTrue(RowState.of(enabled, snapshot(), LayerId.USER, null).protectedKey)
        assertFalse(RowState.of(SettingsSchema.editorFontSize, snapshot(), LayerId.USER, null).protectedKey)
    }
}
