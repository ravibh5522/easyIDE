package dev.easyide.app.ui.shell

import dev.easyide.app.ui.screens.workspace.layout.Pane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellResizeTest {
    private val shell = ShellState(window = EXPANDED)

    @Test fun `a dragged panel width is stored in the current layout`() {
        val resized = shell.act(ShellAction.ResizePane(Pane.EXPLORER, 300f))
        assertEquals(300f, resized.current.layout.sizes.explorer)
    }

    @Test fun `a null size forgets the drag`() {
        val reset = shell.act(ShellAction.ResizePane(Pane.EXPLORER, 300f), ShellAction.ResizePane(Pane.EXPLORER, null))
        assertNull(reset.current.layout.sizes.explorer)
    }

    @Test fun `resizing one pane leaves the others`() {
        val resized = shell.act(ShellAction.ResizePane(Pane.EXPLORER, 300f), ShellAction.ResizePane(Pane.RIGHT, 260f))
        assertEquals(300f, resized.current.layout.sizes.explorer)
        assertEquals(260f, resized.current.layout.sizes.right)
        assertNull(resized.current.layout.sizes.bottom)
    }

    @Test fun `a saved size survives the snapshot`() {
        val resized = shell.act(ShellAction.ResizePane(Pane.EXPLORER, 300f))
        val json = ShellSnapshot.encodeApp(resized.app, resized.arrangement)
        assertEquals(300f, ShellSnapshot.decodeApp(json, resized.arrangement)!!.layout.sizes.explorer)
    }
}
