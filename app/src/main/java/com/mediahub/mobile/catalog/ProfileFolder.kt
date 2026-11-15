package com.mediahub.mobile.catalog

import androidx.room.Entity
import androidx.room.ForeignKey
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CloudProvider
import com.mediahub.mobile.mail.DavPath
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

@Entity(
    tableName = "profile_folders",
    primaryKeys = ["profileId", "storage", "scopeKey", "folderId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceProfile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
internal data class ProfileFolder(
    val profileId: String,
    val storage: String,
    val scopeKey: String,
    val folderId: String,
    val title: String,
    val trailJson: String
) {
    val kind: ProfileStorageKind? get() = ProfileStorageKind.entries.firstOrNull { it.name == storage }

    fun displayPath(): String = readTrail()?.joinToString(" / ") { it.name } ?: title

    fun readTrail(): List<CloudNode>? = try {
        val trail = JSONArray(trailJson)
        List(trail.length()) { index ->
            val folder = trail.getJSONObject(index)
            CloudNode(folder.getString("id"), folder.getString("name"), isFolder = true, sizeBytes = null)
        }
    } catch (_: JSONException) {
        null
    }
}

internal enum class ProfileStorageKind(val title: String, val connectLabel: String) {
    MAIL("Облако Mail", "Подключить Mail"),
    ANDROID_DRIVE("Google Drive / Android", "Выбрать папку через Android")
}

/** Uses the connected account or SAF tree as a namespace for otherwise ambiguous folder IDs. */
internal class ProfileFolderAccess private constructor(
    val kind: ProfileStorageKind,
    private val scopeKey: String,
    val provider: CloudProvider
) {
    fun link(profileId: String, trail: List<CloudNode>): ProfileFolder {
        require(validTrail(trail)) { "Не удалось подтвердить путь папки." }
        val folder = trail.last()
        val encodedTrail = JSONArray()
        for (node in trail) {
            encodedTrail.put(JSONObject().put("id", node.id).put("name", node.name))
        }
        return ProfileFolder(profileId, kind.name, scopeKey, folder.id, folder.name, encodedTrail.toString())
    }

    fun resolve(link: ProfileFolder): List<CloudNode>? {
        if (link.kind != kind || link.scopeKey != scopeKey) return null
        val trail = link.readTrail()?.takeIf(::validTrail) ?: return null
        if (trail.last().id != link.folderId) return null
        return listOf(provider.root) + trail.drop(1)
    }

    private fun validTrail(trail: List<CloudNode>): Boolean {
        if (trail.firstOrNull()?.id != provider.root.id || !trail.all { it.isFolder && it.id.isNotEmpty() } ||
            trail.map { it.id }.toSet().size != trail.size
        ) return false
        if (kind != ProfileStorageKind.MAIL) return true
        return trail.zipWithNext().all { (parent, child) ->
            runCatching { DavPath.normalize(child.id) == child.id && DavPath.parent(child.id) == parent.id }
                .getOrDefault(false)
        }
    }

    companion object {
        fun mail(accountAddress: String, provider: CloudProvider) = ProfileFolderAccess(
            ProfileStorageKind.MAIL, fingerprint(accountAddress.trim().lowercase(Locale.ROOT)), provider
        )

        fun androidDrive(treeUri: String, provider: CloudProvider) = ProfileFolderAccess(
            ProfileStorageKind.ANDROID_DRIVE, fingerprint(treeUri), provider
        )

        private fun fingerprint(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
