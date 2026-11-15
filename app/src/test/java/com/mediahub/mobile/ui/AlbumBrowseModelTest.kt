package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumBrowseModelTest {
    private fun node(id: String, name: String, folder: Boolean = false) =
        CloudNode(id, name, folder, if (folder) null else 123L)

    @Test fun displaysOnlyFoldersAndSupportedPhotos() {
        val rows = listOf(node("1", "a.pdf"), node("2", "C.jpg"),
            node("3", "Photos", true), node("4", "B.WEBP"), node("5", "a.mp4"))
        assertEquals(listOf("3", "4", "2"),
            albumBrowseItems(rows, "", AlbumContentFilter.ALL).map { it.id })
    }

    @Test fun filtersAcrossImageAndFolderNames() {
        val rows = listOf(node("1", "Model.jpg"), node("2", "Model sets", true),
            node("3", "other.jpg"))
        assertEquals(listOf("2"),
            albumBrowseItems(rows, " model ", AlbumContentFilter.FOLDERS).map { it.id })
        assertEquals(listOf("1"),
            albumBrowseItems(rows, "MODEL", AlbumContentFilter.PHOTOS).map { it.id })
        assertTrue(albumBrowseItems(rows, "not found", AlbumContentFilter.ALL).isEmpty())
    }

    @Test fun nonReadableImagesAreExcluded() {
        val remote = CloudNode("1", "private.jpg", false, null, canReadBytes = false)
        assertTrue(albumBrowseItems(listOf(remote), "", AlbumContentFilter.PHOTOS).isEmpty())
    }
}
