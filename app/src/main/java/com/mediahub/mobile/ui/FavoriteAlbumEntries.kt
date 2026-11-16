package com.mediahub.mobile.ui

import java.util.Locale

/** The saved folder path is the navigation target; titles may be locally customized. */
internal data class FavoriteAlbumEntry(val source: Int, val bookmark: AlbumBookmark) {
    val key: String get() = "${source}:${bookmark.id}"
}

/** No recursion, no cloud calls. Sources and opaque IDs remain isolated. */
internal fun visibleFavoriteAlbums(
    bookmarks: List<AlbumBookmark>,
    mailScope: String,
    mailRootId: String?,
    driveScope: String,
    driveRootId: String?,
    query: String,
    descending: Boolean = false
): List<FavoriteAlbumEntry> {
    val term = query.trim()
    return bookmarks.asSequence().mapNotNull { bookmark ->
        val source = when {
            mailRootId != null && bookmark.scope == mailScope &&
                bookmark.trail.firstOrNull()?.id == mailRootId -> 0
            driveRootId != null && bookmark.scope == driveScope &&
                bookmark.trail.firstOrNull()?.id == driveRootId -> 1
            else -> null
        } ?: return@mapNotNull null
        if (bookmark.trail.size < 2 || bookmark.trail.any { !it.isFolder }) {
            return@mapNotNull null
        }
        val path = bookmark.trail.joinToString("/") { it.name }
        if (term.isNotEmpty() &&
            !bookmark.title.contains(term, ignoreCase = true) &&
            !path.contains(term, ignoreCase = true)) return@mapNotNull null
        FavoriteAlbumEntry(source, bookmark)
    }.sortedWith { a, b ->
        val byTitle = a.bookmark.title.lowercase(Locale.ROOT)
            .compareTo(b.bookmark.title.lowercase(Locale.ROOT))
        if (byTitle != 0) {
            if (descending) -byTitle else byTitle
        } else {
            val bySource = a.source.compareTo(b.source)
            if (bySource != 0) bySource else a.bookmark.id.compareTo(b.bookmark.id)
        }
    }.toList()
}
