package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumNavigationStoreTest {
    private fun folder(id: String, name: String = id) = CloudNode(id, name, true, null)
    private val root = folder("/", "Mail")
    private val path = listOf(root, folder("/Models", "Модели"), folder("/Models/Alba", "Alba"))

    @Test fun recordsPathAndScrollForNestedFolders() {
        var records = saveAlbumTrail(emptyList(), "mail:one", path)
        records = saveAlbumPosition(records, "mail:one", root, "/", 35, 12)
        records = saveAlbumPosition(records, "mail:one", root, "/Models", 5, 29)
        assertEquals(path, restoredAlbumTrail(records, "mail:one", root))
        assertEquals(35, savedAlbumPosition(records, "mail:one", "/")?.index)
        assertEquals(29, savedAlbumPosition(records, "mail:one", "/Models")?.offset)
        assertEquals(records, AlbumNavigationCodec.decode(AlbumNavigationCodec.encode(records)))
    }

    @Test fun scopeSeparatesDifferentAccountsAndRoots() {
        val saved = saveAlbumTrail(emptyList(), "drive:one", path)
        assertEquals(listOf(folder("another", "Different")),
            restoredAlbumTrail(saved, "drive:one", folder("another", "Different")))
        assertEquals(listOf(root), restoredAlbumTrail(saved, "mail:two", root))
        assertNull(savedAlbumPosition(saved, "mail:two", "/"))
    }

    @Test fun keepsOtherSourceAndMostRecentNavigation() {
        val first = saveAlbumTrail(emptyList(), "mail:a", path)
        val both = saveAlbumTrail(first, "drive:b", listOf(folder("d", "Drive")))
        assertEquals("drive:b", both.first().scope)
        assertEquals(path, restoredAlbumTrail(both, "mail:a", root))
    }

    @Test fun corruptAndOversizeInputIsIgnored() {
        assertTrue(AlbumNavigationCodec.decode("not-json").isEmpty())
        assertTrue(AlbumNavigationCodec.decode("x".repeat(512*1024+1)).isEmpty())
        assertTrue(AlbumNavigationCodec.decode("""[{"scope":"s","trail":[]}]""").isEmpty())
    }

    @Test fun limitsDepthAndNumberOfSavedFolderPositions() {
        val tooLong = (0..34).map { folder("id$it") }
        assertTrue(saveAlbumTrail(emptyList(), "mail:a", tooLong).isEmpty())
        var state = saveAlbumTrail(emptyList(), "mail:a", path)
        for (i in 0..70) {
            state = saveAlbumPosition(state, "mail:a", root, "id$i", i, 0)
        }
        assertEquals(48, state.single().positions.size)
        assertNull(savedAlbumPosition(state, "mail:a", "id0"))
    }
}
