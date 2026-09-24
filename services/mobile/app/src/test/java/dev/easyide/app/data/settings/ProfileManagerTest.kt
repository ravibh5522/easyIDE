package dev.easyide.app.data.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prefs = FakeDataStore()
    private val defaultUser = DataStoreUserLayer(prefs)

    private fun manager(scope: kotlinx.coroutines.CoroutineScope): ProfileManager {
        val dir = tmp.newFolder("user")
        return ProfileManager(
            defaultUser = defaultUser,
            defaultKeybindings = FileKeybindings(PlainFileIo(File(dir, "keybindings.json"), Dispatchers.IO)),
            profilesDir = File(dir, "profiles"),
            io = Dispatchers.IO,
            scope = scope,
        )
    }

    @Test
    fun createSwitchAndKeepAppLevelKeysInTheDefaultStore() = runTest {
        val m = manager(backgroundScope)
        m.userLayer.write(listOf(SettingEdit("editor.fontSize", null, json("15")), SettingEdit("extensions.safeMode", null, json("false"))))
        m.keybindings.writeText("[{\"key\": \"f5\", \"command\": \"x\"}]")
        assertTrue(m.create("work", SettingsPolicy.DEFAULT_PROFILE).isSuccess)
        assertEquals(listOf("work"), m.profiles.value)
        assertTrue(m.switchTo("work").isSuccess)
        assertEquals("work", m.active.first())

        m.userLayer.write(listOf(SettingEdit("editor.fontSize", null, json("20")), SettingEdit("extensions.safeMode", null, json("true"))))
        val doc = m.userLayer.doc.first { it.plain["editor.fontSize"] == json("20") }
        assertEquals(json("true"), doc.plain["extensions.safeMode"])
        assertEquals("profile work", m.userLayer.source)
        // App-level key went to the default store; the profile value stayed in the profile.
        assertEquals(json("true"), defaultUser.doc.first().plain["extensions.safeMode"])
        assertEquals(json("15"), defaultUser.doc.first().plain["editor.fontSize"])
        assertTrue(m.keybindings.text.first().contains("\"f5\""))

        assertTrue(m.switchTo(SettingsPolicy.DEFAULT_PROFILE).isSuccess)
        assertEquals(json("15"), m.userLayer.doc.first { it.plain["editor.fontSize"] == json("15") }.plain["editor.fontSize"])
    }

    @Test
    fun refusesInvalidNamesActiveDeletesAndBrokenProfiles() = runTest {
        val m = manager(backgroundScope)
        assertTrue(m.create("default", null).isFailure)
        assertTrue(m.create("../x", null).isFailure)
        assertTrue(m.create("a", "missing").isFailure)
        assertTrue(m.create("a", null).isSuccess)
        assertTrue(m.create("a", null).isFailure)
        assertTrue(m.switchTo("a").isSuccess)
        assertTrue(m.delete("a").isFailure)
        assertTrue(m.rename("a", "b").isFailure)
        assertTrue(m.switchTo(SettingsPolicy.DEFAULT_PROFILE).isSuccess)
        assertTrue(m.rename("a", "b").isSuccess)
        assertEquals(listOf("b"), m.profiles.value)

        File(tmp.root, "user/profiles/c.json").writeText("{ broken")
        m.refresh()
        assertTrue(m.switchTo("c").isFailure)
        assertEquals(SettingsPolicy.DEFAULT_PROFILE, m.active.first())
        assertTrue(m.delete("b").isSuccess)
        assertFalse("b" in m.profiles.value)
    }
}
