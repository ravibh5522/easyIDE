package dev.easyide.sandbox.files

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Watches a project's real directories for changes made outside the app's
 * own file-mutation methods - chiefly the terminal, since any shell command
 * (`touch`, `git clone`, `apt-get install`) writes straight to the
 * bind-mounted project directory with no way for the ViewModel to know a
 * refresh is needed otherwise.
 *
 * Only the project root plus whatever directories the explorer currently has
 * expanded are watched, mirroring `WorkspaceUiState.expandedDirs` - a
 * collapsed directory is not visible, so there is nothing to keep fresh
 * there until it is expanded, at which point it is listed from disk anyway.
 *
 * Debounced per directory: a burst of writes (an install, a clone) fires many
 * raw inotify events in a few milliseconds on the same directory, and would
 * otherwise trigger a re-list per event. [onChanged] receives only the
 * directories that actually fired an event since the last flush, not the
 * whole watched set, so the caller can re-list just those instead of the
 * entire tree.
 */
class ProjectFileWatcher(
    private val onChanged: (Set<File>) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableMapOf<String, FileObserver>()
    private val pendingDirs = mutableSetOf<File>()
    private val flush = Runnable {
        val dirs = synchronized(pendingDirs) {
            pendingDirs.toSet().also { pendingDirs.clear() }
        }
        if (dirs.isNotEmpty()) onChanged(dirs)
    }

    /** Call whenever the set of directories visible in the explorer changes. */
    fun watch(root: File, expandedDirs: Set<File>) {
        val target = (expandedDirs + root).filter { it.isDirectory }.associateBy { it.absolutePath }

        (observers.keys - target.keys).forEach { stale ->
            observers.remove(stale)?.stopWatching()
        }
        target.forEach { (path, dir) ->
            if (path !in observers) {
                observers[path] = watcherFor(dir).apply { startWatching() }
            }
        }
    }

    fun stop() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
        synchronized(pendingDirs) { pendingDirs.clear() }
        handler.removeCallbacks(flush)
    }

    @Suppress("DEPRECATION") // File-based constructor needs API 29; minSdk here is 26.
    private fun watcherFor(dir: File): FileObserver =
        object : FileObserver(dir.absolutePath, WATCH_MASK) {
            // Called on this FileObserver's own background thread - a
            // different directory's observer can call this concurrently, so
            // pendingDirs needs the lock even though Handler itself is safe
            // to post to from any thread.
            override fun onEvent(event: Int, path: String?) {
                synchronized(pendingDirs) { pendingDirs.add(dir) }
                handler.removeCallbacks(flush)
                handler.postDelayed(flush, DEBOUNCE_MS)
            }
        }

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val WATCH_MASK = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
    }
}
