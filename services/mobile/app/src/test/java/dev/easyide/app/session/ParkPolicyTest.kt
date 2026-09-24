package dev.easyide.app.session

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Test

@Suppress("DEPRECATION")
class ParkPolicyTest {

    private val held = listOf(
        HeldInfo("active", parked = false, lastActiveMs = 100),
        HeldInfo("old", parked = true, lastActiveMs = 10),
        HeldInfo("newer", parked = true, lastActiveMs = 50),
        HeldInfo("newest", parked = true, lastActiveMs = 90),
    )

    @Test fun `over the limit the least recently used parked ones go first`() {
        assertEquals(listOf("old"), ParkPolicy.overLimit(held, 2))
        assertEquals(listOf("old", "newer"), ParkPolicy.overLimit(held, 1))
        assertEquals(listOf("old", "newer", "newest"), ParkPolicy.overLimit(held, 0))
    }

    @Test fun `within the limit nothing goes, and a negative limit behaves as zero`() {
        assertEquals(emptyList<String>(), ParkPolicy.overLimit(held, 3))
        assertEquals(emptyList<String>(), ParkPolicy.overLimit(held, 8))
        assertEquals(3, ParkPolicy.overLimit(held, -1).size)
    }

    @Test fun `the active workspace is never a candidate`() {
        assertEquals(emptyList<String>(), ParkPolicy.overLimit(listOf(HeldInfo("a", false, 1)), 0))
        assertEquals(emptyList<String>(), ParkPolicy.underPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, listOf(HeldInfo("a", false, 1))))
    }

    @Test fun `mild trims and the ui hidden signal end nothing`() {
        assertEquals(emptyList<String>(), ParkPolicy.underPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, held))
        assertEquals(emptyList<String>(), ParkPolicy.underPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, held))
        assertEquals(emptyList<String>(), ParkPolicy.underPressure(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, held))
    }

    @Test fun `critical and background pressure end the least recently used one`() {
        assertEquals(listOf("old"), ParkPolicy.underPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, held))
        assertEquals(listOf("old"), ParkPolicy.underPressure(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, held))
        assertEquals(listOf("old"), ParkPolicy.underPressure(ComponentCallbacks2.TRIM_MEMORY_MODERATE, held))
    }

    @Test fun `complete pressure ends every parked workspace`() {
        assertEquals(listOf("old", "newer", "newest"), ParkPolicy.underPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, held))
    }
}
