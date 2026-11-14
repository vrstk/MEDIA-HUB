package com.mediahub.mobile.mail

import com.mediahub.mobile.domain.CloudRead
import com.mediahub.mobile.domain.DestinationExistsException
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class MailWebDavClient(
    email: String,
    appPassword: String,
    private val http: OkHttpClient = client,
    private val endpoint: HttpUrl = DavPath.BASE_URL.toHttpUrl()
) {
    private val auth = Credentials.basic(email, appPassword, Charsets.UTF_8)

    private fun address(path: String, directory: Boolean): String =
        endpoint.toString().trimEnd('/') + DavPath.normalize(path, directory)

    private fun request(path: String, directory: Boolean = false) = Request.Builder()
        .url(address(path, directory))
        .header("Authorization", auth)
        .header("Accept-Encoding", "identity")

    fun list(path: String = "/"): List<DavEntry> =
        DavXmlParser.parse(propfind(path, directory = true, depth = "1"), path)

    fun stat(path: String): DavEntry =
        DavXmlParser.parseStat(propfind(path, path.endsWith('/'), "0"), path)
            ?: throw IOException("WebDAV не вернул сведения об объекте.")

    private fun propfind(path: String, directory: Boolean, depth: String): String {
        val req = request(path, directory)
            .header("Depth", depth)
            .method("PROPFIND", PROPERTIES.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull()))
            .build()
        http.newCall(req).execute().use { response ->
            if (response.code != 207 && response.code != 200) fail(response.code)
            val body = response.body ?: throw IOException("Пустой ответ WebDAV.")
            return readBounded(body.byteStream(), body.contentLength(), 8_000_000,
                "Слишком большой XML-ответ WebDAV.").toString(Charsets.UTF_8)
        }
    }

    fun createFolder(parent: String, name: String) {
        val path = DavPath.child(parent, name, true)
        val req = request(path, true)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { response ->
            if (response.code != 201) fail(response.code)
        }
    }

    fun upload(parent: String, name: String, size: Long?, mimeType: String?, source: () -> InputStream) {
        val path = DavPath.child(parent, name, false)
        putNew(path, size, mimeType, allowExistingServerSuccessCodes = true, source)
    }

    /** Stage a copy under a unique name, then publish it with WebDAV's no-overwrite MOVE. */
    fun uploadForCopy(parent: String, name: String, size: Long, mimeType: String?, source: InputStream): String {
        val destination = DavPath.child(parent, name, false)
        try {
            stat(destination)
            throw DestinationExistsException()
        } catch (_: DavNotFoundException) {
            // The MOVE condition also covers a new object appearing after this check.
        }
        val temporary = DavPath.child(parent, "mediahub-${UUID.randomUUID()}.upload", false)
        putNew(temporary, size, mimeType, allowExistingServerSuccessCodes = false) { source }
        if (Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException("Копирование остановлено.")
        }
        val move = request(temporary)
            .header("Destination", address(destination, false))
            .header("Overwrite", "F")
            .method("MOVE", null)
            .build()
        http.newCall(move).execute().use { response ->
            when (response.code) {
                201 -> return destination
                412 -> {
                    // Only the object created by this operation is cleaned up.
                    val removed = runCatching { deleteTemporary(temporary) }.getOrDefault(false)
                    if (!removed) throw IOException(
                        "Имя занято. В папке назначения мог остаться временный файл Media Hub."
                    )
                    throw DestinationExistsException()
                }
                204 -> throw IOException("WebDAV сообщил о замене цели. Проверьте папку назначения.")
                else -> throw IOException(
                    "WebDAV не завершил запись (HTTP ${response.code}). Проверьте папку назначения."
                )
            }
        }
    }

    private fun deleteTemporary(path: String): Boolean =
        http.newCall(request(path).delete().build()).execute().use { it.isSuccessful || it.code == 404 }

    private fun putNew(
        path: String,
        size: Long?,
        mimeType: String?,
        allowExistingServerSuccessCodes: Boolean,
        source: () -> InputStream
    ) {
        val body = object : RequestBody() {
            override fun contentType() = mimeType?.toMediaTypeOrNull()
                ?: "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength() = size ?: -1L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                source().use { it.copyTo(sink.outputStream(), 64 * 1024) }
            }
        }
        val req = request(path).header("If-None-Match", "*").put(body).build()
        http.newCall(req).execute().use { response ->
            when (response.code) {
                201 -> Unit
                200, 204 -> if (!allowExistingServerSuccessCodes) fail(response.code)
                else -> fail(response.code)
            }
        }
    }

    fun openRead(path: String): CloudRead {
        val response = http.newCall(request(path).get().build()).execute()
        try {
            if (response.code != 200) fail(response.code)
            val body = response.body ?: throw IOException("Пустой ответ WebDAV.")
            return CloudRead(body.byteStream(), body.contentLength().takeIf { it >= 0 }, response)
        } catch (e: Exception) {
            response.close()
            throw e
        }
    }

    fun download(path: String, destination: OutputStream) {
        openRead(path).use { it.stream.copyTo(destination, 64 * 1024) }
    }

    fun previewImage(path: String, maxBytes: Int = 16 * 1024 * 1024): ByteArray {
        require(maxBytes in 1..(16 * 1024 * 1024))
        return openRead(path).use { response ->
            readBounded(response.stream, response.sizeBytes ?: -1, maxBytes,
                "Фотография слишком большая для предпросмотра. Её можно скачать.")
        }
    }

    private fun readBounded(stream: InputStream, reported: Long, maxBytes: Int, error: String): ByteArray {
        if (reported > maxBytes) throw IOException(error)
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (result.size() + count > maxBytes) throw IOException(error)
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    private fun fail(code: Int): Nothing = throw when (code) {
        401 -> IOException("Неверная почта или пароль для внешнего приложения.")
        403 -> IOException("Операция запрещена сервером WebDAV (403).")
        404 -> DavNotFoundException()
        412 -> DestinationExistsException()
        405, 409 -> IOException("Конфликт: объект уже существует или операция запрещена ($code).")
        413, 507 -> IOException("Недостаточно места или превышен лимит ($code).")
        else -> IOException("Ошибка WebDAV: HTTP $code.")
    }

    private class DavNotFoundException : IOException("Папка или файл не найдены (404).")

    private companion object {
        const val PROPERTIES = """<?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:"><d:allprop/></d:propfind>"""
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
