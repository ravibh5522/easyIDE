package dev.easyide.app.session

/**
 * How an open buffer relates to the file on disk beyond "same text": set by the external
 * change check (S8) and by a restore that found the file changed since the backup.
 */
sealed interface ExternalState {
    /** The buffer was loaded from, or last saved to, what is on disk. */
    data object InSync : ExternalState

    /** The buffer has unsaved edits and the file now says [diskText], which differs from both. */
    data class Conflict(val diskText: String) : ExternalState

    /** The file was deleted, or is no longer readable as text. Saving recreates it. */
    data object Gone : ExternalState
}

/** What a read of a tab's file returned. */
sealed interface DiskState {
    data class Text(val text: String) : DiskState
    data object Missing : DiskState

    /** Exists but is not an editable text file any more (became binary or oversized). */
    data object NotText : DiskState
}

/** What to do to a tab after comparing it with the disk. */
sealed interface ExternalChange {
    data object None : ExternalChange

    /** The buffer was clean: take the disk text silently. */
    data class Reload(val text: String) : ExternalChange

    /** Buffer and disk hold the same [text] (the edits were also written by someone else): in sync, nothing unsaved. */
    data class Sync(val text: String) : ExternalChange

    data class Conflict(val diskText: String) : ExternalChange

    data object Gone : ExternalChange

    /** A flagged tab whose file went back to what the buffer was based on: only the flag clears. */
    data object Resync : ExternalChange
}

/** A restored buffer: what to show, what to treat as on disk, and how they relate. */
data class RestoredBuffer(val content: String, val savedContent: String, val state: ExternalState)

/**
 * Pure decisions for external change detection (S8) and for restoring a hot-exit backup.
 * The comparison is by text, never by timestamp: the editor's own saves and an unrelated
 * file in the same directory both fire the same watcher event, and a text compare is what
 * makes those harmless.
 */
object ExternalChangeDetector {

    fun classify(content: String, saved: String, disk: DiskState, current: ExternalState): ExternalChange = when (disk) {
        DiskState.NotText -> ExternalChange.None
        DiskState.Missing -> if (current == ExternalState.Gone) ExternalChange.None else ExternalChange.Gone
        is DiskState.Text -> classifyText(content, saved, disk.text, current)
    }

    private fun classifyText(content: String, saved: String, disk: String, current: ExternalState): ExternalChange = when {
        content == disk -> if (saved == disk && current == ExternalState.InSync) ExternalChange.None else ExternalChange.Sync(disk)
        // Already told the user about exactly this disk text: stay quiet.
        current == ExternalState.Conflict(disk) -> ExternalChange.None
        disk == saved -> if (current == ExternalState.InSync) ExternalChange.None else ExternalChange.Resync
        content == saved -> ExternalChange.Reload(disk)
        else -> ExternalChange.Conflict(disk)
    }

    /**
     * A backup [backupText] whose edits were based on disk text hashing to [baseSha256],
     * against what the disk says now. Edits already on disk come back clean; a file that
     * still hashes to the base comes back dirty; anything else is a conflict to resolve.
     */
    fun restore(backupText: String, baseSha256: String, disk: DiskState): RestoredBuffer = when (disk) {
        DiskState.Missing, DiskState.NotText -> RestoredBuffer(backupText, "", ExternalState.Gone)
        is DiskState.Text -> when {
            disk.text == backupText || Hashes.sha256Hex(disk.text) == baseSha256 ->
                RestoredBuffer(backupText, disk.text, ExternalState.InSync)
            else -> RestoredBuffer(backupText, disk.text, ExternalState.Conflict(disk.text))
        }
    }
}

/** Retargets open tabs when the explorer renames or moves a file or a directory (S8). */
object PathRemap {
    /** The path [path] has after [from] became [to], or null when [path] is not affected. */
    fun remap(path: String, from: String, to: String): String? = when {
        path == from -> to
        path.startsWith("$from/") -> to + path.substring(from.length)
        else -> null
    }
}
