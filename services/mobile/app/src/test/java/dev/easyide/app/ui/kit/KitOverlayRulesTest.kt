package dev.easyide.app.ui.kit

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.props.Handedness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KitOverlayRulesTest {

    // --- dialog ---

    @Test fun `a compact window gets a bottom sheet and wider ones a centred dialog`() {
        assertEquals(DialogPlacement.BottomSheet, dialogPlacement(WidthClass.COMPACT))
        assertEquals(DialogPlacement.Centered, dialogPlacement(WidthClass.MEDIUM))
        assertEquals(DialogPlacement.Centered, dialogPlacement(WidthClass.EXPANDED))
    }

    @Test fun `dialog actions run dismiss then confirm, mirrored for a left hand`() {
        assertEquals(listOf("no", "yes"), dialogActionOrder("no", "yes", Handedness.RIGHT))
        assertEquals(listOf("yes", "no"), dialogActionOrder("no", "yes", Handedness.LEFT))
    }

    @Test fun `a dialog with one or no action keeps only what exists`() {
        assertEquals(listOf("yes"), dialogActionOrder(null, "yes", Handedness.RIGHT))
        assertEquals(listOf("no"), dialogActionOrder("no", null, Handedness.LEFT))
        assertEquals(emptyList<String>(), dialogActionOrder<String>(null, null, Handedness.RIGHT))
    }

    // --- menu model ---

    private fun action(label: String, danger: Boolean = false) = KitMenuItem.Action(label, {}, danger = danger)

    @Test fun `dividers are dropped when first, last or doubled`() {
        val a = action("a")
        val b = action("b")
        val d = KitMenuItem.Divider
        assertEquals(listOf(a, d, b), tidyMenu(listOf(d, a, d, d, b, d)))
    }

    @Test fun `tidying keeps order and every action`() {
        val items = List(5) { action("item $it") }
        assertEquals(items, tidyMenu(items))
    }

    @Test fun `only dividers, and no items, tidy to nothing`() {
        assertTrue(tidyMenu(listOf(KitMenuItem.Divider, KitMenuItem.Divider)).isEmpty())
        assertTrue(tidyMenu(emptyList()).isEmpty())
    }

    @Test fun `an action defaults to enabled, plain, unchecked`() {
        val a = action("a")
        assertTrue(a.enabled)
        assertEquals(false, a.danger)
        assertEquals(null, a.checked)
        assertEquals(true, action("x", danger = true).danger)
    }

    // --- menu placement ---

    private val window = IntSize(1000, 800)
    private val popup = IntSize(200, 300)

    @Test fun `a menu opens below its anchor, start aligned`() {
        assertEquals(IntOffset(100, 150), menuPosition(IntRect(100, 100, 180, 150), window, popup))
    }

    @Test fun `a menu near the bottom flips above the anchor`() {
        assertEquals(IntOffset(100, 400), menuPosition(IntRect(100, 700, 180, 750), window, popup))
    }

    @Test fun `a menu never leaves the window sideways`() {
        assertEquals(IntOffset(800, 150), menuPosition(IntRect(950, 100, 990, 150), window, popup))
        assertEquals(IntOffset(0, 150), menuPosition(IntRect(-20, 100, 20, 150), window, popup))
    }

    @Test fun `a menu taller than the space above and below is pinned to the top edge`() {
        assertEquals(0, menuPosition(IntRect(0, 100, 50, 150), window, IntSize(200, 790)).y)
    }

    // --- choice ---

    @Test fun `two to four short values are segments`() {
        assertEquals(ChoiceLayout.Segmented, choiceLayout(listOf("sharp", "soft", "round")))
        assertEquals(ChoiceLayout.Segmented, choiceLayout(listOf("on", "off")))
    }

    @Test fun `one value, many values or long values are a list`() {
        assertEquals(ChoiceLayout.List, choiceLayout(listOf("only")))
        assertEquals(ChoiceLayout.List, choiceLayout(List(5) { "x" }))
        assertEquals(ChoiceLayout.List, choiceLayout(listOf("Geist with Geist Mono", "System fonts")))
    }
}
