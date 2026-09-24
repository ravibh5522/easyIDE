package dev.easyide.app.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The custom set: 24 glyphs in four groups (identity.md 6). A glyph's `name` is its token in
 * the resolver and in packs, so renaming one is a breaking change; a test pins the list.
 */
object EiIcons {
    val all: List<EiGlyph> = CORE_GLYPHS + WORK_GLYPHS + GIT_GLYPHS + SYSTEM_GLYPHS

    private val byName: Map<String, EiGlyph> = all.associateBy { it.name }

    fun glyph(name: String): EiGlyph? = byName[name]

    fun vector(name: String): ImageVector? = byName[name]?.vector
}
