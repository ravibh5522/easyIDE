package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SchemaState
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.layer
import dev.easyide.app.ui.foundation.LocalSettings
import dev.easyide.app.ui.props.Appearance
import dev.easyide.app.ui.screens.workspace.decor.DecorationLayer
import dev.easyide.app.ui.screens.workspace.decor.DecorationModel
import dev.easyide.app.ui.screens.workspace.decor.DiagnosticDecoration
import dev.easyide.app.ui.screens.workspace.decor.DiagnosticSeverity
import dev.easyide.app.ui.screens.workspace.decor.EditorPopup
import dev.easyide.app.ui.screens.workspace.decor.SearchMatchDecoration
import dev.easyide.app.ui.screens.workspace.lsp.CompletionContent
import dev.easyide.app.ui.screens.workspace.lsp.CompletionEntry
import dev.easyide.app.ui.screens.workspace.lsp.CompletionUi
import dev.easyide.app.ui.screens.workspace.lsp.HoverBody
import dev.easyide.app.ui.screens.workspace.lsp.HoverOrigin
import dev.easyide.app.ui.screens.workspace.lsp.HoverUi
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode
import dev.easyide.lsp.protocol.CompletionItem
import dev.easyide.lsp.session.ServerKey
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the editor text area (typography, gutter, caret line, bracket outline, selection, find
 * matches, squiggle, completion list and hover) in the three configurations the owner reads it in,
 * dark and light. The sample is a real Kotlin and a real Python buffer, so the golden shows what
 * the app shows. Record with `./gradlew :app:recordRoborazziDebug --tests '*EditorGolden*'`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class EditorGoldenBase {

    @get:Rule val compose = createComposeRule()

    protected abstract val config: String
    protected open val fontScale = 1f

    private val server = ServerKey("env", "project", "server")

    private fun item(label: String, kind: Int, detail: String?) = CompletionEntry(
        server,
        CompletionItem.fromJson(buildJsonObject { put("label", label); put("kind", kind); detail?.let { put("detail", it) } })!!,
    )

    /** Draws [content] as the editor of one buffer with the caret at [caret] (a selection when [selectionEnd] differs). */
    private fun shot(
        name: String,
        mode: ThemeMode,
        file: String,
        content: String,
        caret: Int,
        selectionEnd: Int = caret,
        settings: SettingsSnapshot = SettingsSnapshot.DEFAULTS,
        decorate: (DecorationModel) -> Unit = {},
        overlay: @Composable (dev.easyide.app.ui.screens.workspace.decor.EditorGeometry) -> Unit = {},
    ) {
        TextMateHighlighter.init(ApplicationProvider.getApplicationContext())
        val tab = EditorTab(file, file.substringAfterLast('/'), content, content)
        val selections = EditorSelections().also { it[file] = TextRange(caret, selectionEnd) }
        val model = DecorationModel(content).also(decorate)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale), LocalSettings provides settings) {
                // A steady caret: a blinking one would make the golden depend on the clock.
                EasyIdeTheme(themeMode = mode, appearance = Appearance.DEFAULT.copy(cursorBlink = false)) {
                    Box(Modifier.fillMaxSize()) {
                        EditorPane(tab = tab, onContentChanged = {}, decorations = model, overlay = overlay, selections = selections)
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        repeat(SETTLE_ROUNDS) {
            Thread.sleep(SETTLE_MS)
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/editor/${config}_${name}_${if (mode == ThemeMode.DARK) "dark" else "light"}.png")
    }

    private fun bracketAndSquiggle(mode: ThemeMode) = shot(
        "bracketSquiggle", mode, "src/Greeter.kt", KOTLIN, KOTLIN.indexOf("greet(") + "greet(".length,
        decorate = {
            val at = KOTLIN.indexOf("StringBuilder")
            it.set(DecorationLayer.Diagnostics, "test", listOf(DiagnosticDecoration(at, at + "StringBuilder".length, DiagnosticSeverity.ERROR)), KOTLIN)
            val warn = KOTLIN.indexOf("builder.toString")
            it.set(DecorationLayer.Diagnostics, "test2", listOf(DiagnosticDecoration(warn, warn + "builder".length, DiagnosticSeverity.WARNING)), KOTLIN)
        },
    )

    private fun selectionAndFind(mode: ThemeMode) = shot(
        "selectionFind", mode, "src/Greeter.kt", KOTLIN, KOTLIN.indexOf("val builder"), KOTLIN.indexOf("for (i"),
        decorate = {
            val hits = Regex("builder").findAll(KOTLIN).map { it.range.first }.toList()
            it.set(
                DecorationLayer.SearchMatches, "find",
                hits.mapIndexed { i, s -> SearchMatchDecoration(s, s + "builder".length, isCurrent = i == 1) }, KOTLIN,
            )
        },
    )

    /** Word wrap, relative numbers and a block caret, on a long line, so the wrapped gutter and the block are seen. */
    private fun wrapBlockRelative(mode: ThemeMode) = shot(
        "wrapBlockRelative", mode, "src/Notes.py", WRAPPED, WRAPPED.indexOf("second"),
        settings = SettingsSnapshot(
            SchemaState.builtInOnly(SettingsSchema.all),
            listOf(layer(LayerId.USER, """{"editor.wordWrap": "on", "editor.lineNumbers": "relative", "editor.cursorStyle": "block"}""")),
        ),
    )

    private fun completion(mode: ThemeMode) = shot(
        "completion", mode, "main.py", PYTHON, PYTHON.length,
        overlay = { geometry ->
            val entries = listOf(
                item("print", 3, "(*values: object) -> None"), item("property", 7, "class property"), item("pow", 3, "(base, exp) -> number"),
                item("pprint", 9, "module"), item("print_function", 6, "__future__"),
            )
            EditorPopup(anchor = { geometry.caretRect() }, onDismiss = {}) {
                CompletionContent(CompletionUi("main.py", PYTHON.length - 3, entries, selected = 0)) {}
            }
        },
    )

    private fun hover(mode: ThemeMode) = shot(
        "hover", mode, "src/Greeter.kt", KOTLIN, KOTLIN.indexOf("append") + 2,
        overlay = { geometry ->
            val start = KOTLIN.indexOf("append")
            val ui = HoverUi(
                "src/Greeter.kt", KOTLIN, start, start + "append".length,
                "```kotlin\nfun append(value: String): StringBuilder\n```\nAppends the string representation of [value] to this builder.",
                "kotlin", "Greeter.kt", HoverOrigin.MOUSE,
            )
            EditorPopup(anchor = { geometry.rangeRect(ui.start, ui.end) }, onDismiss = {}) { HoverBody(ui) }
        },
    )

    @Test fun bracketSquiggleDark() = bracketAndSquiggle(ThemeMode.DARK)
    @Test fun bracketSquiggleLight() = bracketAndSquiggle(ThemeMode.LIGHT)
    @Test fun selectionFindDark() = selectionAndFind(ThemeMode.DARK)
    @Test fun selectionFindLight() = selectionAndFind(ThemeMode.LIGHT)
    @Test fun completionDark() = completion(ThemeMode.DARK)
    @Test fun completionLight() = completion(ThemeMode.LIGHT)
    @Test fun wrapBlockRelativeDark() = wrapBlockRelative(ThemeMode.DARK)
    @Test fun wrapBlockRelativeLight() = wrapBlockRelative(ThemeMode.LIGHT)
    @Test fun hoverDark() = hover(ThemeMode.DARK)
    @Test fun hoverLight() = hover(ThemeMode.LIGHT)

    private companion object {
        const val SETTLE_ROUNDS = 6
        const val SETTLE_MS = 400L

        val KOTLIN = """
            package dev.easyide.sample

            class Greeter(private val name: String) {
                fun greet(times: Int): String {
                    val builder = StringBuilder()
                    for (i in 0 until times) {
                        if (i % 2 == 0) {
                            builder.append("Hello, ${'$'}name")
                        }
                    }
                    return builder.toString()
                }
            }
        """.trimIndent() + "\n"

        val WRAPPED = "first = 1\nsecond = 'a long line that has to wrap at the editor width instead of scrolling sideways, and its number stays on its first row'\nthird = 3\nfourth = 4\n"

        val PYTHON = """
            def main():
                items = [1, 2, 3]
                for item in items:
                    pri
        """.trimIndent()
    }
}

@Config(sdk = [35], qualifiers = "w1152dp-h720dp-xhdpi")
class EditorGoldenExpandedTest : EditorGoldenBase() { override val config = "expanded-dense-1152" }

@Config(sdk = [35], qualifiers = "w411dp-h800dp-xhdpi")
class EditorGoldenCompactTest : EditorGoldenBase() { override val config = "compact-comfortable-411" }

@Config(sdk = [35], qualifiers = "w320dp-h600dp-xhdpi")
class EditorGoldenSmallLargeTextTest : EditorGoldenBase() {
    override val config = "compact-320-font2"
    override val fontScale = LARGEST_FONT_SCALE
}

private const val LARGEST_FONT_SCALE = 2f
