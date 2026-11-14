package com.mediahub.mobile.mail

object MediaFileKinds {
    private val images = setOf("jpg", "jpeg", "png", "webp")
    fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in images
}
