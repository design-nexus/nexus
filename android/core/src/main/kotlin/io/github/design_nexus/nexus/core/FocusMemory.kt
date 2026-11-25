package io.github.design_nexus.nexus.core

/**
 * Where focus was in each row and which row had it, so coming back to a page (or the
 * launcher) puts focus where the viewer left it, clamped when a row has shrunk.
 */
class FocusMemory {
    private val positions = HashMap<String, Int>()
    var currentRow: String? = null
        private set

    fun remember(rowId: String, index: Int) {
        positions[rowId] = index
        currentRow = rowId
    }

    fun positionIn(rowId: String, size: Int): Int {
        if (size <= 0) return 0
        return (positions[rowId] ?: 0).coerceIn(0, size - 1)
    }

    /** The row to focus after a refresh: the remembered one if it's still there, else the first. */
    fun rowToFocus(rowIds: List<String>): String? = currentRow?.takeIf { it in rowIds } ?: rowIds.firstOrNull()

    fun forget(rowId: String) {
        positions.remove(rowId)
        if (currentRow == rowId) currentRow = null
    }

    /** Drop every remembered row and column (Home → top: Down should start fresh). */
    fun clear() {
        positions.clear()
        currentRow = null
    }
}
