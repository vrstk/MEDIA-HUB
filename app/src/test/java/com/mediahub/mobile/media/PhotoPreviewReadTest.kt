package com.mediahub.mobile.media

import com.mediahub.mobile.domain.CloudRead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class PhotoPreviewReadTest {
    private val bytes = byteArrayOf(1, 2, 3, 4)

    @Test fun `preview reads a file with no reported size at the limit`() {
        CloudRead(bytes.inputStream(), null).use { read ->
            assertArrayEquals(bytes, read.readPhotoBytes(bytes.size))
        }
    }

    @Test fun `preview checks limits against actual bytes too`() {
        CloudRead(bytes.inputStream(), 2).use { read ->
            assertThrows(IOException::class.java) { read.readPhotoBytes(3) }
        }
    }

    @Test fun `preview rejects an oversized reported length before reading`() {
        val stream = object : InputStream() {
            override fun read(): Int = throw AssertionError("Oversized file must not be read")
        }
        CloudRead(stream, 4).use { read ->
            assertThrows(IOException::class.java) { read.readPhotoBytes(3) }
        }
    }
}
