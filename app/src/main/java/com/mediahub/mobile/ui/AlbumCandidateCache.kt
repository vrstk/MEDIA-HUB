package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode

/**
 * A bounded, in-memory cache of small cover-candidate lists.
 * Entries contain cloud metadata only and are never stored on disk.
 */
internal class AlbumCandidateCache(private val maxFolders: Int = 80) {
    init { require(maxFolders > 0) }

    private val values = object : LinkedHashMap<String, List<CloudNode>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<CloudNode>>?
        ): Boolean = size > maxFolders
    }

    @Synchronized fun get(folderId: String): List<CloudNode>? = values[folderId]
    @Synchronized fun put(folderId: String, candidates: List<CloudNode>) {
        values[folderId] = candidates.take(3).toList()
    }
    @Synchronized fun clear() { values.clear() }
    @Synchronized fun size(): Int = values.size
}
