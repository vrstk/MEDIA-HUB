package com.mediahub.mobile.cloud

import com.mediahub.mobile.domain.CloudFileCopy
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CopyPhase
import com.mediahub.mobile.domain.CopyVerificationException
import com.mediahub.mobile.domain.DestinationExistsException
import com.mediahub.mobile.drive.DriveProvider
import com.mediahub.mobile.mail.MailProvider
import com.mediahub.mobile.mail.MailWebDavClient
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class CloudFileCopyHttpTest {
    private val http = OkHttpClient.Builder().followRedirects(false)
        .retryOnConnectionFailure(false).readTimeout(3, TimeUnit.SECONDS).build()
    private val driveServer = MockWebServer()
    private val mailServer = MockWebServer()

    @Before fun startServers() {
        driveServer.start()
        mailServer.start()
    }

    @After fun stopServers() {
        driveServer.shutdown()
        mailServer.shutdown()
    }

    private val drive get() = DriveProvider("test-token", http, driveServer.url("/"))
    private val mail get() = MailProvider(MailWebDavClient("test@example.test", "test-password", http, mailServer.url("/")))
    private val data = ByteArray(150_000) { (it % 251).toByte() }
    private val name = "Фото + a#b.jpg"
    private val mime = "image/jpeg"
    private val mailPath = "/%D0%A4%D0%BE%D1%82%D0%BE%20%2B%20a%23b.jpg"
    private val driveFile get() = CloudNode("drive-id", name, false, data.size.toLong(), mime)

    @Test fun mailToDriveStreamsAndChecksActualBytes() {
        mailServer.enqueue(davStat(mailPath, data.size))
        mailServer.enqueue(bytes(data))
        driveServer.enqueue(emptyDrive())
        driveServer.enqueue(MockResponse().setHeader("Location", driveServer.url("/upload/drive/v3/files?upload_id=test")))
        driveServer.enqueue(driveStat())
        driveServer.enqueue(driveStat())
        driveServer.enqueue(bytes(data))

        val phases = mutableSetOf<CopyPhase>()
        val mailNode = CloudNode(mailPath, name, false, data.size.toLong(), mime)
        assertEquals(data.size.toLong(), CloudFileCopy.run(mail, mailNode, drive, drive.root) { phases += it.phase })
        assertEquals(setOf(CopyPhase.COPYING, CopyPhase.VERIFYING), phases)

        assertDavRequest("PROPFIND", mailPath, "0")
        assertDavRequest("GET", mailPath)
        assertEquals(2, mailServer.requestCount)
        assertDriveRequest("GET")
        val metadata = assertDriveRequest("POST")
        assertEquals("/upload/drive/v3/files", metadata.requestUrl?.encodedPath)
        assertEquals("resumable", metadata.requestUrl?.queryParameter("uploadType"))
        assertEquals(name, JSONObject(metadata.body.readUtf8()).getString("name"))
        val put = assertDriveRequest("PUT")
        assertArrayEquals(data, put.body.readByteArray())
        assertDriveRequest("GET")
        assertEquals("media", assertDriveRequest("GET").requestUrl?.queryParameter("alt"))
    }

    @Test fun driveToMailStagesThenMovesAndVerifies() {
        driveServer.enqueue(driveStat())
        driveServer.enqueue(bytes(data))
        val staged = mailDestination()
        assertEquals(data.size.toLong(), CloudFileCopy.run(drive, driveFile, mail, mail.root))

        assertDriveRequest("GET")
        assertDriveRequest("GET")
        assertEquals(2, driveServer.requestCount)
        assertDavRequest("PROPFIND", mailPath, "0")
        val put = assertDavRequest("PUT", staged())
        assertEquals("*", put.getHeader("If-None-Match"))
        assertArrayEquals(data, put.body.readByteArray())
        val move = assertDavRequest("MOVE", staged())
        assertEquals("F", move.getHeader("Overwrite"))
        assertEquals(mailServer.url(mailPath).toString(), move.getHeader("Destination"))
        assertDavRequest("PROPFIND", mailPath, "0")
        assertDavRequest("GET", mailPath)
        assertEquals(5, mailServer.requestCount)
    }

    @Test fun existingDestinationDoesNotIssueWrites() {
        driveServer.enqueue(driveStat())
        driveServer.enqueue(bytes(data))
        mailServer.enqueue(davStat(mailPath, data.size))
        assertThrows(DestinationExistsException::class.java) { CloudFileCopy.run(drive, driveFile, mail, mail.root) }
        assertDavRequest("PROPFIND", mailPath, "0")
        assertEquals(1, mailServer.requestCount)
    }

    @Test fun conflictDuringWebDavMoveOnlyCleansUpItsTemporaryFile() {
        driveServer.enqueue(driveStat())
        driveServer.enqueue(bytes(data))
        val staged = mailDestination(moveStatus = 412)
        assertThrows(DestinationExistsException::class.java) { CloudFileCopy.run(drive, driveFile, mail, mail.root) }
        assertDavRequest("PROPFIND", mailPath, "0")
        assertDavRequest("PUT", staged())
        assertDavRequest("MOVE", staged())
        assertDavRequest("DELETE", staged())
        assertEquals(2, driveServer.requestCount)
    }

    @Test fun destinationWithSameLengthButDifferentBytesIsNotVerified() {
        driveServer.enqueue(driveStat())
        driveServer.enqueue(bytes(data))
        val corrupt = data.clone().apply { this[9] = 127 }
        mailDestination(readBack = corrupt)
        val failure = assertThrows(CopyVerificationException::class.java) { CloudFileCopy.run(drive, driveFile, mail, mail.root) }
        assertTrue(failure.cause!!.message.orEmpty().contains("Контрольные суммы"))
        assertEquals(2, driveServer.requestCount)
    }

    @Test fun cancelledVerificationNeverReportsSuccess() {
        driveServer.enqueue(driveStat())
        driveServer.enqueue(bytes(data))
        mailDestination()
        var verifying = false
        assertThrows(CancellationException::class.java) {
            CloudFileCopy.run(
                drive, driveFile, mail, mail.root,
                checkpoint = { if (verifying) throw CancellationException() },
                report = { verifying = it.phase == CopyPhase.VERIFYING }
            )
        }
        assertEquals(2, driveServer.requestCount)
    }

    @Test fun sourceWithUnexpectedLengthDoesNotStartUpload() {
        driveServer.enqueue(driveStat())
        driveServer.enqueue(bytes(data.copyOf(50)))
        assertThrows(IOException::class.java) { CloudFileCopy.run(drive, driveFile, mail, mail.root) }
        assertEquals(0, mailServer.requestCount)
    }

    @Test fun driveListFollowsPageTokenAndMarksNativeDocuments() {
        val document = JSONObject().put("id", "native").put("name", "Документ")
            .put("mimeType", "application/vnd.google-apps.document")
        driveServer.enqueue(json(JSONObject().put("nextPageToken", "next+page")
            .put("files", JSONArray().put(document))))
        driveServer.enqueue(emptyDrive())
        val first = drive.list("root")
        assertEquals("next+page", first.nextPageToken)
        assertFalse(first.nodes.single().canReadBytes)
        assertTrue(drive.list("root", first.nextPageToken).nodes.isEmpty())
        assertDriveRequest("GET")
        assertEquals("next+page", assertDriveRequest("GET").requestUrl?.queryParameter("pageToken"))
    }

    @Test fun driveRejectsUploadSessionOnAnotherOrigin() {
        mailServer.enqueue(davStat(mailPath, data.size))
        mailServer.enqueue(bytes(data))
        driveServer.enqueue(emptyDrive())
        driveServer.enqueue(MockResponse().setHeader("Location", mailServer.url("/upload/drive/v3/files")))
        val file = CloudNode(mailPath, name, false, data.size.toLong(), mime)
        assertThrows(IOException::class.java) { CloudFileCopy.run(mail, file, drive, drive.root) }
        assertEquals(2, mailServer.requestCount)
        assertEquals(2, driveServer.requestCount)
    }

    @Test fun driveConflictOnLaterPageDoesNotStartUpload() {
        mailServer.enqueue(davStat(mailPath, data.size))
        mailServer.enqueue(bytes(data))
        driveServer.enqueue(json(JSONObject().put("files", JSONArray()).put("nextPageToken", "second")))
        driveServer.enqueue(json(JSONObject().put("files", JSONArray().put(JSONObject().put("id", "existing")))))
        val file = CloudNode(mailPath, name, false, data.size.toLong(), mime)
        assertThrows(DestinationExistsException::class.java) { CloudFileCopy.run(mail, file, drive, drive.root) }
        assertDriveRequest("GET")
        assertEquals("second", assertDriveRequest("GET").requestUrl?.queryParameter("pageToken"))
        assertEquals(2, driveServer.requestCount)
    }

    private fun mailDestination(moveStatus: Int = 201, readBack: ByteArray = data): () -> String {
        var staged: String? = null
        mailServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "PUT" -> {
                    staged = request.requestUrl?.encodedPath
                    MockResponse().setResponseCode(201)
                }
                "MOVE" -> MockResponse().setResponseCode(moveStatus)
                "DELETE" -> MockResponse().setResponseCode(204)
                "PROPFIND" -> if (staged == null) MockResponse().setResponseCode(404)
                    else davStat(mailPath, data.size)
                "GET" -> bytes(readBack)
                else -> MockResponse().setResponseCode(500)
            }
        }
        return {
            requireNotNull(staged).also {
                assertTrue(it.startsWith("/mediahub-") && it.endsWith(".upload"))
            }
        }
    }

    private fun assertDriveRequest(method: String): RecordedRequest = requireNotNull(
        driveServer.takeRequest(2, TimeUnit.SECONDS)
    ).also {
        assertEquals(method, it.method)
        assertEquals("Bearer test-token", it.getHeader("Authorization"))
    }

    private fun assertDavRequest(method: String, path: String, depth: String? = null): RecordedRequest =
        requireNotNull(mailServer.takeRequest(2, TimeUnit.SECONDS)).also {
            assertEquals(method, it.method)
            assertEquals(path, it.requestUrl?.encodedPath)
            assertEquals(depth, it.getHeader("Depth"))
            assertEquals(Credentials.basic("test@example.test", "test-password"), it.getHeader("Authorization"))
        }

    private fun davStat(path: String, size: Int) = MockResponse().setResponseCode(207).setBody(
        """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href>
            <d:propstat><d:prop><d:displayname>$name</d:displayname><d:getcontentlength>$size</d:getcontentlength>
            <d:getcontenttype>$mime</d:getcontenttype></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
    )

    private fun driveStat() = json(JSONObject().put("id", "drive-id").put("name", name)
        .put("size", data.size.toString()).put("mimeType", mime))

    private fun emptyDrive() = json(JSONObject().put("files", JSONArray()))

    private fun json(value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())

    private fun bytes(value: ByteArray) = MockResponse().setBody(Buffer().write(value))
}
