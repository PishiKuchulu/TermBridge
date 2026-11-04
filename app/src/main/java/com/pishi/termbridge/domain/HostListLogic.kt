package com.pishi.termbridge.domain

/** Sort orders for the hosts list (item T-22). */
enum class HostSort { NAME, LAST_CONNECTED, RECENTLY_ADDED }

/**
 * Pure hosts-list logic (item T-22): search, tag parsing and
 * filtering, sorting and grouping. Kept free of Android and
 * Room so every rule is unit-tested on the JVM; the screen and
 * the ViewModel only hold state and call these functions.
 *
 * Standing decisions, made once here rather than per call site:
 * - Favorites are pinned first under EVERY sort order — a
 *   favorite the user pinned should not sink when they sort
 *   by last connected.
 * - "Recently added" orders by database id: ids are
 *   autoincrement, so id order IS insertion order, and the
 *   profile deliberately carries no createdAt.
 * - Ungrouped hosts (blank group name) sort after every named
 *   group.
 */
object HostListLogic {

    /** The host's tags: the stored field is one comma-separated
     *  string; parsing trims, drops blanks and dedupes
     *  case-insensitively, keeping the first spelling. */
    fun tagsOf(host: HostProfile): List<String> =
        host.tags.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    /** Every tag in use across [hosts], sorted, for filter chips. */
    fun allTags(hosts: List<HostProfile>): List<String> =
        hosts.flatMap { tagsOf(it) }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }

    /** Case-insensitive substring match over the fields a user
     *  actually remembers: name, hostname, username, group and
     *  tags. A blank query matches everything. */
    fun matchesQuery(host: HostProfile, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return host.name.lowercase().contains(q) ||
            host.hostname.lowercase().contains(q) ||
            host.username.lowercase().contains(q) ||
            host.groupName.lowercase().contains(q) ||
            tagsOf(host).any { it.lowercase().contains(q) }
    }

    /** Query filter plus an optional exact-tag filter (the
     *  chips); a null [tag] means no tag filter. */
    fun filter(
        hosts: List<HostProfile>,
        query: String,
        tag: String?
    ): List<HostProfile> = hosts.filter { host ->
        matchesQuery(host, query) &&
            (tag == null || tagsOf(host).any { it.equals(tag, ignoreCase = true) })
    }

    /** Favorites first, then the chosen order; name breaks ties
     *  so the result is fully deterministic. */
    fun sorted(hosts: List<HostProfile>, sort: HostSort): List<HostProfile> {
        val key: Comparator<HostProfile> = when (sort) {
            HostSort.NAME -> compareBy { it.name.lowercase() }
            HostSort.LAST_CONNECTED ->
                // Never-connected hosts (null) sink to the end.
                compareByDescending { it.lastConnectedAt ?: Long.MIN_VALUE }
            HostSort.RECENTLY_ADDED -> compareByDescending { it.id }
        }
        return hosts.sortedWith(
            compareByDescending<HostProfile> { it.favorite }
                .then(key)
                .thenBy { it.name.lowercase() }
        )
    }

    /** Group sections in display order: named groups A→Z, the
     *  ungrouped section (key "") last and only when non-empty. */
    fun grouped(hosts: List<HostProfile>): List<Pair<String, List<HostProfile>>> {
        val byGroup = hosts.groupBy { it.groupName.trim() }
        val named = byGroup.keys
            .filter { it.isNotEmpty() }
            .sortedBy { it.lowercase() }
            .map { name -> name to byGroup.getValue(name) }
        val ungrouped = byGroup[""].orEmpty()
        return if (ungrouped.isEmpty()) named else named + ("" to ungrouped)
    }

    /** The full pipeline the list shows: filter, then sort,
     *  then group. */
    fun sections(
        hosts: List<HostProfile>,
        query: String,
        tag: String?,
        sort: HostSort
    ): List<Pair<String, List<HostProfile>>> =
        grouped(sorted(filter(hosts, query, tag), sort))
}
