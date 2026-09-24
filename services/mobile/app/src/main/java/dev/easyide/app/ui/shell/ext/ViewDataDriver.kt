package dev.easyide.app.ui.shell.ext

import dev.easyide.extensions.action.Action
import dev.easyide.extensions.contrib.ViewDataKind
import dev.easyide.extensions.view.Into
import dev.easyide.extensions.view.IntoMode
import dev.easyide.extensions.view.ResultParse
import dev.easyide.extensions.view.ViewData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fetches the data of views and documents, and only while they are on screen (extension-ui.md section 4.4): a view that is not
 * shown runs nothing, which is what keeps ten installed packs cheap. A view's `easyide.viewData` action and a document type's
 * `state.provider` command run when it appears, and again every `intervalSec` (at least 2 s) until it leaves. A navigation
 * badge's view is fetched once when its item first shows, so the badge has a number before the view was ever opened.
 * A source that keeps failing says so once, not every interval.
 */
class ViewDataDriver(
    private val shell: StateFlow<ExtShell>,
    private val hub: ViewDataHub,
    private val calls: ViewCalls,
    private val log: (owner: String, message: String) -> Unit,
    private val scope: CoroutineScope,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /** Where one feed's data comes from and how it is written. */
    private class Feed(
        val owner: String, val key: String, val initial: JsonObject, val intervalSec: Int?, val into: Into,
        val produce: suspend () -> CallResult,
    )

    private val watching = HashMap<String, Int>()
    private val jobs = HashMap<String, Job>()
    private val lastFailure = HashMap<String, String>()
    private val warmed = HashSet<String>()

    init {
        scope.launch {
            shell.map { s -> s.badges.map { it.view }.toSet() }.distinctUntilChanged().collect { views ->
                views.filter { warmed.add(it) }.forEach { view -> feedOfView(view)?.let { f -> scope.launch { fetch(f) } } }
            }
        }
    }

    /** A view came on screen. Balanced by [hideView]; two surfaces of one view share one fetch loop. */
    @Synchronized
    fun showView(viewId: String) = start("view:$viewId") { feedOfView(viewId) }

    @Synchronized
    fun hideView(viewId: String) = stop("view:$viewId")

    @Synchronized
    fun showDocument(typeId: String, uri: String, key: String) = start("doc:$uri") { feedOfDocument(typeId, uri, key) }

    @Synchronized
    fun hideDocument(uri: String) = stop("doc:$uri")

    private fun start(id: String, feed: () -> Feed?) {
        val count = (watching[id] ?: 0) + 1
        watching[id] = count
        if (count > 1) return
        val f = feed() ?: return
        jobs[id] = scope.launch {
            do {
                fetch(f)
                f.intervalSec?.let { pause(it * MS_PER_SEC) }
            } while (f.intervalSec != null)
        }
    }

    private fun stop(id: String) {
        val count = (watching[id] ?: return) - 1
        if (count > 0) { watching[id] = count; return }
        watching.remove(id)
        jobs.remove(id)?.cancel()
    }

    private fun feedOfView(viewId: String): Feed? {
        val source = shell.value.dataSources.firstOrNull { it.viewId == viewId } ?: return null
        val view = shell.value.view(viewId) ?: return null
        val into = when (source.kind) {
            ViewDataKind.OBJECT -> Into(ViewData.MERGE, IntoMode.SET, ResultParse.JSON)
            ViewDataKind.LIST, ViewDataKind.TREE -> Into(LEGACY_ITEMS, IntoMode.SET, ResultParse.JSON)
        }
        return Feed(source.extensionId, viewId, view.body.state, source.intervalSec, into) { calls.inline(source.extensionId, view.name, source.from, null) }
    }

    private fun feedOfDocument(typeId: String, uri: String, key: String): Feed? {
        val doc = shell.value.document(typeId) ?: return null
        val provider = doc.state ?: return null
        val args = JsonObject(mapOf("uri" to JsonPrimitive(uri), "key" to JsonPrimitive(key)))
        val seed = JsonObject(doc.body.state + args)
        return Feed(doc.extensionId, uri, seed, provider.intervalSec, Into(ViewData.MERGE, IntoMode.SET, ResultParse.JSON)) {
            calls.command(doc.extensionId, provider.command, args)
        }
    }

    private suspend fun fetch(f: Feed) {
        when (val r = f.produce()) {
            is CallResult.Value -> {
                val written = hub.write(f.key, f.initial) { ViewData.write(it, f.into, r.value) }
                if (written is ViewData.Written.Rejected) report(f, written.reason) else lastFailure.remove(f.key)
            }
            is CallResult.Failed -> report(f, r.message)
            CallResult.Done, CallResult.Cancelled -> lastFailure.remove(f.key)
        }
    }

    private fun report(f: Feed, reason: String) {
        if (lastFailure.put(f.key, reason) != reason) log(f.owner, "data for ${f.key} failed: $reason")
    }

    private companion object {
        const val MS_PER_SEC = 1_000L

        /** Where a legacy `list` or `tree` result of `easyide.viewData` is written: the `items` of a plain list. */
        const val LEGACY_ITEMS = "items"
    }
}
