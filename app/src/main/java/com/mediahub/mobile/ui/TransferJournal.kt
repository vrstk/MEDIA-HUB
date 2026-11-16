package com.mediahub.mobile.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** No tokens, passwords, provider IDs or folder URIs are written to the journal. */
internal enum class TransferJournalStatus {
    RUNNING, VERIFIED, NEEDS_REVIEW, FAILED, CANCELLED, INTERRUPTED
}

internal data class TransferJournalEntry(
    val id: String,
    val name: String,
    val origin: String,
    val destination: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: TransferJournalStatus,
    val fileCount: Int = 0,
    val byteCount: Long = 0L,
    val note: String = ""
)

internal object TransferJournalCodec {
    const val MAX_ENTRIES = 50

    fun encode(items: List<TransferJournalEntry>): String {
        val array = JSONArray()
        items.take(MAX_ENTRIES).forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("name", item.name.take(200))
                .put("origin", item.origin)
                .put("destination", item.destination)
                .put("startedAt", item.startedAt)
                .put("finishedAt", item.finishedAt ?: JSONObject.NULL)
                .put("status", item.status.name)
                .put("fileCount", item.fileCount)
                .put("byteCount", item.byteCount)
                .put("note", item.note.take(400))
            )
        }
        return array.toString()
    }

    fun decode(source: String?): List<TransferJournalEntry> {
        if (source.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(source)
            val result = mutableListOf<TransferJournalEntry>()
            for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
                val item = array.optJSONObject(index) ?: continue
                val status = runCatching {
                    TransferJournalStatus.valueOf(item.optString("status"))
                }.getOrNull() ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                result.add(
                    TransferJournalEntry(
                        id = id.take(100),
                        name = item.optString("name").take(200),
                        origin = item.optString("origin").take(80),
                        destination = item.optString("destination").take(80),
                        startedAt = item.optLong("startedAt").coerceAtLeast(0),
                        finishedAt = if (item.isNull("finishedAt")) null
                            else item.optLong("finishedAt").coerceAtLeast(0),
                        status = status,
                        fileCount = item.optInt("fileCount").coerceAtLeast(0),
                        byteCount = item.optLong("byteCount").coerceAtLeast(0),
                        note = item.optString("note").take(400)
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun markInterrupted(items: List<TransferJournalEntry>, whenRestored: Long) =
        items.map { item ->
            if (item.status == TransferJournalStatus.RUNNING) item.copy(
                status = TransferJournalStatus.INTERRUPTED,
                finishedAt = whenRestored,
                note = "Работа приложения завершилась до подтверждения результата. " +
                    "Проверьте папку назначения перед новым переносом."
            ) else item
        }
}

/** Durable, size-limited, device-local history. No automatic retry or reupload. */
internal class TransferJournalStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "media_hub_transfer_journal_v1", Context.MODE_PRIVATE
    )
    private val key = "entries"

    @Synchronized
    fun restore(): List<TransferJournalEntry> {
        val entries = TransferJournalCodec.decode(preferences.getString(key, null))
        val corrected = TransferJournalCodec.markInterrupted(entries, System.currentTimeMillis())
        if (corrected != entries) save(corrected)
        return corrected
    }

    @Synchronized
    fun restoreForDisplay(): List<TransferJournalEntry> =
        TransferJournalCodec.decode(preferences.getString(key, null))

    @Synchronized
    fun begin(name: String, from: CloudSite): TransferJournalEntry {
        val entry = TransferJournalEntry(
            id = UUID.randomUUID().toString(),
            name = name.take(200),
            origin = from.label,
            destination = from.other.label,
            startedAt = System.currentTimeMillis(),
            finishedAt = null,
            status = TransferJournalStatus.RUNNING
        )
        save(listOf(entry) + TransferJournalCodec.decode(preferences.getString(key, null)))
        return entry
    }

    @Synchronized
    fun finish(
        id: String,
        status: TransferJournalStatus,
        fileCount: Int = 0,
        byteCount: Long = 0L,
        message: String = ""
    ): List<TransferJournalEntry> {
        val entries = TransferJournalCodec.decode(preferences.getString(key, null))
        val updated = entries.map { entry ->
            if (entry.id != id || entry.status != TransferJournalStatus.RUNNING) entry
            else entry.copy(
                status = status,
                finishedAt = System.currentTimeMillis(),
                fileCount = fileCount.coerceAtLeast(0),
                byteCount = byteCount.coerceAtLeast(0L),
                note = message.take(400)
            )
        }
        save(updated)
        return updated
    }

    private fun save(items: List<TransferJournalEntry>) {
        // commit() is intentional: a RUNNING record must survive process death
        // before any changes are made to destination folders.
        if (!preferences.edit().putString(key, TransferJournalCodec.encode(items)).commit()) {
            throw IOException("Не удалось сохранить журнал передач на устройстве.")
        }
    }
}
