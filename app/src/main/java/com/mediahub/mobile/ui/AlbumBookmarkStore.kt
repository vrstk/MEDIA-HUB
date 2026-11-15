package com.mediahub.mobile.ui

import android.content.Context
import com.mediahub.mobile.domain.CloudNode
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Local, opt-in bookmarks. Never stores passwords, access tokens or photo bytes. */
internal data class AlbumBookmark(val scope: String, val trail: List<CloudNode>) {
    val id: String get() = trail.last().id
    val title: String get() = trail.last().name
}

internal object AlbumBookmarkCodec {
    private const val MAX_BOOKMARKS = 80
    private const val MAX_DEPTH = 33
    private const val MAX_TEXT = 4096
    private const val MAX_STORE_LENGTH = 1024 * 1024

    fun encode(bookmarks: List<AlbumBookmark>): String {
        val array = JSONArray()
        bookmarks.take(MAX_BOOKMARKS).forEach { bookmark ->
            if (bookmark.trail.isEmpty() || bookmark.trail.size > MAX_DEPTH ||
                bookmark.scope.length !in 1..MAX_TEXT) return@forEach
            val parents = JSONArray()
            bookmark.trail.forEach { node ->
                if (node.id.length !in 1..MAX_TEXT || node.name.length > MAX_TEXT || !node.isFolder)
                    return@forEach
                parents.put(JSONObject().put("id", node.id).put("name", node.name))
            }
            if (parents.length() != bookmark.trail.size) return@forEach
            array.put(JSONObject().put("scope", bookmark.scope).put("trail", parents))
        }
        return array.toString()
    }

    fun decode(raw: String?): List<AlbumBookmark> {
        if (raw.isNullOrBlank() || raw.length > MAX_STORE_LENGTH) return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<AlbumBookmark>()
            val seen = HashSet<Pair<String, String>>()
            for (i in 0 until minOf(array.length(), MAX_BOOKMARKS)) {
                val record = array.optJSONObject(i) ?: continue
                val scope = record.optString("scope", "")
                val items = record.optJSONArray("trail") ?: continue
                if (scope.length !in 1..MAX_TEXT ||
                    items.length() !in 2..MAX_DEPTH) continue
                val trail = ArrayList<CloudNode>()
                var valid = true
                for (j in 0 until items.length()) {
                    val node = items.optJSONObject(j)
                    if (node == null) { valid = false; break }
                    val id = node.optString("id", "")
                    val name = node.optString("name", "")
                    if (id.length !in 1..MAX_TEXT || name.length > MAX_TEXT) {
                        valid = false
                        break
                    }
                    trail.add(CloudNode(id, name, true, null))
                }
                if (!valid) continue
                if (seen.add(scope to trail.last().id)) result.add(AlbumBookmark(scope, trail))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Scope prevents accidental use of Mail bookmarks from another account / SAF tree. */
internal fun albumBookmarkScope(source: String, identity: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest((source + ":" + identity).toByteArray(StandardCharsets.UTF_8))
    return source + ":" + bytes.take(16).joinToString("") { "%02x".format(it) }
}

/** Toggle is pure and deterministic; most recently added albums appear first. */
internal fun toggleAlbumBookmark(
    current: List<AlbumBookmark>,
    bookmark: AlbumBookmark
): List<AlbumBookmark> {
    if (bookmark.trail.size < 2 || bookmark.trail.any { !it.isFolder }) return current
    val rest = current.filterNot { it.scope == bookmark.scope && it.id == bookmark.id }
    return if (rest.size == current.size) (listOf(bookmark) + rest).take(80) else rest
}

internal class AlbumBookmarkStore(context: Context) {
    private val prefs = context.getSharedPreferences("mediahub_album_bookmarks", Context.MODE_PRIVATE)
    fun load(): List<AlbumBookmark> = AlbumBookmarkCodec.decode(prefs.getString("albums_v1", null))
    fun save(bookmarks: List<AlbumBookmark>) {
        prefs.edit().putString("albums_v1", AlbumBookmarkCodec.encode(bookmarks)).apply()
    }
}
