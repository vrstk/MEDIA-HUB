package com.mediahub.mobile.domain

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

data class FolderCopyProgress(
    val currentName: String,
    val completedFiles: Int,
    val totalFiles: Int,
    val completedBytes: Long,
    val totalBytes: Long,
    val phase: CopyPhase?
)

data class FolderCopyResult(
    val copiedFiles: Int,
    val copiedBytes: Long,
    val createdFolders: Int,
    val reusedFiles: Int = 0
)

/** File creation returned successfully, but checksum verification is not yet confirmed. */
class FolderVerificationPendingException(
    val verifiedFiles: Int,
    val totalFiles: Int,
    val pendingFile: String,
    cause: Exception
) : IOException("Последний файл создан, но контрольная проверка не завершилась.", cause)

/**
 * Preflight scans the entire source tree before creating anything. Copying is
 * non-destructive and non-overwriting. On an interrupted transfer, partial
 * destination folders remain for manual inspection; the operation never
 * automatically restarts and never deletes originals.
 */
object CloudFolderCopy {
    private const val MAX_DEPTH = 32
    private const val MAX_ITEMS = 10_000

    private sealed interface Entry {
        val node: CloudNode
        data class File(override val node: CloudNode) : Entry
        data class Folder(override val node: CloudNode, val entries: List<Entry>) : Entry
    }

    private data class Plan(val folder: Entry.Folder, val files: Int, val bytes: Long)


    /**
     * A manual reconciliation is opt-in and requires the same-named destination
     * folder to already exist. The existing files are never written to.
     * Only missing files/folders may be created after all source metadata checks.
     */
    fun run(
        source: CloudProvider,
        folder: CloudNode,
        target: CloudProvider,
        destination: CloudNode,
        checkpoint: () -> Unit = {},
        report: (FolderCopyProgress) -> Unit = {},
        reconcileExisting: Boolean = false
    ): FolderCopyResult {
        require(folder.isFolder && destination.isFolder) { "Нужны две папки." }
        val plan = inspect(source, folder, checkpoint)
        checkpoint()
        var completedFiles = 0
        var completedBytes = 0L
        var createdFolders = 0
        var reusedFiles = 0
        var activePath = folder.name
        var activeOperation = "Подготовка"

        // Every listing, including pages, must be read completely. A partial list
        // cannot establish whether a name is free on a cloud provider.
        fun destinationEntries(parentId: String): Map<String, CloudNode> {
            val byName = linkedMapOf<String, CloudNode>()
            val seenTokens = hashSetOf<String>()
            var pageToken: String? = null
            do {
                checkpoint()
                val page = target.list(parentId, pageToken)
                for (node in page.nodes) {
                    checkpoint()
                    require(byName.putIfAbsent(node.name, node) == null) {
                        "В папке назначения есть объекты с одинаковыми именами. " +
                            "Автоматическая сверка остановлена."
                    }
                    require(byName.size <= MAX_ITEMS) {
                        "Папка назначения слишком велика для безопасной сверки."
                    }
                }
                pageToken = page.nextPageToken
                require(pageToken == null || seenTokens.add(pageToken)) {
                    "Провайдер повторил страницу каталога назначения."
                }
            } while (pageToken != null)
            return byName
        }

        fun sha256(provider: CloudProvider, node: CloudNode, expectedSize: Long): ByteArray {
            checkpoint()
            val actual = provider.stat(node.id)
            if (actual.isFolder || !actual.canReadBytes || actual.name != node.name) {
                throw IOException("Объект «${node.name}» недоступен для проверки.")
            }
            if (actual.sizeBytes != null && actual.sizeBytes != expectedSize) {
                throw IOException("У файла «${node.name}» отличается размер.")
            }
            val hash = MessageDigest.getInstance("SHA-256")
            provider.openRead(actual).use { read ->
                if (read.sizeBytes != null && read.sizeBytes != expectedSize) {
                    throw IOException("Размер потока «${node.name}» изменился.")
                }
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    checkpoint()
                    val count = read.stream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    if (size > expectedSize) {
                        throw IOException("Размер файла «${node.name}» превышает ожидаемый.")
                    }
                    hash.update(buffer, 0, count)
                }
                if (size != expectedSize) {
                    throw IOException("Файл «${node.name}» прочитан не полностью.")
                }
            }
            return hash.digest()
        }

        fun verifySameContent(sourceFile: CloudNode, targetFile: CloudNode): Long {
            val length = sourceFile.sizeBytes
                ?: throw IOException("Неизвестен размер исходного файла.")
            val originalHash = sha256(source, sourceFile, length)
            val savedHash = sha256(target, targetFile, length)
            if (!MessageDigest.isEqual(originalHash, savedHash)) {
                throw IOException(
                    "Файл «${sourceFile.name}» уже есть, но SHA-256 отличается. " +
                        "Ничего не перезаписано."
                )
            }
            return length
        }

        fun done(node: CloudNode, size: Long) {
            completedFiles++
            completedBytes += size
            report(FolderCopyProgress(
                node.name, completedFiles, plan.files,
                completedBytes, plan.bytes, null
            ))
        }

        fun copyRecursively(
            tree: Entry.Folder, targetParent: CloudNode,
            existing: CloudNode?, relativePath: String
        ) {
            checkpoint()
            activePath = relativePath
            val currentFolder = when {
                existing == null -> {
                    activeOperation = "Создание папки"
                    target.createFolder(targetParent.id, tree.node.name).also { createdFolders++ }
                }
                !existing.isFolder -> throw IOException(
                    "На месте папки «$relativePath» находится файл."
                )
                else -> existing
            }
            val targets = if (reconcileExisting) destinationEntries(currentFolder.id) else emptyMap()
            for (child in tree.entries) {
                checkpoint()
                val childPath = "$relativePath/${child.node.name}"
                activePath = childPath
                val match = targets[child.node.name]
                when (child) {
                    is Entry.Folder -> copyRecursively(
                        child, currentFolder, match, childPath
                    )
                    is Entry.File -> {
                        val bytes = if (match != null) {
                            activeOperation = "Сверка"
                            if (match.isFolder) throw IOException(
                                "На месте файла «$childPath» находится папка."
                            )
                            verifySameContent(child.node, match).also { reusedFiles++ }
                        } else {
                            activeOperation = "Передача и проверка"
                            CloudFileCopy.run(
                                source, child.node, target, currentFolder,
                                checkpoint = checkpoint,
                                report = { progress ->
                                    report(FolderCopyProgress(
                                        currentName = child.node.name,
                                        completedFiles = completedFiles,
                                        totalFiles = plan.files,
                                        completedBytes = completedBytes +
                                            progress.done.coerceAtMost(child.node.sizeBytes ?: progress.total),
                                        totalBytes = plan.bytes,
                                        phase = progress.phase
                                    ))
                                }
                            )
                        }
                        done(child.node, bytes)
                    }
                }
            }
        }

        try {
            val existingRoot = if (reconcileExisting) {
                activeOperation = "Поиск папки назначения"
                destinationEntries(destination.id)[folder.name]
                    ?: throw IOException(
                        "Папки «${folder.name}» в назначении нет. " +
                            "Для первого переноса отключите режим сверки."
                    )
            } else null
            copyRecursively(plan.folder, destination, existingRoot, folder.name)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (error is CopyVerificationException) {
                throw FolderVerificationPendingException(
                    completedFiles, plan.files, activePath, error
                )
            }
            val why = generateSequence(error as Throwable?) { it.cause }
                .mapNotNull { it?.message }
                .filter { it.length in 1..180 && !it.contains("://") }
                .lastOrNull() ?: "неизвестная причина"
            throw IOException(
                "$activeOperation «$activePath» остановлена: $why. " +
                    "Сверено $completedFiles из ${plan.files} файлов. " +
                    "Ранее созданные файлы не изменялись, исходники не удалены.",
                error
            )
        }
        return FolderCopyResult(completedFiles, completedBytes, createdFolders, reusedFiles)
    }

    private fun inspect(
        source: CloudProvider,
        start: CloudNode,
        checkpoint: () -> Unit
    ): Plan {
        val visited = HashSet<String>()
        var count = 0
        var size = 0L

        fun checkName(name: String) {
            require(name.isNotBlank() && name != "." && name != ".." &&
                '/' !in name && '\\' !in name && '\n' !in name && '\r' !in name
            ) { "Некорректное имя объекта в исходной папке." }
        }

        fun scan(folder: CloudNode, depth: Int): Entry.Folder {
            checkpoint()
            require(depth <= MAX_DEPTH) { "Слишком глубокая структура папок." }
            require(visited.add(folder.id)) { "Обнаружена циклическая ссылка на папку." }
            val current = source.stat(folder.id)
            require(current.isFolder && current.name == folder.name) {
                "Исходная папка изменилась. Обновите список."
            }
            checkName(current.name)
            val children = mutableListOf<Entry>()
            val names = HashSet<String>()
            val seenPageTokens = HashSet<String>()
            var token: String? = null
            do {
                checkpoint()
                val page = source.list(folder.id, token)
                for (node in page.nodes) {
                    checkpoint()
                    checkName(node.name)
                    require(names.add(node.name)) {
                        "В исходной папке есть повторяющиеся имена объектов."
                    }
                    count++
                    require(count <= MAX_ITEMS) {
                        "Слишком много объектов для одного переноса (более $MAX_ITEMS)."
                    }
                    if (node.isFolder) {
                        children.add(scan(node, depth + 1))
                    } else {
                        require(node.canReadBytes && node.sizeBytes != null && node.sizeBytes >= 0) {
                            "Нельзя автоматически скопировать «${node.name}»: " +
                                "нет доступа к содержимому или неизвестен размер."
                        }
                        require(Long.MAX_VALUE - size >= node.sizeBytes) {
                            "Суммарный размер файлов слишком велик."
                        }
                        size += node.sizeBytes
                        children.add(Entry.File(node))
                    }
                }
                token = page.nextPageToken
                require(token == null || seenPageTokens.add(token)) {
                    "Провайдер повторил страницу при сканировании папки."
                }
            } while (token != null)
            return Entry.Folder(current, children)
        }

        val planned = scan(start, 0)
        val files = countEntries(planned)
        return Plan(planned, files, size)
    }

    private fun countEntries(folder: Entry.Folder): Int =
        folder.entries.sumOf {
            when (it) {
                is Entry.File -> 1
                is Entry.Folder -> countEntries(it)
            }
        }
}
