package com.mediahub.mobile.catalog

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CatalogImporterTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var database: CatalogDatabase
    private lateinit var importer: CatalogImporter
    private val dao get() = database.catalog()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CatalogDatabase::class.java).build()
        importer = CatalogImporter(database, temp.root)
    }

    @After fun close() { database.close() }

    @Test fun importsRawZipAndNestedZipWithoutDuplicatingRows() = runBlocking {
        val sqlite = sampleDatabase()
        val seed = starterZip(sqlite)
        val project = temp.newFile().apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.entry("catalog/Media_Hub_Starter_Catalog.zip", seed.readBytes())
                zip.entry("docs/README.md", "fixture".toByteArray())
            }
        }
        assertEquals(CatalogImportResult(true, 3, 1, 1, 3), import(project))
        assertFalse(import(seed).added)
        assertFalse(import(sqlite).added)
        assertEquals(CatalogSummary(3, 1, 1, 1), dao.summary().first())
        assertEquals(2, dao.profiles(normalizedSearch("АННА"), false, null, 0, 10).size)
        assertEquals(1, dao.profiles(normalizedSearch("ЛЕКСА"), false, null, 0, 10).size)
        val unnamed = dao.profiles("", true, null, 0, 10).single()
        assertEquals("Без имени", unnamed.title)
        val tag = dao.terms("tag", normalizedSearch("ЛЕТ"), 0, 10).single()
        assertEquals("Summer", tag.label)
        assertEquals(tag, dao.terms("tag", "summer", 0, 10).single())
        val matching = dao.profiles("", false, tag.id, 0, 10)
        assertEquals(2, matching.size)
        assertEquals(setOf("category", "tag"), dao.profileTerms(matching.first { it.archiveName != null }.id)
            .map { it.kind }.toSet())
        assertEquals(listOf("READING", "CHECKING"), phasesFor(sqlite))
    }

    @Test fun archiveUpsertPreservesLocalNamesAndTheSourceIdentity() = runBlocking {
        assertTrue(import(sampleDatabase()).added)
        val old = dao.profiles("stranger", true, null, 0, 10).single()
        dao.saveProfileName(ProfileName(old.id, "Моё имя", normalizedSearch("Моё имя")))
        val folder = ProfileFolder(old.id, "MAIL", "test-account", "/Photos/", "Фото", "[]")
        dao.saveFolder(folder)
        assertEquals(0, dao.summary().first().unnamed)
        val updated = sampleDatabase(firstName = "Алина", unnamedName = "Имя из обновления", keepLastTag = false)
        assertTrue(import(updated).added)
        val renamed = dao.profiles(normalizedSearch("МОЁ ИМЯ"), false, null, 0, 10).single()
        assertEquals(old.id, renamed.id)
        assertEquals("Моё имя", renamed.title)
        assertEquals("Имя из обновления", renamed.archiveName)
        assertTrue(dao.profileTerms(old.id).isEmpty())
        assertEquals(listOf(folder), dao.profileFolders(old.id))
        assertEquals(3, dao.summary().first().profiles)
        dao.resetProfileName(old.id)
        assertEquals("Имя из обновления", dao.profiles("stranger", false, null, 0, 10).single().title)
    }

    @Test fun namesAndProfilesAreStillAvailableAfterReopeningDatabase() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "catalog-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, CatalogDatabase::class.java, name).build()
        try {
            val sqlite = sampleDatabase()
            val first = open()
            try {
                sqlite.inputStream().use { CatalogImporter(first, temp.root).importArchive(it) }
                val row = first.catalog().profiles("stranger", true, null, 0, 10).single()
                first.catalog().saveProfileName(ProfileName(row.id, "Личное имя", normalizedSearch("Личное имя")))
            } finally {
                first.close()
            }
            val reopened = open()
            try {
                assertEquals(CatalogSummary(3, 1, 1, 0), reopened.catalog().summary().first())
                assertEquals("Личное имя", reopened.catalog().profiles("stranger", false, null, 0, 10).single().title)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun failedImportRollsBackOnLateInvalidRow() {
        val badFile = sampleDatabase(badLink = true)
        assertThrows(InvalidCatalog::class.java) { runBlocking { import(badFile) } }
        runBlocking {
            assertEquals(CatalogSummary(0, 0, 0, 0), dao.summary().first())
            assertTrue(import(sampleDatabase()).added)
        }
    }

    @Test fun rejectsManifestCountsThatDifferFromTheDatabase() {
        val zip = starterZip(sampleDatabase(), JSONObject(metadata(false)).put("profiles_total", 95).toString())
        assertThrows(InvalidCatalog::class.java) { runBlocking { import(zip) } }
        runBlocking { assertEquals(0, dao.summary().first().profiles) }
    }

    @Test fun migratedProjectArchiveMatchesTheVerifiedUserSeed() = runBlocking {
        val path = System.getProperty("mediaHubSeedFile")
        assumeTrue("Set -PmediaHubSeedFile to run the supplied archive", !path.isNullOrBlank())
        val file = File(requireNotNull(path))
        assertTrue("Provided archive is readable", file.isFile)
        assertEquals(CatalogImportResult(true, 22_739, 118, 1_069, 15_307), import(file))
        assertEquals(CatalogSummary(22_739, 118, 1_069, 9_916), dao.summary().first())
        val firstPage = dao.profiles("", false, null, 0, 60)
        val firstNamed = firstPage.firstNotNullOf(ProfileRow::archiveName)
        assertTrue(dao.profiles(normalizedSearch(firstNamed), false, null, 0, 60)
            .any { it.archiveName == firstNamed })
        val secondPage = dao.profiles("", false, null, 60, 60)
        assertEquals(60, secondPage.size)
        assertTrue(firstPage.map { it.id }.intersect(secondPage.map { it.id }.toSet()).isEmpty())
        assertEquals(118, dao.terms("category", "", 0, 150).size)
        assertFalse(import(file).added)
        assertEquals(CatalogSummary(22_739, 118, 1_069, 9_916), dao.summary().first())
    }

    private suspend fun import(file: File): CatalogImportResult =
        file.inputStream().use { importer.importArchive(it) }

    private suspend fun phasesFor(file: File): List<String> = buildList {
        file.inputStream().use { source -> importer.importArchive(source) { phase -> add(phase.name) } }
    }

    private fun starterZip(sqlite: File, manifest: String = JSONObject(metadata(false)).toString()): File =
        temp.newFile().apply {
            ZipOutputStream(outputStream()).use {
                it.entry("media_hub_seed.sqlite", sqlite.readBytes())
                it.entry("import_manifest.json", manifest.toByteArray())
            }
        }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun sampleDatabase(
        firstName: String = "Анна",
        unnamedName: String? = null,
        badLink: Boolean = false,
        keepLastTag: Boolean = true
    ): File {
        val file = temp.newFile()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE catalog_source(source_key TEXT PRIMARY KEY, display_name TEXT, notes TEXT)")
            db.execSQL("""
                CREATE TABLE source_profile(
                    profile_id INTEGER PRIMARY KEY, source_key TEXT, name TEXT, profile_url TEXT,
                    aliases_raw TEXT, profile_snapshot_date TEXT, metadata_json TEXT,
                    source_status TEXT, hair_color_source TEXT, source_tags_raw TEXT, source_categories_raw TEXT,
                    gallery_count_snapshot INTEGER, archived_profile_url TEXT, name_source_url TEXT, site_id TEXT
                )
            """)
            db.execSQL("CREATE TABLE taxonomy_term(term_id INTEGER PRIMARY KEY, source_key TEXT, term_type TEXT," +
                "label_original TEXT, label_ru TEXT, source_count_snapshot INTEGER, source_url TEXT)")
            db.execSQL("CREATE TABLE profile_term(profile_id INTEGER, term_id INTEGER, source_field TEXT)")
            db.execSQL("CREATE TABLE review_queue(profile_id INTEGER, reason TEXT, status TEXT)")
            db.execSQL("CREATE TABLE catalog_meta(key TEXT PRIMARY KEY, value TEXT)")
            db.execSQL("INSERT INTO catalog_source VALUES ('demo', 'Архив теста', '')")
            val insert = "INSERT INTO source_profile(profile_id, source_key, name, profile_url, " +
                "aliases_raw, profile_snapshot_date, metadata_json) VALUES (?, 'demo', ?, ?, ?, '2026-11-12', '{}')"
            db.execSQL(insert, arrayOf<Any?>(1, firstName, "https://example.test/first", "Лекса"))
            db.execSQL(insert, arrayOf<Any?>(2, "Анна", "https://example.test/second", null))
            db.execSQL(insert, arrayOf<Any?>(3, unnamedName, "https://example.test/stranger", null))
            db.execSQL("INSERT INTO taxonomy_term VALUES (1, 'demo', 'category', 'Travel', 'Путешествия', 1, 'https://example.test/category')")
            db.execSQL("INSERT INTO taxonomy_term VALUES (2, 'demo', 'tag', 'Summer', 'Лето', 2, 'https://example.test/tag')")
            db.execSQL("INSERT INTO profile_term VALUES (1, 1, 'profile_categories'), (1, 2, 'profile_tags')")
            if (keepLastTag) db.execSQL("INSERT INTO profile_term VALUES (3, 2, ?)",
                arrayOf(if (badLink) "invalid" else "profile_tags"))
            db.execSQL("INSERT INTO review_queue VALUES (3, 'missing_name', 'pending')")
            metadata(unnamedName != null, keepLastTag).forEach { (key, value) ->
                db.execSQL("INSERT INTO catalog_meta VALUES (?, ?)", arrayOf(key, value.toString()))
            }
        }
        return file
    }

    private fun metadata(allNamed: Boolean, lastTag: Boolean = true) = mapOf(
        "catalog_type" to "historical_seed_data",
        "data_is_live_verified" to false,
        "media_files_included" to false,
        "profiles_total" to 3,
        "profiles_with_name" to (if (allNamed) 3 else 2),
        "profiles_without_name" to (if (allNamed) 0 else 1),
        "categories_total" to 1,
        "tags_total" to 1,
        "taxonomy_total" to 2,
        "profile_taxon_associations" to (if (lastTag) 3 else 2),
        "profile_source_snapshot" to "2026-11-12",
        "tag_source_snapshot" to "2026-11-11"
    )
}
