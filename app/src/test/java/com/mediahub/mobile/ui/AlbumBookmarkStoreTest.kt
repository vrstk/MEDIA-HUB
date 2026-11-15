package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumBookmarkStoreTest {
    private fun node(id: String, name: String) = CloudNode(id, name, true, null)
    private val album = AlbumBookmark("mail:test", listOf(
        node("/", "Облако Mail"), node("/Models/", "Models"), node("/Models/Alba/", "Alba")
    ))

    @Test fun roundTripKeepsUnicodeAndHierarchy() {
        assertEquals(listOf(album), AlbumBookmarkCodec.decode(AlbumBookmarkCodec.encode(listOf(album))))
    }

    @Test fun emptyAndMalformedEntriesAreIgnored() {
        assertTrue(AlbumBookmarkCodec.decode(null).isEmpty())
        assertTrue(AlbumBookmarkCodec.decode("not-json").isEmpty())
        assertTrue(AlbumBookmarkCodec.decode("""[{"scope":"mail","trail":[]}]""").isEmpty())
        assertTrue(AlbumBookmarkCodec.decode("a".repeat(1024 * 1024 + 1)).isEmpty())
    }

    @Test fun togglingDoesNotRemoveOtherAccountsOrOverwriteExistingBookmarks() {
        val other = album.copy(scope = "mail:other")
        val added = toggleAlbumBookmark(listOf(other), album)
        assertEquals(listOf(album, other), added)
        assertEquals(listOf(other), toggleAlbumBookmark(added, album))
    }

    @Test fun bookmarkScopesAreSeparated() {
        assertEquals(albumBookmarkScope("mail", "mail@example.com"),
            albumBookmarkScope("mail", "mail@example.com"))
        assertFalse(albumBookmarkScope("mail", "mail@example.com") ==
            albumBookmarkScope("mail", "other@example.com"))
        assertFalse(albumBookmarkScope("drive", "a") == albumBookmarkScope("mail", "a"))
    }

    @Test fun rejectsFileNodesAndInvalidTrail() {
        val fileNode = CloudNode("/x.jpg", "x.jpg", false, 5)
        assertEquals(listOf(album), toggleAlbumBookmark(listOf(album),
            AlbumBookmark("mail:test", listOf(node("/", "Root"), fileNode))))
    }
}
