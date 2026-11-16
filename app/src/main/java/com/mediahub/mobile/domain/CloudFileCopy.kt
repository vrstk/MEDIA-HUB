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
        pauseBeforeReadbackRetry: (Int) -> Unit = { attempt ->
            try {
                Thread.sleep(if (attempt == 1) 400L else 1200L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.util.concurrent.CancellationException("Проверка прервана.")
            }
        },
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


        // Upload succeeded as an API operation; do not upload again if the
        // provider is temporarily unable to reopen its newly created file.
        try {
            val digest = sourceHash.finish()
            for (attempt in 0..2) {
                checkpoint()
                if (attempt != 0) {
                    pauseBeforeReadbackRetry(attempt)
                    checkpoint()
                }
                report(CopyProgress(CopyPhase.VERIFYING, 0, expectedSize))
                try {
                    val saved = target.stat(uploaded.id)
                    if (saved.isFolder || saved.name != current.name) {
                        throw IOException("Провайдер вернул другой объект.")
                    }
                    if (saved.sizeBytes != null && saved.sizeBytes != expectedSize) {
                        throw IOException("Размер копии не совпадает.")
                    }
                    val actualHash = target.openRead(saved).use { read ->
                        val measured = MeasuredInput(read.stream, expectedSize, checkpoint) {
                            report(CopyProgress(CopyPhase.VERIFYING, it, expectedSize))
                        }
                        val buffer = ByteArray(64 * 1024)
                        while (measured.read(buffer) >= 0) {
                            // Readback validates size and SHA-256 without changing data.
                        }
                        measured.finish()
                    }
                    if (!MessageDigest.isEqual(digest, actualHash)) {
                        throw IOException("Контрольные суммы файлов не совпали.")
                    }
                    return expectedSize
                } catch (cancel: java.util.concurrent.CancellationException) {
                    throw cancel
                } catch (error: IOException) {
                    if (attempt == 2) throw error
                    // A new metadata lookup and readback only, never a second upload.
                }
            }
            throw IOException("Проверить копию не удалось.")
        } catch (cancel: java.util.concurrent.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            throw CopyVerificationException(error)
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
