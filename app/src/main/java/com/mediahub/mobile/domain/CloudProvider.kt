package com.mediahub.mobile.domain

import java.io.Closeable
import java.io.InputStream

/** Provider IDs are opaque outside the adapter that issued them. */
data class CloudNode(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long?,
    val mimeType: String? = null,
    val canReadBytes: Boolean = !isFolder
)

data class CloudPage(val nodes: List<CloudNode>, val nextPageToken: String? = null)

class CloudRead(
    val stream: InputStream,
    val sizeBytes: Long?,
    private val closeResource: Closeable = stream
) : Closeable {
    override fun close() = closeResource.close()
}

interface CloudProvider {
    val root: CloudNode

    fun list(folderId: String, pageToken: String? = null): CloudPage
    fun stat(nodeId: String): CloudNode
    fun createFolder(parentId: String, name: String): CloudNode
    fun openRead(file: CloudNode): CloudRead

    /** Create a new object and return its address. Never update an existing object's bytes. */
    fun uploadNew(parentId: String, file: CloudNode, size: Long, source: InputStream): CloudNode
}
