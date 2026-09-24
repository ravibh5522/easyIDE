package dev.easyide.app.ui.components

import dev.easyide.app.ui.foundation.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowPresentationTest {

    @Test fun `a phone-width window gets a full-screen page`() {
        assertEquals(FlowPresentation.FullScreen, flowPresentation(WidthClass.COMPACT))
    }

    @Test fun `medium and expanded windows get a sheet`() {
        assertEquals(FlowPresentation.Sheet, flowPresentation(WidthClass.MEDIUM))
        assertEquals(FlowPresentation.Sheet, flowPresentation(WidthClass.EXPANDED))
    }
}
