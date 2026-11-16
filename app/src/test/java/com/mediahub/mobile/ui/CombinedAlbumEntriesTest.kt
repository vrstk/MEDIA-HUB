package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedAlbumEntriesTest {
    private fun folder(id: String, name: String) = CloudNode(id, name, true, null)
    private fun photo(id: String) = CloudNode(id, "photo.jpg", false, 20)

    @Test fun keepsSameNamedFoldersFromBothClouds() {
        val merged = mergeRootAlbums(
            listOf(folder("same", "Models"), photo("photo")),
            listOf(folder("same", "Models")), ""
        )
        assertEquals(2, merged.size)
        assertEquals(listOf("0:same", "1:same"), merged.map { it.key })
    }

    @Test fun sortsAcrossProvidersAndFiltersCaseInsensitively() {
        val merged = mergeRootAlbums(
            listOf(folder("z", "Zebra"), folder("b", "Альбом")),
            listOf(folder("a", "apple"), folder("c", "MATERIAL")), ""
        )
        assertEquals(listOf("apple", "MATERIAL", "Zebra", "Альбом"),
            merged.map { it.node.name })
        assertEquals(listOf("MATERIAL"),
            mergeRootAlbums(listOf(folder("z","Zebra")),
                listOf(folder("c","MATERIAL")), "  terI ").map { it.node.name })
    }

    @Test fun doesNotIncludeFilesOrMutateInputs() {
        val mail = listOf(photo("p"), folder("f", "Folder"))
        val result = mergeRootAlbums(mail, emptyList(), "")
        assertEquals(listOf("Folder"), result.map { it.node.name })
        assertEquals("p", mail.first().id)
        assertTrue(mergeRootAlbums(emptyList(), emptyList(), "anything").isEmpty())
    }
}
