package dev.easyide.app.ui.screens.workspace.syntax

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import androidx.compose.ui.text.AnnotatedString
import dev.easyide.app.ui.theme.SyntaxColors
import dev.textmate.grammar.Grammar
import dev.textmate.grammar.raw.GrammarReader
import dev.textmate.registry.Registry

/**
 * Syntax colouring driven by real TextMate grammars - the same
 * `.tmLanguage.json` files VS Code uses - rather than by regex guesses.
 *
 * 229 grammars ship in `assets/grammars`, filtered to permissively licensed
 * ones (see NOTICE.md). They are parsed lazily: opening a `.py` file loads
 * `source.python` and whatever it includes, and nothing else.
 *
 * Per-file tokenizer state lives in [DocumentHighlighter], which is what makes
 * cost depend on *what changed and what is on screen* rather than on file size.
 * Whether a file is colourable at all remains a single decision made once by
 * `FilePolicy.HIGHLIGHT_MAX_BYTES`, and shown to the user as a notice.
 *
 * The upstream tokenizer is explicitly not thread-safe and holds mutable state
 * per grammar, so every entry point here is synchronized. Highlighting is
 * called from a background dispatcher, one file at a time.
 */
object TextMateHighlighter {

    private const val TAG = "TextMateHighlighter"

    private var index: GrammarIndex? = null
    private var registry: Registry? = null

    /** Grammars that failed to load, so a broken asset is not retried per keystroke. */
    private val unavailable = HashSet<String>()
    private val grammars = HashMap<String, Grammar>()

    /**
     * Only the file currently being edited keeps tokenizer state. Switching tabs
     * discards it, which costs one viewport-sized tokenize on the way back -
     * cheap, and far better than holding state for every open tab.
     */
    private var documentKey: String? = null
    private var document: DocumentHighlighter? = null

    /**
     * Called once from the Application. Only the index is read eagerly (44 KB);
     * grammars themselves are pulled in on first use.
     */
    @Synchronized
    fun init(context: Context) {
        if (index != null) return
        val manager: AssetManager = context.applicationContext.assets
        index = runCatching { GrammarIndex.load(manager) }
            .onFailure { Log.e(TAG, "grammar index unreadable, highlighting disabled", it) }
            .getOrNull()
        val loaded = index ?: return
        registry = Registry(
            grammarSource = { scope ->
                val asset = loaded.assetFor(scope) ?: return@Registry null
                runCatching {
                    manager.open("${GrammarIndex.DIRECTORY}/$asset").use(GrammarReader::readGrammar)
                }.onFailure { Log.w(TAG, "grammar $scope unreadable", it) }.getOrNull()
            },
        )
    }

    /** True when a grammar exists for this file, so callers can label the language. */
    @Synchronized
    fun supports(fileName: String): Boolean = index?.scopeFor(fileName) != null

    /**
     * Colour [source] for the window of lines `[firstLine, lastLine]`.
     *
     * [key] identifies the buffer (its project-relative path) so tokenizer state
     * survives edits and scrolling but is dropped when the tab changes.
     */
    @Synchronized
    fun highlight(
        key: String,
        source: String,
        fileName: String,
        colors: SyntaxColors,
        firstLine: Int,
        lastLine: Int,
    ): AnnotatedString {
        if (source.isEmpty()) return AnnotatedString(source)
        val grammar = grammarFor(fileName) ?: return AnnotatedString(source)

        val doc = document?.takeIf { documentKey == key } ?: DocumentHighlighter(grammar).also {
            documentKey = key
            document = it
        }
        doc.setContent(source)
        return doc.annotate(source, colors, firstLine, lastLine)
    }

    private fun grammarFor(fileName: String): Grammar? {
        val scope = index?.scopeFor(fileName) ?: return null
        if (scope in unavailable) return null
        grammars[scope]?.let { return it }

        // Asset I/O plus a third-party parser over 229 vendored files: a real
        // boundary, and one bad grammar must not take the editor down with it.
        val grammar = runCatching { registry?.loadGrammar(scope) }
            .onFailure { Log.w(TAG, "grammar $scope failed to compile", it) }
            .getOrNull()
        if (grammar == null) {
            unavailable += scope
            return null
        }
        grammars[scope] = grammar
        return grammar
    }
}
