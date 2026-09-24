package dev.easyide.app.ui.shell

/** Where "open beside" put a document. */
enum class BesideResult {
    /** The group after the active one was empty of room, so a new one was added for it. */
    NEW_GROUP,

    /** The group after the active one already existed. */
    NEXT_GROUP,

    /** Every group the window can show is in use and the active one is the last: it opens where it is. */
    NO_ROOM,

    /** The window shows one group at a time (a phone): it opens normally and the caller says why it did not split. */
    ONE_GROUP,

    /** The document's type cannot be split off (its type says `supportsSplit = false`). */
    NOT_SPLITTABLE,
}

/**
 * What [EditorStage.open] with [GroupTarget.BESIDE] will do, decided from the stage alone so the UI can
 * say so before or after (a phone's toast, a disabled menu item) without repeating the engine's rules.
 * The engine stays the authority; the tests hold the two together.
 */
object OpenBeside {
    fun of(stage: EditorStage, capacity: Int, splittable: Boolean): BesideResult = when {
        !splittable -> BesideResult.NOT_SPLITTABLE
        capacity <= 1 -> BesideResult.ONE_GROUP
        stage.active + 1 < stage.groups.size -> BesideResult.NEXT_GROUP
        stage.groups.size < capacity.coerceAtMost(ShellLimits.MAX_GROUPS) -> BesideResult.NEW_GROUP
        else -> BesideResult.NO_ROOM
    }

    /** "Move into next group": there has to be a group after the active one and a document to move. */
    fun canMoveToNext(stage: EditorStage): Boolean = stage.active + 1 < stage.groups.size && stage.activeGroup.active != null
}
