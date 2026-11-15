package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.media.MediaFileKinds

internal enum class AlbumContentFilter { ALL, FOLDERS, PHOTOS }

/**
 * Current-folder view only. No recursive scan, modification or download.
 * Non-photo files remain accessible in the "Файлы" screen.
 */
internal fun albumBrowseItems(
    nodes: List<CloudNode>,
    query: String,
    filter: AlbumContentFilter
): List<CloudNode> = filterAndSortCloudNodes(nodes, query).filter { node ->
    when (filter) {
        AlbumContentFilter.ALL -> node.isFolder || MediaFileKinds.isPhoto(node)
        AlbumContentFilter.FOLDERS -> node.isFolder
        AlbumContentFilter.PHOTOS -> MediaFileKinds.isPhoto(node)
    }
}
