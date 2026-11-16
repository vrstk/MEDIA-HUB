package com.mediahub.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedRootLoadingTest {
    @Test fun favoritesModeNeverListsCloudRoots() {
        assertFalse(shouldListCombinedRoot(favoritesOnly = true, connected = true))
        assertFalse(shouldListCombinedRoot(favoritesOnly = true, connected = false))
    }

    @Test fun folderModeListsOnlyConnectedSources() {
        assertTrue(shouldListCombinedRoot(favoritesOnly = false, connected = true))
        assertFalse(shouldListCombinedRoot(favoritesOnly = false, connected = false))
    }
}
