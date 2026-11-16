package com.mediahub.mobile.mail

import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CloudPage
import com.mediahub.mobile.domain.CloudProvider
import java.io.InputStream

internal class MailProvider(private val client: MailWebDavClient) : CloudProvider {
    override val root = CloudNode("/", "Облако Mail", true, null)

    override fun list(folderId: String, pageToken: String?) =
        CloudPage(client.list(folderId).map(DavEntry::toCloudNode))

    override fun stat(nodeId: String) = client.stat(nodeId).toCloudNode()

    override fun openRead(file: CloudNode) = client.openRead(file.id)

    override fun createFolder(parentId: String, name: String): CloudNode {
        client.createFolder(parentId, name)
        return CloudNode(DavPath.child(parentId, name, true), name, true, null)
    }

    override fun uploadNew(parentId: String, file: CloudNode, size: Long, source: InputStream): CloudNode {
        val path = client.uploadForCopy(parentId, file.name, size, file.mimeType, source)
        return file.copy(id = path)
    }
}

internal fun DavEntry.toCloudNode() = CloudNode(path, name, isDirectory, size, mimeType)
