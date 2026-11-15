package com.mediahub.mobile.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CloudRead
import com.mediahub.mobile.domain.readBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException

internal const val MAX_PHOTO_PREVIEW_BYTES = 16 * 1024 * 1024
private const val MAX_THUMBNAIL_BYTES = 5 * 1024 * 1024
private const val PHOTO_TOO_LARGE = "Фотография слишком большая для предпросмотра."

internal fun CloudRead.readPhotoBytes(maxBytes: Int): ByteArray =
    stream.readBounded(sizeBytes, maxBytes, PHOTO_TOO_LARGE)

/** A small cache belongs to the current provider. Full previews are held by their pages. */
internal class PhotoPreviewLoader(private val readBytes: (CloudNode, Int) -> ByteArray) {
    private val thumbnailReads = Semaphore(2)
    private val cache = object : LruCache<CloudNode, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: CloudNode, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    fun cachedThumbnail(file: CloudNode): Bitmap? = cache.get(file)

    suspend fun thumbnail(file: CloudNode): Bitmap = cachedThumbnail(file) ?: thumbnailReads.withPermit {
        cachedThumbnail(file) ?: decode(file, MAX_THUMBNAIL_BYTES, 384).also { cache.put(file, it) }
    }

    suspend fun preview(file: CloudNode): Bitmap = decode(file, MAX_PHOTO_PREVIEW_BYTES, 2048)

    private suspend fun decode(file: CloudNode, maxBytes: Int, maxSide: Int): Bitmap {
        if (file.sizeBytes != null && file.sizeBytes > maxBytes) {
            throw IOException(PHOTO_TOO_LARGE)
        }
        val bytes = runInterruptible(Dispatchers.IO) { readBytes(file, maxBytes) }
        return withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (maxDimension.toLong() > maxSide.toLong() * sample) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
            }) ?: throw IOException("Не удалось прочитать изображение.")
        }
    }
}
