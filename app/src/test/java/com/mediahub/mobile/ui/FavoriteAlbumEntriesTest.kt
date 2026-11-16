package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteAlbumEntriesTest {
    private fun folder(id: String, name: String = id) = CloudNode(id, name, true, null)
    private val mail = AlbumBookmark("mail:1",
        listOf(folder("mailroot"), folder("Models"), folder("alba", "Alba")))
    private val drive = AlbumBookmark("drive:1",
        listOf(folder("driveroot"), folder("Models"), folder("alba", "Alba")),
        "Vacation")

    @Test fun nestedFavoritesAppearWithoutRootListing() {
        val entries = visibleFavoriteAlbums(listOf(drive, mail),
            "mail:1", "mailroot", "drive:1", "driveroot", "")
        assertEquals(listOf("0:alba", "1:alba"), entries.map { it.key })
        assertEquals(listOf("Alba", "Vacation"), entries.map { it.bookmark.title })
    }

    @Test fun searchMatchesLocalLabelAndNestedPath() {
        val all = listOf(mail, drive)
        assertEquals(listOf("1:alba"), visibleFavoriteAlbums(all,
            "mail:1", "mailroot", "drive:1", "driveroot", "vac").map { it.key })
        assertEquals(2, visibleFavoriteAlbums(all,
            "mail:1", "mailroot", "drive:1", "driveroot", "Models").size)
        assertEquals(listOf("1:alba", "0:alba"), visibleFavoriteAlbums(all,
            "mail:1", "mailroot", "drive:1", "driveroot", "", true).map { it.key })
    }

    @Test fun disconnectedAndDifferentAccountsAreNeverDisplayed() {
        assertEquals(listOf("0:alba"), visibleFavoriteAlbums(listOf(mail, drive),
            "mail:1", "mailroot", "drive:another", "driveroot", "").map { it.key })
        assertTrue(visibleFavoriteAlbums(listOf(mail, drive),
            "mail:1", null, "drive:1", null, "").isEmpty())
        assertTrue(visibleFavoriteAlbums(listOf(mail),
            "mail:1", "different-root", "drive:1", "driveroot", "").isEmpty())
    }
}
