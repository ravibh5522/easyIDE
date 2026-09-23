package dev.easyide.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingTest {

    private val size = Setting.IntRange("t.size", SettingCategory.EDITOR, 0, 0, default = 10, SettingScope.L, min = 5, max = 20)
    private val list = Setting.StrList("t.list", SettingCategory.EDITOR, 0, 0, default = listOf("a"), SettingScope.P)
    private val str = Setting.Str("t.id", SettingCategory.EDITOR, 0, 0, default = "x", SettingScope.G, pattern = Regex("[a-z]+"))
    private val flag = Setting.Bool("t.flag", SettingCategory.EDITOR, 0, 0, default = false, SettingScope.G)

    @Test
    fun intRangeRejectsOutOfRangeAndWrongType() {
        assertEquals(5, size.decode(5))
        assertEquals(20, size.decode(20))
        assertNull(size.decode(4))
        assertNull(size.decode(21))
        assertNull(size.decode("12"))
        assertNull(size.decode(null))
    }

    @Test
    fun strHonoursPattern() {
        assertEquals("abc", str.decode("abc"))
        assertNull(str.decode("ABC"))
        assertNull(flag.decode("true"))
    }

    @Test
    fun strListRoundTripsIncludingEmpty() {
        assertEquals(listOf("a", "b c"), list.decode(list.encode(listOf("a", "b c"))))
        assertEquals(emptyList<String>(), list.decode(list.encode(emptyList())))
    }

    @Test
    fun resolveTakesFirstValidLayerHighToLow() {
        assertEquals(15, size.resolve(listOf(15, 8)))
        // An invalid higher layer falls through to the next one, then to the default.
        assertEquals(8, size.resolve(listOf(99, 8)))
        assertEquals(10, size.resolve(listOf(99, null)))
        assertEquals(10, size.resolve(emptyList()))
    }
}
