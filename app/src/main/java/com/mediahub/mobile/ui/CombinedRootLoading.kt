package com.mediahub.mobile.ui

/**
 * The favorites list is held in local preferences. Only the "Все папки"
 * overview requires an on-demand listing of each connected provider's root.
 */
internal fun shouldListCombinedRoot(favoritesOnly: Boolean, connected: Boolean): Boolean =
    !favoritesOnly && connected
