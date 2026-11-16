package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBrowserEntriesTest {
    private fun entry(id: String, name: String, folder: Boolean = false) =
        CloudNode(id, name, folder, if (folder) null else 10L)

    @Test fun foldersFirstAndNamesCaseInsensitive() {
        val input = listOf(entry("c", "Zoo.jpg"), entry("a", "beta", true),
            entry("b", "Alpha", true), entry("d", "apple.JPG"))
        assertEquals(listOf("b", "a", "d", "c"),
            filterAndSortCloudNodes(input, "").map { it.id })
        assertEquals("c", input.first().id)
    }

    @Test fun trimsSearchAndMatchesWithoutCaseSensitivity() {
        val input = listOf(entry("a", "Family.jpg"), entry("b", "My FAMILY", true),
            entry("c", "Holiday.jpg"))
        assertEquals(listOf("b", "a"),
            filterAndSortCloudNodes(input, "  fAmIlY ").map { it.id })
        assertTrue(filterAndSortCloudNodes(input, "not found").isEmpty())
    }

    @Test fun duplicateNamesAreDeterministic() {
        assertEquals(listOf("a", "z"),
            filterAndSortCloudNodes(listOf(entry("z", "a.jpg"), entry("a", "a.jpg")), "")
                .map { it.id })
    }
}
