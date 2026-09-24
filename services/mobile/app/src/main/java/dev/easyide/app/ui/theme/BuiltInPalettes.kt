package dev.easyide.app.ui.theme

import androidx.compose.ui.graphics.Color

// The built-in palettes of decision 0019: calm graphite / warm paper neutrals,
// one iris accent, and low-saturation syntax tuned to those neutrals rather
// than borrowed from Dark+. Every text pair here is checked against WCAG AA
// (AAA for the high-contrast pair) by BuiltInPaletteContrastTest; change a value
// and that test says whether it still reads.

/** Default dark: cool graphite ramp (editor < panel < raised < overlay) with the iris accent. */
val GraphiteDarkPalette = Palette(
    isDark = true,
    neutrals = Neutrals(
        editor = Color(0xFF0E1014),
        panel = Color(0xFF13161B),
        raised = Color(0xFF181C22),
        overlay = Color(0xFF1F242C),
        hairline = Color(0xFF262C35),
        text = Color(0xFFD5DAE1),
        textMuted = Color(0xFF9AA3B0),
        textFaint = Color(0xFF7A8494),
    ),
    accent = Accent(accent = Color(0xFFFF8A3D), onAccent = Color(0xFF0E1014)),
    signals = Signals(
        error = Color(0xFFF87171),
        warning = Color(0xFFFBBF24),
        info = Color(0xFF60A5FA),
        success = Color(0xFF4ADE80),
        gitAdded = Color(0xFF4ADE80),
        gitModified = Color(0xFFFBBF24),
        gitDeleted = Color(0xFFF87171),
        gitConflict = Color(0xFFF472B6),
    ),
    lanes = listOf(
        Color(0xFF6CB6FF), Color(0xFFF59ABF), Color(0xFFC3A6FF), Color(0xFF5FD4C4),
        Color(0xFFF4B860), Color(0xFF9BD37A), Color(0xFFF28B82), Color(0xFFB8C4D6),
    ),
    ansi = listOf(
        Color(0xFF2A303A), Color(0xFFF07A7A), Color(0xFF9BD37A), Color(0xFFE8C46A),
        Color(0xFF7AA7F0), Color(0xFFC49DDE), Color(0xFF6FC7CF), Color(0xFFC8CED8),
        Color(0xFF7A8494), Color(0xFFFF9A9A), Color(0xFFB5E599), Color(0xFFF5D98C),
        Color(0xFF9CC0FF), Color(0xFFD9B8EE), Color(0xFF93DDE3), Color(0xFFEEF1F5),
    ),
    syntax = SyntaxColors(
        plain = Color(0xFFD5DAE1),
        keyword = Color(0xFFC49DDE),
        string = Color(0xFFA8C99A),
        comment = Color(0xFF7A8494),
        number = Color(0xFFE0B08A),
        type = Color(0xFF7FC4C9),
        function = Color(0xFF8FB5F0),
        variable = Color(0xFFCDD3DC),
        parameter = Color(0xFFE3C9A0),
        property = Color(0xFFA9C3DD),
        constant = Color(0xFFDFA88F),
        operator = Color(0xFF9FB0C3),
        punctuation = Color(0xFF8C96A5),
        tag = Color(0xFFE39B9B),
        attribute = Color(0xFFE3C9A0),
        namespace = Color(0xFF7FC4C9),
        regexp = Color(0xFFD69B7E),
        escape = Color(0xFFE5C07B),
        heading = Color(0xFF8FB5F0),
        link = Color(0xFF8FB5F0),
        invalid = Color(0xFFF87171),
    ),
    emphasis = Emphasis(
        selection = 0.22f,
        currentLine = 0.05f,
        listSelection = 0.18f,
        bracketMatch = 0.22f,
        wordHighlight = 0.1f,
        searchMatch = 0.28f,
        searchMatchCurrent = 0.4f,
        inlayBackground = 0.06f,
    ),
)

/** Default light: warm paper ramp; the accent darkens to iris-600 so it clears AA on paper. */
val PaperLightPalette = Palette(
    isDark = false,
    neutrals = Neutrals(
        editor = Color(0xFFFBFAF8),
        panel = Color(0xFFF3F2EE),
        raised = Color(0xFFECEAE4),
        overlay = Color(0xFFFFFFFF),
        hairline = Color(0xFFE4E2DC),
        text = Color(0xFF1E2228),
        textMuted = Color(0xFF555B66),
        textFaint = Color(0xFF656B76),
    ),
    accent = Accent(accent = Color(0xFFB04600), onAccent = Color(0xFFFFFFFF)),
    signals = Signals(
        error = Color(0xFFC62828),
        warning = Color(0xFF8F5F00),
        info = Color(0xFF1D5FC7),
        success = Color(0xFF1E7B34),
        gitAdded = Color(0xFF1E7B34),
        gitModified = Color(0xFF8F5F00),
        gitDeleted = Color(0xFFC62828),
        gitConflict = Color(0xFFB0306E),
    ),
    lanes = listOf(
        Color(0xFF1F6FD1), Color(0xFFC0397A), Color(0xFF7A4FD6), Color(0xFF0F8577),
        Color(0xFFA86A00), Color(0xFF4B8A1F), Color(0xFFC4473A), Color(0xFF5A6679),
    ),
    ansi = listOf(
        Color(0xFF1E2228), Color(0xFFC62828), Color(0xFF2E7D32), Color(0xFF8A6100),
        Color(0xFF1D5FC7), Color(0xFF8E3FB0), Color(0xFF0E7C86), Color(0xFF656B76),
        Color(0xFF555B66), Color(0xFFD03030), Color(0xFF347F38), Color(0xFF8F6400),
        Color(0xFF2A66D0), Color(0xFF9A45BE), Color(0xFF127E8C), Color(0xFF8C929C),
    ),
    syntax = SyntaxColors(
        plain = Color(0xFF1E2228),
        keyword = Color(0xFF7B3FB0),
        string = Color(0xFF3B7229),
        comment = Color(0xFF656B76),
        number = Color(0xFFA14D17),
        type = Color(0xFF17727A),
        function = Color(0xFF2F5CB0),
        variable = Color(0xFF1E2228),
        parameter = Color(0xFF855410),
        property = Color(0xFF335C88),
        constant = Color(0xFF98442A),
        operator = Color(0xFF4B5563),
        punctuation = Color(0xFF5E6570),
        tag = Color(0xFFAE2F37),
        attribute = Color(0xFF855410),
        namespace = Color(0xFF17727A),
        regexp = Color(0xFFA1421C),
        escape = Color(0xFF8A5B00),
        heading = Color(0xFF2F5CB0),
        link = Color(0xFF2F5CB0),
        invalid = Color(0xFFC62828),
    ),
    emphasis = Emphasis(
        selection = 0.2f,
        currentLine = 0.045f,
        listSelection = 0.16f,
        bracketMatch = 0.2f,
        wordHighlight = 0.1f,
        searchMatch = 0.28f,
        searchMatchCurrent = 0.4f,
        inlayBackground = 0.06f,
    ),
)

/** High contrast on black: every text pair clears 7:1 (AAA) and surfaces separate by bright hairlines. */
val HighContrastDarkPalette = Palette(
    isDark = true,
    neutrals = Neutrals(
        editor = Color(0xFF000000),
        panel = Color(0xFF000000),
        raised = Color(0xFF0A0B0E),
        overlay = Color(0xFF101217),
        hairline = Color(0xFFA0A8B6),
        text = Color(0xFFFFFFFF),
        textMuted = Color(0xFFE1E5EB),
        textFaint = Color(0xFFC3C9D3),
    ),
    accent = Accent(accent = Color(0xFFA6B1FF), onAccent = Color(0xFF000000)),
    signals = Signals(
        error = Color(0xFFFF9A9A),
        warning = Color(0xFFFFD166),
        info = Color(0xFF8EC5FF),
        success = Color(0xFF7EE8A2),
        gitAdded = Color(0xFF7EE8A2),
        gitModified = Color(0xFFFFD166),
        gitDeleted = Color(0xFFFF9A9A),
        gitConflict = Color(0xFFFF9CD0),
    ),
    lanes = listOf(
        Color(0xFF8EC5FF), Color(0xFFFFA8CC), Color(0xFFD2BCFF), Color(0xFF7FE3D6),
        Color(0xFFFFCB7A), Color(0xFFB5E599), Color(0xFFFFA29A), Color(0xFFD5DEE8),
    ),
    ansi = listOf(
        Color(0xFF3A4150), Color(0xFFFF9A9A), Color(0xFFB5E599), Color(0xFFFFD166),
        Color(0xFF9CC0FF), Color(0xFFD9B8F5), Color(0xFF93DDE3), Color(0xFFE1E5EB),
        Color(0xFFAEB6C3), Color(0xFFFFB3B3), Color(0xFFC8F0B0), Color(0xFFFFE08F),
        Color(0xFFB8D3FF), Color(0xFFE6CCF8), Color(0xFFB0E8EC), Color(0xFFFFFFFF),
    ),
    syntax = SyntaxColors(
        plain = Color(0xFFFFFFFF),
        keyword = Color(0xFFD9B8F5),
        string = Color(0xFFBFE3AE),
        comment = Color(0xFFC3C9D3),
        number = Color(0xFFF5C9A3),
        type = Color(0xFF9EE0E4),
        function = Color(0xFFB3CEFF),
        variable = Color(0xFFFFFFFF),
        parameter = Color(0xFFF5DDB5),
        property = Color(0xFFC6DAF0),
        constant = Color(0xFFF2C0A8),
        operator = Color(0xFFD5DEE8),
        punctuation = Color(0xFFC3C9D3),
        tag = Color(0xFFF5B5B5),
        attribute = Color(0xFFF5DDB5),
        namespace = Color(0xFF9EE0E4),
        regexp = Color(0xFFF0B79A),
        escape = Color(0xFFF5D48F),
        heading = Color(0xFFB3CEFF),
        link = Color(0xFFB3CEFF),
        invalid = Color(0xFFFF9A9A),
    ),
    emphasis = Emphasis(
        selection = 0.4f,
        currentLine = 0.1f,
        listSelection = 0.32f,
        bracketMatch = 0.4f,
        wordHighlight = 0.18f,
        searchMatch = 0.4f,
        searchMatchCurrent = 0.55f,
        inlayBackground = 0.12f,
    ),
)

/** High contrast on white: every text pair clears 7:1 (AAA); the hc-light counterpart. */
val HighContrastLightPalette = Palette(
    isDark = false,
    neutrals = Neutrals(
        editor = Color(0xFFFFFFFF),
        panel = Color(0xFFFFFFFF),
        raised = Color(0xFFF4F4F5),
        overlay = Color(0xFFFFFFFF),
        hairline = Color(0xFF3A3F47),
        text = Color(0xFF000000),
        textMuted = Color(0xFF1F2329),
        textFaint = Color(0xFF3A3F47),
    ),
    accent = Accent(accent = Color(0xFF2A37A8), onAccent = Color(0xFFFFFFFF)),
    signals = Signals(
        error = Color(0xFFA00000),
        warning = Color(0xFF6B4500),
        info = Color(0xFF0B3F99),
        success = Color(0xFF0F5A22),
        gitAdded = Color(0xFF0F5A22),
        gitModified = Color(0xFF6B4500),
        gitDeleted = Color(0xFFA00000),
        gitConflict = Color(0xFF8A1650),
    ),
    lanes = listOf(
        Color(0xFF0B4FA8), Color(0xFF96205C), Color(0xFF5A2FB0), Color(0xFF06645A),
        Color(0xFF7A4C00), Color(0xFF336614), Color(0xFF9A2A1F), Color(0xFF3A4455),
    ),
    ansi = listOf(
        Color(0xFF000000), Color(0xFFA00000), Color(0xFF1F5A1F), Color(0xFF6B4500),
        Color(0xFF0B3F99), Color(0xFF6A1F8C), Color(0xFF06606A), Color(0xFF3A3F47),
        Color(0xFF2E333B), Color(0xFFB01010), Color(0xFF1F5F1F), Color(0xFF7A5000),
        Color(0xFF1447A8), Color(0xFF7A2AA0), Color(0xFF075A64), Color(0xFF5A606B),
    ),
    syntax = SyntaxColors(
        plain = Color(0xFF000000),
        keyword = Color(0xFF5A1F8C),
        string = Color(0xFF1F5214),
        comment = Color(0xFF3A3F47),
        number = Color(0xFF7A3308),
        type = Color(0xFF0B5358),
        function = Color(0xFF1B3F85),
        variable = Color(0xFF000000),
        parameter = Color(0xFF5E3A05),
        property = Color(0xFF1F4068),
        constant = Color(0xFF6E2A14),
        operator = Color(0xFF1F2329),
        punctuation = Color(0xFF2E333B),
        tag = Color(0xFF86161E),
        attribute = Color(0xFF5E3A05),
        namespace = Color(0xFF0B5358),
        regexp = Color(0xFF7A2A0C),
        escape = Color(0xFF5E3E00),
        heading = Color(0xFF1B3F85),
        link = Color(0xFF1B3F85),
        invalid = Color(0xFFA00000),
    ),
    emphasis = Emphasis(
        selection = 0.3f,
        currentLine = 0.08f,
        listSelection = 0.24f,
        bracketMatch = 0.3f,
        wordHighlight = 0.14f,
        searchMatch = 0.35f,
        searchMatchCurrent = 0.5f,
        inlayBackground = 0.1f,
    ),
)

/**
 * Graphite with the surface family pushed to true black for OLED panels. Only
 * the surfaces change: text, accent and syntax already clear AA on black.
 */
val GraphiteAmoledPalette = GraphiteDarkPalette.copy(
    neutrals = GraphiteDarkPalette.neutrals.copy(
        editor = Color(0xFF000000),
        panel = Color(0xFF050607),
        raised = Color(0xFF0B0D10),
        overlay = Color(0xFF14171C),
        hairline = Color(0xFF1E232B),
    ),
)

/**
 * The palette a [ThemeMode] stands for. SYSTEM_DEFAULT, DYNAMIC and
 * HIGH_CONTRAST follow the system night setting; DYNAMIC additionally swaps in
 * the wallpaper accent at [EasyIdeTheme], because only a Context can read it.
 */
fun paletteFor(mode: ThemeMode, systemInDark: Boolean): Palette = when (mode) {
    ThemeMode.LIGHT -> PaperLightPalette
    ThemeMode.DARK -> GraphiteDarkPalette
    ThemeMode.AMOLED_BLACK -> GraphiteAmoledPalette
    ThemeMode.HIGH_CONTRAST -> if (systemInDark) HighContrastDarkPalette else HighContrastLightPalette
    ThemeMode.SYSTEM_DEFAULT, ThemeMode.DYNAMIC -> if (systemInDark) GraphiteDarkPalette else PaperLightPalette
}

/** Every built-in palette, for tests and the theme picker. */
val BuiltInPalettes: List<Palette> = listOf(
    GraphiteDarkPalette,
    GraphiteAmoledPalette,
    PaperLightPalette,
    HighContrastDarkPalette,
    HighContrastLightPalette,
)
