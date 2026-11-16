package com.mediahub.mobile.ui

/**
 * Multi-selection is local to the UI. The pair is deliberately scoped by
 * connected account, since opaque IDs can coincide across cloud sources.
 */
internal data class FavoriteSelectionKey(val scope: String, val folderId: String)

internal fun favoriteSelectionKey(bookmark: AlbumBookmark): FavoriteSelectionKey =
    FavoriteSelectionKey(bookmark.scope, bookmark.id)

/**
 * Only removes local bookmarks. Never accesses CloudProvider, photo files or
 * album cover preferences. A caller must confirm the selection beforehand.
 */
internal fun removeSelectedFavorites(
    bookmarks: List<AlbumBookmark>,
    selected: Set<FavoriteSelectionKey>
): List<AlbumBookmark> {
    if (selected.isEmpty()) return bookmarks
    return bookmarks.filterNot { favoriteSelectionKey(it) in selected }
}
