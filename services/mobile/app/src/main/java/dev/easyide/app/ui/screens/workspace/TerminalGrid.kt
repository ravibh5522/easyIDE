package dev.easyide.app.ui.screens.workspace

/**
 * The character grid the terminal view last measured. A session's process starts as soon as its
 * tab is created, before any view has sized it, so it starts at this grid (a phone panel is not 80
 * columns) and the view resizes it if the tab lands in a different frame. Programs that size
 * themselves once at start (progress bars, installers) then get a width close to the real one.
 */
object TerminalGrid {
    @Volatile var columns = DEFAULT_COLUMNS
        private set
    @Volatile var rows = DEFAULT_ROWS
        private set

    fun remember(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
    }

    /** Pixel size of a cell is only reported to programs that ask; a plausible size is enough until the view measures. */
    const val CELL_WIDTH_PX = 8
    const val CELL_HEIGHT_PX = 16

    private const val DEFAULT_COLUMNS = 80
    private const val DEFAULT_ROWS = 24
}
