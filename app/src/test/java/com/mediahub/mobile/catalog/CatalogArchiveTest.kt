package com.mediahub.mobile.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CatalogArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acceptsOnlyRecognizedZipEntriesWithoutExtractingOtherPaths() {
        val manifest = """{"catalog_type":"historical_seed_data"}"""
        val db = "SQLite format 3\u0000fixture".toByteArray()
        val a = zip("media_hub_seed.sqlite" to db, "import_manifest.json" to manifest.toByteArray(),
            "../unexpected.txt" to "ignore".toByteArray())
        CatalogArchive.open(temporary.root, a.inputStream()).use {
            assertEquals(manifest, it.manifestJson)
            assertFalse(temporary.root.resolve("unexpected.txt").exists())
            assertEquals(setOf("input", "archive.sqlite", "manifest.json"), it.database.parentFile!!.list()!!.toSet())
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun corruptedAndUnrecognizedInputsLeaveNoTemporaryFiles() {
        val badInputs = listOf("not a database".toByteArray(),
            "PK corrupt Zip".toByteArray(),
            zip("unknown.sqlite" to byteArrayOf(1, 2, 3)))
        for (bad in badInputs) {
            assertThrows(InvalidCatalog::class.java) {
                CatalogArchive.open(temporary.root, bad.inputStream())
            }
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content)
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
