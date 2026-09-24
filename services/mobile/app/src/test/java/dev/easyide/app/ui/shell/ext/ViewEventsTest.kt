package dev.easyide.app.ui.shell.ext

import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.OpenGroup
import dev.easyide.extensions.view.Effect
import dev.easyide.extensions.view.EffectOp
import dev.easyide.extensions.view.Into
import dev.easyide.extensions.view.IntoMode
import dev.easyide.extensions.view.ResolvedAction
import dev.easyide.extensions.view.ResolvedEffect
import dev.easyide.extensions.view.ResolvedTarget
import dev.easyide.extensions.view.ResultParse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewEventsTest {
    private class FakeCalls : ViewCalls {
        val commands = ArrayList<Triple<String, String, JsonElement?>>()
        val opened = ArrayList<Pair<String, OpenGroup>>()
        var result: CallResult = CallResult.Value(JsonNull)
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun command(owner: String, id: String, args: JsonElement?): CallResult {
            commands += Triple(owner, id, args)
            gate?.await()
            return result
        }
        override suspend fun inline(owner: String, title: String, action: Action, args: JsonElement?): CallResult = result
        override fun open(uri: String, group: OpenGroup): Boolean { opened += uri to group; return uri.startsWith("ext://") }
    }

    private val hub = ViewDataHub()
    private val calls = FakeCalls()
    private val events = ViewEvents(hub, calls)
    private val initial = obj("""{ "draft": "hi", "messages": [] }""")
    private val failures = ArrayList<Pair<String, String>>()

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private fun action(
        target: ResolvedTarget = ResolvedTarget.Command("demo.send", obj("""{ "text": "hi" }""")),
        into: Into? = null,
        before: List<ResolvedEffect> = emptyList(),
        after: List<ResolvedEffect> = emptyList(),
    ) = ResolvedAction(target, null, into, before, after)

    private suspend fun run(a: ResolvedAction) = events.run("acme.demo", "chat", initial, "Send", a) { what, why -> failures += what to why }

    @Test fun `effects run around the command and the result is written back`() = runTest {
        val msg = obj("""{ "role": "assistant", "text": "yo" }""")
        calls.result = CallResult.Value(msg)
        run(action(
            into = Into("messages", IntoMode.APPEND, ResultParse.JSON),
            before = listOf(ResolvedEffect(EffectOp.APPEND, "messages", obj("""{ "role": "user", "text": "hi" }""")), ResolvedEffect(EffectOp.CLEAR, "draft", null)),
            after = listOf(ResolvedEffect(EffectOp.SET, "busy", JsonPrimitive(false))),
        ))
        assertEquals(obj("""{ "messages": [{ "role": "user", "text": "hi" }, { "role": "assistant", "text": "yo" }], "busy": false }"""), hub.data("chat", initial))
        assertEquals(listOf(Triple("acme.demo", "demo.send", obj("""{ "text": "hi" }"""))), calls.commands)
        assertTrue(failures.isEmpty())
    }

    @Test fun `a failure is told once and the after effects still run`() = runTest {
        calls.result = CallResult.Failed("no such container")
        run(action(before = listOf(ResolvedEffect(EffectOp.SET, "busy", JsonPrimitive(true))), after = listOf(ResolvedEffect(EffectOp.SET, "busy", JsonPrimitive(false)))))
        assertEquals(listOf("Send" to "no such container"), failures)
        assertEquals(JsonPrimitive(false), hub.data("chat", initial)["busy"])
    }

    @Test fun `a rejected result is reported and leaves the data as it was`() = runTest {
        calls.result = CallResult.Value(obj("""{ "exitCode": 2, "stdout": "", "stderr": "boom" }"""))
        run(action(into = Into("rows", IntoMode.SET, ResultParse.JSON)))
        assertEquals(listOf("Send" to "boom"), failures)
        assertEquals(initial, hub.data("chat", initial))
    }

    @Test fun `a command that exits non-zero is a failure even when nothing is written back`() = runTest {
        calls.result = CallResult.Value(obj("""{ "exitCode": 1, "stdout": "", "stderr": "Error: no such container: web\n" }"""))
        run(action())
        assertEquals(listOf("Send" to "Error: no such container: web"), failures)
    }

    @Test fun `a dismissed prompt is silent`() = runTest {
        calls.result = CallResult.Cancelled
        run(action(into = Into("rows", IntoMode.SET, ResultParse.JSON)))
        assertTrue(failures.isEmpty())
        assertEquals(initial, hub.data("chat", initial))
    }

    @Test fun `open goes to the shell, local only edits`() = runTest {
        run(action(ResolvedTarget.Open("ext://acme.demo/box/n1", OpenGroup.BESIDE)))
        assertEquals(listOf("ext://acme.demo/box/n1" to OpenGroup.BESIDE), calls.opened)
        run(action(ResolvedTarget.Open("file:///x", OpenGroup.ACTIVE)))
        assertEquals(listOf("Send" to "cannot open file:///x"), failures)
        run(action(ResolvedTarget.Local, before = listOf(ResolvedEffect(EffectOp.SET, "flag", JsonPrimitive(true)))))
        assertEquals(JsonPrimitive(true), hub.data("chat", initial)["flag"])
    }

    @Test fun `the same event is not started twice while it runs`() = runTest(UnconfinedTestDispatcher()) {
        calls.gate = CompletableDeferred()
        val first = async { run(action()) }
        run(action())
        calls.gate!!.complete(Unit)
        first.await()
        assertEquals(1, calls.commands.size)
        // Once finished it may run again.
        calls.gate = null
        run(action())
        assertEquals(2, calls.commands.size)
    }

    @Test fun `effects of one view do not touch another`() = runTest {
        run(action(target = ResolvedTarget.Local, before = listOf(ResolvedEffect(EffectOp.SET, "x", JsonPrimitive(1)))))
        assertEquals(obj("{}"), hub.data("other", obj("{}")))
        assertEquals(setOf("chat"), hub.values.value.keys)
    }
}
