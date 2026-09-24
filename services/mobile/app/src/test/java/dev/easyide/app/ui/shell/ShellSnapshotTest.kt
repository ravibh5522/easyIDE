package dev.easyide.app.ui.shell

import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement
import dev.easyide.app.ui.screens.workspace.layout.PaneSizes
import dev.easyide.app.ui.screens.workspace.layout.StageVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellSnapshotTest {
    private val a = file("a.kt")
    private val b = file("b.kt")

    private fun workspace(): ScopeState {
        val layout = PanelLayout(
            preset = "workbench",
            containers = mapOf(Placement.SIDEBAR to "explorer", Placement.PANEL to "terminal"),
            open = StageVisibility(left = true, bottom = true),
            sizes = PaneSizes(explorer = 280f),
        )
        val group = EditorGroup.sanitized(listOf(Tab(a), Tab(b, TabState.PREVIEW)), b, listOf(b, a))
        return ScopeState(
            nav = "files",
            layout = layout,
            saved = mapOf(PaneArrangement.SINGLE_PANE to PanelLayout()),
            stage = EditorStage(listOf(group), 0, SplitAxis.ROW),
        )
    }

    private val goldenWorkspace = """{"v":1,"scope":"workspace","nav":"files","layouts":""" +
        """{"compact":{"preset":"auto","containers":{},"open":[],"sizes":{}},""" +
        """"expanded":{"preset":"workbench","containers":{"sidebar":"explorer","panel":"terminal"},"open":["sidebar","panel"],"sizes":{"sidebar":280.0}}},""" +
        """"stage":{"axis":"row","active":0,"groups":[{"active":"file:///workspace/b.kt","tabs":[""" +
        """{"uri":"file:///workspace/a.kt","state":"kept"},{"uri":"file:///workspace/b.kt","state":"preview"}],""" +
        """"mru":["file:///workspace/b.kt","file:///workspace/a.kt"]}]},""" +
        """"docState":{"file:///workspace/a.kt":"{\"line\":3}"}}"""

    private val goldenApp = """{"v":1,"scope":"app","nav":"settings","selection":{"settings":"easyide://settings/editor"},""" +
        """"layouts":{"compact":{"preset":"auto","containers":{"sidebar":"settings.categories"},"open":["sidebar"],"sizes":{}}}}"""

    @Test
    fun `workspace encodes to the golden json`() {
        val json = ShellSnapshot.encodeWorkspace(workspace(), PaneArrangement.FULL, ::typeOf, mapOf(a to """{"line":3}"""))
        assertEquals(goldenWorkspace, json)
    }

    @Test
    fun `the golden workspace json restores exactly`() {
        val restored = requireNotNull(ShellSnapshot.decodeWorkspace(goldenWorkspace, PaneArrangement.FULL))
        assertEquals(workspace(), restored.state)
        assertEquals(mapOf(a to """{"line":3}"""), restored.docStates)
    }

    @Test
    fun `app scope encodes to the golden json and restores`() {
        val app = ScopeState.app().copy(
            nav = CoreShell.SETTINGS,
            layout = PanelLayout(containers = mapOf(Placement.SIDEBAR to CoreShell.SETTINGS_CATEGORIES), open = StageVisibility(left = true)),
            selection = mapOf(CoreShell.SETTINGS to uri("easyide://settings/editor")),
        )
        assertEquals(goldenApp, ShellSnapshot.encodeApp(app, PaneArrangement.SINGLE_PANE))
        val restored = requireNotNull(ShellSnapshot.decodeApp(goldenApp, PaneArrangement.SINGLE_PANE))
        assertEquals(CoreShell.SETTINGS, restored.nav)
        assertEquals(app.layout, restored.layout)
        assertEquals(app.selection, restored.selection)
        assertEquals(listOf(uri("easyide://settings/editor")), restored.stage.documents)
    }

    @Test
    fun `restoring into another arrangement keeps the saved layouts and picks the matching one`() {
        val restored = requireNotNull(ShellSnapshot.decodeWorkspace(goldenWorkspace, PaneArrangement.SINGLE_PANE)).state
        assertEquals(PanelLayout(), restored.layout)
        assertEquals("workbench", restored.saved.getValue(PaneArrangement.FULL).preset)
        assertEquals(setOf(a, b), restored.stage.documents.toSet())
    }

    @Test
    fun `restoring into an arrangement with no saved layout uses the auto preset and the window rule`() {
        val restored = requireNotNull(ShellSnapshot.decodeWorkspace(goldenWorkspace, PaneArrangement.ONE_SIDE)).state
        assertEquals(setOf(Placement.SIDEBAR), Placement.entries.filter(restored.layout::isOpen).toSet())
        assertEquals("auto", restored.layout.preset)
    }

    @Test
    fun `restoring into a smaller window merges groups and keeps every document`() {
        val two = workspace().copy(stage = EditorStage().open(a, OpenOptions(), 4, FILE_TYPE).open(b, OpenOptions(group = GroupTarget.BESIDE), 4, FILE_TYPE))
        val json = ShellSnapshot.encodeWorkspace(two, PaneArrangement.FULL, ::typeOf)
        val restored = requireNotNull(ShellSnapshot.decodeWorkspace(json, PaneArrangement.SINGLE_PANE)).state
        assertEquals(1, restored.stage.groups.size)
        assertEquals(setOf(a, b), restored.stage.documents.toSet())
    }

    @Test
    fun `every field round trips`() {
        val stage = EditorStage()
            .open(a, OpenOptions(), 4, FILE_TYPE)
            .open(b, OpenOptions(preview = true), 4, FILE_TYPE)
            .open(file("c.kt"), OpenOptions(group = GroupTarget.BESIDE), 4, FILE_TYPE)
            .pin(0, a)
            .open(uri("easyide://settings/editor#fonts"), OpenOptions(group = GroupTarget.BESIDE), 4, DocumentType.UNAVAILABLE)
            .focusGroup(1)
        val scope = workspace().copy(stage = stage)
        val json = ShellSnapshot.encodeWorkspace(scope, PaneArrangement.FULL, ::typeOf)
        val back = requireNotNull(ShellSnapshot.decodeWorkspace(json, PaneArrangement.FULL)).state
        assertEquals(stage.axis, back.stage.axis)
        assertEquals(stage.active, back.stage.active)
        assertEquals(stage.groups.map { it.tabs }, back.stage.groups.map { it.tabs })
        assertEquals(stage.groups.map { it.active }, back.stage.groups.map { it.active })
        assertEquals(stage.groups.map { it.mru }, back.stage.groups.map { it.mru })
        assertEquals(scope.layout, back.layout)
        assertEquals(scope.saved, back.saved)
        assertEquals(scope.nav, back.nav)
    }

    @Test
    fun `documents of a type that is not restorable are left out`() {
        val ephemeral = requireNotNull(DocumentUri.preview("/workspace/README.md"))
        val scope = workspace().copy(stage = EditorStage(listOf(EditorGroup().open(a, false).open(ephemeral, false))))
        val json = ShellSnapshot.encodeWorkspace(scope, PaneArrangement.FULL, ::typeOf, mapOf(ephemeral to "x", a to "y"))
        assertTrue(ephemeral.toString() !in json)
        val restored = requireNotNull(ShellSnapshot.decodeWorkspace(json, PaneArrangement.FULL))
        assertEquals(listOf(a), restored.state.stage.documents)
        assertEquals(mapOf(a to "y"), restored.docStates)
    }

    @Test
    fun `a missing type stays as a tab that resolves to the placeholder`() {
        val json = goldenWorkspace.replace("file:///workspace/b.kt", "ext://gone.pack/thing/1")
        val restored = requireNotNull(ShellSnapshot.decodeWorkspace(json, PaneArrangement.FULL)).state
        val gone = uri("ext://gone.pack/thing/1")
        assertTrue(gone in restored.stage.documents)
        assertEquals(DocumentType.UNAVAILABLE_ID, DocumentRegistry.EMPTY.resolve(gone).id)
        assertEquals(gone, restored.stage.activeGroup.active)
    }

    @Test
    fun `damaged content degrades instead of failing`() {
        val json = """{"v":1,"scope":"workspace","nav":7,"layouts":{"wide":{},"compact":"x","expanded":{"preset":5,"containers":{"left":"a","sidebar":3,"panel":"terminal"},"open":["sidebar","nowhere",4],"sizes":{"sidebar":"big","panel":120}}},""" +
            """"stage":{"axis":"diagonal","active":"zero","groups":[7,{"tabs":[{"uri":"not a uri"},{"uri":"file:///workspace/a.kt","state":"weird"},{"uri":"file:///workspace/a.kt"},{"state":"kept"},5],"active":"file:///nowhere","mru":["file:///workspace/a.kt","file:///workspace/a.kt","x"]}]},""" +
            """"docState":{"file:///workspace/a.kt":"keep","file:///workspace/zzz.kt":"drop","not a uri":"drop","file:///workspace/a.kt2":5}}"""
        val restored = requireNotNull(ShellSnapshot.decodeWorkspace(json, PaneArrangement.FULL))
        val s = restored.state
        assertEquals(CoreShell.FILES, s.nav)
        assertEquals(listOf(a), s.stage.documents)
        assertEquals(TabState.KEPT, s.stage.groups.single().tabs.single().state)
        assertEquals(a, s.stage.activeGroup.active)
        assertEquals(SplitAxis.ROW, s.stage.axis)
        assertEquals(LayoutPresets.AUTO, s.layout.preset)
        assertEquals(mapOf(Placement.PANEL to "terminal"), s.layout.containers)
        assertEquals(PaneSizes(bottom = 120f), s.layout.sizes)
        assertEquals(setOf(Placement.SIDEBAR), Placement.entries.filter(s.layout::isOpen).toSet())
        assertEquals(mapOf(a to "keep"), restored.docStates)
    }

    @Test
    fun `more than four groups are truncated and the active group is clamped`() {
        val group = """{"tabs":[],"mru":[]}"""
        val json = """{"v":1,"scope":"workspace","stage":{"active":9,"groups":[$group,$group,$group,$group,$group,$group]}}"""
        val s = requireNotNull(ShellSnapshot.decodeWorkspace(json, PaneArrangement.FULL)).state
        assertEquals(ShellLimits.MAX_GROUPS, s.stage.groups.size)
        assertEquals(ShellLimits.MAX_GROUPS - 1, s.stage.active)
    }

    @Test
    fun `an empty document restores to the default workspace`() {
        val s = requireNotNull(ShellSnapshot.decodeWorkspace("""{"v":1,"scope":"workspace"}""", PaneArrangement.FULL)).state
        assertEquals(ScopeState.workspace(PaneArrangement.FULL), s)
    }

    @Test
    fun `unreadable or foreign files yield null`() {
        val cases = listOf(
            "not json", "", "[]", "\"x\"", "null", "{}",
            """{"scope":"workspace"}""",
            """{"v":"1","scope":"workspace"}""",
            """{"v":0,"scope":"workspace"}""",
            """{"v":${ShellSnapshot.VERSION + 1},"scope":"workspace"}""",
            """{"v":1,"scope":"app"}""",
            """{"v":1}""",
        )
        cases.forEach { assertNull("workspace: $it", ShellSnapshot.decodeWorkspace(it, PaneArrangement.FULL)) }
        assertNull(ShellSnapshot.decodeApp("""{"v":1,"scope":"workspace"}""", PaneArrangement.FULL))
        assertNull(ShellSnapshot.decodeApp("{{", PaneArrangement.FULL))
        assertNull(ShellSnapshot.decodeApp("""{"v":2,"scope":"app"}""", PaneArrangement.FULL))
    }

    @Test
    fun `app restore tolerates a missing nav and bad selections`() {
        val s = requireNotNull(ShellSnapshot.decodeApp("""{"v":1,"scope":"app","selection":{"settings":"bad uri","home":5}}""", PaneArrangement.FULL))
        assertEquals(CoreShell.HOME, s.nav)
        assertEquals(emptyMap<String, DocumentUri>(), s.selection)
        assertEquals(EditorStage(), s.stage)
        assertEquals(ScopeState.app().layout, s.layout)
    }

    @Test
    fun `encoding is stable across runs`() {
        val first = ShellSnapshot.encodeWorkspace(workspace(), PaneArrangement.FULL, ::typeOf)
        val again = ShellSnapshot.encodeWorkspace(requireNotNull(ShellSnapshot.decodeWorkspace(first, PaneArrangement.FULL)).state, PaneArrangement.FULL, ::typeOf)
        assertEquals(first, again)
    }
}
