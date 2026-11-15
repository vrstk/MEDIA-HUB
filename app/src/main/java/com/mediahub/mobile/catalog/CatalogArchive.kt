package com.mediahub.mobile.catalog

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal class InvalidCatalog(message: String) : IOException(message)

internal class CatalogArchive private constructor(
    val database: File,
    val manifestJson: String?,
    private val work: File
) : Closeable {
    fun digest(checkpoint: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        database.inputStream().buffered().use { source ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                checkpoint()
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun close() {
        work.deleteRecursively()
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val MAX_INPUT = 128 * 1024 * 1024
        private const val MAX_MANIFEST = 128 * 1024
        private const val SQLITE = "media_hub_seed.sqlite"
        private const val MANIFEST = "import_manifest.json"
        private const val INNER_ARCHIVE = "Media_Hub_Starter_Catalog.zip"
        private val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        /** Extract only the recognized entries, to temporary names chosen by the app. */
        fun open(cache: File, source: InputStream, checkpoint: () -> Unit = {}): CatalogArchive {
            val work = Files.createTempDirectory(cache.toPath(), "catalog-").toFile()
            try {
                val input = File(work, "input")
                copy(source, input, MAX_INPUT, checkpoint)
                val head = input.inputStream().use { it.readNBytesCompat(sqliteHeader.size) }
                if (head.contentEquals(sqliteHeader)) return CatalogArchive(input, null, work)
                if (head.size < 4 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
                    throw InvalidCatalog("Выберите ZIP с каталогом Media Hub или исходный SQLite-файл.")
                }

                return ZipFile(input).use { outer ->
                    val direct = outer.findUnique(SQLITE)
                    if (direct != null) extract(outer, direct, work, checkpoint)
                    else {
                        val nested = outer.findUnique("catalog/$INNER_ARCHIVE", INNER_ARCHIVE)
                            ?: throw InvalidCatalog("В ZIP не найден стартовый каталог Media Hub.")
                        val inner = File(work, "catalog.zip")
                        outer.getInputStream(nested).use { copy(it, inner, MAX_INPUT, checkpoint) }
                        ZipFile(inner).use { zip ->
                            val sqlite = zip.findUnique(SQLITE)
                                ?: throw InvalidCatalog("В архиве отсутствует $SQLITE.")
                            extract(zip, sqlite, work, checkpoint)
                        }
                    }
                }
            } catch (e: Exception) {
                work.deleteRecursively()
                if (e is java.util.zip.ZipException) {
                    throw InvalidCatalog("ZIP повреждён или имеет неподдерживаемый формат.")
                }
                throw e
            }
        }

        private fun extract(zip: ZipFile, sqlite: ZipEntry, work: File, checkpoint: () -> Unit): CatalogArchive {
            val entry = zip.findUnique(MANIFEST)
                ?: throw InvalidCatalog("В каталоге отсутствует файл $MANIFEST.")
            if (sqlite.size !in 1L..MAX_INPUT.toLong() || entry.size !in 1L..MAX_MANIFEST.toLong()) {
                throw InvalidCatalog("Размер каталога или манифеста выходит за допустимый предел.")
            }
            val db = File(work, "archive.sqlite")
            zip.getInputStream(sqlite).use { copy(it, db, MAX_INPUT, checkpoint) }
            if (!db.inputStream().use { it.readNBytesCompat(sqliteHeader.size) }.contentEquals(sqliteHeader)) {
                throw InvalidCatalog("В архиве нет корректного файла SQLite.")
            }
            val manifest = File(work, "manifest.json")
            zip.getInputStream(entry).use { copy(it, manifest, MAX_MANIFEST, checkpoint) }
            return CatalogArchive(db, manifest.readText(), work)
        }

        private fun ZipFile.findUnique(vararg names: String): ZipEntry? {
            if (size() > 10_000) throw InvalidCatalog("В ZIP слишком много записей.")
            val matches = entries().asSequence().filter { !it.isDirectory && it.name in names }.toList()
            if (matches.size > 1) throw InvalidCatalog("В ZIP есть повторяющиеся файлы каталога.")
            return matches.singleOrNull()
        }

        private fun copy(source: InputStream, target: File, max: Int, checkpoint: () -> Unit) {
            target.outputStream().buffered().use { sink ->
                val buffer = ByteArray(BUFFER)
                var total = 0
                while (true) {
                    checkpoint()
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count > max - total) throw InvalidCatalog("Файл каталога превышает допустимый размер.")
                    sink.write(buffer, 0, count)
                    total += count
                }
            }
        }

        private fun InputStream.readNBytesCompat(length: Int): ByteArray {
            val result = ByteArray(length)
            var total = 0
            while (total < length) {
                val count = read(result, total, length - total)
                if (count < 0) break
                total += count
            }
            return result.copyOf(total)
        }
    }
}
