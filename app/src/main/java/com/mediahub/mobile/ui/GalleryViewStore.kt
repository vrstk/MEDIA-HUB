package com.mediahub.mobile.ui

import android.content.Context

/** Local display preferences only; never reads or changes remote files. */
internal data class GalleryViewOptions(
    val compact: Boolean = false,
    val descending: Boolean = false
)

internal class GalleryViewStore(context: Context) {
    private val prefs = context.getSharedPreferences("mediahub_gallery_view", Context.MODE_PRIVATE)

    fun read() = GalleryViewOptions(
        compact = prefs.getBoolean("compact", false),
        descending = prefs.getBoolean("descending", false)
    )

    fun save(options: GalleryViewOptions) {
        prefs.edit()
            .putBoolean("compact", options.compact)
            .putBoolean("descending", options.descending)
            .apply()
    }
}
