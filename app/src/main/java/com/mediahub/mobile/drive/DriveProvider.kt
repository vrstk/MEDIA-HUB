package com.mediahub.mobile.drive

import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CloudPage
import com.mediahub.mobile.domain.CloudProvider
import com.mediahub.mobile.domain.CloudRead
import com.mediahub.mobile.domain.DestinationExistsException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal class DriveAuthorizationRequired :
    IOException("Доступ к Google Drive истёк. Подключите Google Drive снова.")

internal class DriveProvider(
    private val accessToken: String,
    private val http: OkHttpClient = client,
    private val endpoint: HttpUrl = productionEndpoint
) : CloudProvider {
    override val root = CloudNode("root", "Мой диск", true, null)
    private val files = endpoint.newBuilder().addPathSegments("drive/v3/files").build()
    private val uploads = endpoint.newBuilder().addPathSegments("upload/drive/v3/files").build()

    fun checkAccess() {
        // Reading the root listing proves that the Drive API is enabled and accepts this token.
        list(root.id)
    }

    override fun list(folderId: String, pageToken: String?): CloudPage {
        val url = files.newBuilder()
            .addQueryParameter("q", "'${quote(folderId)}' in parents and trashed = false")
            .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
            .addQueryParameter("orderBy", "folder,name_natural")
            .addQueryParameter("pageSize", "100")
            .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
            .build()
        val json = request(url).get().build().readJson()
        val entries = json.optJSONArray("files")
        return CloudPage(
            entries?.let { rows -> List(rows.length()) { node(rows.getJSONObject(it)) } }.orEmpty(),
            json.optString("nextPageToken").takeIf(String::isNotEmpty)
        )
    }

    override fun stat(nodeId: String): CloudNode {
        val url = files.newBuilder().addPathSegment(nodeId)
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        return node(request(url).get().build().readJson())
    }

    override fun createFolder(parentId: String, name: String): CloudNode {
        checkNameAvailable(parentId, name)
        return createMetadata(parentId, name, FOLDER)
    }

    override fun openRead(file: CloudNode): CloudRead {
        require(file.canReadBytes) { "Документы Google и ярлыки нельзя скачать как обычные файлы." }
        val url = files.newBuilder().addPathSegment(file.id)
            .addQueryParameter("alt", "media")
            .build()
        val response = http.newCall(request(url).get().build()).execute()
        try {
            response.ensureSuccess()
            val body = response.body ?: throw IOException("Google Drive вернул пустой ответ.")
            return CloudRead(body.byteStream(), body.contentLength().takeIf { it >= 0 }, response)
        } catch (e: Exception) {
            response.close()
            throw e
        }
    }

    override fun uploadNew(parentId: String, file: CloudNode, size: Long, source: InputStream): CloudNode {
        checkNameAvailable(parentId, file.name)
        val mime = file.mimeType?.takeUnless { it.startsWith("application/vnd.google-apps.", ignoreCase = true) }
            ?.toMediaTypeOrNull() ?: BINARY
        if (size == 0L) {
            if (source.read() != -1) throw IOException("Размер исходного файла изменился.")
            return createMetadata(parentId, file.name, mime.toString())
        }
        val url = uploads.newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val start = request(url)
            .header("X-Upload-Content-Type", mime.toString())
            .header("X-Upload-Content-Length", size.toString())
            .post(metadata(parentId, file.name, mime.toString()).toString().toRequestBody(JSON))
            .build()
        val session = http.newCall(start).execute().use { response ->
            response.ensureSuccess()
            val location = response.header("Location")?.toHttpUrlOrNull()
                ?: throw IOException("Google Drive не вернул адрес загрузки.")
            if (location.scheme != endpoint.scheme || location.host != endpoint.host ||
                location.port != endpoint.port || location.username.isNotEmpty() || location.password.isNotEmpty() ||
                !location.encodedPath.startsWith("/upload/drive/v3/files")
            ) throw IOException("Недопустимый адрес загрузки Google Drive.")
            location
        }
        val body = object : RequestBody() {
            override fun contentType() = mime
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                }
            }
        }
        return node(request(session).put(body).build().readJson())
    }

    private fun checkNameAvailable(parentId: String, name: String) {
        require(name.isNotBlank() && name !in listOf(".", "..")) { "Укажите имя файла или папки." }
        val query = "'${quote(parentId)}' in parents and name = '${quote(name)}' and trashed = false"
        var page: String? = null
        val seen = HashSet<String>()
        do {
            val url = files.newBuilder().addQueryParameter("q", query)
                .addQueryParameter("fields", "nextPageToken,files(id)")
                .addQueryParameter("pageSize", "1")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = request(url).get().build().readJson()
            if ((json.optJSONArray("files")?.length() ?: 0) != 0) throw DestinationExistsException()
            page = json.optString("nextPageToken").takeIf(String::isNotEmpty)
            if (page != null && !seen.add(page)) throw IOException("Google Drive повторил страницу списка.")
        } while (page != null)
    }

    private fun createMetadata(parentId: String, name: String, mime: String): CloudNode {
        val url = files.newBuilder().addQueryParameter("fields", FILE_FIELDS).build()
        val body = metadata(parentId, name, mime).toString().toRequestBody(JSON)
        return node(request(url).post(body).build().readJson())
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url)
        .header("Authorization", "Bearer $accessToken")
        .header("Accept-Encoding", "identity")

    private fun Request.readJson(): JSONObject = http.newCall(this).execute().use { response ->
        response.ensureSuccess()
        JSONObject(response.body?.string() ?: throw IOException("Google Drive вернул пустой ответ."))
    }

    private fun Response.ensureSuccess() {
        if (isSuccessful) return
        throw when (code) {
            401 -> DriveAuthorizationRequired()
            403 -> IOException("Google Drive отклонил запрос (403). Проверьте права и настройку Drive API.")
            404 -> IOException("Объект Google Drive недоступен с текущими правами (404).")
            429 -> IOException("Google Drive ограничил частоту запросов (429).")
            else -> IOException("Google Drive вернул ошибку HTTP $code.")
        }
    }

    private fun node(value: JSONObject): CloudNode {
        val mime = value.getString("mimeType")
        val folder = mime == FOLDER
        val canDownload = value.optJSONObject("capabilities")?.optBoolean("canDownload", true) ?: true
        return CloudNode(
            value.getString("id"), value.getString("name"), folder,
            value.optString("size").toLongOrNull(), mime,
            !mime.startsWith("application/vnd.google-apps.") && canDownload
        )
    }

    private fun metadata(parentId: String, name: String, mime: String) = JSONObject()
        .put("name", name).put("mimeType", mime).put("parents", org.json.JSONArray().put(parentId))

    private fun quote(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    private companion object {
        val productionEndpoint = "https://www.googleapis.com/".toHttpUrl()
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val JSON = "application/json; charset=utf-8".toMediaType()
        val BINARY = "application/octet-stream".toMediaType()
        const val FOLDER = "application/vnd.google-apps.folder"
        const val FILE_FIELDS = "id,name,mimeType,size,capabilities(canDownload)"
    }
}
