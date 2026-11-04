package com.pishi.termbridge

import com.pishi.termbridge.domain.HostListLogic
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.HostSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item T-22: the pure hosts-list rules. */
class HostListLogicTest {

    private fun host(
        id: Long = 0L,
        name: String = "Host",
        hostname: String = "example.com",
        username: String = "user",
        tags: String = "",
        favorite: Boolean = false,
        groupName: String = "",
        lastConnectedAt: Long? = null
    ) = HostProfile(
        id = id, name = name, hostname = hostname, username = username,
        tags = tags, favorite = favorite, groupName = groupName,
        lastConnectedAt = lastConnectedAt
    )

    @Test
    fun tagsParseTrimAndDedupe() {
        val h = host(tags = " prod, web ,prod, ,PROD, db ")
        assertEquals(listOf("prod", "web", "db"), HostListLogic.tagsOf(h))
        assertEquals(emptyList<String>(), HostListLogic.tagsOf(host(tags = " , ")))
    }

    @Test
    fun allTagsAreSortedAndUnique() {
        val hosts = listOf(
            host(tags = "web, prod"),
            host(tags = "db, Prod")
        )
        assertEquals(listOf("db", "prod", "web"), HostListLogic.allTags(hosts))
    }

    @Test
    fun queryMatchesEveryRememberedField() {
        val h = host(
            name = "Pi server", hostname = "raspberry.lan",
            username = "pi", tags = "home, arm", groupName = "Home"
        )
        assertTrue(HostListLogic.matchesQuery(h, "PI SER"))
        assertTrue(HostListLogic.matchesQuery(h, "raspberry"))
        assertTrue(HostListLogic.matchesQuery(h, "arm"))
        assertTrue(HostListLogic.matchesQuery(h, "home"))
        assertTrue(HostListLogic.matchesQuery(h, "  "))
        assertFalse(HostListLogic.matchesQuery(h, "cloud"))
    }

    @Test
    fun tagFilterIsExactAndCaseInsensitive() {
        val hosts = listOf(
            host(name = "A", tags = "prod"),
            host(name = "B", tags = "production"),
            host(name = "C", tags = "PROD, web")
        )
        val filtered = HostListLogic.filter(hosts, "", "prod")
        assertEquals(listOf("A", "C"), filtered.map { it.name })
        assertEquals(3, HostListLogic.filter(hosts, "", null).size)
    }

    @Test
    fun favoritesArePinnedFirstUnderEverySort() {
        val hosts = listOf(
            host(id = 1, name = "Alpha"),
            host(id = 2, name = "Zulu", favorite = true),
            host(id = 3, name = "Mike", lastConnectedAt = 900L),
            host(id = 4, name = "Beta", favorite = true, lastConnectedAt = 100L)
        )
        for (sort in HostSort.values()) {
            val names = HostListLogic.sorted(hosts, sort).map { it.name }
            assertEquals(
                "$sort must pin favorites first",
                setOf("Zulu", "Beta"), names.take(2).toSet()
            )
        }
        // Within favorites, the sort key still applies.
        assertEquals(
            listOf("Beta", "Zulu", "Alpha", "Mike"),
            HostListLogic.sorted(hosts, HostSort.NAME).map { it.name }
        )
    }

    @Test
    fun lastConnectedSortPutsNeverConnectedLast() {
        val hosts = listOf(
            host(name = "never"),
            host(name = "old", lastConnectedAt = 100L),
            host(name = "new", lastConnectedAt = 500L)
        )
        assertEquals(
            listOf("new", "old", "never"),
            HostListLogic.sorted(hosts, HostSort.LAST_CONNECTED).map { it.name }
        )
    }

    @Test
    fun recentlyAddedSortsByIdDescending() {
        val hosts = listOf(
            host(id = 5, name = "b"), host(id = 9, name = "a"), host(id = 2, name = "c")
        )
        assertEquals(
            listOf(9L, 5L, 2L),
            HostListLogic.sorted(hosts, HostSort.RECENTLY_ADDED).map { it.id }
        )
    }

    @Test
    fun groupsAreAlphabeticalWithUngroupedLast() {
        val hosts = listOf(
            host(name = "u1"),
            host(name = "w1", groupName = "Work"),
            host(name = "h1", groupName = "home"),
            host(name = "w2", groupName = " Work ")
        )
        val sections = HostListLogic.grouped(hosts)
        assertEquals(listOf("home", "Work", ""), sections.map { it.first })
        assertEquals(listOf("w1", "w2"), sections[1].second.map { it.name })
    }

    @Test
    fun sectionsPipelineFiltersSortsAndGroups() {
        val hosts = listOf(
            host(name = "db-1", tags = "prod", groupName = "Work"),
            host(name = "db-2", tags = "dev", groupName = "Work"),
            host(name = "pi", tags = "prod")
        )
        val sections = HostListLogic.sections(hosts, "db", "prod", HostSort.NAME)
        assertEquals(1, sections.size)
        assertEquals("Work", sections.single().first)
        assertEquals(listOf("db-1"), sections.single().second.map { it.name })
    }
}
