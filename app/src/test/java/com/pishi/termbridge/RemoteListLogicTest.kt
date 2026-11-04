package com.pishi.termbridge

import com.pishi.termbridge.transport.RemoteEntry
import com.pishi.termbridge.transport.RemoteListLogic
import com.pishi.termbridge.transport.RemoteSort
import org.junit.Assert.assertEquals
import org.junit.Test

/** Item T-21: remote listing filter/sort logic. */
class RemoteListLogicTest {

    private fun file(name: String, size: Long = 10) =
        RemoteEntry(name, "/$name", isDirectory = false, size = size, isRegularFile = true)

    private fun dir(name: String) =
        RemoteEntry(name, "/$name", isDirectory = true, size = -1, isRegularFile = false)

    @Test
    fun hiddenFilesAreFilteredUnlessShown() {
        val entries = listOf(file("a.txt"), file(".secret"), dir(".config"), dir("docs"))
        assertEquals(
            listOf("docs", "a.txt"),
            RemoteListLogic.displayed(entries, "", showHidden = false, RemoteSort.NAME)
                .map { it.name }
        )
        assertEquals(
            listOf(".config", "docs", ".secret", "a.txt"),
            RemoteListLogic.displayed(entries, "", showHidden = true, RemoteSort.NAME)
                .map { it.name }
        )
    }

    @Test
    fun queryMatchesNameCaseInsensitively() {
        val entries = listOf(file("Report.pdf"), file("notes.txt"), dir("Reports"))
        assertEquals(
            listOf("Reports", "Report.pdf"),
            RemoteListLogic.displayed(entries, "report", false, RemoteSort.NAME)
                .map { it.name }
        )
        assertEquals(
            emptyList<String>(),
            RemoteListLogic.displayed(entries, "zzz", false, RemoteSort.NAME)
                .map { it.name }
        )
    }

    @Test
    fun directoriesAlwaysComeFirst() {
        val entries = listOf(file("aaa.txt"), dir("zzz"))
        assertEquals(
            listOf("zzz", "aaa.txt"),
            RemoteListLogic.sorted(entries, RemoteSort.NAME).map { it.name }
        )
    }

    @Test
    fun sizeSortPutsUnknownSizesLast() {
        val entries = listOf(file("big", 900), file("unknown", -1), file("small", 5))
        assertEquals(
            listOf("small", "big", "unknown"),
            RemoteListLogic.sorted(entries, RemoteSort.SIZE).map { it.name }
        )
    }

    @Test
    fun typeSortGroupsByExtension() {
        val entries = listOf(file("b.zip"), file("a.txt"), file("c.txt"), file("noext"))
        assertEquals(
            listOf("noext", "a.txt", "c.txt", "b.zip"),
            RemoteListLogic.sorted(entries, RemoteSort.TYPE).map { it.name }
        )
    }
}
