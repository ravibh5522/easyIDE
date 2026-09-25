package dev.easyide.app.ui.shell.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToastQueueTest {

    @Test fun `the first message shows at once`() {
        assertEquals("saved", ToastQueue().show("saved").current)
    }

    @Test fun `later messages wait and follow one at a time, in order`() {
        val queue = ToastQueue().show("a").show("b").show("c")
        assertEquals("a", queue.current)
        assertEquals(listOf("b", "c"), queue.waiting)
        val next = queue.dismissed()
        assertEquals("b", next.current)
        assertEquals("c", next.dismissed().current)
        assertNull(next.dismissed().dismissed().current)
    }

    @Test fun `a repeat of what is showing or waiting adds nothing`() {
        val queue = ToastQueue().show("a").show("b")
        assertEquals(queue, queue.show("a").show("b"))
    }

    @Test fun `a blank message is ignored`() {
        assertEquals(ToastQueue(), ToastQueue().show("").show("  "))
    }

    @Test fun `dismissing an empty queue stays empty`() {
        assertEquals(ToastQueue(), ToastQueue().dismissed())
    }

    @Test fun `the waiting list is capped and keeps the newest`() {
        val many = (1..ShellTokens.TOAST_QUEUE_MAX + 3).fold(ToastQueue()) { q, i -> q.show("m$i") }
        assertEquals("m1", many.current)
        assertEquals(ShellTokens.TOAST_QUEUE_MAX, many.waiting.size)
        assertEquals("m${ShellTokens.TOAST_QUEUE_MAX + 3}", many.waiting.last())
    }

    @Test fun `a message can show again once it has been dismissed`() {
        val queue = ToastQueue().show("a").dismissed().show("a")
        assertEquals("a", queue.current)
    }
}
