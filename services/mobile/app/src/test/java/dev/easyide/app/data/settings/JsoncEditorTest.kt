package dev.easyide.app.data.settings

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsoncEditorTest {

    private val file = """
        {
          // keep me
          "editor.fontSize": 14, // and me
          "files.x": true
        }
    """.trimIndent()

    private fun set(text: String, key: String, value: String?, lang: String? = null) =
        JsoncEditor.set(text, SettingEdit(key, lang, value?.let(::json)))

    @Test
    fun replacesAValueInPlace() {
        val out = set(file, "editor.fontSize", "16")!!
        assertTrue(out.contains("// keep me"))
        assertTrue(out.contains("\"editor.fontSize\": 16, // and me"))
    }

    @Test
    fun insertsAfterTheLastMember() {
        val out = set(file, "terminal.fontSize", "12")!!
        assertTrue(out.contains("// keep me"))
        assertEquals(JsonPrimitive(12), obj(out)["terminal.fontSize"].let { JsonPrimitive(it.toString().toInt()) })
        assertEquals(3, obj(out).size)
    }

    @Test
    fun removesAMemberAndItsComma() {
        val last = set(file, "files.x", null)!!
        assertEquals(setOf("editor.fontSize"), obj(last).keys)
        assertTrue(last.contains("// keep me"))
        val first = set(file, "editor.fontSize", null)!!
        assertEquals(setOf("files.x"), obj(first).keys)
    }

    @Test
    fun writesIntoLanguageBlocks() {
        val created = set(file, "editor.fontSize", "18", lang = "python")!!
        assertEquals(18, LayerDoc.fromJson(obj(created)).lang["python"]?.get("editor.fontSize").toString().toInt())
        val updated = set(created, "editor.fontSize", "20", lang = "python")!!
        assertEquals("20", LayerDoc.fromJson(obj(updated)).lang["python"]?.get("editor.fontSize").toString())
        assertTrue(updated.contains("// keep me"))
    }

    @Test
    fun startsFromEmptyText() {
        val out = JsoncEditor.apply("", listOf(SettingEdit("a", null, json("{\"b\": [1, 2]}"))))!!
        assertEquals(obj("{\"a\": {\"b\": [1, 2]}}"), obj(out))
    }

    @Test
    fun refusesBrokenText() {
        assertNull(set("{\"a\": ", "a", "1"))
        assertNull(set("[1]", "a", "1"))
    }
}
