package dev.easyide.app.lsp

import android.content.ComponentCallbacks2
import dev.easyide.lsp.manager.MemoryPressure
import dev.easyide.lsp.protocol.ClientCapabilitiesBuilder
import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.protocol.Milestone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class LspRuntimeTest {

    @Test
    fun trimLevelsMapToTheKillOrderSteps() {
        assertNull(LspRuntime.memoryPressureFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(MemoryPressure.RUNNING_LOW, LspRuntime.memoryPressureFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(MemoryPressure.RUNNING_LOW, LspRuntime.memoryPressureFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(MemoryPressure.UI_HIDDEN, LspRuntime.memoryPressureFor(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(MemoryPressure.BACKGROUND, LspRuntime.memoryPressureFor(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertEquals(MemoryPressure.BACKGROUND, LspRuntime.memoryPressureFor(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertEquals(MemoryPressure.COMPLETE, LspRuntime.memoryPressureFor(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun clientAdvertisesOnlyWhatTheAppRenders() {
        val features = ClientCapabilitiesBuilder.advertisedFeatures(Milestone.M4, LspRuntime.CLIENT_UI)
        for (f in listOf(LspFeature.COMPLETION, LspFeature.HOVER, LspFeature.INLAY_HINTS, LspFeature.WORKSPACE_SYMBOL, LspFeature.DOCUMENT_HIGHLIGHT)) {
            assertTrue("$f has a presenter", f in features)
        }
        for (f in listOf(LspFeature.SEMANTIC_TOKENS, LspFeature.CODE_LENS, LspFeature.FOLDING_RANGE, LspFeature.SELECTION_RANGE, LspFeature.DOCUMENT_LINK)) {
            assertFalse("$f has no presenter", f in features)
        }
    }
}
