package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CloudProvider
import com.mediahub.mobile.media.PhotoPreviewLoader
import com.mediahub.mobile.media.readPhotoBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore

/**
 * Shared for the lifetime of one connected provider instance.
 * Keeps a 16 MiB bitmap LRU (inside PhotoPreviewLoader) and at most 80
 * first-page folder summaries. Replaced when the provider/account changes.
 */
internal class AlbumThumbnailSession(val provider: CloudProvider) {
    val loader = PhotoPreviewLoader { file, maxBytes ->
        provider.openRead(file).use { it.readPhotoBytes(maxBytes) }
    }
    val semaphore = Semaphore(2)
    private val candidates = AlbumCandidateCache(maxFolders = 80)

    suspend fun folderCandidates(folderId: String): List<CloudNode> {
        candidates.get(folderId)?.let { return it }
        val loaded = runInterruptible(Dispatchers.IO) {
            // Only first page, no recursive scan.
            autoCoverCandidates(provider.list(folderId).nodes)
        }
        candidates.put(folderId, loaded)
        return loaded
    }

    fun invalidateCandidates() { candidates.clear() }
}
