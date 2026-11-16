package com.mediahub.mobile.media

import com.mediahub.mobile.domain.CloudNode

object MediaFileKinds {
    private val images = setOf("jpg", "jpeg", "png", "webp")
    private val imageMimeTypes = setOf("image/jpeg", "image/png", "image/webp")

    fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in images

    fun isPhoto(file: CloudNode): Boolean = !file.isFolder && file.canReadBytes &&
        (isImage(file.name) || file.mimeType?.substringBefore(';')?.trim()?.lowercase() in imageMimeTypes)
}
