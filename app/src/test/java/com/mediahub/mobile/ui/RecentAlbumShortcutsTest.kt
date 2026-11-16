package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentAlbumShortcutsTest {
    private fun folder(id: String, name: String = id) = CloudNode(id, name, true, null)
    private val mr = folder("mail-root")
    private val dr = folder("drive-root")
    private val mailTrail = listOf(mr, folder("models", "Models"), folder("alba", "Alba"))
    private val driveTrail = listOf(dr, folder("models", "Models"))

    @Test fun returnsOneShortcutPerConnectedSource() {
        val states = listOf(
            AlbumNavigationState("mail:a", mailTrail),
            AlbumNavigationState("drive:b", driveTrail))
        val shortcuts = recentAlbumShortcuts(states, "mail:a", mr, "drive:b", dr)
        assertEquals(listOf("0:alba", "1:models"), shortcuts.map { it.key })
        assertEquals(listOf("Alba", "Models"), shortcuts.map { it.title })
    }

    @Test fun ignoresDisconnectedAccountsAndChangedRoots() {
        val states = listOf(AlbumNavigationState("mail:a", mailTrail))
        assertTrue(recentAlbumShortcuts(states, "mail:other", mr, "drive:b", dr).isEmpty())
        assertTrue(recentAlbumShortcuts(states, "mail:a", folder("new-root"),
            "drive:b", null).isEmpty())
    }

    @Test fun ignoresRootOnlyAndInvalidSavedPaths() {
        val states = listOf(AlbumNavigationState("mail:a", listOf(mr)),
            AlbumNavigationState("drive:b", listOf(dr, CloudNode("file", "photo.jpg", false, 99))))
        assertTrue(recentAlbumShortcuts(states, "mail:a", mr, "drive:b", dr).isEmpty())
    }
}
