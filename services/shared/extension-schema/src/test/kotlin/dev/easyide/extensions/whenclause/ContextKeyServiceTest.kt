package dev.easyide.extensions.whenclause

import dev.easyide.extensions.FakeSettings
import dev.easyide.extensions.settings.RuntimeScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContextKeyServiceTest {
    private fun expr(s: String) = (WhenParser.parse(s) as WhenParseResult.Ok).expr

    @Test fun `typed keys encode and validate their values`() {
        val svc = ContextKeyService(FakeSettings())
        svc.set(ContextKeys.inputMode, "touch")
        svc.set(ContextKeys.editorFocus, true)
        svc.set(ContextKeys.stageVisible("bottom"), true)
        val s = svc.snapshot.value
        assertTrue(s.evaluate(expr("inputMode == touch && editorFocus && stageVisible:bottom")))
        runCatching { svc.set(ContextKeys.inputMode, "gamepad") }.let { assertTrue(it.isFailure) }
        svc.set(ContextKeys.editorFocus, null)
        assertFalse(svc.snapshot.value.evaluate(expr("editorFocus")))
    }

    @Test fun `setAll is one version bump and no-op writes do not bump`() {
        val svc = ContextKeyService(FakeSettings())
        svc.setAll(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2)))
        assertEquals(1, svc.snapshot.value.version)
        svc.setAll(mapOf("a" to JsonPrimitive(1)))
        assertEquals(1, svc.snapshot.value.version)
        svc.setRaw("a", null)
        assertEquals(2, svc.snapshot.value.version)
    }

    @Test fun `observe re-evaluates only when a referenced key changes`() = runTest(UnconfinedTestDispatcher()) {
        var evaluations = 0
        val svc = ContextKeyService(FakeSettings(), evaluator = { e, c -> evaluations++; WhenEvaluator.evaluate(e, c) })
        val seen = ArrayList<Boolean>()
        val job = launch { svc.observe(expr("editorLangId == python")).toList(seen) }
        assertEquals(1, evaluations)
        svc.setRaw("terminalFocus", JsonPrimitive(true))
        svc.setRaw("gitBranch", JsonPrimitive("main"))
        assertEquals(1, evaluations)
        svc.setRaw("editorLangId", JsonPrimitive("python"))
        assertEquals(2, evaluations)
        svc.setRaw("editorLangId", JsonPrimitive("rust"))
        assertEquals(listOf(false, true, false), seen)
        job.cancel()
    }

    @Test fun `config keys resolve lazily per language and follow settings changes`() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings()
        settings.set("editor.formatOnSave", "false")
        settings.languageValues["python" to "editor.formatOnSave"] = JsonPrimitive(true)
        val svc = ContextKeyService(settings, RuntimeScope("env1", "p1"))
        val e = expr("config.editor.formatOnSave")
        assertFalse(svc.snapshot.value.evaluate(e))
        svc.set(ContextKeys.editorLangId, "python")
        assertTrue(svc.snapshot.value.evaluate(e))

        val seen = ArrayList<Boolean>()
        svc.set(ContextKeys.editorLangId, "rust")
        val job = launch { svc.observe(e).toList(seen) }
        settings.set("editor.formatOnSave", "true")
        assertEquals(listOf(false, true), seen)
        job.cancel()
    }

    @Test fun `config keys cannot be written as context keys`() {
        val svc = ContextKeyService(FakeSettings())
        assertTrue(runCatching { svc.setRaw("config.x", JsonPrimitive(1)) }.isFailure)
    }

    @Test fun `scoped overrides shadow and hide keys for one evaluation`() {
        val svc = ContextKeyService(FakeSettings())
        svc.setRaw("viewItem", JsonPrimitive("file"))
        svc.setRaw("gitDirty", JsonPrimitive(true))
        val scoped = svc.snapshot.value.with(mapOf("viewItem" to JsonPrimitive("folder"), "gitDirty" to null))
        assertTrue(WhenEvaluator.evaluate(expr("viewItem == folder && !gitDirty"), scoped))
        assertTrue(svc.snapshot.value.evaluate(expr("viewItem == file && gitDirty")))
    }
}
