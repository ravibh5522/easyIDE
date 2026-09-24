package dev.easyide.lsp.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageIdsTest {
    @Test fun reactGrammarsUseTheLspIdentifiers() {
        assertEquals("typescriptreact", LanguageIds.wire("tsx"))
        assertEquals("javascriptreact", LanguageIds.wire("jsx"))
    }

    @Test fun otherIdsPassThrough() {
        listOf("python", "typescript", "go", "cpp", "shellscript", "jsonc").forEach { assertEquals(it, LanguageIds.wire(it)) }
    }
}
