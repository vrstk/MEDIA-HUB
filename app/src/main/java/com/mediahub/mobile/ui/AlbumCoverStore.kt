package com.mediahub.mobile.ui

import android.content.Context
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.media.MediaFileKinds
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-selected album cover metadata only. No image bytes or cloud credentials.
 * Keys are scoped to the connected source/account, not the current screen.
 */
internal data class AlbumCover(val scope: String, val folderId: String, val photo: CloudNode)

internal object AlbumCoverCodec {
    private const val LIMIT = 128
    private const val MAX_TEXT = 4096
    private const val MAX_JSON = 1024 * 1024

    private fun valid(cover: AlbumCover): Boolean =
        cover.scope.length in 1..MAX_TEXT &&
        cover.folderId.length in 1..MAX_TEXT &&
        cover.photo.id.length in 1..MAX_TEXT &&
        cover.photo.name.length in 1..MAX_TEXT &&
        (cover.photo.mimeType?.length ?: 0) <= 128 &&
        MediaFileKinds.isPhoto(cover.photo)

    fun encode(covers: List<AlbumCover>): String {
        val array = JSONArray()
        covers.filter(::valid).take(LIMIT).forEach { cover ->
            array.put(JSONObject()
                .put("scope", cover.scope)
                .put("folder", cover.folderId)
                .put("id", cover.photo.id)
                .put("name", cover.photo.name)
                .put("size", cover.photo.sizeBytes ?: JSONObject.NULL)
                .put("mime", cover.photo.mimeType ?: JSONObject.NULL))
        }
        return array.toString()
    }

    fun decode(raw: String?): List<AlbumCover> {
        if (raw.isNullOrBlank() || raw.length > MAX_JSON) return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<AlbumCover>()
            val seen = HashSet<Pair<String, String>>()
            for (i in 0 until minOf(array.length(), LIMIT)) {
                val obj = array.optJSONObject(i) ?: continue
                val mime = obj.optString("mime", "").takeIf { it.isNotBlank() && it != "null" }
                val size = if (obj.isNull("size")) null else obj.optLong("size", -1L).takeIf { it >= 0L }
                val photo = CloudNode(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", ""),
                    isFolder = false,
                    sizeBytes = size,
                    mimeType = mime
                )
                val item = AlbumCover(
                    scope = obj.optString("scope", ""),
                    folderId = obj.optString("folder", ""),
                    photo = photo
                )
                if (valid(item) && seen.add(item.scope to item.folderId)) result.add(item)
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }
}

internal fun setAlbumCover(
    existing: List<AlbumCover>,
    scope: String,
    folderId: String,
    photo: CloudNode?
): List<AlbumCover> {
    val others = existing.filterNot { it.scope == scope && it.folderId == folderId }
    if (scope.isBlank() || folderId.isBlank() || photo == null ||
        !MediaFileKinds.isPhoto(photo)) return others
    return (listOf(AlbumCover(scope, folderId, photo)) + others).take(128)
}

internal fun autoCoverCandidates(nodes: List<CloudNode>): List<CloudNode> =
    nodes.asSequence()
        .filter(MediaFileKinds::isPhoto)
        .filter { it.sizeBytes == null || it.sizeBytes <= 5L * 1024 * 1024 }
        .take(3)
        .toList()

internal class AlbumCoverStore(context: Context) {
    private val preferences = context.getSharedPreferences("mediahub_album_covers", Context.MODE_PRIVATE)
    fun load(): List<AlbumCover> = AlbumCoverCodec.decode(preferences.getString("selected_v1", null))
    fun save(value: List<AlbumCover>) {
        preferences.edit().putString("selected_v1", AlbumCoverCodec.encode(value)).apply()
    }
}
