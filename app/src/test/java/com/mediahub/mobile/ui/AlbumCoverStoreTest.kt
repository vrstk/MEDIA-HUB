package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumCoverStoreTest {
    private fun photo(id: String, name: String = "cover.jpg", size: Long? = 200L) =
        CloudNode(id, name, false, size)

    @Test fun roundTripUnicodeAndSourceScopes() {
        val covers = listOf(
            AlbumCover("mail:one", "/Галереи/", photo("/Галереи/01.jpg", "Лето.jpg")),
            AlbumCover("drive:other", "content://album", photo("content://image", "Sunset.WEBP", null))
        )
        assertEquals(covers, AlbumCoverCodec.decode(AlbumCoverCodec.encode(covers)))
    }

    @Test fun updateAndRemoveCoverDoNotChangeAnotherStorage() {
        val prior = listOf(AlbumCover("mail:a", "dir", photo("one")),
            AlbumCover("drive:b", "dir", photo("other")))
        val updated = setAlbumCover(prior, "mail:a", "dir", photo("two"))
        assertEquals("two", updated.first().photo.id)
        assertEquals("other", updated.last().photo.id)
        assertEquals(listOf(prior.last()), setAlbumCover(updated, "mail:a", "dir", null))
    }

    @Test fun malformedOrInvalidContentIsIgnored() {
        assertTrue(AlbumCoverCodec.decode("{broken").isEmpty())
        assertTrue(AlbumCoverCodec.decode(null).isEmpty())
        assertTrue(AlbumCoverCodec.decode("a".repeat(1024*1024+1)).isEmpty())
        assertTrue(AlbumCoverCodec.decode("""[{"scope":"x","folder":"abc","id":"0","name":"note.txt"}]""").isEmpty())
        assertTrue(setAlbumCover(emptyList(), "x", "folder", photo("x", "not-image.txt")).isEmpty())
    }

    @Test fun autoSelectionSkipsTooLargeFilesAndLimitsAttempts() {
        val input = listOf(
            CloudNode("d", "dir", true, null),
            photo("big", size=6L * 1024 * 1024),
            photo("a"), photo("b"), photo("c"), photo("d")
        )
        assertEquals(listOf("a", "b", "c"), autoCoverCandidates(input).map { it.id })
    }
}
