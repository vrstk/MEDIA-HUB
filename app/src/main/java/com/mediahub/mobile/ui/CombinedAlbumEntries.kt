package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import java.util.Locale

/**
 * Display-only merged album index for the two connected roots.
 * IDs are scoped to their provider: equal folder names or opaque IDs are NOT de-duplicated.
 */
internal data class CombinedAlbum(val source: Int, val node: CloudNode) {
    val key: String get() = "${source}:${node.id}"
}

internal fun mergeRootAlbums(
    mailNodes: List<CloudNode>,
    driveNodes: List<CloudNode>,
    query: String
): List<CombinedAlbum> {
    val term = query.trim()
    return (mailNodes.asSequence().filter { it.isFolder }.map { CombinedAlbum(0, it) } +
            driveNodes.asSequence().filter { it.isFolder }.map { CombinedAlbum(1, it) })
        .filter { term.isBlank() || it.node.name.contains(term, ignoreCase = true) }
        .sortedWith(compareBy<CombinedAlbum> { it.node.name.lowercase(Locale.ROOT) }
            .thenBy { it.source }
            .thenBy { it.node.id })
        .toList()
}
