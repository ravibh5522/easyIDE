package dev.easyide.app.extensions.adapters

import dev.easyide.app.extensions.ExtensionUiPolicy
import dev.easyide.app.ui.theme.GraphiteDarkPalette
import dev.easyide.app.ui.theme.HighContrastDarkPalette
import dev.easyide.app.ui.theme.HighContrastLightPalette
import dev.easyide.app.ui.theme.PaperLightPalette
import dev.easyide.app.ui.theme.ThemeTokens
import dev.easyide.app.ui.theme.TokenColorRule
import dev.easyide.app.ui.theme.VsCodeColorTheme
import dev.easyide.app.ui.theme.VsCodeThemeMapper
import dev.easyide.app.ui.theme.toTokens
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.PackageFile
import dev.easyide.extensions.contrib.ThemeContribution
import dev.easyide.extensions.contrib.UiTheme
import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.manifest.PackagePaths
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Reads a contributed VS Code colour theme file into [VsCodeColorTheme]
 * (customization.md sec 8). Pure apart from [read], so the JVM tests drive it
 * with an in-memory file map.
 *
 * `include` names another theme file relative to the including one (VS Code's
 * rule); the included theme is read first and the including file wins: `colors`
 * and `semanticTokenColors` override key-wise, `tokenColors` append (so later,
 * including rules win TextMate ties). Includes may not leave the package, and
 * cycles or chains deeper than [ExtensionUiPolicy.THEME_MAX_INCLUDE_DEPTH] stop
 * with a warning. A `tokenColors` path (a `.tmTheme` plist) is not read.
 */
object ColorThemeFile {

    /**
     * [read] maps a host path to the file's text, or null when it is unreadable or
     * too large. Null result: the theme file itself is unusable (reason sent to [warn]).
     * Problems inside it (a broken include, a malformed rule) warn and are skipped.
     */
    fun load(file: PackageFile, read: (String) -> String?, warn: (String) -> Unit): VsCodeColorTheme? {
        val root = packageRoot(file) ?: return null.also { warn("theme file ${file.path}: host path does not match the package path") }
        return loadAt(root, file.path, read, warn, depth = 0, seen = emptySet())
    }

    private fun loadAt(
        root: String,
        path: String,
        read: (String) -> String?,
        warn: (String) -> Unit,
        depth: Int,
        seen: Set<String>,
    ): VsCodeColorTheme? {
        val text = read(root + path) ?: return null.also { warn("theme file $path is unreadable or too large") }
        val obj = when (val r = JsonText.parseLenient(text)) {
            is JsonParse.Ok -> r.value as? JsonObject ?: return null.also { warn("theme file $path must be a JSON object") }
            is JsonParse.Error -> return null.also { warn("theme file $path, line ${r.line}, column ${r.column}: ${r.message}") }
        }
        val included = obj["include"]?.stringOrNull?.let { ref ->
            val target = resolveRelative(path, ref)
            when {
                target == null -> null.also { warn("theme file $path: include '$ref' is outside the package") }
                target in seen || target == path -> null.also { warn("theme file $path: include '$ref' is a cycle") }
                depth >= ExtensionUiPolicy.THEME_MAX_INCLUDE_DEPTH -> null.also { warn("theme file $path: includes nest too deep") }
                else -> loadAt(root, target, read, warn, depth + 1, seen + path)
            }
        }
        val own = parse(obj, path, warn)
        return if (included == null) own else VsCodeColorTheme(
            colors = included.colors + own.colors,
            tokenColors = included.tokenColors + own.tokenColors,
            semanticTokenColors = included.semanticTokenColors + own.semanticTokenColors,
        )
    }

    /** The colour sections of one theme object; unknown keys are ignored. */
    fun parse(obj: JsonObject, path: String, warn: (String) -> Unit): VsCodeColorTheme {
        val colors = (obj["colors"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> v.stringOrNull?.let { k to it } }.toMap()
        val tokenColors = when (val tc = obj["tokenColors"]) {
            null -> emptyList()
            is JsonArray -> tc.mapNotNull(::rule)
            else -> emptyList<TokenColorRule>().also { warn("theme file $path: tokenColors files (.tmTheme) are not supported; syntax colours use the base palette") }
        }
        val semantic = (obj["semanticTokenColors"] as? JsonObject).orEmpty().mapNotNull { (selector, v) ->
            val fg = v.stringOrNull ?: (v as? JsonObject)?.get("foreground")?.stringOrNull
            fg?.let { selector to it }
        }.toMap()
        return VsCodeColorTheme(colors, tokenColors, semantic)
    }

    /** `{scope?, settings: {foreground?}}`; `scope` is a string or an array of strings. */
    private fun rule(e: JsonElement): TokenColorRule? {
        val o = e as? JsonObject ?: return null
        val settings = o["settings"] as? JsonObject ?: return null
        val scopes = when (val s = o["scope"]) {
            null -> emptyList()
            is JsonArray -> s.mapNotNull { it.stringOrNull }
            else -> listOfNotNull(s.stringOrNull)
        }
        return TokenColorRule(scopes, settings["foreground"]?.stringOrNull)
    }

    /** The directory the package's files live under, from a file's host path. */
    private fun packageRoot(file: PackageFile): String? =
        file.hostPath.takeIf { it.endsWith("/" + file.path) }?.removeSuffix(file.path)

    /**
     * [ref] against the directory of [base] (both package-relative). `..` may climb
     * within the package but not out of it; null when it would, or when the result
     * is not a valid package path.
     */
    internal fun resolveRelative(base: String, ref: String): String? {
        if (ref.startsWith("/")) return null
        val parts = base.split('/').dropLast(1).toMutableList()
        for (seg in ref.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
        }
        return PackagePaths.normalize(parts.joinToString("/"))
    }
}

/**
 * Selecting and resolving a contributed theme (customization.md sec 8.1): the
 * setting names a theme by label, or as `<extensionId>/<themeId>`; `uiTheme`
 * picks the built-in palette the theme's colours are laid over.
 */
object ContributedThemes {

    /** The selected theme among the live (enabled, conflict-resolved) ones; null means built-in. */
    fun find(selection: String, themes: List<Owned<ThemeContribution>>): Owned<ThemeContribution>? {
        if (selection.isBlank()) return null
        return themes.firstOrNull { it.value.label == selection } ?: themes.firstOrNull { refOf(it) == selection }
    }

    /** `<extensionId>/<themeId>`, or null for a theme without an id. */
    fun refOf(theme: Owned<ThemeContribution>): String? {
        val id = theme.value.id ?: return null
        return "${(theme.owner as? Owner.Ext)?.id?.value ?: "builtin"}/$id"
    }

    fun baseTokensFor(uiTheme: UiTheme): ThemeTokens = when (uiTheme) {
        UiTheme.DARK -> GraphiteDarkPalette
        UiTheme.LIGHT -> PaperLightPalette
        UiTheme.HIGH_CONTRAST_DARK -> HighContrastDarkPalette
        UiTheme.HIGH_CONTRAST_LIGHT -> HighContrastLightPalette
    }.toTokens()

    /**
     * The theme's tokens over its base, or null when the file is unusable (the
     * caller then keeps the built-in palette). [info] gets the unmapped keys as
     * one line; [warn] gets every skipped entry.
     */
    fun resolve(
        theme: ThemeContribution,
        read: (String) -> String?,
        warn: (String) -> Unit,
        info: (String) -> Unit = {},
    ): ThemeTokens? {
        val parsed = ColorThemeFile.load(theme.file, read, warn) ?: return null
        val mapped = VsCodeThemeMapper.map(parsed, baseTokensFor(theme.uiTheme))
        mapped.invalidEntries.forEach { warn("theme '${theme.label}': $it is not a colour (#RGB, #RGBA, #RRGGBB or #RRGGBBAA)") }
        if (mapped.unmappedKeys.isNotEmpty()) {
            info("theme '${theme.label}': ${mapped.unmappedKeys.size} colour keys have no easyIDE token and are ignored")
        }
        return mapped.tokens
    }
}
