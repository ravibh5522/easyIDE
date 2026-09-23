package dev.easyide.extensions.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PackageLayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pkg(vararg files: Pair<String, String>): File {
        val root = tmp.newFolder()
        files.forEach { (p, c) -> File(root, p).apply { parentFile.mkdirs(); writeText(c) } }
        return root
    }

    private fun codes(root: File, limits: PackageLimits = PackageLimits.DEFAULT) =
        (PackageLayoutReader.read(root, limits) as PackageLayout.Invalid).errors.map { it.code to it.file }

    @Test fun `lists regular files with relative slash paths`() {
        val root = pkg("package.json" to "{}", "syntaxes/a.json" to "{}", ".descriptor.json" to "{}")
        val files = (PackageLayoutReader.read(root, PackageLimits.DEFAULT) as PackageLayout.Ok).files
        assertEquals(listOf("package.json", "syntaxes/a.json"), files.list())
        assertTrue(files.hostPath("syntaxes/a.json").endsWith("syntaxes" + File.separator + "a.json"))
        assertEquals(2L, files.size("package.json"))
    }

    @Test fun `symlinks are refused`() {
        val root = pkg("package.json" to "{}")
        Files.createSymbolicLink(File(root, "link.json").toPath(), File("/etc/hostname").toPath())
        Files.createSymbolicLink(File(root, "dir").toPath(), tmp.newFolder().toPath())
        assertEquals(setOf(DiagnosticCode.PACKAGE_SYMLINK to "link.json", DiagnosticCode.PACKAGE_SYMLINK to "dir"), codes(root).toSet())
    }

    @Test fun `nested archives are refused`() {
        assertEquals(listOf(DiagnosticCode.PACKAGE_NESTED_ARCHIVE to "vendor/inner.zip"), codes(pkg("package.json" to "{}", "vendor/inner.zip" to "PK")))
        assertEquals(DiagnosticCode.PACKAGE_NESTED_ARCHIVE, codes(pkg("a.VSIX" to "x")).single().first)
    }

    @Test fun `case-insensitive duplicates are refused`() {
        val root = pkg("Readme.md" to "a")
        val other = File(root, "README.md")
        if (other.exists()) return // case-insensitive filesystem: the duplicate cannot exist on disk
        other.writeText("b")
        assertEquals(DiagnosticCode.PACKAGE_DUPLICATE, codes(root).single().first)
    }

    @Test fun `per-file and total size limits`() {
        val limits = PackageLimits(fileBytes = 5, unpackedBytes = 8, packageBytes = 100, wasmModuleBytes = 100)
        assertEquals(listOf(DiagnosticCode.PACKAGE_FILE_TOO_LARGE to "big.txt"), codes(pkg("big.txt" to "123456"), limits))
        assertEquals(listOf(DiagnosticCode.PACKAGE_TOO_LARGE to ""), codes(pkg("a" to "12345", "b" to "1234"), limits))
    }

    @Test fun `missing directory is an io diagnostic`() {
        assertEquals(DiagnosticCode.PACKAGE_IO, codes(File(tmp.root, "absent")).single().first)
    }

    @Test fun `manifest path normalization`() {
        assertEquals("syntaxes/a.json", PackagePaths.normalize("./syntaxes/a.json"))
        assertEquals("a/b", PackagePaths.normalize("a//./b"))
        listOf("../a", "a/../../b", "a/..", "/abs", "C:/x", "a\\b", "", ".", "a\u0000b").forEach { assertNull(it, PackagePaths.normalize(it)) }
    }
}
