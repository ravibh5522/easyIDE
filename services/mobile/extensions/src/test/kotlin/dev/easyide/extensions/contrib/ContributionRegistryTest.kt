package dev.easyide.extensions.contrib

import dev.easyide.extensions.manifest.DiagnosticCode
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.SemVer
import dev.easyide.extensions.settings.ExtensionSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContributionRegistryTest {
    private val a = ExtensionId.parse("acme.a")!!
    private val b = ExtensionId.parse("acme.b")!!
    private val v1 = SemVer(1, 0, 0)
    private fun file(p: String) = PackageFile(p, "/host/$p")
    private fun cmd(id: String) = CommandContribution(id, id, null, null, null, null)
    private fun ext(id: ExtensionId, c: Contributions, v: SemVer = v1) = RegisteredExtension(id, v, c)
    private fun setting(key: String, default: Int) =
        ConfigurationProperty(key, null, null, JsonObject(mapOf("type" to JsonPrimitive("integer"))), JsonPrimitive(default), null, null)

    @Test fun `add, update and remove owners by id and version`() {
        val reg = ContributionRegistry()
        val d1 = reg.update(listOf(ext(a, Contributions(commands = listOf(cmd("a.run"))))))
        assertEquals(listOf(a), d1.added)
        assertEquals(listOf("a.run"), reg.commands.entries.value.map { it.value.command })
        val same = reg.update(listOf(ext(a, Contributions(commands = listOf(cmd("a.run"))))))
        assertTrue(same.isEmpty)
        assertEquals(1, reg.snapshot.value.version)
        val upgraded = reg.update(listOf(ext(a, Contributions(commands = listOf(cmd("a.run2"))), SemVer(1, 1, 0))))
        assertEquals(listOf(a), upgraded.added)
        assertEquals(listOf(a), upgraded.removed)
        assertEquals(listOf("a.run2"), reg.commands.entries.value.map { it.value.command })
        reg.remove(a)
        assertTrue(reg.commands.entries.value.isEmpty())
        assertEquals(3, reg.snapshot.value.version)
    }

    @Test fun `each store emits at most once per update and only when it changed`() = runTest(UnconfinedTestDispatcher()) {
        val reg = ContributionRegistry()
        val commands = ArrayList<Int>()
        val themes = ArrayList<Int>()
        val jobs = listOf(
            launch { reg.commands.entries.collect { commands += it.size } },
            launch { reg.themes.entries.collect { themes += it.size } },
        )
        reg.update(listOf(ext(a, Contributions(commands = listOf(cmd("a.x"), cmd("a.y"))))))
        reg.update(listOf(ext(a, Contributions(commands = listOf(cmd("a.x"), cmd("a.y")))), ext(b, Contributions(themes = listOf(ThemeContribution(null, "T", UiTheme.DARK, file("t.json")))))))
        assertEquals(listOf(0, 2), commands)
        assertEquals(listOf(0, 1), themes)
        jobs.forEach { it.cancel() }
    }

    @Test fun `built-in command wins, extension duplicate dropped with E_COMMAND_SHADOWED`() {
        val reg = ContributionRegistry(Contributions(commands = listOf(cmd("workbench.save"))))
        reg.update(listOf(ext(a, Contributions(commands = listOf(cmd("workbench.save"))))))
        assertEquals(listOf(Owner.BuiltIn), reg.commands.entries.value.map { it.owner })
        assertEquals(DiagnosticCode.COMMAND_SHADOWED, reg.snapshot.value.conflicts.single().code)
        assertEquals(Owner.BuiltIn, reg.commandOwner("workbench.save"))
    }

    @Test fun `between extensions the earlier wins and later menus bind to the winner`() {
        val reg = ContributionRegistry()
        val later = Contributions(commands = listOf(cmd("x.run")), menus = listOf(MenuItemContribution("editor/title", "x.run", null, null, null, null)))
        reg.update(listOf(ext(a, Contributions(commands = listOf(cmd("x.run")))), ext(b, later)))
        assertEquals(Owner.Ext(a), reg.commandOwner("x.run"))
        assertEquals(listOf(Owner.Ext(b)), reg.menus.entries.value.map { it.owner })
        assertEquals(DiagnosticCode.CONTRIBUTION_SHADOWED, reg.snapshot.value.conflicts.single().code)
    }

    @Test fun `configuration keys - built-in then earlier owns the key`() {
        val reg = ContributionRegistry(Contributions(configuration = listOf(setting("editor.tabSize", 4))))
        reg.update(listOf(
            ext(a, Contributions(configuration = listOf(setting("editor.tabSize", 2), setting("shared.key", 1)))),
            ext(b, Contributions(configuration = listOf(setting("shared.key", 9), setting("b.own", 0)))),
        ))
        val owners = reg.configuration.entries.value.associate { it.value.key to it.owner }
        assertEquals(mapOf("editor.tabSize" to Owner.BuiltIn, "shared.key" to Owner.Ext(a), "b.own" to Owner.Ext(b)), owners)
        assertEquals(setOf("b.own"), reg.ownedSettings(Owner.Ext(b)))
    }

    @Test fun `extension grammar replaces bundled, earlier extension wins`() {
        fun g(scope: String, p: String) = GrammarContribution(null, scope, file(p), emptyMap(), emptyList(), emptyMap())
        val reg = ContributionRegistry(Contributions(grammars = listOf(g("source.python", "bundled.json"), g("source.go", "go.json"))))
        reg.update(listOf(ext(a, Contributions(grammars = listOf(g("source.python", "a.json")))), ext(b, Contributions(grammars = listOf(g("source.python", "b.json"))))))
        val byScope = reg.grammars.entries.value.associate { it.value.scopeName to it.value.file.path }
        assertEquals(mapOf("source.go" to "go.json", "source.python" to "a.json"), byScope)
        assertEquals(2, reg.snapshot.value.conflicts.size)
        reg.update(emptyList())
        assertEquals("bundled.json", reg.grammars.entries.value.first { it.value.scopeName == "source.python" }.value.file.path)
    }

    @Test fun `languages merge instead of conflicting`() {
        fun lang(ext: List<String>, first: String?, config: PackageFile?) = LanguageContribution("py", listOf("Py"), ext, emptyList(), emptyList(), first, emptyList(), config)
        val reg = ContributionRegistry(Contributions(languages = listOf(lang(listOf(".py"), null, null))))
        reg.update(listOf(ext(a, Contributions(languages = listOf(lang(listOf(".pyi"), "^#!python", file("a.json"))))),
            ext(b, Contributions(languages = listOf(lang(listOf(".py", ".pyw"), "^#!other", file("b.json")))))))
        val merged = reg.languages.entries.value.single()
        assertEquals(listOf(".py", ".pyi", ".pyw"), merged.value.extensions)
        assertEquals("^#!python", merged.value.firstLine)
        assertEquals("a.json", merged.value.configuration?.path)
        assertEquals(Owner.BuiltIn, merged.owner)
    }

    @Test fun `duplicate theme labels are kept with the publisher appended`() {
        val t = ThemeContribution(null, "Night", UiTheme.DARK, file("t.json"))
        val reg = ContributionRegistry()
        reg.update(listOf(ext(a, Contributions(themes = listOf(t))), ext(ExtensionId.parse("other.b")!!, Contributions(themes = listOf(t)))))
        assertEquals(listOf("Night", "Night (other)"), reg.themes.entries.value.map { it.value.label })
    }

    @Test fun `ids of views, stages, key rows, status items and icon themes - earlier wins`() {
        val row = KeyRowContribution("r", "R", null, emptyList())
        val stage = StageContribution("s", "S", null, StagePlacement.RIGHT, emptyList(), null)
        val item = StatusBarItemContribution("i", dev.easyide.extensions.action.Template.literal("x"), null, null, StatusBarAlignment.LEFT, 0, null)
        val view = ViewContribution("c", "v", "V", null)
        val c = Contributions(keyRows = listOf(row), stages = listOf(stage), statusBarItems = listOf(item), views = listOf(view),
            iconThemes = listOf(IconThemeContribution("icons", "I", file("i.json"))))
        val reg = ContributionRegistry()
        reg.update(listOf(ext(a, c), ext(b, c)))
        val s = reg.snapshot.value
        listOf(s.keyRows, s.stages, s.statusBarItems, s.views, s.iconThemes).forEach { assertEquals(listOf(Owner.Ext(a)), it.map { e -> e.owner }) }
        assertEquals(5, s.conflicts.size)
    }

    @Test fun `server keys cannot collide`() {
        fun server(key: String) = LanguageServerContribution(key, "s", listOf("x"), emptyList(), emptyMap(), JsonObject(emptyMap()), null, listOf(".git"), null, null, null, null, emptyList(), 0)
        val reg = ContributionRegistry()
        reg.update(listOf(ext(a, Contributions(languageServers = listOf(server("acme.a/s")))), ext(b, Contributions(languageServers = listOf(server("acme.b/s"))))))
        assertEquals(2, reg.languageServers.entries.value.size)
        assertTrue(reg.snapshot.value.conflicts.isEmpty())
    }

    @Test fun `view data only from the owner of the view`() {
        val from = dev.easyide.extensions.action.Action.ShowMessage(dev.easyide.extensions.action.Template.literal("x"), dev.easyide.extensions.action.MessageSeverity.INFO, emptyList(), null)
        val vd = ViewDataContribution("v", ViewDataKind.LIST, from, emptyList())
        val reg = ContributionRegistry()
        reg.update(listOf(ext(a, Contributions(views = listOf(ViewContribution("c", "v", "V", null)))), ext(b, Contributions(viewData = listOf(vd)))))
        assertTrue(reg.viewData.entries.value.isEmpty())
        reg.update(listOf(ext(a, Contributions(views = listOf(ViewContribution("c", "v", "V", null)), viewData = listOf(vd)))))
        assertEquals(1, reg.viewData.entries.value.size)
    }

    @Test fun `inspector names owner, pointer, hiding setting and conflicts`() {
        val reg = ContributionRegistry()
        reg.update(listOf(ext(a, Contributions(commands = listOf(cmd("a.run")), menus = listOf(MenuItemContribution("editor/title", "a.run", null, null, null, null))))))
        val ref = ContributionRef.parse("menu:editor/title:a.run")!!
        val entry = reg.inspect(ref, hidden = listOf("menu:editor/title:a.run"))!!
        assertEquals(Owner.Ext(a), entry.owner)
        assertEquals("/contributes/menus/editor~1title/0", entry.pointer)
        assertEquals(ExtensionSettings.WORKBENCH_HIDDEN, entry.hiddenBy)
        assertNull(reg.inspect(ContributionRef.parse("view:none")!!))
    }

    @Test fun `contribution refs round-trip their text form`() {
        listOf("menu:editor/title:python.runFile", "view:python.venvs", "statusBar:python.interpreter", "keyRow:python.symbols", "server:acme.a/s").forEach {
            assertEquals(it, ContributionRef.parse(it).toString())
        }
        assertEquals("a:b", ContributionRef.parse("menu:editor/title:a:b")!!.id)
        assertNull(ContributionRef.parse("menu:editor/title"))
        assertNull(ContributionRef.parse("unknown:x"))
    }
}
