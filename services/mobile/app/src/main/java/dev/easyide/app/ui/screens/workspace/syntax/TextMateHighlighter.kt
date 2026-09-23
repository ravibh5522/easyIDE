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
 * called from a background dispatcher.
 */
object TextMateHighlighter {

    private const val TAG = "TextMateHighlighter"

    /** Open buffers whose tokenizer state is retained; roughly lines x a few objects each. */
    private const val MAX_CACHED_DOCUMENTS = 8

    /** Distinct languages warmed per [prewarm] call, so a polyglot repo root cannot stall the thread. */
    private const val MAX_PREWARM_GRAMMARS = 6

    /** Touches identifiers, calls, strings, numbers and comments - the patterns most grammars reach first. */
    private const val PREWARM_SAMPLE = "value = call(\"text\", 42) // note"

    private lateinit var assets: AssetManager
    private var indexLoadAttempted = false
    private var index: GrammarIndex? = null
    private var registry: Registry? = null

    /** Grammars that failed to load, so a broken asset is not retried per keystroke. */
    private val unavailable = HashSet<String>()
    private val grammars = HashMap<String, Grammar>()

    /**
     * Tokenizer state for the most recently highlighted buffers, keyed by path.
     * Keeping only the active file meant every tab switch re-tokenized from line
     * 0 down to wherever that tab was scrolled - thousands of lines for a tab
     * left deep in a file. A small access-ordered LRU bounds memory to a few
     * files' worth of line states while making switching back instant.
     */
    private val documents = object : LinkedHashMap<String, DocumentHighlighter>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DocumentHighlighter>) =
            size > MAX_CACHED_DOCUMENTS
    }

    /**
     * Called once from the Application. Only remembers where the assets are:
     * reading the index is deferred to the first [highlight] or [prewarm],
     * both of which run off the main thread, so launch pays nothing for it.
     */
    @Synchronized
    fun init(context: Context) {
        assets = context.applicationContext.assets
    }

    /** Reads the index (44 KB) once; grammars themselves are pulled in on first use. Caller holds the lock. */
    private fun loadedIndex(): GrammarIndex? {
        if (indexLoadAttempted) return index
        indexLoadAttempted = true
        val manager = assets
        index = runCatching { GrammarIndex.load(manager) }
            .onFailure { Log.e(TAG, "grammar index unreadable, highlighting disabled", it) }
            .getOrNull()
        val loaded = index ?: return null
        LanguageConfigs.init(manager, loaded)
        registry = Registry(
            grammarSource = { scope ->
                val asset = loaded.assetFor(scope) ?: return@Registry null
                runCatching {
                    manager.open("${GrammarIndex.DIRECTORY}/$asset").use(GrammarReader::readGrammar)
                }.onFailure { Log.w(TAG, "grammar $scope unreadable", it) }.getOrNull()
            },
        )
        return loaded
    }

    /**
     * Loads the index if nothing has yet, so [LanguageConfigs] works for a tab
     * opened before its first highlight pass. Always takes this lock before
     * [LanguageConfigs]' own, which is what keeps the two from deadlocking.
     */
    @Synchronized
    internal fun ensureIndexLoaded() {
        loadedIndex()
    }

    /**
     * Colour [source] for the window of lines `[firstLine, lastLine]`.
     *
     * [key] identifies the buffer (its project-relative path) so tokenizer state
     * survives edits, scrolling and switching between recent tabs.
     * [checkCancelled] is polled between lines; it should throw when the caller
     * no longer wants the result.
     */
    @Synchronized
    fun highlight(
        key: String,
        source: String,
        fileName: String,
        colors: SyntaxColors,
        firstLine: Int,
        lastLine: Int,
        checkCancelled: () -> Unit = {},
    ): AnnotatedString {
        if (source.isEmpty()) return AnnotatedString(source)
        val grammar = grammarFor(fileName) ?: return AnnotatedString(source)

        // A rename to a different extension keeps the key but changes grammar.
        val doc = documents[key]?.takeIf { it.grammar === grammar }
            ?: DocumentHighlighter(grammar).also { documents[key] = it }
        doc.setContent(source)
        return doc.annotate(source, colors, firstLine, lastLine, checkCancelled)
    }

    /**
     * Load and exercise the grammars for [fileNames] ahead of the first open.
     *
     * The expensive part of a first open is not reading the grammar but the
     * regex engine compiling its patterns, which the library defers to the
     * first tokenize. Running one sample line here moves that cost onto a
     * background thread while the user is still looking at the file tree.
     * Each grammar takes the lock separately, so a real highlight request can
     * interleave instead of waiting for the whole batch.
     */
    fun prewarm(fileNames: Collection<String>) {
        val scopes = synchronized(this) {
            fileNames.mapNotNull { loadedIndex()?.scopeFor(it) }.distinct().take(MAX_PREWARM_GRAMMARS)
        }
        for (scope in scopes) {
            synchronized(this) {
                if (scope in grammars || scope in unavailable) return@synchronized
                grammarForScope(scope)?.tokenizeLine(PREWARM_SAMPLE, null)
            }
        }
    }

    private fun grammarFor(fileName: String): Grammar? =
        loadedIndex()?.scopeFor(fileName)?.let(::grammarForScope)

    private fun grammarForScope(scope: String): Grammar? {
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
