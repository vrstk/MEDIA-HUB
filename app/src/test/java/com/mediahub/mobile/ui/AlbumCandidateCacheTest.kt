package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumCandidateCacheTest {
    private fun pic(id: String) = CloudNode(id, "$id.jpg", false, 50)

    @Test fun leastRecentlyUsedEntryEvictedAtLimit() {
        val cache = AlbumCandidateCache(2)
        cache.put("folder-a", listOf(pic("a")))
        cache.put("folder-b", listOf(pic("b")))
        cache.get("folder-a")
        cache.put("folder-c", listOf(pic("c")))
        assertNull(cache.get("folder-b"))
        assertEquals(listOf("a"), cache.get("folder-a")?.map { it.id })
        assertEquals(listOf("c"), cache.get("folder-c")?.map { it.id })
        assertEquals(2, cache.size())
    }

    @Test fun candidateListIsBoundedAndCopied() {
        val cache = AlbumCandidateCache()
        val original = mutableListOf(pic("1"), pic("2"), pic("3"), pic("4"))
        cache.put("album", original)
        original.clear()
        assertEquals(listOf("1", "2", "3"), cache.get("album")?.map { it.id })
    }

    @Test fun clearingRemovesEntriesAndEmptyCandidateListsAreMemoized() {
        val cache = AlbumCandidateCache(3)
        cache.put("empty-folder", emptyList())
        assertTrue(cache.get("empty-folder")?.isEmpty() == true)
        cache.clear()
        assertNull(cache.get("empty-folder"))
        assertEquals(0, cache.size())
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonpositiveCapacityRejected() {
        AlbumCandidateCache(0)
    }
}
