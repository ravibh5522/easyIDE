package dev.easyide.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalChangeTest {

    private fun classify(content: String, saved: String, disk: DiskState, current: ExternalState = ExternalState.InSync) =
        ExternalChangeDetector.classify(content, saved, disk, current)

    @Test fun `identical disk text is no change`() {
        assertEquals(ExternalChange.None, classify("a", "a", DiskState.Text("a")))
        assertEquals(ExternalChange.None, classify("edited", "a", DiskState.Text("a")))
    }

    @Test fun `a clean buffer silently reloads what changed on disk`() {
        assertEquals(ExternalChange.Reload("b"), classify("a", "a", DiskState.Text("b")))
    }

    @Test fun `a dirty buffer with a changed disk is a conflict`() {
        assertEquals(ExternalChange.Conflict("b"), classify("mine", "a", DiskState.Text("b")))
    }

    @Test fun `edits that were also written to disk are just in sync`() {
        assertEquals(ExternalChange.Sync("mine"), classify("mine", "a", DiskState.Text("mine")))
    }

    @Test fun `the same conflict is not reported twice but a new disk text is`() {
        val flagged = ExternalState.Conflict("b")
        assertEquals(ExternalChange.None, classify("mine", "a", DiskState.Text("b"), flagged))
        assertEquals(ExternalChange.Conflict("c"), classify("mine", "a", DiskState.Text("c"), flagged))
    }

    @Test fun `a conflict clears when the disk goes back to the base or the user matches it`() {
        val flagged = ExternalState.Conflict("b")
        assertEquals(ExternalChange.Resync, classify("mine", "a", DiskState.Text("a"), flagged))
        assertEquals(ExternalChange.Sync("b"), classify("b", "a", DiskState.Text("b"), flagged))
    }

    @Test fun `a restored conflict stays flagged while the disk is unchanged`() {
        // A restore keeps the disk text as the saved baseline, so disk == saved here.
        assertEquals(ExternalChange.None, classify("mine", "b", DiskState.Text("b"), ExternalState.Conflict("b")))
    }

    @Test fun `a deleted file is reported once`() {
        assertEquals(ExternalChange.Gone, classify("a", "a", DiskState.Missing))
        assertEquals(ExternalChange.None, classify("a", "a", DiskState.Missing, ExternalState.Gone))
    }

    @Test fun `a file that reappears identical is back in sync`() {
        assertEquals(ExternalChange.Sync("a"), classify("a", "a", DiskState.Text("a"), ExternalState.Gone))
    }

    @Test fun `a file that is no longer editable text is left alone`() {
        assertEquals(ExternalChange.None, classify("a", "a", DiskState.NotText))
    }

    @Test fun `restore keeps a backup dirty when the disk still matches its base`() {
        val base = Hashes.sha256Hex("base")
        assertEquals(RestoredBuffer("mine", "base", ExternalState.InSync), ExternalChangeDetector.restore("mine", base, DiskState.Text("base")))
    }

    @Test fun `restore flags a conflict when the disk changed since the backup`() {
        val base = Hashes.sha256Hex("base")
        assertEquals(RestoredBuffer("mine", "other", ExternalState.Conflict("other")), ExternalChangeDetector.restore("mine", base, DiskState.Text("other")))
    }

    @Test fun `restore treats edits already on disk as saved`() {
        val base = Hashes.sha256Hex("base")
        assertEquals(RestoredBuffer("mine", "mine", ExternalState.InSync), ExternalChangeDetector.restore("mine", base, DiskState.Text("mine")))
    }

    @Test fun `restore of a missing or unreadable file keeps the text and marks it gone`() {
        assertEquals(RestoredBuffer("mine", "", ExternalState.Gone), ExternalChangeDetector.restore("mine", "x", DiskState.Missing))
        assertEquals(RestoredBuffer("mine", "", ExternalState.Gone), ExternalChangeDetector.restore("mine", "x", DiskState.NotText))
    }

    @Test fun `remap follows a file and everything under a renamed folder`() {
        assertEquals("b.kt", PathRemap.remap("a.kt", "a.kt", "b.kt"))
        assertEquals("lib/x/y.kt", PathRemap.remap("src/x/y.kt", "src", "lib"))
        assertNull(PathRemap.remap("srcs/x.kt", "src", "lib"))
        assertNull(PathRemap.remap("other.kt", "src", "lib"))
    }
}
