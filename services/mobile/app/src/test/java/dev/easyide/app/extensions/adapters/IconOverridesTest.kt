package dev.easyide.app.extensions.adapters

import dev.easyide.app.extensions.materialicons.MaterialIconsPack
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The user's icon associations: precedence in front of the theme's mappings, and the ids that do not exist. */
class IconOverridesTest {

    private fun obj(vararg p: Pair<String, String>): JsonElement = JsonObject(p.associate { it.first to JsonPrimitive(it.second) })

    private fun overrides(files: JsonElement = obj(), folders: JsonElement = obj()) = IconOverrides.of(files, folders)

    private val small = IconTheme(
        id = "t",
        icons = listOf("file", "folder", "folder-open", "py", "docker", "dir", "dir-open", "solo", "ts").associateWith { "/$it.svg" },
        dark = IconAssociations(
            file = "file", folder = "folder", folderExpanded = "folder-open",
            fileExtensions = mapOf("ts" to "ts"), folderNames = mapOf("src" to "dir"),
        ),
        light = null,
    )

    private fun file(o: IconOverrides, name: String) = small.copy(overrides = o).fileIcon(name, null, false)?.removePrefix("/")?.removeSuffix(".svg")

    private fun folder(o: IconOverrides, name: String, open: Boolean) = small.copy(overrides = o).folderIcon(name, open, false)?.removePrefix("/")?.removeSuffix(".svg")

    @Test fun `no overrides leave the theme's own mapping`() {
        assertEquals("ts", file(IconOverrides.NONE, "a.ts"))
        assertEquals("dir", folder(IconOverrides.NONE, "src", false))
    }

    @Test fun `an exact name beats a glob beats a suffix beats the theme`() {
        val o = overrides(obj("Jenkinsfile" to "docker", "*file" to "solo", "ts" to "py", "*.ts" to "docker"))
        assertEquals("docker", file(o, "jenkinsfile"))
        assertEquals("solo", file(o, "Makefile"))
        // "*.ts" is a glob and comes before the suffix `ts`.
        assertEquals("docker", file(o, "a.ts"))
        assertEquals("py", file(overrides(obj("ts" to "py")), "a.b.ts"))
        assertEquals("ts", file(overrides(obj("foo" to "py")), "a.ts"))
    }

    @Test fun `extensions can be written with or without the dot and dotfiles match by name`() {
        val o = overrides(obj(".foo" to "py", ".env" to "docker"))
        assertEquals("py", file(o, "a.foo"))
        assertEquals("py", file(o, ".foo"))
        assertEquals("docker", file(o, ".env"))
        assertEquals("file", file(o, "a.other"))
    }

    @Test fun `a glob matches the whole name, case-insensitively, with question marks`() {
        val o = overrides(obj("docker-compose*.yml" to "docker", "a?.txt" to "py"))
        assertEquals("docker", file(o, "Docker-Compose.override.YML"))
        assertEquals("py", file(o, "a1.txt"))
        assertEquals("file", file(o, "a12.txt"))
        assertEquals("file", file(o, "x-docker-compose.yml.bak"))
    }

    @Test fun `an id the theme does not define is skipped so the theme's mapping shows`() {
        assertEquals("ts", file(overrides(obj("*.ts" to "no-such-icon")), "a.ts"))
        assertEquals("dir", folder(overrides(folders = obj("src" to "no-such-icon")), "src", false))
    }

    @Test fun `folders use the open variant when the theme has one`() {
        val o = overrides(folders = obj("api" to "dir", "lonely" to "solo"))
        assertEquals("dir", folder(o, "API", false))
        assertEquals("dir-open", folder(o, "api", true))
        assertEquals("solo", folder(o, "lonely", true))
    }

    @Test fun `unknown ids are listed with the setting they came from`() {
        val o = overrides(obj("*.foo" to "python", "x" to "nope"), obj("api" to "gone"))
        val known = setOf("python")
        assertEquals(
            listOf(
                Triple(IconOverrides.FILE_KEY, "x", "nope"),
                Triple(IconOverrides.FOLDER_KEY, "api", "gone"),
            ),
            o.unknownIds(known),
        )
    }

    @Test fun `non-string members are ignored`() {
        val o = IconOverrides.of(JsonObject(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive("py"))), JsonPrimitive("x"))
        assertEquals("py", file(o, "b"))
        assertEquals("file", file(o, "a"))
    }

    @Test fun `on the Material Icon Theme the documented examples change the icon`() {
        val t = MaterialIconsPack.theme
        val o = overrides(obj("*.foo" to "python", "Jenkinsfile" to "docker"), obj("api" to "folder-src"))
        val with = t.copy(overrides = o)
        fun name(p: String?) = p?.substringAfterLast('/')?.removeSuffix(".svg")
        assertEquals("python", name(with.fileIcon("a.foo", null, false)))
        assertNotEquals("docker", name(t.fileIcon("Jenkinsfile", null, false)))
        assertEquals("docker", name(with.fileIcon("Jenkinsfile", null, false)))
        assertEquals("folder-src", name(with.folderIcon("api", false, false)))
        assertEquals("folder-src-open", name(with.folderIcon("api", true, false)))
        assertEquals(emptyList<Any>(), o.unknownIds(t.icons.keys).filter { it.second != "api" })
    }
}
