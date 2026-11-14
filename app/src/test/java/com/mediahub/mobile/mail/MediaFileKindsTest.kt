package com.mediahub.mobile.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFileKindsTest {
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
