package com.mediahub.mobile.mail

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Real Mail WebDAV implementation: HTTPS only, external application password.
 * No credentials are logged. Each client is per-account and never stored globally.
 */
class MailWebDavClient(
    email: String,
    appPassword: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
) {
    private val auth = Credentials.basic(email, appPassword, Charsets.UTF_8)

    private fun request(method: String, path: String, body: RequestBody? = null): Request.Builder {
        val validPath = DavPath.normalize(path, method == "PROPFIND" || method == "MKCOL")
        return Request.Builder()
            .url(DavPath.BASE_URL + validPath)
            .header("Authorization", auth)
            .header("Accept-Encoding", "identity")
    }

    fun list(path: String = "/"): List<DavEntry> {
        val normalized = DavPath.normalize(path)
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:"><d:allprop/></d:propfind>"""
        val req = request("PROPFIND", normalized)
            .header("Depth", "1")
            .method("PROPFIND", xml.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull()))
            .build()
        http.newCall(req).execute().use { response ->
            if (response.code != 207 && response.code != 200) fail(response.code)
            val data = response.body?.string() ?: throw IOException("Пустой ответ WebDAV.")
            return DavXmlParser.parse(data, normalized)
        }
    }

    fun createFolder(parent: String, name: String) {
        val path = DavPath.child(parent, name, true)
        val req = request("MKCOL", path)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { response ->
            if (response.code != 201) fail(response.code)
        }
    }

    fun upload(
        parent: String,
        name: String,
        size: Long?,
        mimeType: String?,
        source: () -> InputStream
    ) {
        val path = DavPath.child(parent, name, false)
        // Conditional PUT avoids silently replacing an existing remote file.
        val body = object : RequestBody() {
            override fun contentType() = mimeType?.toMediaTypeOrNull()
                ?: "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength(): Long = size?.takeIf { it >= 0 } ?: -1L
            override fun writeTo(sink: BufferedSink) {
                source().use { input -> sink.outputStream().let { input.copyTo(it, 64 * 1024) } }
            }
        }
        val req = request("PUT", path)
            .header("If-None-Match", "*")
            .put(body)
            .build()
        http.newCall(req).execute().use { response ->
            if (response.code !in listOf(200, 201, 204)) fail(response.code)
        }
    }

    fun download(path: String, destination: OutputStream) {
        val req = request("GET", DavPath.normalize(path, false)).get().build()
        http.newCall(req).execute().use { response ->
            if (response.code != 200) fail(response.code)
            (response.body ?: throw IOException("Пустой ответ WebDAV."))
                .byteStream().use { input -> input.copyTo(destination, 64 * 1024) }
        }
    }

    private fun fail(code: Int): Nothing = throw IOException(when (code) {
        401 -> "Неверная почта или пароль для внешнего приложения."
        403 -> "У сервера нет разрешения на эту операцию (403)."
        404 -> "Папка или файл не найдены (404)."
        405, 409, 412 -> "Конфликт: объект уже существует или операция запрещена ($code)."
        413, 507 -> "Недостаточно места или превышен лимит ($code)."
        else -> "Ошибка WebDAV: HTTP $code."
    })
}
