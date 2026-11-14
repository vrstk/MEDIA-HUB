package com.mediahub.mobile.domain

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class DestinationExistsException : IOException("В папке назначения уже есть объект с таким именем.")
class CopyVerificationException(cause: Exception) :
    IOException("Файл мог сохраниться, но проверить его не удалось. Проверьте папку назначения.", cause)

enum class CopyPhase { COPYING, VERIFYING }

data class CopyProgress(val phase: CopyPhase, val done: Long, val total: Long)

object CloudFileCopy {
    /**
     * Streams one file through the device, then re-reads the destination and compares SHA-256.
     * Cancellation or a failed check never triggers a retry, replacement, or source deletion.
     */
    fun run(
        source: CloudProvider,
        file: CloudNode,
        target: CloudProvider,
        folder: CloudNode,
        checkpoint: () -> Unit = {},
        report: (CopyProgress) -> Unit = {}
    ): Long {
        require(!file.isFolder && file.canReadBytes) { "Этот объект нельзя скопировать как файл." }
        require(folder.isFolder) { "Для копирования нужна папка назначения." }
        val current = source.stat(file.id)
        require(!current.isFolder && current.canReadBytes && current.name == file.name) {
            "Исходный файл изменился. Обновите папку."
        }
        if (file.sizeBytes != null && current.sizeBytes != null && file.sizeBytes != current.sizeBytes) {
            throw IOException("Размер исходного файла изменился. Обновите папку.")
        }
        checkpoint()
        val (uploaded, sourceHash, expectedSize) = source.openRead(current).use { read ->
            val expected = current.sizeBytes ?: read.sizeBytes
                ?: throw IOException("Сервер не сообщил размер исходного файла.")
            if (read.sizeBytes != null && read.sizeBytes != expected) {
                throw IOException("Размер файла изменился при чтении. Обновите папку.")
            }
            report(CopyProgress(CopyPhase.COPYING, 0, expected))
            val measured = MeasuredInput(read.stream, expected, checkpoint) {
                report(CopyProgress(CopyPhase.COPYING, it, expected))
            }
            val result = target.uploadNew(folder.id, current, expected, measured)
            Triple(result, measured, expected)
        }

        try {
            checkpoint()
            val digest = sourceHash.finish()
            val saved = target.stat(uploaded.id)
            if (saved.isFolder || saved.name != current.name) {
                throw IOException("Сервер вернул другой объект.")
            }
            if (saved.sizeBytes != null && saved.sizeBytes != expectedSize) {
                throw IOException("Размер копии не совпадает.")
            }
            report(CopyProgress(CopyPhase.VERIFYING, 0, expectedSize))
            val verified = target.openRead(saved).use { read ->
                val measured = MeasuredInput(read.stream, expectedSize, checkpoint) {
                    report(CopyProgress(CopyPhase.VERIFYING, it, expectedSize))
                }
                val buffer = ByteArray(64 * 1024)
                while (measured.read(buffer) >= 0) {
                    // MeasuredInput updates SHA-256 and checks the length incrementally.
                }
                measured.finish()
            }
            if (!MessageDigest.isEqual(digest, verified)) {
                throw IOException("Контрольные суммы файлов не совпали.")
            }
            return expectedSize
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CopyVerificationException(e)
        }
    }

    private class MeasuredInput(
        input: InputStream,
        private val expected: Long,
        private val checkpoint: () -> Unit,
        private val report: (Long) -> Unit
    ) : FilterInputStream(input) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var read = 0L

        override fun read(): Int {
            checkpoint()
            val value = `in`.read()
            if (value != -1) {
                consumed(1)
                digest.update(value.toByte())
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkpoint()
            val count = `in`.read(buffer, offset, length)
            if (count > 0) {
                consumed(count)
                digest.update(buffer, offset, count)
            }
            return count
        }

        private fun consumed(count: Int) {
            read += count
            if (read > expected) throw IOException("Сервер передал больше данных, чем было заявлено.")
            report(read)
        }

        fun finish(): ByteArray {
            if (read != expected) throw IOException("Передача оборвалась до конца файла.")
            return digest.digest()
        }
    }
}
