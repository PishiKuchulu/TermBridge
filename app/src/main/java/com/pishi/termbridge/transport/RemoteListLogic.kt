package com.pishi.termbridge.transport

/** How the remote listing is ordered (item T-21). */
enum class RemoteSort { NAME, SIZE, TYPE }

/**
 * Pure presentation logic for the remote file list (item
 * T-21): hidden-file filtering, name search and sorting.
 * Directories always come first — a file manager that mixes
 * them into the files is unusable — and every comparison is
 * case-insensitive with a name tiebreak, so the order is
 * stable and locale-independent.
 */
object RemoteListLogic {
    /** A hidden entry: the name starts with a dot. */
    fun isHidden(entry: RemoteEntry): Boolean = entry.name.startsWith(".")

    fun visible(
        entries: List<RemoteEntry>,
        query: String,
        showHidden: Boolean
    ): List<RemoteEntry> {
        val q = query.trim()
        return entries.filter { entry ->
            (showHidden || !isHidden(entry)) &&
                (q.isEmpty() || entry.name.contains(q, ignoreCase = true))
        }
    }

    fun sorted(entries: List<RemoteEntry>, sort: RemoteSort): List<RemoteEntry> {
        val byName = compareBy<RemoteEntry> { it.name.lowercase() }
        val comparator: Comparator<RemoteEntry> = when (sort) {
            RemoteSort.NAME -> byName
            // Unknown sizes (-1) sink to the end of the files.
            RemoteSort.SIZE -> compareBy<RemoteEntry> {
                if (it.size < 0) Long.MAX_VALUE else it.size
            }.then(byName)
            RemoteSort.TYPE -> compareBy<RemoteEntry> {
                it.name.substringAfterLast('.', "").lowercase()
            }.then(byName)
        }
        val (dirs, files) = entries.partition { it.isDirectory }
        return dirs.sortedWith(byName) + files.sortedWith(comparator)
    }

    /** The listing exactly as the screen shows it. */
    fun displayed(
        entries: List<RemoteEntry>,
        query: String,
        showHidden: Boolean,
        sort: RemoteSort
    ): List<RemoteEntry> = sorted(visible(entries, query, showHidden), sort)
}
