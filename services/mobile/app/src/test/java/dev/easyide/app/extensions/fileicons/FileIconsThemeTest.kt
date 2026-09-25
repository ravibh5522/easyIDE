package dev.easyide.app.extensions.fileicons

import dev.easyide.app.extensions.fileicons.FileIconsPack.table
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The generated pack is valid data: no dangling ids, the size budgets hold, coverage is as broad as promised. */
class FileIconsThemeTest {

    private val raw = FileIconsPack.raw
    private val theme = FileIconsPack.theme
    private val defined = FileIconsPack.definitions.keys

    private fun referencedIds(): List<String> {
        val single = listOf("file", "folder", "folderExpanded", "rootFolder", "rootFolderExpanded")
        val tables = listOf("fileExtensions", "fileNames", "languageIds", "folderNames", "folderNamesExpanded")
        fun idsOf(section: JsonObject) =
            single.mapNotNull { section[it]?.jsonPrimitive?.content } + tables.flatMap { table(it, section).values }
        return idsOf(raw) + idsOf(FileIconsPack.light)
    }

    @Test fun `every mapping points at a defined icon and every definition has its file`() {
        val dangling = referencedIds().filter { it !in defined }.distinct()
        assertEquals("dangling ids", emptyList<String>(), dangling)
        val missing = FileIconsPack.definitions.filterValues { !it.isFile }.keys
        assertEquals("missing svg files", emptySet<String>(), missing)
        assertEquals("theme parse keeps every definition", defined, theme.icons.keys)
    }

    @Test fun `no orphan svg files`() {
        val onDisk = File(FileIconsPack.root, "icons/svg").listFiles()!!.map { it.canonicalFile }.toSet()
        assertEquals(FileIconsPack.definitions.values.toSet(), onDisk)
    }

    @Test fun `size budgets`() {
        assertTrue("theme json under 250 KB", FileIconsPack.themeFile.length() < 250 * 1024)
        val svgs = File(FileIconsPack.root, "icons/svg").listFiles()!!
        assertTrue("each svg under 1.5 KB", svgs.all { it.length() < 1500 })
        assertTrue("svgs under 1 MB in total", svgs.sumOf { it.length() } < 1024 * 1024)
    }

    @Test fun `coverage`() {
        assertTrue(table("fileExtensions").size >= 500)
        assertTrue(table("fileNames").size >= 150)
        assertTrue(table("folderNames").size >= 60)
        assertEquals("every folder name has an open variant", table("folderNames").keys, table("folderNamesExpanded").keys)
        val required = listOf(
            "Dockerfile", "Makefile", "package.json", "tsconfig.json", "pom.xml", "build.gradle", "build.gradle.kts",
            "Cargo.toml", "go.mod", "requirements.txt", "pyproject.toml", ".gitignore", ".env", ".env.local", "LICENSE",
            "README.md", "CMakeLists.txt", "docker-compose.yml", ".eslintrc.json", ".prettierrc", "webpack.config.js",
            "vite.config.ts", "rollup.config.js", "AndroidManifest.xml", "strings.xml", "Info.plist", "project.pbxproj",
        )
        val generic = theme.icons.getValue("file")
        for (name in required) {
            val hit = theme.fileIcon(name, null, light = false)
            assertNotNull(name, hit)
            assertTrue("$name has a specific icon", hit != generic)
        }
        val exts = listOf(
            "kt", "java", "swift", "m", "c", "cpp", "h", "cs", "fs", "vb", "go", "rs", "zig", "py", "rb", "php", "pl", "lua",
            "r", "jl", "dart", "ex", "erl", "hs", "ml", "scala", "clj", "groovy", "js", "ts", "jsx", "tsx", "vue", "svelte",
            "astro", "sh", "ps1", "bat", "sql", "graphql", "sol", "wasm", "asm", "v", "vhd", "f90", "cob", "lisp", "scm",
            "rkt", "nim", "cr", "pro", "html", "css", "scss", "sass", "less", "xml", "json", "jsonc", "json5", "yaml", "toml",
            "ini", "csv", "tsv", "md", "mdx", "rst", "adoc", "tex", "bib", "proto", "thrift", "avsc", "parquet", "db", "xlsx",
            "docx", "pptx", "odt", "pdf", "epub", "png", "jpg", "gif", "svg", "webp", "ico", "heic", "avif", "psd", "ai",
            "sketch", "mp3", "wav", "flac", "mp4", "mkv", "ttf", "woff2", "zip", "tar", "gz", "7z", "rar", "xz", "zst", "apk",
            "aab", "jar", "war", "deb", "rpm", "dmg", "iso", "exe", "dll", "so", "dylib", "o", "a", "class", "dex", "pem",
            "crt", "key", "p12", "jks", "keystore", "log", "lock", "diff", "patch", "obj", "stl", "gltf", "fbx", "blend",
            "dxf", "ipynb", "plist", "storyboard", "pbxproj", "tf", "nix", "gradle",
        )
        val unmapped = exts.filter { theme.fileIcon("x.$it", null, light = false) == generic }
        assertEquals("extensions that fall through to the generic file", emptyList<String>(), unmapped)
    }

    @Test fun `every language id the app knows has an icon`() {
        val index = Json.parseToJsonElement(File("src/main/assets/grammars/index.json").readText()).jsonObject
        val grammarNames = index["grammars"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val packLanguages = File("src/main/assets/extensions").listFiles()!!.flatMap { dir ->
            val manifest = File(dir, "package.json").takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()).jsonObject }
            (manifest?.get("contributes") as? JsonObject)?.get("languages")?.jsonArray?.map { it.jsonObject["id"]!!.jsonPrimitive.content }.orEmpty()
        }
        val languages = table("languageIds").keys
        val missing = (grammarNames + packLanguages).filter { it !in languages }.distinct().sorted()
        assertEquals("language ids without an icon", emptyList<String>(), missing)
    }

    @Test fun `the pack declares one icon theme`() {
        val manifest = Json.parseToJsonElement(File(FileIconsPack.root, "package.json").readText()).jsonObject
        val themes = manifest["contributes"]!!.jsonObject["iconThemes"]!!.jsonArray
        assertEquals(1, themes.size)
        assertEquals("easyide-file-icons", themes[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("EasyIDE File Icons", themes[0].jsonObject["label"]!!.jsonPrimitive.content)
    }
}
