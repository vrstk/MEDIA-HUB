package com.mediahub.mobile.mail

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class PreviewRetryPolicyTest {
    @Test fun onlyReadTransportFailuresAreRetryable() {
        assertTrue(PreviewRetryPolicy.retryable(SocketException("Software caused connection abort")))
        assertTrue(PreviewRetryPolicy.retryable(SocketTimeoutException()))
        assertTrue(PreviewRetryPolicy.retryable(EOFException()))
        assertTrue(PreviewRetryPolicy.retryable(ProtocolException("unexpected end of stream")))
        assertFalse(PreviewRetryPolicy.retryable(IOException("Ошибка WebDAV: HTTP 403.")))
        assertFalse(PreviewRetryPolicy.retryable(IOException("Фотография слишком большая.")))
    }

    @Test fun nonRetryableHttpErrorDoesNotIssueAnotherGet() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403))
            val client = MailWebDavClient(
                "test@example.test", "secret",
                OkHttpClient.Builder().retryOnConnectionFailure(false)
                    .readTimeout(3, TimeUnit.SECONDS).build(),
                server.url("/")
            )
            val failure = assertThrows(IOException::class.java) {
                client.previewImage("/image.jpg")
            }
            assertTrue(failure.message?.contains("403") == true)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun workingImageReadIsUnchanged() {
        val server = MockWebServer()
        server.start()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4, 5)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val client = MailWebDavClient(
                "test@example.test", "secret",
                OkHttpClient.Builder().retryOnConnectionFailure(false)
                    .readTimeout(3, TimeUnit.SECONDS).build(),
                server.url("/")
            )
            assertArrayEquals(bytes, client.previewImage("/image.jpg"))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
