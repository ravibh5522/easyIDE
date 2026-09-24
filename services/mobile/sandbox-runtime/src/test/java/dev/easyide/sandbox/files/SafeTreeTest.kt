package dev.easyide.sandbox.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SafeTreeTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun `delete does not follow a symlink out of the tree`() {
        val outside = temp.newFolder("outside").also { File(it, "keep.txt").writeText("precious") }
        val tree = temp.newFolder("tree")
        File(tree, "file.txt").writeText("x")
        Files.createSymbolicLink(File(tree, "escape").toPath(), outside.toPath())

        SafeTree.deleteRecursively(tree)

        assertFalse(tree.exists())
        assertEquals("precious", File(outside, "keep.txt").readText())
    }

    @Test fun `delete removes read-only directories`() {
        val tree = temp.newFolder("tree")
        val locked = File(tree, "locked").apply { mkdirs() }
        File(locked, "f").writeText("x")
        locked.setWritable(false)

        SafeTree.deleteRecursively(tree)

        assertFalse(tree.exists())
    }

    @Test fun `delete of a missing path is a no-op`() {
        SafeTree.deleteRecursively(File(temp.root, "nothing"))
    }

    @Test fun `copy keeps symlinks as links and the executable bit`() {
        val source = temp.newFolder("src")
        File(source, "sub").mkdirs()
        File(source, "sub/a.txt").writeText("a")
        File(source, "run.sh").apply { writeText("#!/bin/sh"); setExecutable(true) }
        val outside = temp.newFolder("outside")
        Files.createSymbolicLink(File(source, "link").toPath(), outside.toPath())
        val target = File(temp.root, "dst")

        SafeTree.copyRecursively(source, target)

        assertEquals("a", File(target, "sub/a.txt").readText())
        assertTrue(File(target, "run.sh").canExecute())
        assertTrue(Files.isSymbolicLink(File(target, "link").toPath()))
        // The link's target was not duplicated into the copy.
        assertEquals(0, outside.list()!!.size)
    }
}
