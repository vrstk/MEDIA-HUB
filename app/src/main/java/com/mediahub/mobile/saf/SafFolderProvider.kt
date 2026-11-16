package com.mediahub.mobile.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CloudPage
import com.mediahub.mobile.domain.CloudProvider
import com.mediahub.mobile.domain.CloudRead
import com.mediahub.mobile.domain.DestinationExistsException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Android DocumentsProvider adapter. The user may select a Google Drive folder
 * with ACTION_OPEN_DOCUMENT_TREE; Media Hub never handles Google credentials.
 *
 * IMPORTANT: reading uploaded bytes through the provider verifies the accessible
 * copy, NOT that the Google Drive server has finished cloud synchronization.
 */
class SafFolderProvider(private val context: Context, val treeUri: Uri) : CloudProvider {
    private val resolver = context.contentResolver
    private val treeId = DocumentsContract.getTreeDocumentId(treeUri)
    private val preferences = context.getSharedPreferences("mediahub_saf_pending_v1", Context.MODE_PRIVATE)
    private val directoryMime = DocumentsContract.Document.MIME_TYPE_DIR
    override val root: CloudNode = stat(treeId).also {
        require(it.isFolder) { "Выбрана не папка." }
    }

    private fun document(id: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun safeName(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name && '\n' !in name && '\r' !in name
        ) { "Недопустимое имя файла или папки." }
        return name
    }

    private fun query(uri: Uri): List<CloudNode> {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        val cursor = resolver.query(uri, columns, null, null, null)
            ?: throw IOException("Провайдер Android не ответил. Данные не менялись.")
        return cursor.use { rows ->
            val out = ArrayList<CloudNode>()
            val id = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val size = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val flags = rows.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            while (rows.moveToNext()) {
                val docId = rows.getString(id) ?: continue
                val docMime = rows.getString(mime)
                val folder = docMime == directoryMime
                val bits = if (flags >= 0 && !rows.isNull(flags)) rows.getInt(flags) else 0
                val virtual = bits and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0
                out.add(CloudNode(
                    docId,
                    if (name >= 0) rows.getString(name).orEmpty() else docId,
                    folder,
                    if (size >= 0 && !rows.isNull(size)) rows.getLong(size).takeIf { it >= 0 } else null,
                    docMime,
                    !folder && !virtual
                ))
            }
            out
        }
    }

    override fun stat(nodeId: String): CloudNode =
        query(document(nodeId)).singleOrNull()
            ?: throw IOException("Объект недоступен через выбранную папку.")

    private fun children(parentId: String): List<CloudNode> =
        query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId))

    override fun list(folderId: String, pageToken: String?): CloudPage {
        require(pageToken == null) { "Страницы SAF не поддерживаются." }
        require(stat(folderId).isFolder) { "Это не папка." }
        return CloudPage(children(folderId).sortedWith(
            compareBy<CloudNode> { !it.isFolder }.thenBy { it.name.lowercase() }
        ))
    }

    private fun pendingKey(parentId: String, name: String): String {
        val raw = (treeUri.toString() + "\n" + parentId + "\n" + name)
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Never create when a same-named object exists or a previous write is uncertain. */
    private fun ensureFree(parentId: String, name: String): String {
        val matches = children(parentId).filter { it.name == name }
        if (matches.isNotEmpty()) throw DestinationExistsException()
        val key = pendingKey(parentId, name)
        if (preferences.getBoolean(key, false)) throw IOException(
            "Предыдущее создание «$name» могло завершиться. " +
                "Проверьте папку в Google Диске; повторная запись заблокирована."
        )
        check(preferences.edit().putBoolean(key, true).commit()) {
            "Не удалось зафиксировать защиту от повторной записи."
        }
        return key
    }

    override fun createFolder(parentId: String, name: String): CloudNode {
        val clean = safeName(name)
        val key = ensureFree(parentId, clean)
        // Failure after this call may mean the cloud provider did create the folder.
        // Keep the pending marker, never retry creation automatically.
        val created = DocumentsContract.createDocument(
            resolver, document(parentId), directoryMime, clean
        ) ?: throw IOException("Провайдер не подтвердил создание папки.")
        val actual = stat(DocumentsContract.getDocumentId(created))
        if (actual.name != clean || !actual.isFolder) {
            throw IOException("Провайдер изменил название или тип папки.")
        }
        preferences.edit().remove(key).apply()
        return actual
    }

    override fun openRead(file: CloudNode): CloudRead {
        require(!file.isFolder && file.canReadBytes) { "Этот файл нельзя открыть через Android." }
        val stream = resolver.openInputStream(document(file.id))
            ?: throw IOException("Провайдер не открыл поток чтения.")
        return CloudRead(stream, file.sizeBytes)
    }

    override fun uploadNew(
        parentId: String,
        file: CloudNode,
        size: Long,
        source: InputStream
    ): CloudNode {
        require(size >= 0) { "Неизвестен размер файла." }
        val name = safeName(file.name)
        val key = ensureFree(parentId, name)
        val mime = file.mimeType?.takeUnless { it.isBlank() } ?: "application/octet-stream"
        val created = DocumentsContract.createDocument(
            resolver, document(parentId), mime, name
        ) ?: throw IOException("Провайдер не подтвердил создание файла.")
        // If writing or verification fails, keep the marker and do not delete or
        // silently retry: the remote provider may have accepted some or all data.
        val output = resolver.openOutputStream(created, "w")
            ?: throw IOException("Провайдер не открыл поток записи.")
        output.use { source.copyTo(it, 64 * 1024) }
        val actual = stat(DocumentsContract.getDocumentId(created))
        if (actual.name != name || actual.isFolder ||
            (actual.sizeBytes != null && actual.sizeBytes != size)
        ) throw IOException("Результат записи отличается от ожидаемого. Проверьте облако.")
        preferences.edit().remove(key).apply()
        return actual
    }
}

/** Persist a user-approved directory URI, never Google tokens. */
class SafFolderStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("mediahub_drive_saf_v1", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    fun connect(uri: Uri): SafFolderProvider {
        require(uri.scheme == "content") { "Нужно выбрать папку через Android." }
        resolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val provider = SafFolderProvider(context, uri)
        prefs.edit().putString("tree", uri.toString()).apply()
        return provider
    }

    fun restore(): SafFolderProvider? {
        val value = prefs.getString("tree", null) ?: return null
        val uri = Uri.parse(value)
        val granted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (granted) runCatching { SafFolderProvider(context, uri) }.getOrNull() else null
    }

    fun disconnect() {
        prefs.getString("tree", null)?.let { raw ->
            val uri = Uri.parse(raw)
            runCatching {
                resolver.releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit().remove("tree").apply()
    }
}
