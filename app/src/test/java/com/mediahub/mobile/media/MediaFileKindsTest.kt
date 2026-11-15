package com.mediahub.mobile.media

import com.mediahub.mobile.domain.CloudNode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFileKindsTest {
    @Test fun `gallery includes readable photos with a known extension or mime type`() {
        assertTrue(MediaFileKinds.isPhoto(CloudNode("a", "cover", false, null, "IMAGE/JPEG")))
        assertTrue(MediaFileKinds.isPhoto(CloudNode("b", "cover.png", false, 200, "application/octet-stream")))
        assertFalse(MediaFileKinds.isPhoto(CloudNode("c", "cover.jpg", true, null)))
        assertFalse(MediaFileKinds.isPhoto(CloudNode("d", "cover.webp", false, null, canReadBytes = false)))
        assertFalse(MediaFileKinds.isPhoto(CloudNode("e", "clip.mp4", false, null, "video/mp4")))
    }
    @Test fun imageExtensionsIgnoreCase() {
        assertTrue(MediaFileKinds.isImage("model.JPG"))
        assertTrue(MediaFileKinds.isImage("photo.jpeg"))
        assertTrue(MediaFileKinds.isImage("picture.PNG"))
        assertTrue(MediaFileKinds.isImage("cover.webp"))
    }

    @Test fun nonImageFilesNeverTriggerThumbnailLoading() {
        assertFalse(MediaFileKinds.isImage("video.mp4"))
        assertFalse(MediaFileKinds.isImage("image.jpg.tmp"))
        assertFalse(MediaFileKinds.isImage("folder"))
        assertFalse(MediaFileKinds.isImage(".jpg-not-file"))
    }
}
