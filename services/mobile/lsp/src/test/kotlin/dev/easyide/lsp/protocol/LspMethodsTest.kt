package dev.easyide.lsp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LspMethodsTest {

    @Test
    fun allListsEveryMethodOnce() {
        val names = LspMethods.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(LspMethods.FORMATTING in LspMethods.ALL && LspMethods.EXECUTE_COMMAND in LspMethods.ALL)
        // Every feature the client can route has at least one method gating on it.
        val gated = LspMethods.ALL.mapNotNull { it.feature }.toSet()
        assertEquals(LspFeature.entries.toSet(), gated)
    }
}
