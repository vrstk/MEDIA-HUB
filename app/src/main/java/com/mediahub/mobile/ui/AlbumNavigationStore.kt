package com.mediahub.mobile.ui

import android.content.Context
import com.mediahub.mobile.domain.CloudNode
import org.json.JSONArray
import org.json.JSONObject

/** Local navigation metadata only. No file bytes, credentials or network access. */
internal data class AlbumScrollPosition(val folderId: String, val index: Int, val offset: Int)
internal data class AlbumNavigationState(
    val scope: String,
    val trail: List<CloudNode>,
    val positions: List<AlbumScrollPosition> = emptyList()
)

internal object AlbumNavigationCodec {
    private const val MAX_SOURCES = 10
    private const val MAX_DEPTH = 33
    private const val MAX_POSITIONS = 48
    private const val MAX_TEXT = 4096
    private const val MAX_JSON = 512 * 1024
    private const val MAX_SCROLL = 1_000_000

    private fun validTrail(trail: List<CloudNode>): Boolean =
        trail.size in 1..MAX_DEPTH &&
        trail.all { it.isFolder && it.id.length in 1..MAX_TEXT && it.name.length <= MAX_TEXT }

    fun encode(states: List<AlbumNavigationState>): String {
        val root = JSONArray()
        states.take(MAX_SOURCES).forEach { state ->
            if (state.scope.length !in 1..MAX_TEXT || !validTrail(state.trail)) return@forEach
            val path = JSONArray()
            state.trail.forEach { node ->
                path.put(JSONObject().put("id", node.id).put("name", node.name))
            }
            val positions = JSONArray()
            state.positions.take(MAX_POSITIONS).forEach { position ->
                if (position.folderId.length in 1..MAX_TEXT &&
                    position.index in 0..MAX_SCROLL && position.offset in 0..MAX_SCROLL
                ) {
                    positions.put(JSONObject().put("id", position.folderId)
                        .put("i", position.index).put("o", position.offset))
                }
            }
            root.put(JSONObject().put("scope", state.scope)
                .put("trail", path).put("positions", positions))
        }
        return root.toString()
    }

    fun decode(raw: String?): List<AlbumNavigationState> {
        if (raw.isNullOrBlank() || raw.length > MAX_JSON) return emptyList()
        return try {
            val array = JSONArray(raw)
            val output = ArrayList<AlbumNavigationState>()
            val seen = HashSet<String>()
            for (i in 0 until minOf(array.length(), MAX_SOURCES)) {
                val item = array.optJSONObject(i) ?: continue
                val scope = item.optString("scope", "")
                if (scope.length !in 1..MAX_TEXT || !seen.add(scope)) continue
                val nodes = item.optJSONArray("trail") ?: continue
                if (nodes.length() !in 1..MAX_DEPTH) continue
                val trail = ArrayList<CloudNode>()
                for (j in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(j) ?: break
                    trail.add(CloudNode(node.optString("id", ""),
                        node.optString("name", ""), true, null))
                }
                if (trail.size != nodes.length() || !validTrail(trail)) continue
                val positions = ArrayList<AlbumScrollPosition>()
                val seenFolders = HashSet<String>()
                val saved = item.optJSONArray("positions")
                if (saved != null) {
                    for (j in 0 until minOf(saved.length(), MAX_POSITIONS)) {
                        val value = saved.optJSONObject(j) ?: continue
                        val id = value.optString("id", "")
                        val index = value.optInt("i", -1)
                        val offset = value.optInt("o", -1)
                        if (id.length in 1..MAX_TEXT && index in 0..MAX_SCROLL &&
                            offset in 0..MAX_SCROLL && seenFolders.add(id)) {
                            positions.add(AlbumScrollPosition(id, index, offset))
                        }
                    }
                }
                output.add(AlbumNavigationState(scope, trail, positions))
            }
            output
        } catch (_: Exception) {
            emptyList()
        }
    }
}

internal fun restoredAlbumTrail(
    states: List<AlbumNavigationState>, scope: String, root: CloudNode
): List<CloudNode> {
    val path = states.firstOrNull { it.scope == scope }?.trail.orEmpty()
    if (path.isEmpty() || path.first().id != root.id ||
        path.any { !it.isFolder }) return listOf(root)
    return listOf(root) + path.drop(1)
}

internal fun savedAlbumPosition(
    states: List<AlbumNavigationState>, scope: String, folderId: String
): AlbumScrollPosition? =
    states.firstOrNull { it.scope == scope }?.positions?.firstOrNull { it.folderId == folderId }

internal fun saveAlbumTrail(
    states: List<AlbumNavigationState>, scope: String, trail: List<CloudNode>
): List<AlbumNavigationState> {
    if (scope.isBlank() || trail.isEmpty() || trail.size > 33 || trail.any { !it.isFolder }) {
        return states
    }
    val previous = states.firstOrNull { it.scope == scope }
    val next = AlbumNavigationState(scope, trail.toList(), previous?.positions.orEmpty())
    return (listOf(next) + states.filterNot { it.scope == scope }).take(10)
}

internal fun saveAlbumPosition(
    states: List<AlbumNavigationState>, scope: String, root: CloudNode,
    folderId: String, index: Int, offset: Int
): List<AlbumNavigationState> {
    if (folderId.isBlank() || index < 0 || offset < 0) return states
    val current = states.firstOrNull { it.scope == scope }
        ?: AlbumNavigationState(scope, listOf(root))
    val entry = AlbumScrollPosition(folderId, index.coerceAtMost(1_000_000),
        offset.coerceAtMost(1_000_000))
    if (current.positions.firstOrNull() == entry) return states
    val next = current.copy(
        positions = (listOf(entry) + current.positions.filterNot { it.folderId == folderId }).take(48)
    )
    return (listOf(next) + states.filterNot { it.scope == scope }).take(10)
}

internal class AlbumNavigationStore(context: Context) {
    private val preferences = context.getSharedPreferences("mediahub_album_navigation", Context.MODE_PRIVATE)
    fun load(): List<AlbumNavigationState> =
        AlbumNavigationCodec.decode(preferences.getString("navigation_v1", null))
    fun save(states: List<AlbumNavigationState>) {
        preferences.edit().putString("navigation_v1", AlbumNavigationCodec.encode(states)).apply()
    }
}
