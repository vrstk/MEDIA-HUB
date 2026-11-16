package com.mediahub.mobile.catalog

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CatalogMigrationTest {
    @Test fun upgradesThePublishedSchemaAndKeepsProfilesNamesTagsAndImportHistory() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = context.getDatabasePath("mediahub-catalog.db")
        path.parentFile!!.mkdirs()
        try {
            createVersionOne(path)
            val first = CatalogDatabase.open(context)
            try {
                val dao = first.catalog()
                assertEquals(2, first.openHelper.writableDatabase.version)
                assertEquals("Своё имя", dao.profile("known")?.title)
                assertEquals("Из архива", dao.profile("known")?.archiveName)
                assertEquals(listOf("Travel"), dao.profileTerms("known").map(TaxonomyTerm::label))
                assertTrue(dao.hasImport("previous"))
                assertTrue(dao.profileFolders("known").isEmpty())

                val mail = ProfileFolder("known", "MAIL", "account-one", "/Photos/", "Снимки", "[]")
                val other = mail.copy(scopeKey = "account-two", title = "Другая учётная запись")
                val drive = mail.copy(storage = "ANDROID_DRIVE", scopeKey = "tree-one", folderId = "opaque-id", title = "Диск")
                dao.saveFolder(mail)
                dao.saveFolder(mail.copy(title = "Фото"))
                dao.saveFolder(other)
                dao.saveFolder(drive)
                assertEquals(setOf("Фото", "Диск", "Другая учётная запись"),
                    dao.profileFolders("known").map(ProfileFolder::title).toSet())
                dao.removeFolder(mail)
                assertEquals(setOf(other, drive), dao.profileFolders("known").toSet())
                assertEquals("Своё имя", dao.profile("known")?.title)
            } finally {
                first.close()
            }

            val reopened = CatalogDatabase.open(context)
            try {
                assertEquals(2, reopened.catalog().profileFolders("known").size)
                assertTrue(reopened.catalog().hasImport("previous"))
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(path.name)
        }
        Unit
    }

    private fun createVersionOne(path: java.io.File) {
        val resource = "/com.mediahub.mobile.catalog.CatalogDatabase/1.json"
        val schemaJson = checkNotNull(javaClass.getResourceAsStream(resource)).bufferedReader().use { it.readText() }
        val schema = JSONObject(schemaJson).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO catalog_sources VALUES ('archive', 'Источник', '')")
            db.execSQL("""
                INSERT INTO source_profiles
                    (id, sourceKey, sourceUrl, displayName, metadataJson, searchText, sortName)
                VALUES ('known', 'archive', 'https://example.test/profile', 'Из архива', '{}', 'из архива', 'из архива')
            """)
            db.execSQL("INSERT INTO profile_names VALUES ('known', 'Своё имя', 'своё имя')")
            db.execSQL("""
                INSERT INTO taxonomy_terms
                    (id, sourceKey, sourceUrl, kind, label, labelRu, searchText, sortLabel)
                VALUES ('travel', 'archive', 'https://example.test/travel', 'category', 'Travel', 'Путешествия', 'travel', 'путешествия')
            """)
            db.execSQL("INSERT INTO profile_terms VALUES ('known', 'travel', 'profile_categories')")
            db.execSQL("""
                INSERT INTO catalog_imports
                    (digest, importedAt, profileCount, categoryCount, tagCount, linkCount)
                VALUES ('previous', 0, 1, 1, 0, 1)
            """)
            db.version = 1
        }
    }
}
