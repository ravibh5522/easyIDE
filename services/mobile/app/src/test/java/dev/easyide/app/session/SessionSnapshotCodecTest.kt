package dev.easyide.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSnapshotCodecTest {

    private val full = SessionSnapshot(
        projectId = "p1",
        savedAtMs = 1_700_000_000_000,
        tabs = listOf(
            TabSnapshot("src/a.kt", caretStart = 3, caretEnd = 9, scrollY = 400, scrollX = 12, showPreview = true, backup = BackupRef("0123456789abcdef.txt", "ab".repeat(32))),
            TabSnapshot("README.md"),
        ),
        activePath = "README.md",
        expandedDirs = listOf("src", "src/main"),
        layout = LayoutSnapshot(left = true, right = false, bottom = true, sidePanel = "SOURCE_CONTROL"),
    )

    @Test fun `a snapshot survives an encode decode round trip`() {
        assertEquals(full, SessionSnapshotCodec.decode(SessionSnapshotCodec.encode(full)))
    }

    @Test fun `no active tab and no layout round trip as null`() {
        val bare = SessionSnapshot("p", 1, listOf(TabSnapshot("a")), activePath = null, expandedDirs = emptyList(), layout = null)
        assertEquals(bare, SessionSnapshotCodec.decode(SessionSnapshotCodec.encode(bare)))
    }

    @Test fun `a newer writer's extra keys and higher version are ignored`() {
        val text = """
            {"v": 7, "projectId": "p", "savedAtMs": 5, "futureThing": {"x": [1, 2]},
             "tabs": [{"path": "a.txt", "caretStart": 2, "somethingNew": true, "backup": {"file": "0123456789abcdef.txt", "baseSha256": "s", "codec": "zstd"}}],
             "layout": {"left": true, "right": false, "bottom": false, "sidePanel": "EXPLORER", "dock": "top"}}
        """.trimIndent()
        val decoded = SessionSnapshotCodec.decode(text)!!
        assertEquals("a.txt", decoded.tabs.single().path)
        assertEquals(2, decoded.tabs.single().caretStart)
        assertEquals(BackupRef("0123456789abcdef.txt", "s"), decoded.tabs.single().backup)
        assertEquals(LayoutSnapshot(true, false, false, "EXPLORER"), decoded.layout)
    }

    @Test fun `an older file with only the required keys decodes with defaults`() {
        val decoded = SessionSnapshotCodec.decode("""{"v": 1, "projectId": "p"}""")!!
        assertEquals(emptyList<TabSnapshot>(), decoded.tabs)
        assertNull(decoded.activePath)
        assertNull(decoded.layout)
        assertTrue(decoded.isEmpty)
    }

    @Test fun `tabs without a path and layouts missing a stage are dropped, the rest survives`() {
        val decoded = SessionSnapshotCodec.decode(
            """{"v":1,"projectId":"p","tabs":[{"caretStart":1},{"path":""},{"path":"ok"},"junk"],"layout":{"left":true}}""",
        )!!
        assertEquals(listOf("ok"), decoded.tabs.map { it.path })
        assertNull(decoded.layout)
    }

    @Test fun `unusable files decode to null`() {
        assertNull(SessionSnapshotCodec.decode(""))
        assertNull(SessionSnapshotCodec.decode("not json"))
        assertNull(SessionSnapshotCodec.decode("[1,2]"))
        assertNull(SessionSnapshotCodec.decode("""{"projectId":"p"}"""))
        assertNull(SessionSnapshotCodec.decode("""{"v":0,"projectId":"p"}"""))
        assertNull(SessionSnapshotCodec.decode("""{"v":1}"""))
        assertNull(SessionSnapshotCodec.decode("""{"v":1,"projectId":"p","tabs":"""))
    }

    @Test fun `text with quotes newlines and non-ASCII survives`() {
        val odd = full.copy(tabs = listOf(TabSnapshot("dir/na\"me\né.txt")), expandedDirs = listOf("d\\ir"))
        assertEquals(odd, SessionSnapshotCodec.decode(SessionSnapshotCodec.encode(odd)))
    }
}
