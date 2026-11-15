package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySortTest {
    private fun node(id: String, name: String, folder: Boolean = false) =
        CloudNode(id, name, folder, if (folder) null else 100L)

    @Test fun reverseNameOrderNeverPutsPhotosBeforeFolders() {
        val entries = listOf(node("p1", "Z.jpg"), node("d1", "Alpha", true),
            node("d2", "zebra", true), node("p2", "A.jpg"))
        assertEquals(listOf("d2", "d1", "p1", "p2"),
            albumBrowseItems(entries, "", AlbumContentFilter.ALL, true).map { it.id })
        assertEquals(listOf("d1", "d2", "p2", "p1"),
            albumBrowseItems(entries, "", AlbumContentFilter.ALL).map { it.id })
    }

    @Test fun sourceIdsRemainDistinctInBothNameOrders() {
        val a = node("same", "Models", true)
        for (descending in listOf(false, true)) {
            assertEquals(listOf("0:same", "1:same"),
                mergeRootAlbums(listOf(a), listOf(a), "", descending).map { it.key })
        }
    }

    @Test fun reverseSortStillRespectsSearchAndSupportedImageTypes() {
        val entries = listOf(node("1", "photo.jpg"),
            node("2", "photos", true), node("3", "photo.mp4"))
        assertEquals(listOf("2", "1"),
            albumBrowseItems(entries, " PHOTO ", AlbumContentFilter.ALL, true).map { it.id })
        assertTrue(albumBrowseItems(entries, "missing", AlbumContentFilter.ALL, true).isEmpty())
    }
}
