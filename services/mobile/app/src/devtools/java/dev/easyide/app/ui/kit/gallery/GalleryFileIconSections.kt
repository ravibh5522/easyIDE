package dev.easyide.app.ui.kit.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.theme.ThemedIcon
import dev.easyide.app.ui.theme.rememberThemedFileIcon

private val SIZES = listOf(16.dp, 24.dp)
private val CELL_WIDTH = 72.dp

/** One name per family the pack draws, so the sheet shows the whole palette and glyph set. */
val FILE_ICON_SAMPLES = listOf(
    "Main.kt", "App.java", "view.swift", "main.c", "util.cpp", "Program.cs", "main.go", "lib.rs", "build.zig",
    "app.py", "gem.rb", "index.php", "init.lua", "stats.r", "main.dart", "mix.ex", "Main.hs", "core.clj",
    "index.js", "index.ts", "App.jsx", "App.tsx", "types.d.ts", "a.test.ts", "App.vue", "Page.svelte",
    "run.sh", "setup.ps1", "query.sql", "schema.graphql", "module.wasm", "boot.asm", "chip.v", "x.f90",
    "index.html", "style.css", "theme.scss", "data.xml", "data.json", "conf.yaml", "Cargo.toml", "app.ini",
    "table.csv", "README.md", "notes.txt", "paper.tex", "api.proto", "data.parquet", "app.db", "sheet.xlsx",
    "doc.docx", "deck.pptx", "book.pdf", "logo.png", "photo.jpg", "anim.gif", "icon.svg", "mock.psd",
    "song.mp3", "voice.wav", "clip.mp4", "font.ttf", "bundle.zip", "src.tar.gz", "app.apk", "lib.jar",
    "pack.deb", "disk.iso", "tool.exe", "lib.so", "main.o", "Main.class", "cert.pem", "id.key", "store.jks",
    "run.log", "yarn.lock", "fix.patch", "mesh.obj", "part.stl", "scene.blend", "drawing.dxf", "nb.ipynb",
    "Info.plist", "Main.storyboard", "Dockerfile", "Makefile", "package.json", "tsconfig.json", "pom.xml",
    "build.gradle.kts", ".gitignore", ".env", "LICENSE", "CMakeLists.txt", "unknown.zzz",
)

val FOLDER_ICON_SAMPLES = listOf("src", "lib", "test", "docs", "assets", "node_modules", ".git", ".github", "build", "config", "scripts", "components", "public", "vendor", "other")

/** The icon theme's whole look at 16dp (a dense row) and 24dp, for the owner to inspect and for the golden. */
@Composable
fun FileIconsSection() {
    KitSection(stringResource(R.string.gallery_file_icons_title), description = stringResource(R.string.gallery_file_icons_note)) {
        for (size in SIZES) {
            Sample(stringResource(R.string.gallery_file_icons_size, size.value.toInt())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Kit.space.s), verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
                    for (name in FOLDER_ICON_SAMPLES) Cell(name, size) { FolderIcon(name, expanded = false, size) }
                    for (name in FOLDER_ICON_SAMPLES.take(4)) Cell("$name/", size) { FolderIcon(name, expanded = true, size) }
                    for (name in FILE_ICON_SAMPLES) Cell(name, size) { FileIcon(name, size = size) }
                }
            }
        }
    }
}

@Composable
private fun Cell(label: String, size: Dp, icon: @Composable () -> Unit) {
    Column(Modifier.width(CELL_WIDTH), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        icon()
        BasicText(label, style = Kit.text.monoSmall.copy(color = Kit.colors.textMuted), maxLines = 1)
    }
}

@Composable
private fun FolderIcon(name: String, expanded: Boolean, size: Dp) {
    when (val icon = rememberThemedFileIcon(name, isDirectory = true, expanded = expanded, size = size)) {
        is ThemedIcon.Ready -> Image(icon.image, null, Modifier.size(size))
        else -> Spacer(Modifier.size(size))
    }
}
