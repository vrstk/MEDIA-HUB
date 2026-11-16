package com.mediahub.mobile.ui

import com.mediahub.mobile.domain.CloudNode

/**
 * Navigation shortcuts are derived from metadata already stored on this device.
 * No recursive scans or cloud requests are needed to populate the shortcuts.
 */
internal data class RecentAlbumShortcut(
    val source: Int,
    val trail: List<CloudNode>
) {
    val title: String get() = trail.last().name
    val key: String get() = "${source}:${trail.last().id}"
}

internal fun recentAlbumShortcuts(
    navigation: List<AlbumNavigationState>,
    mailScope: String,
    mailRoot: CloudNode?,
    driveScope: String,
    driveRoot: CloudNode?
): List<RecentAlbumShortcut> {
    val result = ArrayList<RecentAlbumShortcut>(2)
    for ((source, scope, root) in listOf(
        Triple(0, mailScope, mailRoot),
        Triple(1, driveScope, driveRoot)
    )) {
        if (root == null) continue
        val saved = navigation.firstOrNull { it.scope == scope } ?: continue
        if (saved.trail.size < 2 || saved.trail.size > 33 ||
            saved.trail.first().id != root.id || saved.trail.any { !it.isFolder }) continue
        result.add(RecentAlbumShortcut(source, listOf(root) + saved.trail.drop(1)))
    }
    return result
}
