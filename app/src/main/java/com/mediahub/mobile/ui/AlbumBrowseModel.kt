package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.media.MediaFileKinds
import java.util.Locale

internal enum class AlbumContentFilter { ALL, FOLDERS, PHOTOS }

/**
 * Current-folder view only. No recursive scan, modification or download.
 * Non-photo files remain accessible in the "Файлы" screen.
 */
internal fun albumBrowseItems(
    nodes: List<CloudNode>,
    query: String,
    filter: AlbumContentFilter,
    descending: Boolean = false
): List<CloudNode> {
    val visible = nodes.asSequence()
        .filter { query.trim().isEmpty() || it.name.contains(query.trim(), ignoreCase = true) }
        .filter { node ->
            when (filter) {
                AlbumContentFilter.ALL -> node.isFolder || MediaFileKinds.isPhoto(node)
                AlbumContentFilter.FOLDERS -> node.isFolder
                AlbumContentFilter.PHOTOS -> MediaFileKinds.isPhoto(node)
            }
        }.toList()
    return visible.sortedWith { a, b ->
        val typeOrder = (!a.isFolder).compareTo(!b.isFolder)
        if (typeOrder != 0) typeOrder else {
            val nameOrder = a.name.lowercase(Locale.ROOT)
                .compareTo(b.name.lowercase(Locale.ROOT))
            if (nameOrder != 0) {
                if (descending) -nameOrder else nameOrder
            } else a.id.compareTo(b.id)
        }
    }
}
