package dev.easyide.app.ui.screens.workspace.syntax

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import androidx.compose.ui.text.AnnotatedString
import dev.easyide.app.extensions.adapters.ExtensionLanguages
import dev.easyide.app.ui.theme.SyntaxColors
import dev.easyide.extensions.ExtensionPolicy
import dev.textmate.grammar.Grammar
import dev.textmate.grammar.raw.GrammarReader
import dev.textmate.registry.Registry
import java.io.File
import java.util.concurrent.TimeUnit

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

    /** Grammars and languages from enabled extensions; they win over bundled ones (EXT-20). */
    private var extensions = ExtensionLanguages.EMPTY

    /** Told when an extension grammar blew its per-line budget (for the Extension Log). */
    @Volatile var onSlowGrammar: (scope: String) -> Unit = {}

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
        registry = newRegistry(manager, loaded)
        return loaded
    }

    /**
     * One registry resolving a scope to an extension grammar file first, then the bundled
     * asset. Asset and file I/O plus a third-party parser: a real boundary, and one bad
     * grammar must not take the editor down, so failures are logged and the scope is
     * treated as unavailable. Plist `.tmLanguage` files are not readable by the vendored
     * parser (JSON only) and fail the same way.
     */
    private fun newRegistry(manager: AssetManager, loaded: GrammarIndex) = Registry(
        grammarSource = { scope ->
            val extFile = extensions.grammarFile(scope)
            runCatching {
                if (extFile != null) File(extFile).inputStream().use(GrammarReader::readGrammar)
                else loaded.assetFor(scope)?.let { asset -> manager.open("${GrammarIndex.DIRECTORY}/$asset").use(GrammarReader::readGrammar) }
            }.onFailure { Log.w(TAG, "grammar $scope unreadable", it) }.getOrNull()
        },
    )

    /**
     * Swaps in the enabled extensions' languages and grammars. Every cached grammar and
     * document state is dropped (a pack can replace a scope an open tab uses), so open tabs
     * re-highlight on their next pass; enabling or disabling a pack is rare enough that a
     * full re-tokenize is the simple correct choice.
     */
    @Synchronized
    fun setExtensionLanguages(languages: ExtensionLanguages) {
        extensions = languages
        grammars.clear()
        unavailable.clear()
        documents.clear()
        val loaded = index ?: return
        registry = newRegistry(assets, loaded)
    }

    /**
     * The language id of a file (`editorLangId`): an extension language first, then the
     * bundled grammar's name; null when nothing recognises it.
     */
    @Synchronized
    fun languageIdFor(fileName: String, firstLine: String?): String? =
        extensions.languageFor(fileName, firstLine)
            ?: loadedIndex()?.let { idx -> idx.scopeFor(fileName)?.let(idx::languageFor) }

    /** Host path of an extension `language-configuration.json` for this file, if a pack contributes one. */
    @Synchronized
    internal fun extensionConfigFile(fileName: String): String? =
        extensions.languageFor(fileName, null)?.let(extensions::configurationFile)

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
     * no longer wants the result. [semantic] tokens are painted over the grammar's colours.
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
        semantic: SemanticPaint? = null,
    ): AnnotatedString {
        if (source.isEmpty()) return AnnotatedString(source)
        val head = source.substringBefore('\n').take(ExtensionPolicy.FIRST_LINE_MAX_CHARS)
        val scope = scopeFor(fileName, head)
        val grammar = scope?.let(::grammarForScope)
        if (scope == null || grammar == null) return semantic?.let { semanticOnly(source, it, firstLine, lastLine) } ?: AnnotatedString(source)

        // A rename to a different extension keeps the key but changes grammar.
        val doc = documents[key]?.takeIf { it.grammar === grammar }
            ?: newDocument(grammar, scope).also { documents[key] = it }
        doc.setContent(source)
        return doc.annotate(source, colors, firstLine, lastLine, checkCancelled, semantic)
    }

    /** A file no grammar colours can still get the language server's colours. */
    private fun semanticOnly(source: String, semantic: SemanticPaint, firstLine: Int, lastLine: Int): AnnotatedString {
        val builder = AnnotatedString.Builder(source)
        var lineStart = 0
        var line = 0
        while (line <= lastLine && lineStart <= source.length) {
            val end = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
            if (line >= firstLine) semantic.addLine(builder, line, lineStart, end - lineStart)
            lineStart = end + 1
            line++
        }
        return builder.toAnnotatedString()
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
            fileNames.mapNotNull { scopeFor(it, null) }.distinct().take(MAX_PREWARM_GRAMMARS)
        }
        for (scope in scopes) {
            synchronized(this) {
                if (scope in grammars || scope in unavailable) return@synchronized
                grammarForScope(scope)?.tokenizeLine(PREWARM_SAMPLE, null)
            }
        }
    }

    private fun scopeFor(fileName: String, firstLine: String?): String? =
        extensions.scopeFor(fileName, firstLine) ?: loadedIndex()?.scopeFor(fileName)

    private fun newDocument(grammar: Grammar, scope: String): DocumentHighlighter =
        if (scope in extensions.scopes) {
            DocumentHighlighter(grammar, TimeUnit.MILLISECONDS.toNanos(ExtensionPolicy.GRAMMAR_LINE_TIME_LIMIT_MS)) { onSlowGrammar(scope) }
        } else {
            DocumentHighlighter(grammar)
        }

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
