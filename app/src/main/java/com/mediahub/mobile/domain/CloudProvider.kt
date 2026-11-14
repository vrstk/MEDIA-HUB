package com.mediahub.mobile.domain

/** Provider-specific IDs remain opaque: they must never be treated as filesystem paths. */
data class CloudNode(val id: String, val name: String, val isFolder: Boolean, val sizeBytes: Long?)
data class CloudPage(val nodes: List<CloudNode>, val nextPageToken: String?)

/**
 * Contract for future Google Drive and Mail WebDAV adapters.
 * This interface does not imply that the adapters have been implemented.
 */
interface CloudProvider {
    suspend fun list(folderId: String, pageToken: String? = null): CloudPage
    suspend fun stat(nodeId: String): CloudNode
    suspend fun createFolder(parentId: String, name: String): CloudNode
    suspend fun delete(nodeId: String)
}
