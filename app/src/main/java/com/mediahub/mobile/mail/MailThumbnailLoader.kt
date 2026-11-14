package com.mediahub.mobile.mail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Only thumbnails for visible grid cells are requested.
 * Network reads are bounded, simultaneous downloads capped at two, and
 * decoded pixels are downsampled before entering a small in-memory cache.
 */
class MailThumbnailLoader(private val client: MailWebDavClient) {
    private val permits = Semaphore(2)
    private val cache = object : LruCache<DavEntry, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: DavEntry, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    suspend fun load(file: DavEntry): Bitmap? {
        cache.get(file)?.let { return it }
        return permits.withPermit {
            cache.get(file)?.let { return@withPermit it }
            val raw = runInterruptible(Dispatchers.IO) {
                client.previewImage(file.path, maxBytes = 5 * 1024 * 1024)
            }
            val thumb = withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    null
                } else {
                    var sample = 1
                    while (bounds.outWidth / sample > 384 ||
                        bounds.outHeight / sample > 384
                    ) sample *= 2
                    BitmapFactory.decodeByteArray(
                        raw, 0, raw.size,
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                    )
                }
            }
            if (thumb != null) cache.put(file, thumb)
            thumb
        }
    }
}
