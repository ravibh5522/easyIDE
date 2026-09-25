package dev.easyide.app.ui.shell.ext

import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.GroupTarget
import dev.easyide.app.ui.shell.OpenOptions
import dev.easyide.extensions.action.OpenGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentOpenersTest {
    private val opened = ArrayList<Pair<DocumentUri, OpenOptions>>()
    private val opener = documentOpener { uri, options -> opened += uri to options }

    @Test fun `a well formed uri opens with the requested group and preview`() {
        assertTrue(opener.open("ext://acme.docker/container/9f2c", OpenGroup.BESIDE, preview = true))
        val (uri, options) = opened.single()
        assertEquals("ext://acme.docker/container/9f2c", uri.toString())
        assertEquals(GroupTarget.BESIDE, options.group)
        assertTrue(options.preview)
    }

    @Test fun `every group maps to its shell target`() {
        assertEquals(listOf(GroupTarget.ACTIVE, GroupTarget.BESIDE, GroupTarget.NEW), OpenGroup.entries.map { groupOf(it) })
    }

    @Test fun `a malformed uri is refused and nothing opens`() {
        assertFalse(opener.open("not a uri", OpenGroup.ACTIVE, preview = false))
        assertFalse(opener.open("ext://ONLY", OpenGroup.ACTIVE, preview = false))
        assertTrue(opened.isEmpty())
    }
}
