package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode
import java.util.Locale

/** Display-only filtering and sorting; cloud contents and provider order are never changed. */
internal fun filterAndSortCloudNodes(nodes: List<CloudNode>, query: String): List<CloudNode> {
    val needle = query.trim()
    return nodes.asSequence()
        .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
        .sortedWith(
            compareBy<CloudNode> { !it.isFolder }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.id }
        )
        .toList()
}
