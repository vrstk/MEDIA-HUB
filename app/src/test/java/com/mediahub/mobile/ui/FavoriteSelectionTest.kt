package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteSelectionTest {
    private fun folder(id: String) = CloudNode(id, id, true, null)
    private fun favorite(scope: String, id: String) =
        AlbumBookmark(scope, listOf(folder("root"), folder(id)))

    @Test fun removalIsScopedToAccountEvenWithIdenticalFolderIds() {
        val mail = favorite("mail:a", "models")
        val drive = favorite("drive:b", "models")
        val other = favorite("mail:c", "models")
        val original = listOf(mail, drive, other)
        val result = removeSelectedFavorites(original, setOf(favoriteSelectionKey(mail)))
        assertEquals(listOf(drive, other), result)
        assertEquals(3, original.size)
    }

    @Test fun multipleSelectedFavoritesCanBeRemovedAcrossSources() {
        val a = favorite("mail:a", "A")
        val b = favorite("drive:b", "B")
        val c = favorite("mail:a", "C")
        assertEquals(listOf(c), removeSelectedFavorites(listOf(a, b, c),
            setOf(favoriteSelectionKey(a), favoriteSelectionKey(b))))
    }

    @Test fun emptyOrUnknownSelectionLeavesAllBookmarksUnchanged() {
        val list = listOf(favorite("mail:a", "one"))
        assertEquals(list, removeSelectedFavorites(list, emptySet()))
        assertEquals(list, removeSelectedFavorites(list,
            setOf(FavoriteSelectionKey("mail:a", "unknown"))))
        assertTrue(removeSelectedFavorites(emptyList(),
            setOf(FavoriteSelectionKey("mail:a", "one"))).isEmpty())
    }
}
