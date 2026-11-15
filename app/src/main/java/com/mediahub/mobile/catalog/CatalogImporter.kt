package com.mediahub.mobile.catalog

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.Locale

internal enum class ImportPhase { READING, CHECKING, SAVING }

internal data class CatalogImportResult(
    val added: Boolean,
    val profiles: Int,
    val categories: Int,
    val tags: Int,
    val links: Int
)

internal class CatalogImporter(private val database: CatalogDatabase, private val cache: File) {
    private val dao = database.catalog()

    suspend fun importArchive(source: InputStream, phase: (ImportPhase) -> Unit = {}): CatalogImportResult =
        withContext(Dispatchers.IO) {
            val jobContext = coroutineContext
            phase(ImportPhase.READING)
            try {
                CatalogArchive.open(cache, source) { jobContext.ensureActive() }.use { archive ->
                    phase(ImportPhase.CHECKING)
                    val digest = archive.digest { jobContext.ensureActive() }
                    val options = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                    SQLiteDatabase.openDatabase(archive.database.path, null, options).use { sqlite ->
                        val record = sqlite.inspect(digest, archive.manifestJson)
                        jobContext.ensureActive()
                        val saved = database.withTransaction {
                            if (dao.hasImport(digest)) return@withTransaction false
                            phase(ImportPhase.SAVING)
                            sqlite.importRows(record.taxonomySnapshot)
                            dao.recordImport(record)
                            true
                        }
                        CatalogImportResult(saved, record.profileCount, record.categoryCount, record.tagCount, record.linkCount)
                    }
                }
            } catch (_: JSONException) {
                throw InvalidCatalog("В каталоге повреждён манифест.")
            } catch (_: SQLiteException) {
                throw InvalidCatalog("Не удалось прочитать или сохранить каталог. Проверьте файл и свободное место.")
            }
        }

    private fun SQLiteDatabase.inspect(digest: String, manifestText: String?): CatalogImport {
        execSQL("PRAGMA trusted_schema = OFF")
        val tables = rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { rows ->
            buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
        }
        if (!tables.containsAll(REQUIRED_TABLES)) {
            throw InvalidCatalog("Структура SQLite не соответствует стартовому каталогу Media Hub.")
        }
        if (!rawQuery("PRAGMA integrity_check(1)", null).use { it.moveToFirst() && it.getString(0) == "ok" } ||
            rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }
        ) throw InvalidCatalog("SQLite не прошёл проверку целостности или связей.")

        val meta = rawQuery("SELECT key, value FROM catalog_meta LIMIT 256", null).use { rows ->
            buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getString(1)) }
        }
        val manifest = manifestText?.let(::JSONObject)
        val profiles = count("SELECT COUNT(*) FROM source_profile")
        val categories = count("SELECT COUNT(*) FROM taxonomy_term WHERE term_type = 'category'")
        val tags = count("SELECT COUNT(*) FROM taxonomy_term WHERE term_type = 'tag'")
        val links = count("SELECT COUNT(*) FROM profile_term")
        val named = count("SELECT COUNT(*) FROM source_profile WHERE NULLIF(TRIM(name), '') IS NOT NULL")
        if (profiles !in 1..100_000 || categories + tags > 30_000 || links > 500_000L ||
            count("SELECT COUNT(*) FROM catalog_source") !in 1..1_000 ||
            count("SELECT COUNT(*) FROM taxonomy_term") != categories + tags
        ) throw InvalidCatalog("Объём или состав каталога выходит за поддерживаемые пределы.")

        val amount = mapOf(
            "profiles_total" to profiles, "profiles_with_name" to named, "profiles_without_name" to profiles - named,
            "categories_total" to categories, "tags_total" to tags, "taxonomy_total" to categories + tags,
            "profile_taxon_associations" to links
        )
        val properties = mapOf("catalog_type" to "historical_seed_data", "media_files_included" to "false",
            "data_is_live_verified" to "false")
        if (amount.any { (key, value) -> meta[key]?.toLongOrNull() != value ||
                (manifest != null && manifest.optLong(key, -1) != value) } ||
            properties.any { (key, value) -> meta[key] != value ||
                (manifest != null && manifest.optString(key) != value) }
        ) throw InvalidCatalog("Манифест и данные SQLite расходятся.")

        fun snapshot(key: String): String? {
            val date = meta[key]
            if (manifest != null && manifest.optString(key) != date) {
                throw InvalidCatalog("Дата архивного снимка расходится с манифестом.")
            }
            return date
        }
        return CatalogImport(
            digest, System.currentTimeMillis(), snapshot("profile_source_snapshot"),
            snapshot("tag_source_snapshot"), manifestText, profiles.toInt(),
            categories.toInt(), tags.toInt(), links.toInt()
        )
    }

    private suspend fun SQLiteDatabase.importRows(taxonomyDate: String?) {
        batches("SELECT source_key, display_name, notes FROM catalog_source ORDER BY source_key", { row ->
            CatalogSource(row.required("source_key"), row.required("display_name"), row.required("notes", allowEmpty = true))
        }, dao::writeSources)
        batches(
            """
                SELECT p.*, r.reason AS review_reason, r.status AS review_status
                FROM source_profile p LEFT JOIN review_queue r ON p.profile_id = r.profile_id
                ORDER BY p.profile_id
            """,
            { row ->
                val source = row.required("source_key")
                val url = row.required("profile_url")
                val name = row.string("name")?.trim()?.takeIf(String::isNotEmpty)
                val aliases = row.string("aliases_raw")
                SourceProfile(
                    id = entryKey(source, url), sourceKey = source, sourceUrl = url, displayName = name,
                    sourceSnapshot = row.string("profile_snapshot_date"), aliases = aliases,
                    sourceStatus = row.string("source_status"), hairColor = row.string("hair_color_source"),
                    tagsRaw = row.string("source_tags_raw"), categoriesRaw = row.string("source_categories_raw"),
                    galleryCount = row.optionalInt("gallery_count_snapshot"), archivedUrl = row.string("archived_profile_url"),
                    nameSourceUrl = row.string("name_source_url"), siteId = row.string("site_id"),
                    reviewReason = row.string("review_reason"), reviewStatus = row.string("review_status"),
                    metadataJson = row.required("metadata_json"),
                    searchText = normalizedSearch(listOfNotNull(name, aliases, url).joinToString("\n")),
                    sortName = name?.let(::normalizedSearch) ?: "\uFFFF"
                )
            },
            { profiles ->
                dao.writeProfiles(profiles)
                dao.clearLinksFor(profiles.map(SourceProfile::id))
            }
        )
        batches("SELECT * FROM taxonomy_term ORDER BY term_id", { row ->
            val source = row.required("source_key")
            val url = row.required("source_url")
            val ru = row.required("label_ru")
            val original = row.required("label_original")
            TaxonomyTerm(
                entryKey(source, url), source, url, row.required("term_type"), original, ru,
                row.optionalInt("source_count_snapshot"), taxonomyDate,
                normalizedSearch("$ru\n$original"), normalizedSearch(ru)
            )
        }, dao::writeTaxonomy)
        batches(
            """
                SELECT p.source_key AS profile_source, p.profile_url, t.source_key AS term_source,
                       t.source_url, pt.source_field
                FROM profile_term pt
                LEFT JOIN source_profile p ON pt.profile_id = p.profile_id
                LEFT JOIN taxonomy_term t ON pt.term_id = t.term_id
                ORDER BY pt.profile_id, pt.term_id, pt.source_field
            """,
            { row ->
                val field = row.required("source_field")
                if (field !in setOf("profile_tags", "profile_categories")) {
                    throw InvalidCatalog("В каталоге обнаружен неизвестный тип связи.")
                }
                ProfileTerm(
                    entryKey(row.required("profile_source"), row.required("profile_url")),
                    entryKey(row.required("term_source"), row.required("source_url")), field
                )
            },
            dao::writeLinks
        )
    }

    private suspend fun <T> SQLiteDatabase.batches(sql: String, map: (Cursor) -> T, write: suspend (List<T>) -> Unit) {
        val execution = kotlin.coroutines.coroutineContext
        rawQuery(sql, null).use { rows ->
            val batch = ArrayList<T>(256)
            while (rows.moveToNext()) {
                execution.ensureActive()
                batch.add(map(rows))
                if (batch.size == 256) {
                    write(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) write(batch)
        }
    }

    private fun SQLiteDatabase.count(sql: String): Long = rawQuery(sql, null).use {
        check(it.moveToFirst())
        it.getLong(0)
    }

    private fun Cursor.string(key: String): String? = getString(getColumnIndexOrThrow(key))

    private fun Cursor.required(key: String, allowEmpty: Boolean = false): String =
        string(key)?.takeIf { allowEmpty || it.isNotBlank() }
            ?: throw InvalidCatalog("В каталоге отсутствуют обязательные поля.")

    private fun Cursor.optionalInt(key: String): Int? =
        getColumnIndexOrThrow(key).let { column -> if (isNull(column)) null else getInt(column) }

    private fun entryKey(source: String, url: String): String {
        if (source.length > 128 || source.any { it.isISOControl() } || url.length > 2_000 || !isCatalogWebUrl(url)) {
            throw InvalidCatalog("В каталоге обнаружен некорректный адрес источника.")
        }
        return "$source\u001F$url"
    }

    private companion object {
        val REQUIRED_TABLES = setOf("catalog_source", "source_profile", "taxonomy_term", "profile_term", "review_queue", "catalog_meta")
    }
}

internal fun normalizedSearch(value: String): String = value.trim().lowercase(Locale.ROOT)

internal fun isCatalogWebUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)
