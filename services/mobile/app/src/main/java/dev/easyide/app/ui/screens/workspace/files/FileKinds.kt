package dev.easyide.app.ui.screens.workspace.files

/**
 * What kind of file a name denotes, for its icon. Pure, so the mapping is testable and the
 * tree, tabs, quick open and the welcome view all agree; [FileIcon] turns a kind into a glyph
 * and a theme token tint.
 */
enum class FileKind {
    JAVASCRIPT, TYPESCRIPT, HTML, CSS, PYTHON, JVM, PHP, SYSTEMS, SCRIPT_OTHER, SHELL,
    JSON, CONFIG_DATA, MARKUP, MARKDOWN, TEXT, IMAGE, VIDEO, PDF, ARCHIVE, DATABASE,
    LOCK, GIT, ENVIRONMENT, BUILD, CONTAINER, LICENSE, DOTFILE, DEFAULT,
}

object FileKinds {

    /** Whole-name matches, lowercase; these win over the extension (`package-lock.json` is a lock, not JSON). */
    private val BY_NAME: Map<String, FileKind> = mapOf(
        "dockerfile" to FileKind.CONTAINER,
        "docker-compose.yml" to FileKind.CONTAINER,
        "docker-compose.yaml" to FileKind.CONTAINER,
        "compose.yml" to FileKind.CONTAINER,
        "compose.yaml" to FileKind.CONTAINER,
        ".dockerignore" to FileKind.CONTAINER,
        "makefile" to FileKind.BUILD,
        "cmakelists.txt" to FileKind.BUILD,
        "build.gradle" to FileKind.BUILD,
        "build.gradle.kts" to FileKind.BUILD,
        "settings.gradle" to FileKind.BUILD,
        "settings.gradle.kts" to FileKind.BUILD,
        "package.json" to FileKind.BUILD,
        "cargo.toml" to FileKind.BUILD,
        "pyproject.toml" to FileKind.BUILD,
        "go.mod" to FileKind.BUILD,
        "license" to FileKind.LICENSE,
        "licence" to FileKind.LICENSE,
        "copying" to FileKind.LICENSE,
        ".gitignore" to FileKind.GIT,
        ".gitattributes" to FileKind.GIT,
        ".gitmodules" to FileKind.GIT,
        ".gitkeep" to FileKind.GIT,
        ".editorconfig" to FileKind.DOTFILE,
        "package-lock.json" to FileKind.LOCK,
        "yarn.lock" to FileKind.LOCK,
        "pnpm-lock.yaml" to FileKind.LOCK,
        "gemfile.lock" to FileKind.LOCK,
        "poetry.lock" to FileKind.LOCK,
    )

    private val BY_EXTENSION: Map<String, FileKind> = buildMap {
        fun kind(kind: FileKind, vararg extensions: String) = extensions.forEach { put(it, kind) }
        kind(FileKind.JAVASCRIPT, "js", "jsx", "mjs", "cjs")
        kind(FileKind.TYPESCRIPT, "ts", "tsx", "mts", "cts")
        kind(FileKind.HTML, "html", "htm", "xhtml", "vue", "svelte")
        kind(FileKind.CSS, "css", "scss", "sass", "less")
        kind(FileKind.PYTHON, "py", "pyi", "pyw", "ipynb")
        kind(FileKind.JVM, "java", "kt", "kts", "scala", "groovy", "gradle", "clj")
        kind(FileKind.PHP, "php")
        kind(FileKind.SYSTEMS, "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "cs", "go", "rs", "zig", "swift", "m", "mm", "dart")
        kind(FileKind.SCRIPT_OTHER, "rb", "lua", "pl", "pm", "r", "jl", "ex", "exs", "erl", "hs", "ml")
        kind(FileKind.SHELL, "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd")
        kind(FileKind.JSON, "json", "jsonc", "json5", "jsonl", "geojson")
        kind(FileKind.CONFIG_DATA, "yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "csv", "tsv")
        kind(FileKind.MARKUP, "xml", "xsl", "xsd", "plist", "svg")
        kind(FileKind.MARKDOWN, "md", "markdown", "mdx", "rst", "adoc")
        kind(FileKind.TEXT, "txt", "log", "text")
        kind(FileKind.IMAGE, "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "avif", "heic")
        kind(FileKind.VIDEO, "mp4", "mov", "mkv", "webm", "avi", "mp3", "wav", "ogg", "flac", "m4a")
        kind(FileKind.PDF, "pdf")
        kind(FileKind.ARCHIVE, "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "aar", "apk", "war")
        kind(FileKind.DATABASE, "sql", "db", "sqlite", "sqlite3")
        kind(FileKind.LOCK, "lock", "lockfile")
        kind(FileKind.ENVIRONMENT, "env")
    }

    fun of(fileName: String): FileKind {
        val name = fileName.lowercase()
        BY_NAME[name]?.let { return it }
        if (name == ".env" || name.startsWith(".env.")) return FileKind.ENVIRONMENT
        val dot = name.lastIndexOf('.')
        if (dot > 0) BY_EXTENSION[name.substring(dot + 1)]?.let { return it }
        // ".bashrc", ".prettierrc": a leading dot with nothing else known is configuration.
        return if (name.startsWith('.')) FileKind.DOTFILE else FileKind.DEFAULT
    }
}
